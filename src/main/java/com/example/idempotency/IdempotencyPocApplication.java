package com.example.idempotency;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IdempotencyPocApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdempotencyPocApplication.class, args);
    }
}
