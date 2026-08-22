package com.example.idempotency.service;

/**
 * Stand-in for whatever you'd really use to publish -- an SNS client,
 * an SQS client, a Kafka producer. The outbox pattern doesn't care which;
 * it only cares that publishing is a separate, retriable step that
 * happens AFTER the DB transaction that recorded the event has committed.
 */
public interface EventPublisher {
    void publish(String eventType, String payload);
}
