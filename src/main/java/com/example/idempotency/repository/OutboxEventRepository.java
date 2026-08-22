package com.example.idempotency.repository;

import com.example.idempotency.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    // In production, with more than one instance of this service running,
    // this plain query would let two instances grab and publish the same
    // row. You'd guard it with a native query using
    // "SELECT ... FOR UPDATE SKIP LOCKED" (Postgres/MySQL 8+) so each
    // poller instance only ever claims rows nobody else already has a
    // lock on. Left as a findAll-by-status here to keep the POC's SQL
    // portable across H2/Postgres/MySQL without native syntax.
    List<OutboxEvent> findTop20ByStatusOrderByCreatedAtAsc(OutboxEvent.Status status);
}
