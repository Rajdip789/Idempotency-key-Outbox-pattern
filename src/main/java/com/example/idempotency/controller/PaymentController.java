package com.example.idempotency.controller;

import com.example.idempotency.dto.PaymentRequest;
import com.example.idempotency.dto.PaymentResponse;
import com.example.idempotency.exception.MissingIdempotencyKeyException;
import com.example.idempotency.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }

        PaymentResponse response = paymentService.processPayment(idempotencyKey, request);

        // 200 on replay (nothing new happened), 201 on first-time creation.
        HttpStatus status = response.isReplayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }
}
