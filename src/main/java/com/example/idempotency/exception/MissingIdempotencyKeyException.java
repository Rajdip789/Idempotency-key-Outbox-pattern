package com.example.idempotency.exception;

public class MissingIdempotencyKeyException extends RuntimeException {
    public MissingIdempotencyKeyException() {
        super("Idempotency-Key header is required for POST /payments");
    }
}
