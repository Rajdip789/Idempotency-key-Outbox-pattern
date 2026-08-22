package com.example.idempotency.entity;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * One row per Idempotency-Key header value we've ever seen.
 *
 * The trick this whole POC hinges on: we don't take an explicit lock.
 * We INSERT a row with status=PENDING first. The database's PRIMARY KEY
 * uniqueness constraint IS the lock. Two concurrent requests with the same
 * key race to insert; exactly one wins, the other gets a
 * DataIntegrityViolationException and knows someone else already owns
 * this key.
 *
 * GOTCHA THIS CLASS WORKS AROUND: because @Id here is an assigned String
 * (not @GeneratedValue), Spring Data JPA's default isNew() check --
 * "is the id field null?" -- always says false, since we always set the
 * key ourselves. That makes repository.save() call entityManager.merge()
 * instead of persist(), and merge() does a SELECT-then-decide before
 * writing. That extra read reintroduces exactly the race window we're
 * trying to close with a single atomic INSERT. Implementing Persistable
 * and tracking "is this a brand-new instance I just constructed, or one
 * I loaded from the DB" ourselves forces Spring Data to call persist()
 * for new rows, guaranteeing a plain INSERT that the primary key
 * constraint can arbitrate.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey implements Persistable<String> {

    // Not persisted. true only for instances built via the constructor
    // (a fresh reserve attempt); flipped to false once Hibernate has
    // loaded a row from the database, so subsequent saves on that
    // instance go through merge()/UPDATE as expected.
    @Transient
    private boolean isNewRow = true;

    @Id
    @Column(name = "idem_key", nullable = false, updatable = false, length = 255)
    private String key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    // The exact HTTP response body we returned the first time. On a
    // duplicate request against a COMPLETED key, we replay this verbatim
    // instead of re-running any business logic.
    @Lob
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // A PENDING row older than this is presumed abandoned (the process that
    // owned it crashed) and becomes eligible for retry instead of being
    // treated as "still in flight forever".
    @Column(name = "lease_expires_at", nullable = false)
    private Instant leaseExpiresAt;

    protected IdempotencyKey() {
        // JPA
    }

    public IdempotencyKey(String key, Instant now, long leaseSeconds) {
        this.key = key;
        this.status = Status.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
        this.leaseExpiresAt = now.plusSeconds(leaseSeconds);
    }

    public enum Status {
        PENDING,
        COMPLETED,
        FAILED
    }

    @Override
    public String getId() {
        return key;
    }

    @Override
    public boolean isNew() {
        return isNewRow;
    }

    @PostLoad
    @PrePersist
    private void markNotNewAfterLoadOrSave() {
        // @PostLoad: Hibernate just hydrated this from a row that already
        // existed -- treat it as not-new so future saves are UPDATEs.
        // @PrePersist: we're about to INSERT this exact instance -- once
        // that succeeds, it's no longer "new" either, so a second save()
        // call on the same in-memory object later in the same request
        // (there isn't one here, but this keeps the invariant honest)
        // won't try to persist() again.
        this.isNewRow = false;
    }

    public boolean isLeaseExpired(Instant now) {
        return now.isAfter(this.leaseExpiresAt);
    }

    public void markCompleted(String responseBody, int httpStatus, Instant now) {
        this.status = Status.COMPLETED;
        this.responseBody = responseBody;
        this.httpStatus = httpStatus;
        this.updatedAt = now;
    }

    public void markFailed(Instant now) {
        this.status = Status.FAILED;
        this.updatedAt = now;
    }

    public void reclaimAsPending(Instant now, long leaseSeconds) {
        this.status = Status.PENDING;
        this.updatedAt = now;
        this.leaseExpiresAt = now.plusSeconds(leaseSeconds);
    }

    // --- getters ---
    public String getKey() { return key; }
    public Status getStatus() { return status; }
    public String getResponseBody() { return responseBody; }
    public Integer getHttpStatus() { return httpStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
}
