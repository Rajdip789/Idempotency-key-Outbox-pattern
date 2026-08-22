package com.example.idempotency.service;

import com.example.idempotency.entity.OutboxEvent;
import com.example.idempotency.repository.OutboxEventRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Deliberately tiny: recordEvent() just does a repository.save(). The
 * entire point of the outbox pattern is that this call happens INSIDE
 * whatever transaction the caller already has open (see
 * PaymentService.markCompleted), so the outbox INSERT and the business
 * UPDATE commit or roll back together, atomically, for free -- because
 * they're both just rows in the same local database.
 */
@Component
public class OutboxService {

    private final OutboxEventRepository repository;

    public OutboxService(OutboxEventRepository repository) {
        this.repository = repository;
    }

    public void recordEvent(String aggregateType, String aggregateId, String eventType, String payloadJson) {
        OutboxEvent event = new OutboxEvent(aggregateType, aggregateId, eventType, payloadJson, Instant.now());
        repository.save(event);
    }
}
