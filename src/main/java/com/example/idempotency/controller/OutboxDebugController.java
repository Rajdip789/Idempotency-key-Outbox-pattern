package com.example.idempotency.controller;

import com.example.idempotency.entity.OutboxEvent;
import com.example.idempotency.repository.OutboxEventRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Debug-only: lets you watch outbox rows move from PENDING to PUBLISHED
 * without opening the H2 console. Not something you'd ship in a real API.
 */
@RestController
@RequestMapping("/debug/outbox")
public class OutboxDebugController {

    private final OutboxEventRepository repository;

    public OutboxDebugController(OutboxEventRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<OutboxEvent> listAll() {
        return repository.findAll();
    }
}
