package com.example.idempotency.exception;

/**
 * Thrown when a request comes in with an Idempotency-Key that's currently
 * PENDING and whose lease hasn't expired yet -- i.e. another request with
 * the same key is genuinely in flight right now. The correct client
 * behavior is "retry shortly", not "treat as new" or "treat as failed".
 */
public class ConcurrentRequestException extends RuntimeException {
    public ConcurrentRequestException(String key) {
        super("Request with Idempotency-Key '" + key + "' is already being processed");
    }
}
