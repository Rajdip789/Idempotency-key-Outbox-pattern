# Idempotency Key + Transactional Outbox POC (Spring Boot)

Two patterns, side by side, in one payments flow — because they solve
two different halves of "keep external side effects consistent with
your database":

| Pattern | Problem it solves | Direction |
|---|---|---|
| **Idempotency Key** | An inbound request might arrive more than once (retry, timeout, double-click). Only the first should take effect. | Inbound |
| **Transactional Outbox** | A DB write needs to reliably cause an outbound event (SNS/SQS/Kafka), and a message broker can't share a transaction with your database. | Outbound |

## Part 1 — Idempotency Key (`POST /payments`)

`idempotency_keys` table, `idem_key` as PRIMARY KEY:

1. **Reserve** — `INSERT (key, PENDING)` in its own committed
   transaction. The PK's uniqueness constraint *is* the lock — no
   explicit `SELECT ... FOR UPDATE` needed. Concurrent requests with the
   same key race to insert; the DB guarantees exactly one wins.
2. **Branch** on what the loser finds when it re-reads the row:
   - `COMPLETED` → replay the stored response, no charge.
   - `PENDING`, lease not expired → truly concurrent → `409 Conflict`.
   - `PENDING` past its lease, or `FAILED` → previous owner crashed →
     reclaim and retry.
3. **Charge the provider**, passing the same key through — real gateways
   (Stripe, Adyen) dedupe on their side too, a safety net underneath our
   own table.
4. **Complete** — `UPDATE ... COMPLETED` in its own committed transaction.

Reserve and complete are deliberately *separate* `REQUIRES_NEW`
transactions (see `PaymentService`), not one big `@Transactional` method
— otherwise a crash after charging the provider rolls back the PENDING
row too, and you lose all record the charge was ever attempted.

## Part 2 — Transactional Outbox (`outbox_events`)

Once a payment completes, something else probably needs to know — loyalty
points, order fulfillment, a notification service. The naive approach:

```java
db.update(row -> row.status = COMPLETED);   // step A: your database
snsClient.publish("PaymentSucceeded", ...);  // step B: a different system
```

There's no way to make A and B atomic — they're two unrelated systems.
Crash between them, or have B throw, and downstream services never learn
the payment succeeded even though your DB says it did.

The fix: never call the broker inside the transaction. Instead:

1. **Same transaction as step A**, insert a `PENDING` row into
   `outbox_events` — see `PaymentService.markCompleted()`, which calls
   `outboxService.recordEvent(...)` in the exact same `REQUIRES_NEW` block
   as the idempotency-key `UPDATE`. Trivially atomic: it's the same
   database, same commit.
2. **`OutboxPoller`** — a `@Scheduled` job, decoupled from any HTTP
   request — reads `PENDING` rows and calls `EventPublisher.publish()`
   (a stand-in for a real SNS/SQS client). Success → mark `PUBLISHED`.
   Failure → leave `PENDING`, retried on the next tick. Nothing is lost
   if the broker is down; rows just queue up.

## How the two connect in this POC

```
POST /payments  ->  PaymentService.processPayment()
                       |- reserve()        [idempotency_keys, REQUIRES_NEW]
                       |- providerClient.charge()
                       `- markCompleted()  [idempotency_keys UPDATE
                                            + outbox_events INSERT,
                                            same REQUIRES_NEW transaction]

OutboxPoller (every 3s) -> reads outbox_events WHERE status=PENDING
                         -> publisher.publish(...)
                         -> marks PUBLISHED or leaves PENDING for retry
```

## Project layout

```
entity/IdempotencyKey.java         inbound: request dedup + status machine
entity/OutboxEvent.java            outbound: event-to-publish + status machine
repository/IdempotencyKeyRepository.java
repository/OutboxEventRepository.java
service/PaymentProviderClient.java  fake gateway, dedupes by key itself
service/PaymentService.java         reserve -> charge -> complete (+ outbox write)
service/OutboxService.java          tiny helper: insert a PENDING outbox row
service/OutboxPoller.java           @Scheduled: publish PENDING rows
service/EventPublisher.java         interface a real SNS/SQS client would implement
service/FakePublisher.java          stub + a failure toggle for testing retries
controller/PaymentController.java   POST /payments
controller/OutboxDebugController.java  GET /debug/outbox (inspect the table)
exception/                          409 on genuine concurrency, 400 on missing header
```

## The assigned-ID gotcha (applies to both entities)

Both `IdempotencyKey` and `OutboxEvent` use a self-assigned `String` id
(a header value; a generated UUID), not `@GeneratedValue`. Spring Data's
default "is this new?" check just asks "is the id field null?" — which
is always false here, since we always set the id ourselves. Left alone,
that silently turns every `save()` into `merge()` (SELECT-then-decide)
instead of `persist()` (plain INSERT). For `IdempotencyKey` that's a
real correctness bug — it reopens the exact race the unique constraint
was supposed to close. For `OutboxEvent` it's "only" a wasted round
trip. Both entities implement `Persistable<String>` with an `isNew` flag
flipped by `@PostLoad`/`@PrePersist` to force a real INSERT for brand
new rows.

## Running it

```bash
mvn spring-boot:run
```

Make a payment:

```bash
curl -i -X POST http://localhost:8080/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: abc123" \
  -d '{"customerId":"cust_1","amountCents":1999,"currency":"USD"}'
# -> 201 CREATED, replayed:false
```

Retry with the same key -> `200 OK`, same `paymentId`, no second charge.

Watch the outbox:

```bash
curl http://localhost:8080/debug/outbox
```

You'll see a `PENDING` row appear the instant the payment completes, then
flip to `PUBLISHED` within `poc.outbox-poll-interval-ms` (default 3s) --
watch the app logs for `PUBLISHED to broker -> ...`.

## Reproducing failure scenarios

**Idempotency crash-after-charge:**
1. Set `poc.simulate-crash-after-charge: true`, restart, send a new key.
2. Row is stuck `PENDING`. Immediate retry -> `409` (lease not expired).
3. Set `poc.pending-lease-seconds: 5`, flip `simulate-crash-after-charge`
   back to `false`, restart, wait 6s, retry -> row is reclaimed and
   processed cleanly.

**Outbox broker outage:**
1. Set `poc.simulate-publish-failure: true`, restart.
2. Make a payment -- `curl http://localhost:8080/debug/outbox` shows the
   row stuck `PENDING` with `attempts` climbing and `lastError` populated
   on each poll.
3. Set the flag back to `false`, restart (or just wait, once you fix the
   underlying "outage") -- next poll publishes it and it flips to
   `PUBLISHED`. No event was lost the whole time; it just wasn't
   delivered yet.

## Tests

```bash
mvn test
```

`PaymentIdempotencyTest` covers:
- retrying the same key replays instead of double-charging
- different keys produce independent charges
- 10 concurrent requests with the same key still net exactly one charge
- a successful payment writes an outbox row atomically with completion

## What a real system adds on top of this POC

- **Idempotency side:** a reconciliation job for rows stuck past their
  lease that actually *queries the provider* before deciding retry vs.
  fail, instead of trusting the timeout blindly. Request-body
  fingerprinting so the same key can't be reused with a different body.
  TTL/expiry on old `COMPLETED` rows.
- **Outbox side:** `SELECT ... FOR UPDATE SKIP LOCKED` (Postgres/MySQL 8+)
  in the poller query so multiple instances of this service can run
  without double-publishing the same row. A max-attempt count that moves
  a row to `FAILED`/a dead-letter queue instead of retrying forever. Many
  teams replace the polling loop entirely with CDC (Debezium reading the
  DB's write-ahead log) so publishing happens with near-zero added
  latency instead of on a fixed timer.
