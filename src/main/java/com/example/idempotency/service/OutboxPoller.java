package com.example.idempotency.service;

import com.example.idempotency.entity.OutboxEvent;
import com.example.idempotency.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The other half of the outbox pattern. Runs on its own schedule,
 * completely decoupled from whatever request originally wrote the
 * PENDING row. If the broker is down, rows just accumulate as PENDING
 * and get retried next tick -- nothing is lost, because the event's
 * existence was already durably committed to the DB in the same
 * transaction as the business write that caused it.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventRepository repository;
    private final EventPublisher publisher;

    public OutboxPoller(OutboxEventRepository repository, EventPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${poc.outbox-poll-interval-ms:3000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = repository.findTop20ByStatusOrderByCreatedAtAsc(OutboxEvent.Status.PENDING);
        if (pending.isEmpty()) {
            return;
        }

        for (OutboxEvent event : pending) {
            try {
                publisher.publish(event.getEventType(), event.getPayload());
                event.markPublished(Instant.now());
                log.info("Outbox event {} ({}) published successfully", event.getEventId(), event.getEventType());
            } catch (Exception ex) {
                // Left as PENDING on purpose -- the next scheduled run
                // will pick it right back up. A production version would
                // move to FAILED (and a dead-letter path) after some max
                // attempt count instead of retrying forever.
                event.recordFailedAttempt(ex.getMessage());
                log.warn("Outbox event {} publish failed (attempt {}): {}",
                        event.getEventId(), event.getAttempts(), ex.getMessage());
            }
        }
        // No explicit save() calls needed: these entities were loaded in
        // this same @Transactional method, so Hibernate's dirty checking
        // flushes the status/attempts changes automatically at commit.
    }
}
