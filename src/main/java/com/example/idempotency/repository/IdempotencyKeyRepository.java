package com.example.idempotency.repository;

import com.example.idempotency.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {
    // save() is enough here: because @Id is assigned (not generated),
    // Spring Data/Hibernate issues a plain INSERT for a new entity, and the
    // idem_key PRIMARY KEY constraint does the uniqueness enforcement for us.
    // We catch the resulting DataIntegrityViolationException in the service.
}
