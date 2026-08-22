package com.example.idempotency;

import com.example.idempotency.dto.PaymentRequest;
import com.example.idempotency.dto.PaymentResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentIdempotencyTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    // Use a throwaway, uniquely-named H2 file DB per test run so tests
    // don't collide with your manual curl/crash-simulation runs against
    // the default ./data/idempotency-poc DB.
    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        String dbName = "test-" + UUID.randomUUID();
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1");
    }

    private HttpEntity<PaymentRequest> request(String idempotencyKey) {
        PaymentRequest body = new PaymentRequest();
        body.setCustomerId("cust_42");
        body.setAmountCents(1999);
        body.setCurrency("USD");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return new HttpEntity<>(body, headers);
    }

    @Test
    void retryingSameKeyReplaysTheOriginalChargeInsteadOfChargingAgain() {
        String key = "test-key-" + UUID.randomUUID();
        String url = "http://localhost:" + port + "/payments";

        ResponseEntity<PaymentResponse> first =
                restTemplate.postForEntity(url, request(key), PaymentResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().isReplayed()).isFalse();
        String firstPaymentId = first.getBody().getPaymentId();

        // Client retries -- same key, e.g. after a network timeout where it
        // never actually saw the first response.
        ResponseEntity<PaymentResponse> second =
                restTemplate.postForEntity(url, request(key), PaymentResponse.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().isReplayed()).isTrue();

        // Same charge id both times -- the customer was charged exactly once.
        assertThat(second.getBody().getPaymentId()).isEqualTo(firstPaymentId);
    }

    @Test
    void differentKeysProduceIndependentCharges() {
        String url = "http://localhost:" + port + "/payments";

        ResponseEntity<PaymentResponse> a =
                restTemplate.postForEntity(url, request("key-a-" + UUID.randomUUID()), PaymentResponse.class);
        ResponseEntity<PaymentResponse> b =
                restTemplate.postForEntity(url, request("key-b-" + UUID.randomUUID()), PaymentResponse.class);

        assertThat(a.getBody().getPaymentId()).isNotEqualTo(b.getBody().getPaymentId());
    }

    @Test
    void concurrentRequestsWithSameKeyResultInExactlyOneCharge() throws InterruptedException {
        String key = "concurrent-key-" + UUID.randomUUID();
        String url = "http://localhost:" + port + "/payments";
        int threadCount = 10;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<ResponseEntity<PaymentResponse>>> futures = IntStream.range(0, threadCount)
                .mapToObj(i -> pool.submit(() -> restTemplate.postForEntity(url, request(key), PaymentResponse.class)))
                .collect(Collectors.toList());

        List<ResponseEntity<PaymentResponse>> results = futures.stream()
                .map(f -> {
                    try {
                        return f.get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toList());
        pool.shutdown();

        // Every response that carries a body should report the SAME
        // payment id -- whether it was the winner (CREATED), a replay
        // (OK), or arrived as CONFLICT before the winner finished.
        long distinctPaymentIds = results.stream()
                .filter(r -> r.getBody() != null && r.getBody().getPaymentId() != null)
                .map(r -> r.getBody().getPaymentId())
                .distinct()
                .count();

        assertThat(distinctPaymentIds).isEqualTo(1);
    }

    @Test
    void successfulPaymentAtomicallyWritesAnOutboxEvent() {
        String key = "outbox-key-" + UUID.randomUUID();
        String url = "http://localhost:" + port + "/payments";

        ResponseEntity<PaymentResponse> response =
                restTemplate.postForEntity(url, request(key), PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // The outbox row for this payment should exist immediately after
        // the HTTP call returns -- it was written in the SAME transaction
        // as marking the idempotency key COMPLETED, so there's no
        // "eventually" about its existence, only about when the poller
        // gets around to publishing it.
        String outboxUrl = "http://localhost:" + port + "/debug/outbox";
        ResponseEntity<Object[]> outboxEvents = restTemplate.getForEntity(outboxUrl, Object[].class);
        assertThat(outboxEvents.getBody()).isNotEmpty();
    }
}
