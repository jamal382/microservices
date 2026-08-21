# Lab 06 — Event-Driven Orders with Kafka, the Outbox and a Saga

**Prerequisites:** the stack runs healthy (`docker compose up -d --build`) and the
seed data is loaded. Labs 04 and 05 are not required, but this lab ends by removing
some of what they configured, and the reason why is the most useful thing in it.

**What you will end up with:** the ability to answer, for any order, this chain:

```
Order কোথায় আটকে আছে?        Where is this order actually stuck?
       ↓
কোন event পাঠানো হয়নি?        Which event was never published?
       ↓
কে সেটা consume করেনি?        Which consumer has not read it?
       ↓
কত lag জমেছে?                How far behind is that consumer?
       ↓
Duplicate এলে কী হয়?          What happens when the same event arrives twice?
       ↓
Payment fail করলে stock কে ফেরত দেয়?   Who gives the stock back?
```

Every output below was read out of the running stack.

---

## 1. Concept

### The two problems this lab exists to fix

Both were listed under **Known limitations** in the README for as long as this
project has existed, and both come from the same root cause.

**1. There were no compensating transactions.** If payment failed after stock was
reserved, the reservation was never released. The order ended at `PAYMENT_FAILED`
and the units stayed held forever — stock nobody could sell, attached to an order
nobody would ship.

**2. Unknown payment outcomes were left unresolved.** This one is subtler and worse.
`sales` called `payment` over HTTP. If that call timed out, the caller learned
nothing useful:

- It could not retry — the charge might already have gone through, and a second
  attempt could take the customer's money twice.
- It could not mark the order `PAYMENT_FAILED` — that status asserts no money moved,
  and nobody actually knew that.

So the code did the only honest thing available and left the order at
`STOCK_RESERVED`, visibly unfinished, for a human to sort out. The comment in
`SalesService` said as much, and named the fix: *"exactly the job a saga or an
outbox would take on."*

> **The root cause is that a synchronous call conflates two different failures.**
> "It did not happen" and "I do not know whether it happened" arrive as the same
> `ResourceAccessException`, and no amount of retry configuration can separate them
> after the fact. Every pattern in Lab 05 makes that ambiguity *cheaper* — a rate
> limiter rejects before sending, so at least you know nothing happened. None of
> them make it go away.

### What replaces it

`POST /api/orders` no longer orchestrates anything. It prices the order, writes it
down, and returns `202 Accepted`. The rest is a **choreographed saga**: each service
reacts to an event and publishes its own outcome, with no central coordinator.

```
  client
    │  POST /api/orders           ← catalog priced synchronously (still a read)
    ▼
  sales ──[order.placed]──▶ inventory
                                │
              ┌─────────────────┴─────────────────┐
              ▼                                   ▼
      [stock.rejected]                    [stock.reserved]
              │                                   │
              ▼                        ┌──────────┴──────────┐
        sales: REJECTED                ▼                     ▼
         (terminal)                  sales                payment
                            STOCK_RESERVED    ┌──────────────┴──────────────┐
                                              ▼                             ▼
                                     [payment.completed]            [payment.failed]
                                              │                             │
                                              ▼                             ▼
                                     sales: CONFIRMED              sales: PAYMENT_FAILED
                                       (terminal)                          │
                                                                [order.cancelled]
                                                                           │
                                                                           ▼
                                                                       inventory
                                                                   releases the units
                                                                           │
                                                                  [stock.released]
                                                                           │
                                                                           ▼
                                                                  sales: CANCELLED
                                                                      (terminal)
```

Note the bottom-right branch. That is compensation, and it is why `CANCELLED` —
an enum constant that nothing in this project ever set — finally means something.

### Choreography vs orchestration, and what it costs

This is **choreography**: no service is in charge. `payment` charges because
`inventory` said stock was reserved, not because anyone told it to. The alternative
is **orchestration**, where one component holds the saga's state and issues each
step in turn.

| | Choreography (this lab) | Orchestration |
|---|---|---|
| Coupling | Services know topics, not each other | Everyone knows the orchestrator |
| Adding a consumer | Subscribe; nothing else changes | Change the orchestrator |
| Seeing the whole flow | Read the topics — it is nowhere in code | Read one class |
| Debugging | Follow the events across services | One place to look |

The last two rows are the real cost and they are not small. **There is no file in
this repository that describes the order flow.** The diagram above is documentation,
not code, and it can drift from what actually runs. Orchestration would have kept
the flow readable in one class at the price of coupling every service to it. Neither
answer is correct in general; choreography was chosen here because the flow is short
and linear, and because the topics make the coupling visible instead of hiding it in
a coordinator.

### The dual-write problem, which is the actual hard part

Every service in this saga does two things when it acts: it changes its own database
and it publishes an event. There is no transaction spanning Postgres and Kafka, so
one of them can succeed alone.

```
   commit DB ────▶ ✗ crash ────▶ publish        order exists, nobody hears about it
   publish  ────▶ ✗ crash ────▶ commit DB       everyone acts on an order that does not exist
```

**Neither ordering is safe, and this is not a problem you can solve by being
careful.** It is solved by refusing to do two writes at all.

### The outbox pattern

The event is inserted into an `outbox` table **in the same transaction as the
business change**. One database, one commit, all-or-nothing. A separate relay reads
committed rows and publishes them — a job that is safe to retry, because the
intent is already durable.

```
┌─ one transaction ────────────────────┐
│  INSERT INTO orders  ...             │
│  INSERT INTO outbox  (order.placed)  │
└──────────────────────────────────────┘
                 │  committed
                 ▼
        OutboxRelay (polls every 500ms)
                 │  publish, then mark published_at
                 ▼
              Kafka
```

The relay holds a row lock across the network call and only sets `published_at`
after the broker acknowledges. If it dies mid-publish the transaction rolls back,
the row unlocks still unpublished, and the next poll retries it. **The cost of that
choice is duplicates** — a record the broker accepted but whose `published_at` never
committed gets sent twice.

### At-least-once, and the inbox that makes it survivable

Kafka delivers **at least once**. Not at most once, not exactly once — a consumer
that does its work and dies before committing its offset gets the same record again.

So every consumer keeps an `processed_events` table (an **inbox**) and checks the
producer-assigned `eventId` before acting. The check, the business change and the
inbox insert all happen in one transaction:

```
┌─ one transaction ─────────────────────────────┐
│  SELECT FROM processed_events WHERE event_id  │   already done? stop.
│  ... do the actual work ...                   │
│  INSERT INTO outbox         (the reply)       │
│  INSERT INTO processed_events (event_id)      │
└───────────────────────────────────────────────┘
                     │ commit
                     ▼
              commit Kafka offset      ← outside the transaction, necessarily
```

The offset commit is *outside* and cannot be brought in: offsets live in Kafka, rows
live in Postgres. A crash between the two replays the record — into a consumer that
now finds its own inbox row and does nothing. That is the whole trick, and it is why
"exactly-once processing" is achievable at the application level even though
exactly-once *delivery* is not.

> **Correctness does not rest on the `SELECT` winning a race.** Two concurrent
> deliveries both see an empty inbox and both try to insert; the primary key lets one
> through and the loser's business change rolls back with it. The `SELECT` only makes
> the common case cheap.

---

## 2. What was added

### The broker — one container, KRaft, no ZooKeeper

Kafka 4.x has no ZooKeeper. The `kafka` service is both `broker` and `controller`,
which is why it binds two internal listeners plus one for you:

| Listener | Port | Who uses it |
|---|---|---|
| `PLAINTEXT` | 9092 | services on the `backend` network → `kafka:9092` |
| `CONTROLLER` | 9093 | the KRaft metadata quorum, never a client |
| `EXTERNAL` | 29092 | you, from the host → `localhost:29092` |

> **Host port 29092, not 9092.** `inventory1` and `inventory2` already publish 9092
> and 9093. Kafka's own 9092/9093 are container-internal and collide with nothing —
> only the *published* port had to move.

**Advertised addresses matter more than bound ones.** A client's first connection is
only a metadata lookup; the broker replies with the address to actually use and the
client reconnects there. Advertise the wrong one and a client connects successfully,
then fails on every produce.

`kafka-ui` on **:8090** shows topics, messages, consumer groups and lag.

### Topics — seven, all keyed by order id

| Topic | Producer | Consumers |
|---|---|---|
| `order.placed` | sales | inventory |
| `stock.reserved` | inventory | **sales *and* payment** |
| `stock.rejected` | inventory | sales |
| `payment.completed` | payment | sales |
| `payment.failed` | payment | sales |
| `order.cancelled` | sales | inventory |
| `stock.released` | inventory | sales |

Plus `sales.dlt`, `inventory.dlt`, `payment.dlt` — one dead-letter topic per
*consumer*, not per topic. `stock.reserved` is read by two groups doing two different
jobs; a record `payment` chokes on may be perfectly consumable by `sales`, so a
single `stock.reserved.DLT` would not say which side actually failed.

**Every event is keyed by `orderId`.** Kafka orders records within a partition, not
across a topic, so keying by order is what guarantees one order's events cannot
overtake each other — while leaving different orders free to be processed in
parallel on other partitions.

**Three partitions each, created explicitly.** Auto-creation is off at the broker
(`KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"`), and each service declares only the
topics it produces. See §4 Break 1 for what one partition would silently cost.

### The tables, per service

```
sales/…/db/migration/     V3 outbox, V4 processed_events
inventory/…/db/migration/ V3 outbox, V4 processed_events
payment/…/db/migration/   V2 outbox, V3 processed_events
```

All three services have both, because all three both consume and produce. The
`payload` column is `TEXT`, not `jsonb`, on purpose: the relay must publish exactly
the bytes that were committed, and `jsonb` does not preserve key order or whitespace.
The outbox is a record of a decision, not a document to reinterpret.

### The dependency trap

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-kafka</artifactId>
</dependency>
```

**Not `org.springframework.kafka:spring-kafka` on its own.** Spring Boot 4 split
auto-configuration into per-technology modules. The raw artifact gives you the
`KafkaTemplate` *class* but not the auto-configuration that reads `spring.kafka.*`
and defines the *bean*. The app compiles cleanly and then dies at startup with:

```
Parameter 1 of constructor in ...OutboxRelay required a bean of type
'org.springframework.kafka.core.KafkaTemplate' that could not be found.
```

This is the Boot 4 equivalent of the `spring-boot-starter-aop` → `-aspectj` rename
that Lab 04 warns about, and it fails the same way: at runtime, for a reason the
compiler cannot see.

### Serialization — strings, deliberately

```properties
spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer
```

The obvious alternative, Spring's `JsonSerializer`, stamps the producer's
fully-qualified Java class name into a `__TypeId__` header and has the consumer
instantiate that class by name. It is convenient inside one codebase and a trap
across services: it makes `com.finalearth.sales.event.OrderPlacedEvent` part of the
**wire contract**, so renaming a package in the producer breaks a consumer that was
never recompiled.

Each service therefore declares its own copy of each event record, naming only the
fields it reads. `inventory`'s `OrderPlacedEvent` omits `orderNumber` and
`customerId` entirely and ignores them without configuration — which is exactly what
lets the producer add fields without a coordinated release.

### The settings that are not defaults

| Setting | Value | Why |
|---|---|---|
| `producer.acks` | `all` | The leader answers only once every in-sync replica has it. A formality with one broker; the thing that stops silent loss on failover with more. |
| `producer.enable.idempotence` | `true` | Removes duplicates from the producer's **own** retries. Does not survive a restart — a new session gets a new producer id. |
| `consumer.auto-offset-reset` | `earliest` | A new group reads from the beginning instead of skipping history. This is what makes the log replayable. |
| `consumer.enable-auto-commit` | `false` | Auto-commit acknowledges records when *polled*. A crash mid-batch then loses everything fetched but unhandled — at-most-once, silently. |
| `listener.ack-mode` | `record` | Offset committed after each listener returns. |
| `listener.missing-topics-fatal` | `false` | A service consumes topics *other* services declare, so at a cold start they may not exist yet. Fatal would mean coming up permanently deaf. |

### What was removed, and why that is the lesson

`sales` no longer has an `InventoryClient` or a `PaymentClient`. Gone with them:
their circuit breakers, bulkheads, rate limiters and fallbacks — about half of what
Lab 05 configured.

**That deletion is the point of this lab.** All five of those patterns answer one
question: *what does a caller do when the callee cannot be reached right now?*
Publishing to a durable log does not raise the question. The broker holds the event
until `inventory` and `payment` are healthy enough to read it, and a consumer that is
down is a consumer with **lag**, not a failed call.

`catalog` keeps its full stack, because pricing is still a synchronous
request/response call and every argument in Labs 04 and 05 still applies to it
unchanged. The lesson is not "Kafka replaces Resilience4j" — it is that those
patterns are for synchronous calls, and the most effective way to survive a
synchronous call is sometimes to not make one.

---

## 3. Verify

### Check 1 — the topics exist with the partitions you asked for

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list
```

```
__consumer_offsets   order.cancelled   order.placed      payment.completed
payment.dlt          payment.failed    sales.dlt         stock.rejected
stock.released       stock.reserved    inventory.dlt
```

### Check 2 — the two inventory replicas split the partitions

This is the one to look at first, because it is invisible from the outside.

```bash
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group inventory
```

```
GROUP      TOPIC          PARTITION  ...  HOST            CLIENT-ID
inventory  order.placed   0               /172.24.0.7     consumer-inventory-1
inventory  order.placed   1               /172.24.0.7     consumer-inventory-1
inventory  order.placed   2               /172.24.0.6     consumer-inventory-1
```

**Two different hosts.** `inventory1` and `inventory2` share a `group-id`, so Kafka
treats them as one group and gives each partition to exactly one member — a 2/1
split. No order is reserved twice, and there is no coordination code anywhere making
that true.

Compare that to what these same two containers do over HTTP: they sit behind a DNS
alias, and which one serves a request depends on whichever A record the caller
happened to cache (see the README's note on per-connection balancing). Group
membership is a real assignment; DNS rotation is a hint.

### Check 3 — the happy path

```bash
curl -i -X POST localhost:8083/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":2}],"paymentMethod":"CARD"}'
```

```
HTTP/1.1 202
Location: http://localhost:8083/api/orders/4
{"id":4,...,"status":"PENDING","totalAmount":2999.98,...}
```

**202, not 201.** The order exists; its outcome does not. Poll for the result:

```bash
curl -s localhost:8083/api/orders/4 | python3 -m json.tool
```

```
"status": "CONFIRMED"
```

### Check 4 — follow one order across all four services

Every listener puts `order-<id>` in the MDC, so one grep gets the whole saga:

```bash
docker compose logs sales inventory1 inventory2 payment | grep 'order=5'
```

```
sales      [outbox] queued topic=order.placed order=5 event=OrderPlacedEvent
sales      [saga] order=5 ACCEPTED status=PENDING total=2255.13 -- awaiting stock
sales      [outbox] published topic=order.placed key=5
inventory  [kafka] consume topic=order.placed partition=0 offset=0 key=5
inventory  [stock] order=5 RESERVED 2 line(s)
inventory  [outbox] queued topic=stock.reserved order=5
sales      [kafka] consume topic=stock.reserved partition=0 offset=0 key=5
payment    [kafka] consume topic=stock.reserved partition=0 offset=0 key=5
sales      [saga] order=5 PENDING -> STOCK_RESERVED (stock reserved)
payment    [payment] order=5 DECLINED amount=2255.13 reason="Insufficient funds"
payment    [outbox] queued topic=payment.failed order=5
sales      [kafka] consume topic=payment.failed partition=0 offset=0 key=5
sales      [saga] order=5 STOCK_RESERVED -> PAYMENT_FAILED (payment failed: ...)
sales      [outbox] queued topic=order.cancelled order=5
sales      [saga] order=5 COMPENSATING -- releasing reserved stock
inventory  [kafka] consume topic=order.cancelled partition=0 offset=0 key=5
inventory  [saga] order=5 COMPENSATING reason="payment failed: Insufficient funds"
inventory  [stock] order=5 RELEASED 2 line(s) -- compensation complete
inventory  [outbox] queued topic=stock.released order=5
sales      [kafka] consume topic=stock.released partition=0 offset=0 key=5
sales      [saga] order=5 PAYMENT_FAILED -> CANCELLED (stock released, saga complete)
```

Note the two `consume topic=stock.reserved` lines — `sales` and `payment`, same
record, two groups, both at offset 0 of partition 0.

### Check 5 — compensation actually returns the stock

To get a declined payment you need a total ending in `.13`, on products that are
actually in stock. `37 × 59.99 + 1 × 35.50 = 2255.13`:

```bash
docker compose exec -T postgres psql -U postgres -d microservices \
  -c "SELECT product_id, quantity_available, quantity_reserved
      FROM inventory.stock_items WHERE product_id IN (6,8);"

curl -s -X POST localhost:8083/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":2,"items":[{"productId":8,"quantity":37},
                               {"productId":6,"quantity":1}],"paymentMethod":"CARD"}'
```

After a couple of seconds:

```
order status = CANCELLED

 product_id | quantity_available | quantity_reserved
------------+--------------------+-------------------
          6 |                 20 |                 0      ← back where it started
          8 |                100 |                 0
```

And the audit trail:

```bash
docker compose exec -T postgres psql -U postgres -d microservices \
  -c "SELECT order_id, movement_type, quantity FROM inventory.stock_movements
      WHERE order_id=5 ORDER BY id;"
```

```
 order_id | movement_type | quantity
----------+---------------+----------
        5 | RESERVE       |       37
        5 | RESERVE       |        1
        5 | RELEASE       |       37
        5 | RELEASE       |        1
```

**The `RESERVE` rows are still there.** Compensation is a new business fact, not an
undo — the history has to show the units were held and then given back, because that
is what happened.

### Check 6 — the failure that used to be unrecoverable

This is the one worth doing slowly. Kill `payment` *mid-saga*:

```bash
docker compose stop payment

curl -s -X POST localhost:8083/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":4,"items":[{"productId":3,"quantity":2}],"paymentMethod":"CARD"}'
```

The order is accepted, and:

```bash
curl -s localhost:8083/api/orders/7        # → "status": "STOCK_RESERVED"

docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group payment
```

```
GROUP    TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
payment  stock.reserved  0          1               2               1
```

**`LAG 1`.** The order is at `STOCK_RESERVED` — the exact status the old code left
stranded orders in. The difference is that this one is not stranded, it is *queued*,
and the broker can tell you so with a number. Now:

```bash
docker compose start payment
```

```
payment  [kafka] consume topic=stock.reserved partition=0 offset=1 key=7 order=7
payment  [payment] order=7 COMPLETED ref=PAY-1787296344935-7 amount=99.98
payment  [outbox] queued topic=payment.completed order=7

curl -s localhost:8083/api/orders/7        # → "status": "CONFIRMED"
```

**No retry logic ran and nobody reconciled anything.** The known limitation is gone
because the situation it described cannot arise: an outcome that is slow is no longer
an outcome that is unknown.

### Check 7 — a duplicate changes nothing

Replay a real event straight back onto its topic:

```bash
PAYLOAD=$(docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic stock.reserved \
  --from-beginning --max-messages 1 --timeout-ms 8000 2>/dev/null | head -1)

docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic stock.reserved \
  --property parse.key=true --property key.separator='|' <<< "4|$PAYLOAD"
```

```
sales    [kafka] DUPLICATE topic=stock.reserved partition=1 offset=1 order=4
                 event=8c5dbc61-... -- already applied, skipping
payment  [kafka] DUPLICATE topic=stock.reserved partition=1 offset=1 order=4
                 event=8c5dbc61-... -- already charged, skipping
```

Both groups received it. Both dropped it. The order is unchanged and there is still
exactly one payment row:

```sql
SELECT order_id, count(*) FROM payment.payments WHERE order_id=4 GROUP BY order_id;
-- 4 | 1
```

### Check 8 — the outbox drains and nothing dead-letters

```bash
docker compose exec -T postgres psql -U postgres -d microservices -c "
  SELECT 'sales' svc, count(*) FILTER (WHERE published_at IS NULL) unpublished,
         count(*) total FROM sales.outbox
  UNION ALL SELECT 'inventory', count(*) FILTER (WHERE published_at IS NULL),
         count(*) FROM inventory.outbox
  UNION ALL SELECT 'payment', count(*) FILTER (WHERE published_at IS NULL),
         count(*) FROM payment.outbox;"
```

```
   svc     | unpublished | total
-----------+-------------+-------
 sales     |           0 |     5
 inventory |           0 |     5
 payment   |           0 |     3
```

A non-zero `unpublished` that does not fall back to zero within a second means the
relay cannot reach the broker. **The rows are the backlog, and they are safe** —
that is the entire promise of the outbox.

### Check 9 — see it in kafka-ui

<http://localhost:8090> → **Topics** for messages and keys, **Consumer Groups** for
lag per group per partition. Lag is the number worth watching: it is the only metric
here that tells you the system is falling behind *before* anything fails.

---

## 4. Break it yourself

### Break 1 — one partition, and half your consumers go idle

Create a topic the way auto-creation would have:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create --topic order.placed.broken \
  --partitions 1 --replication-factor 1
```

Then reason about what would happen if `order.placed` were built that way: with one
partition, one member of the `inventory` group gets it and **the other gets nothing**.

**Expect** — verifiable on the real topic — that consumer-group members never exceed
partitions:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group inventory
```

The idle replica reports itself perfectly healthy, serves HTTP normally, and consumes
nothing. **Partition count is the hard ceiling on consumer parallelism, and it is
fixed at creation time.** This is why the broker has auto-creation switched off.

### Break 2 — give the replicas different group ids

In `inventory/src/main/resources/application.properties`:

```properties
spring.kafka.consumer.group-id=inventory-${random.uuid}
```

Rebuild and place one order.

**Expect the order to be reserved twice** — both replicas are now their own group, so
both receive every record. `quantity_available` drops by double, there are two
`RESERVE` movements, and **no error appears anywhere**. The inbox does not save you:
the two services have separate `processed_events` tables and neither has seen the
event before.

This is the most dangerous single line in the Kafka configuration.

### Break 3 — publish outside the transaction

In `SalesService.placeOrder`, replace the `events.emit(...)` call with a direct
`kafkaTemplate.send(...)`, then make the transaction fail right after it:

```java
kafka.send(Topics.ORDER_PLACED, String.valueOf(persisted.getId()), payload);
throw new IllegalStateException("boom");
```

**Expect** no order in the database and `inventory` reserving stock for it anyway.
The stock is now held for an order that does not exist and never will, and nothing
will ever release it — there is no order to cancel. That is the dual-write problem
producing exactly the orphaned-stock outcome the outbox exists to prevent.

### Break 4 — remove the inbox check

Comment out the `inbox.existsById(...)` guard in `StockEventListener`, then replay a
`stock.reserved` record as in Check 7.

**Expect** the second delivery to reach `chargeForOrder`. The order is charged
once anyway — because `PaymentService` *also* checks for an existing payment row —
and that redundancy is deliberate. Now remove that check too and repeat: two payment
rows for one order. **Only one of those two guards has to fail for a customer to be
charged twice**, which is why the service with money at stake has both.

### Break 5 — poison the topic

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic order.placed <<< 'not json at all'
```

**Expect** `inventory` to fail parsing, skip the retries (a malformed payload is
configured as non-retryable — it will never parse), and dead-letter the record:

```
[kafka] DEAD-LETTER topic=order.placed partition=? offset=? key=null cause=...
```

Then check that the partition kept flowing by placing a normal order. **Without the
error handler that record would be retried forever and the offset would never
advance** — one third of all orders silently frozen behind it, on a service reporting
itself healthy. That is the poison-pill failure, and it is the most common way an
event-driven system falls over.

Inspect what landed:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic inventory.dlt --from-beginning \
  --timeout-ms 5000
```

### Break 6 — stop the broker entirely

```bash
docker compose stop kafka
curl -s -X POST localhost:8083/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1}],"paymentMethod":"CARD"}'
```

**Expect `202` anyway.** The order commits with its outbox row; only the relay fails,
loudly and repeatedly. Check the backlog:

```sql
SELECT count(*) FROM sales.outbox WHERE published_at IS NULL;   -- 1
```

Then `docker compose start kafka` and watch the order complete on its own. **Accepting
work while the broker is unreachable is a property of the outbox, not of Kafka** — and
it is the clearest demonstration of why the event goes to your own database first.

---

## 5. Questions to answer

1. `POST /api/orders` returns `202`. What has the system actually promised the caller
   at that moment, and what has it not?
2. The relay holds a database row lock across a network call to Kafka. Normally that
   is a mistake. Why is it correct here, and what breaks if you set `published_at`
   before the send instead of after?
3. `enable.idempotence=true` is set on every producer. Why does the system still need
   an inbox table?
4. `stock.reserved` is consumed by both `sales` and `payment`. How many times is each
   record delivered, and what would change if they shared a `group-id`?
5. `payment` learns the amount to charge from `inventory`, which copied it from
   `sales`. Why not have `payment` call `sales` for it instead?
6. Why does `reserveForOrder` return a `ReservationOutcome` instead of throwing
   `InsufficientStockException` like the HTTP path does?
7. An order sits at `STOCK_RESERVED` for ten minutes. Name three different causes and
   the command that distinguishes them.
8. `sales` lost its circuit breakers for `inventory` and `payment` but kept the one
   for `catalog`. What distinguishes those calls?
9. Compensation sets `CANCELLED` only after `stock.released` arrives. Why not set it
   when `order.cancelled` is published?

---

## 6. What is still missing

- **The relay is a poller, not a log tail.** Debezium reading Postgres' WAL would
  publish with no polling and no added latency. The 500ms interval here is a floor on
  every hop — four hops means up to two seconds of pure scheduling.
- **Relay ordering is per row, not per key.** `FOR UPDATE SKIP LOCKED` lets two
  relays claim disjoint batches and publish out of order. Harmless only because each
  service emits at most one event per order per step; a service emitting two would
  need to claim by key.
- **Nothing prunes the outbox or the inbox.** Both grow forever. The outbox is
  deliberately an audit log, but `processed_events` is pure overhead after the
  retention window and wants a scheduled delete.
- **The DLT is a dumping ground.** Records land there and nothing routes, alerts or
  replays them. A dead-lettered `order.placed` means an order that will never be
  reserved and no one is told.
- **No schema registry.** "Add fields, never remove them" is enforced by convention
  and code review. A registry would enforce compatibility at publish time.
- **One broker, replication factor 1.** `acks=all` is a formality when there is one
  in-sync replica. Lose the volume and you lose every retained event.
- **Saga timeouts do not exist.** If a consumer never answers, the order waits
  forever. Real sagas run a timeout per step that triggers compensation.
- **The flow lives only in this document.** Choreography's real cost: no code
  describes the order lifecycle, so this diagram can drift from what runs.

---

**Previous:** [Lab 05 — The Five Patterns, and the Order They Run In](05-resilience-patterns.md)
— the synchronous patterns this lab removes half of, and the reasoning for why.
