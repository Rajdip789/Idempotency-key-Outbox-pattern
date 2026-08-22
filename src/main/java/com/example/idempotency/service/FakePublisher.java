package com.example.idempotency.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fake SNS/SQS. Logs what it "published" instead of making a network
 * call. Swap this out for a real SnsClient/SqsClient in a non-POC build --
 * nothing else in the outbox pattern changes, because the poller and the
 * DB transaction that wrote the event don't know or care what publish()
 * actually does.
 */
@Component
public class FakePublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(FakePublisher.class);

    // Set true to make every publish attempt throw, so you can watch the
    // OutboxPoller retry the same row on its next scheduled run instead
    // of silently losing the event.
    @Value("${poc.simulate-publish-failure:false}")
    private boolean simulateFailure;

    @Override
    public void publish(String eventType, String payload) {
        if (simulateFailure) {
            throw new RuntimeException("Simulated broker outage while publishing " + eventType);
        }
        log.info("PUBLISHED to broker -> eventType={}, payload={}", eventType, payload);
    }
}
