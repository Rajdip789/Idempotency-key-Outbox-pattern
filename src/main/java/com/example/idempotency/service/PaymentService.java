package com.example.idempotency.service;

import com.example.idempotency.dto.PaymentRequest;
import com.example.idempotency.dto.PaymentResponse;
import com.example.idempotency.entity.IdempotencyKey;
import com.example.idempotency.exception.ConcurrentRequestException;
import com.example.idempotency.repository.IdempotencyKeyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final IdempotencyKeyRepository repository;
    private final PaymentProviderClient providerClient;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    // Two separate TransactionTemplates configured for REQUIRES_NEW.
    // This is the crux of the design: the "reserve" write and the
    // "complete" write are each their OWN committed transaction, entirely
    // separate from any transaction wrapping the HTTP request as a whole.
    //
    // If everything were one big @Transactional method instead, a crash
    // (or an exception) after charging the provider would roll EVERYTHING
    // back -- including the PENDING row insert -- and you'd lose all
    // record that a charge attempt even happened. The reserve step MUST
    // be durable and visible to other transactions before we ever call
    // an external, non-transactional system like a payment gateway.
    private final TransactionTemplate requiresNewTx;

    @Value("${poc.simulate-crash-after-charge:false}")
    private boolean simulateCrashAfterCharge;

    @Value("${poc.pending-lease-seconds:30}")
    private long pendingLeaseSeconds;

    public PaymentService(IdempotencyKeyRepository repository,
                           PaymentProviderClient providerClient,
                           OutboxService outboxService,
                           ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.providerClient = providerClient;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehaviorName("PROPAGATION_REQUIRES_NEW");
    }

    public PaymentResponse processPayment(String idempotencyKey, PaymentRequest request) {

        // ---- STEP 1: atomic reserve ----
        // Try to INSERT a PENDING row. The primary key constraint on
        // idem_key is doing 100% of our locking here -- no explicit
        // SELECT ... FOR UPDATE, no external lock manager needed.
        ReserveOutcome outcome = reserve(idempotencyKey);

        if (outcome.alreadyCompleted()) {
            log.info("Idempotency-Key {} already COMPLETED -- replaying stored response", idempotencyKey);
            return deserialize(outcome.existingRow().getResponseBody(), true);
        }
        // outcome is either a fresh PENDING row we just inserted, or a
        // stale/failed row we just reclaimed as PENDING. Either way we now
        // OWN this key and are clear to actually charge the provider.

        //In case the PENDING row is existing, ConcurrentRequestException will be thrown in reserve() and we won't reach this point.

        try {
            // ---- STEP 2: charge the provider (non-transactional, external call) ----
            PaymentProviderClient.ChargeResult chargeResult = providerClient.charge(idempotencyKey, request);

            if (simulateCrashAfterCharge) {
                // Demonstrates the exact failure case from the interview
                // question: money moved, but we die before persisting that
                // fact. Restart the app afterward and re-send the same
                // request -- you'll see it get picked up via the lease
                // timeout instead of double-charging.
                log.error("SIMULATED CRASH after charging provider, key={}, chargeId={}",
                        idempotencyKey, chargeResult.chargeId());
                throw new RuntimeException("Simulated crash after provider charge succeeded");
            }

            PaymentResponse response = new PaymentResponse(
                    chargeResult.chargeId(),
                    "SUCCEEDED",
                    request.getAmountCents(),
                    request.getCurrency(),
                    false
            );

            // ---- STEP 3: mark completed (separate committed transaction) ----
            markCompleted(idempotencyKey, response);
            return response;

        } catch (Exception ex) {
            // Only reachable when simulateCrashAfterCharge is false and the
            // charge itself failed for a real reason (provider error, etc).
            // A genuine process crash skips this catch block entirely --
            // which is exactly why the lease-timeout reclaim path in
            // reserve() exists as the safety net for that case.
            markFailed(idempotencyKey);
            throw new RuntimeException("Payment failed for key " + idempotencyKey, ex);
        }
    }

    private ReserveOutcome reserve(String key) {
        return requiresNewTx.execute(status -> {
            Instant now = Instant.now();
            try {
                IdempotencyKey row = new IdempotencyKey(key, now, pendingLeaseSeconds);
                repository.saveAndFlush(row);
                // Insert succeeded -- we're the sole owner of this key.
                return ReserveOutcome.fresh();

            } catch (DataIntegrityViolationException dup) {
                // Someone (possibly a previous, now-dead process, possibly
                // a truly concurrent request) already owns this key.
                // saveAndFlush's failed insert leaves nothing committed, so
                // a plain read now gives us the authoritative existing row.
                IdempotencyKey existing = repository.findById(key)
                        .orElseThrow(() -> dup); // should not happen, but fail loudly if it does

                switch (existing.getStatus()) {
                    case COMPLETED -> {
                        return ReserveOutcome.completed(existing);
                    }
                    case PENDING -> {
                        if (existing.isLeaseExpired(now)) {
                            // The process that reserved this key never came
                            // back to mark it COMPLETED or FAILED within the
                            // lease window -- almost certainly it crashed.
                            // Reclaim the row and let this request retry.
                            existing.reclaimAsPending(now, pendingLeaseSeconds);
                            repository.saveAndFlush(existing);
                            return ReserveOutcome.fresh();
                        }
                        // A genuinely concurrent request is in flight right now.
                        throw new ConcurrentRequestException(key);
                    }
                    case FAILED -> {
                        // Previous attempt failed cleanly (provider declined,
                        // validation error, etc) -- safe to retry immediately.
                        existing.reclaimAsPending(now, pendingLeaseSeconds);
                        repository.saveAndFlush(existing);
                        return ReserveOutcome.fresh();
                    }
                    default -> throw new IllegalStateException("Unhandled status: " + existing.getStatus());
                }
            }
        });
    }

    private void markCompleted(String key, PaymentResponse response) {
        requiresNewTx.executeWithoutResult(status -> {
            IdempotencyKey row = repository.findById(key)
                    .orElseThrow(() -> new IllegalStateException("Idempotency row vanished for key " + key));
            row.markCompleted(serialize(response), 200, Instant.now());
            repository.saveAndFlush(row);

            // Outbox write happens in this SAME transaction as the line
            // above. Either both the "payment completed" update and the
            // "PaymentSucceeded" outbox row commit together, or neither
            // does -- there's no window where one exists without the
            // other, because as far as the database is concerned they're
            // just two INSERT/UPDATE statements in one transaction.
            outboxService.recordEvent(
                    "Payment",
                    response.getPaymentId(),
                    "PaymentSucceeded",
                    serialize(response)
            );
        });
    }

    private void markFailed(String key) {
        requiresNewTx.executeWithoutResult(status -> {
            repository.findById(key).ifPresent(row -> {
                row.markFailed(Instant.now());
                repository.saveAndFlush(row);
            });
        });
    }

    private String serialize(PaymentResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize response", e);
        }
    }

    private PaymentResponse deserialize(String json, boolean replayed) {
        try {
            PaymentResponse response = objectMapper.readValue(json, PaymentResponse.class);
            response.setReplayed(replayed);
            return response;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize stored response", e);
        }
    }

    /** Small internal result type so reserve() can report which branch it took. */
    private record ReserveOutcome(boolean alreadyCompleted, IdempotencyKey existingRow) {
        static ReserveOutcome fresh() {
            return new ReserveOutcome(false, null);
        }
        static ReserveOutcome completed(IdempotencyKey row) {
            return new ReserveOutcome(true, row);
        }
    }
}
