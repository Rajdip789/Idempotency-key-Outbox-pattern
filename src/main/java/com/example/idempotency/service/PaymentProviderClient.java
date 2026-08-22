package com.example.idempotency.service;

import com.example.idempotency.dto.PaymentRequest;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for a real payment gateway SDK (Stripe, Adyen, Razorpay, etc).
 *
 * The important detail: real gateways accept an idempotency key on the
 * CHARGE call itself, and dedupe on THEIR side too. That means even if a
 * bug on our end calls charge() twice for the same key, the provider
 * still only moves money once and just hands back the same charge id.
 *
 * This is the "belt and suspenders" layer -- our own idempotency_keys
 * table is the primary guard, this is the safety net underneath it.
 */
@Component
public class PaymentProviderClient {

    // Simulates the provider's own dedupe store, keyed by idempotency key.
    private final Map<String, String> providerSideDedupe = new ConcurrentHashMap<>();

    public ChargeResult charge(String idempotencyKey, PaymentRequest request) {
        String existingChargeId = providerSideDedupe.get(idempotencyKey);
        if (existingChargeId != null) {
            // The provider recognizes this idempotency key was already
            // charged and returns the original result instead of charging again.
            return new ChargeResult(existingChargeId, true);
        }

        // --- pretend to call out to the network and move real money ---
        String chargeId = "ch_" + UUID.randomUUID().toString().substring(0, 12);
        providerSideDedupe.put(idempotencyKey, chargeId);
        return new ChargeResult(chargeId, false);
    }

    public record ChargeResult(String chargeId, boolean dedupedByProvider) { }
}
