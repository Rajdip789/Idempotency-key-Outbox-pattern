package com.example.idempotency.entity;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per event we need to eventually publish to a broker (SNS/SQS/
 * Kafka/etc). This is the Transactional Outbox pattern.
 *
 * The problem it solves is different from IdempotencyKey's problem.
 * IdempotencyKey makes an INBOUND request safe to retry. OutboxEvent
 * makes an OUTBOUND side effect (publishing "PaymentSucceeded" so other
 * services can react) atomic with the DB write that caused it.
 *
 * Without this table, PaymentService.markCompleted() would look like:
 *
 *   db.update(row -> row.status = COMPLETED);   // step A
 *   snsClient.publish("PaymentSucceeded", ...);  // step B
 *
 * and there is no way to make A and B a single atomic unit -- if the
 * process dies between them, or B throws, you've recorded the payment
 * as done but NOBODY downstream (loyalty points, order fulfillment,
 * notifications) ever hears about it. Two independent systems (your
 * relational DB and the message broker) can't share one transaction.
 *
 * The fix: only ever write to ONE transactional system inside the
 * transaction. Step B becomes "insert a PENDING outbox row in the SAME
 * local transaction as step A" -- trivially atomic, it's the same
 * database. A separate poller (OutboxPoller) reads PENDING rows on its
 * own schedule and does the actual, non-transactional publish to SNS/SQS,
 * retrying independently until it succeeds.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent implements Persistable<String> {

    // Same assigned-ID gotcha as IdempotencyKey (see that class's javadoc):
    // eventId is a self-assigned UUID, so Spring Data's default isNew()
    // check would always say false and route every save() through
    // merge() (SELECT-then-decide) instead of persist() (plain INSERT).
    // Not a correctness bug here -- no unique-constraint race to protect --
    // but it's a needless extra round trip on every single event we ever
    // record, and it's worth being consistent about the fix.
    @Transient
    private boolean isNewRow = true;

    @Id
    @Column(name = "event_id", updatable = false, length = 36)
    private String eventId;

    // What this event is about, e.g. aggregateType="Payment",
    // aggregateId=<idempotency key or payment id>. Lets a consumer or a
    // human debugging this table trace an event back to the business
    // entity that caused it.
    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 255)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    protected OutboxEvent() {
        // JPA
    }

    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload, Instant now) {
        this.eventId = UUID.randomUUID().toString();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = Status.PENDING;
        this.attempts = 0;
        this.createdAt = now;
    }

    public enum Status {
        PENDING,
        PUBLISHED,
        // Not used by this POC's poller directly, but a real system would
        // move a row here after N failed attempts so it stops being
        // retried forever and instead pages someone / goes to a DLQ.
        FAILED
    }

    @Override
    public String getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return isNewRow;
    }

    @PostLoad
    @PrePersist
    private void markNotNewAfterLoadOrSave() {
        this.isNewRow = false;
    }

    public void markPublished(Instant now) {
        this.status = Status.PUBLISHED;
        this.publishedAt = now;
    }

    public void recordFailedAttempt(String error) {
        this.attempts++;
        this.lastError = error;
    }

    // --- getters ---
    public String getEventId() { return eventId; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getLastError() { return lastError; }
}
