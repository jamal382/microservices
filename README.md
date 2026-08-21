# Spring Boot Microservices — Learning Project

A four-service e-commerce backend built to make distributed-systems concepts
concrete: service-to-service calls, client-side load balancing, schema-per-service
data ownership, retries and circuit breakers, event-driven sagas with Kafka, and
the failure modes each of those introduces.

The order path deliberately uses **both** styles of communication, so the trade
between them is visible side by side: pricing is a synchronous HTTP read guarded by
Resilience4j, while reserving stock and taking payment travel as durable events
through Kafka, with a transactional outbox on the way out and an inbox on the way
in.

It is deliberately not a template to copy into production. Several things are
simplified on purpose, and the interesting parts are the trade-offs — documented
inline in the code and worked through in [`docs/labs/`](docs/labs/).

---

## Architecture

```
        client ──┬──▶ catalog :8081 ──▶ inventory ×2  (:9092 / :9093)
                 │
                 ├──▶ sales   :8083 ──▶ catalog   (pricing — the last sync hop)
                 │          │
                 │          ▼
                 │      Kafka :29092 ──┬──▶ inventory ×2   (reserve / release)
                 │          ▲          └──▶ payment        (charge)
                 │          └───────────────── outcomes flow back
                 │
                 ├──▶ kafka-ui :8090
                 └──▶ pgadmin  :5050

        east–west reads resolve through Docker's embedded DNS;
        everything that CHANGES state travels as an event
```

**There is no gateway and no service registry.** Clients address each service
directly on its own host port; services address each other by container name on
the `backend` Docker network. `payment:8084` and `postgres:5432` publish no host
port — `payment` is now only ever reached through Kafka, and the database is only
ever reached through a service or `docker compose exec`.

`inventory1` and `inventory2` share the `inventory` network alias, so
`http://inventory:8082` resolves to **two** A records that Docker rotates. That is
still how `catalog` reaches them for stock reads. On the order path they are
reached differently — both replicas share one Kafka **consumer group**, so the
broker assigns each partition to exactly one of them. Group membership is a real
assignment; DNS rotation is only a hint.

**Two ways to talk, chosen per call.** The distinction the whole system now turns
on is whether a call has a definite answer the caller cannot proceed without:

| | Synchronous HTTP | Asynchronous event |
|---|---|---|
| Used for | `catalog` pricing, `catalog`→`inventory` stock reads | reserving stock, charging, compensating |
| Failure looks like | an exception, right now | consumer **lag**, resolved later |
| Protected by | Resilience4j (Lab 04, 05) | the broker's retention (Lab 06) |
| Caller learns outcome | immediately | when the reply event arrives |

**DNS reports existence, not readiness.** A replica that is up but failing every
request keeps receiving its share of read traffic — nothing in the resolution path
health-checks anything. What stops that from taking callers down with it is
**Resilience4j**, applied at each caller:

| | |
|---|---|
| Timeouts | Every outbound HTTP call is bounded (1s connect, 2s read) |
| Time limiter | A single 1.5s wall-clock deadline on `catalog` → `inventory` |
| Retry | Only on idempotent reads — `GET` product and stock |
| Circuit breaker | One per synchronous dependency |
| Rate limiter | One per synchronous dependency |
| Bulkhead | One per synchronous dependency |
| Fallback | Only where an honest degraded answer exists |

Two distinctions govern all of it. First, a 404, a 409 or a declined card are
**successful conversations with healthy services** — failures of the order, not
of the system, configured never to trip a breaker. Second, a rate-limiter or
bulkhead rejection is a call *this* service refused to make, so it is evidence
about us and not about the dependency.

> **`sales` used to have all five patterns pointed at `inventory` and `payment`.
> They were deleted, not disabled.** Every one of them answers "what does a caller
> do when the callee is unreachable right now?", and publishing to a durable log
> does not raise that question. See [Lab 06](docs/labs/06-kafka.md) for the full
> argument — it is the most useful thing in that lab.

See [Lab 04](docs/labs/04-resilience4j.md) for retries and breakers,
[Lab 05](docs/labs/05-resilience-patterns.md) for all five patterns and the fixed
order they compose in, and [Lab 06](docs/labs/06-kafka.md) for the saga.

> This project previously ran Netflix Eureka, and then HAProxy as an edge
> gateway. Both were removed. See [Lab 02](docs/labs/02-service-discovery.md)
> and [Lab 03](docs/labs/03-haproxy-load-balancing.md) (both archived) for what
> each layer did and why.

---

## Stack

| | |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.0 |
| Database | PostgreSQL 17 (`postgres:17-alpine`), one instance, schema per service |
| Migrations | Flyway, per service |
| Messaging | **Apache Kafka 4.3.1 (`apache/kafka:4.3.1`), KRaft mode — no ZooKeeper** |
| Kafka client | Spring for Apache Kafka 4.1.0 (`spring-boot-starter-kafka`) |
| Resilience | Resilience4j 2.4.0 (`resilience4j-spring-boot4`) |
| DB UI | pgAdmin 4 (`dpage/pgadmin4:9.13`) |
| Kafka UI | kafbat kafka-ui (`kafbat/kafka-ui:v1.5.0`) |
| Build | Maven wrapper (`./mvnw`) per module — no parent aggregator POM |
| Load testing | Locust |

Each service is an independent Maven project with its own `pom.xml` and
multi-stage `Dockerfile` (Maven build stage → `eclipse-temurin:21-jre-alpine`
runtime, running as a non-root `spring` user).

> **The Kafka dependency is `spring-boot-starter-kafka`, not
> `org.springframework.kafka:spring-kafka`.** Boot 4 split auto-configuration into
> per-technology modules; the raw artifact provides the `KafkaTemplate` class but
> not the bean, so the app compiles and then fails at startup. Same shape as the
> `spring-boot-starter-aop` → `-aspectj` rename that Lab 04 warns about.

---

## Quick start

```bash
docker compose up -d --build
```

Wait for all nine containers to report healthy (~60–90s on a cold build —
Kafka and the broker healthcheck add a little):

```bash
docker compose ps
```

**Then load the sample data.** This step is manual and easy to miss:

```bash
docker compose exec -T postgres psql -U postgres -d microservices < infra/postgres/seed.sql
```

> **Why it isn't automatic.** `infra/postgres/init.sql` *is* mounted into
> `/docker-entrypoint-initdb.d/` and creates the four schemas and four DB users.
> `seed.sql` is **not** mounted, because Postgres runs `initdb.d` scripts before
> the services ever start — and the tables it inserts into don't exist until
> Flyway migrates them. It has to run *after* the app containers are up.
> Skip it and every `GET` returns `404` against empty tables.

Verify:

```bash
curl -s http://localhost:8081/api/products/1
curl -s http://localhost:9092/api/stock/1

# Place an order. Returns 202 with "status":"PENDING" -- the saga runs after this.
curl -s -X POST http://localhost:8083/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":5,"quantity":2}],"paymentMethod":"CARD"}'

# ...then poll for the outcome. Within ~2s: "status":"CONFIRMED"
curl -s http://localhost:8083/api/orders/1
```

> **The `POST` returns `202 Accepted`, not `201 Created`, and the order comes back
> as `PENDING`.** That is correct. Stock has not been checked and no card has been
> charged yet — both happen on Kafka topics, usually within a second or two. See
> [The order flow](#the-order-flow).

| URL | What |
|---|---|
| <http://localhost:8081> | catalog |
| <http://localhost:8083> | sales |
| <http://localhost:9092>, <http://localhost:9093> | the two inventory replicas, addressed individually |
| <http://localhost:8090> | **kafka-ui** — topics, messages, consumer groups, lag |
| <http://localhost:5050> | pgAdmin (no login prompt — see [Security notes](#security-notes)) |
| `localhost:29092` | Kafka bootstrap, from the host |

`payment` has no host port and is no longer called over HTTP by anything; it is
reached by consuming `stock.reserved`. To exercise it directly, use
`docker compose exec payment wget -qO- http://localhost:8084/...`.

Tear down (`-v` also drops the database volume):

```bash
docker compose down        # keep data
docker compose down -v     # reset to a clean database
```

---

## API

Each service answers on its own port — there is no single base URL.

| Method | Path | Service | From the host | Notes |
|---|---|---|---|---|
| `GET` | `/api/products/{id}` | catalog | `:8081` | Product with category name |
| `GET` | `/api/products/{id}/stock` | catalog | `:8081` | **Cross-service** — catalog calls inventory over HTTP |
| `GET` | `/api/stock/{productId}` | inventory | `:9092` / `:9093` | Stock levels direct |
| `POST` | `/api/stock/reserve` | inventory | `:9092` / `:9093` | Reserve directly. **No longer on the order path** — kept for testing and for Labs 04/05 |
| `POST` | `/api/orders` | sales | `:8083` | Place an order — returns **`202 Accepted`**, then runs as a saga |
| `GET` | `/api/orders/{id}` | sales | `:8083` | Order with line items — **poll this for the outcome** |
| `POST` | `/api/payments` | payment | — | Charge directly. Also off the order path |
| `GET` | `/api/payments/order/{orderId}` | payment | — | Payment for an order |
| `GET`/`POST`/`DELETE` | `/api/lab/fault` | inventory | `:9092` / `:9093` | **Lab only** — make this replica fail or stall on demand |

Hitting an inventory replica by its own port pins the request to that one
container. Callers inside the network use `http://inventory:8082` instead and
get whichever replica DNS hands back.

> **`POST /api/orders` returns `202`, not `201`.** The order is durable and its
> saga is guaranteed to run, but stock has not been checked and no card has been
> charged. Returning `201` would claim the order succeeded at the moment nobody
> knows whether it will. Poll `GET /api/orders/{id}` — `CONFIRMED`, `REJECTED`,
> `PAYMENT_FAILED` and `CANCELLED` are the terminal states.

### The order flow

`POST /api/orders` exercises the whole system, now as a **choreographed saga** —
no service is in charge, each reacts to an event and publishes its own outcome.

```
client → sales   POST /api/orders
           │
           ├─ sync ─▶ catalog            price each line, compute total
           │
           └─ one transaction: order (PENDING) + outbox row
                      │
                      ▼  OutboxRelay
              [order.placed] ──▶ inventory
                                     │
                    ┌────────────────┴────────────────┐
                    ▼                                 ▼
            [stock.rejected]                  [stock.reserved]
                    │                                 │
                    ▼                    ┌────────────┴────────────┐
             sales: REJECTED             ▼                         ▼
                                       sales                    payment
                                  STOCK_RESERVED     ┌──────────────┴──────────────┐
                                                     ▼                             ▼
                                            [payment.completed]           [payment.failed]
                                                     │                             │
                                                     ▼                             ▼
                                            sales: CONFIRMED           sales: PAYMENT_FAILED
                                                                                   │
                                                                          [order.cancelled]
                                                                                   ▼
                                                                        inventory releases
                                                                                   │
                                                                          [stock.released]
                                                                                   ▼
                                                                          sales: CANCELLED
```

Order status advances `PENDING → STOCK_RESERVED → CONFIRMED`, or terminates at
`REJECTED` (no stock), or runs the compensation branch
`PAYMENT_FAILED → CANCELLED`. **`CANCELLED` now means something specific**: the
payment failed *and* the reserved stock has been confirmed back on the shelf. It
was previously an enum constant nothing ever set.

`catalog` also makes its own synchronous call to `inventory` for
`GET /api/products/{id}/stock`, which is what keeps a second, independent
east–west HTTP hop to observe in Labs 04 and 05.

**Topics** — seven, all keyed by `orderId` so one order's events stay ordered,
three partitions each, plus one dead-letter topic per consuming service:

| Topic | Producer | Consumers |
|---|---|---|
| `order.placed` | sales | inventory |
| `stock.reserved` | inventory | **sales *and* payment** (two groups) |
| `stock.rejected` | inventory | sales |
| `payment.completed` | payment | sales |
| `payment.failed` | payment | sales |
| `order.cancelled` | sales | inventory |
| `stock.released` | inventory | sales |
| `sales.dlt`, `inventory.dlt`, `payment.dlt` | — | dead letters, per consumer |

**Every state change is written with a transactional outbox**, and every consumer
de-duplicates against an inbox (`processed_events`) keyed on the producer-assigned
event id. Kafka delivers at least once; those two tables are what make that
survivable. [Lab 06](docs/labs/06-kafka.md) works through both.

---

## Test fixtures

The seed data contains deliberate edge cases:

| Fixture | Behaviour |
|---|---|
| **Any amount ending in `.13`** | Payment declines with `402` and `"Insufficient funds"` — see `PaymentService.processPayment` |
| **Product 10** (`13.13`, seeded with 0 stock) | Out-of-stock path; its price also triggers the decline rule |
| **Product 9** (seeded with 1 unit) | Low stock — for concurrent-reservation / race testing |
| **Product 11** (no row in `inventory`) | Stock lookup returns a genuine `404` — a business answer from a healthy service. Used in Lab 04 to show it never trips a circuit breaker |

Payments are idempotent per `orderId`: a second attempt for an order that already
has a payment returns the existing record rather than charging twice. On the event
path this is enforced **twice** — the consumer's inbox rejects a redelivered event,
and `chargeForOrder` independently refuses an order that already has a payment row.
Only one of those two has to fail for a customer to be charged twice, which is why
the service handling money has both.

To get a **declined** payment on products that are actually in stock, you need a
total ending in `.13`: `37 × 59.99 + 1 × 35.50 = 2255.13` works, and exercises
multi-line reserve *and* multi-line compensating release.

> **Seed drift.** `seed.sql` uses `ON CONFLICT (id) DO NOTHING`, so re-running it
> restores *missing* rows but never resets rows that have changed. Once you have
> placed orders, product 9 and 10 stock will no longer match the table above. Use
> `docker compose down -v` for a genuinely clean slate.

---

## Services

| Service | Internal port | Host port | Schema | DB user | Replicas |
|---|---|---|---|---|---|
| `catalog` | 8081 | 8081 | `catalog` | `catalog_user` | 1 |
| `inventory1`, `inventory2` | 8082 | 9092, 9093 | `inventory` | `inventory_user` | **2**, sharing the `inventory` alias **and one Kafka consumer group** |
| `sales` | 8083 | 8083 | `sales` | `sales_user` | 1 |
| `payment` | 8084 | — | `payment` | `payment_user` | 1 |
| `kafka` | 9092 (internal), 9093 (controller) | **29092** | — | — | 1 |
| `kafka-ui` | 8080 | **8090** | — | — | 1 |

> **Kafka publishes 29092, not 9092.** Host 9092 and 9093 already belong to the
> inventory replicas. Kafka's own 9092/9093 are container-internal and collide
> with nothing — only the published port had to move.

Every service exposes `/actuator/health`, `/actuator/info` and
`/actuator/metrics`. Nothing currently consults them to route traffic — they are
there to be read, not acted on.

**`X-Instance-Id`** — `inventory` stamps its container hostname on every HTTP
response. DNS rotation does not tell a caller which replica it reached, so this
header is the only way to observe distribution over HTTP. Addressing a replica from
the host pins it (`:9092` is always `inventory1`), so the interesting case is the
east–west hop: `catalog` reads the header off each reply and logs it as
`served-by`.

```bash
for i in $(seq 6); do curl -s -o /dev/null http://localhost:8081/api/products/1/stock; done
docker compose logs catalog | grep 'catalog->inventory'
```

**Expect one replica to serve all six.** Docker's DNS really does rotate — run
`docker compose exec catalog getent hosts inventory` a few times and the address
changes — but `RestClient` resolves the name when it opens a connection and then
keeps that connection alive, so subsequent requests reuse the socket without
asking DNS again. Balancing across replicas therefore happens per *connection*,
not per request, and a long-lived caller can sit on one replica indefinitely.
That is the honest state of "client-side load balancing" here: real, but far
coarser than the round-robin the alias suggests.

**On the Kafka path the same two containers behave completely differently.** They
share a `group-id`, so the broker assigns each partition of `order.placed` to
exactly one of them — a real 2/1 split, with no order ever reserved twice and no
coordination code making that true:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group inventory
```

Note that partition count is a hard ceiling: a third replica would take the third
partition, a fourth would join the group and receive nothing.

Both services also log one line per HTTP request (method, path, status, duration,
response body) via a `RequestResponseLoggingFilter`, with `/actuator` traffic
filtered out. Kafka listeners log their own line per record with topic, partition,
offset and key.

---

## Database

One PostgreSQL instance, four schemas, four users — each service owns its schema
and holds credentials for only that schema. `REVOKE ALL ON SCHEMA public FROM
PUBLIC` in `init.sql` enforces the boundary. No service reads another's tables;
they call each other's HTTP APIs or exchange events instead, which is the whole
point of the arrangement.

Schema is managed by Flyway (`spring.jpa.hibernate.ddl-auto=validate` — Hibernate
verifies the mapping and never alters the schema):

```
catalog/src/main/resources/db/migration/    V1 categories, V2 products
inventory/src/main/resources/db/migration/  V1 stock_items, V2 stock_movements,
                                            V3 outbox, V4 processed_events
sales/src/main/resources/db/migration/      V1 orders, V2 order_items,
                                            V3 outbox, V4 processed_events
payment/src/main/resources/db/migration/    V1 payments,
                                            V2 outbox, V3 processed_events
```

**`outbox` and `processed_events` exist in all three saga participants**, because
all three both produce and consume events:

| Table | Role |
|---|---|
| `outbox` | Events written *in the same transaction* as the business change, drained by a relay. Solves the dual-write problem: there is no ordering of "commit DB" and "publish" that is safe, so only one write happens. |
| `processed_events` | The inbox. One row per event already acted on, keyed on the producer-assigned event id. Makes at-least-once delivery survivable — a redelivered event finds its own row and does nothing. |

The outbox is never pruned: it doubles as an audit log of everything a service has
emitted. `payload` is `TEXT` rather than `jsonb` so the relay publishes exactly the
bytes that were committed — `jsonb` does not preserve key order or whitespace.

Direct access:

```bash
docker compose exec postgres psql -U postgres -d microservices
```

Useful saga queries:

```sql
-- anything stuck in an outbox? (should be 0 within a second)
SELECT count(*) FROM sales.outbox WHERE published_at IS NULL;

-- what did this order actually do to stock, including compensation?
SELECT order_id, movement_type, quantity FROM inventory.stock_movements
WHERE order_id = 5 ORDER BY id;
```

---

## Labs

Guided walkthroughs in [`docs/labs/`](docs/labs/) — each has a concept section,
the change, verification steps, and deliberate breakage with expected symptoms.

| Lab | Status |
|---|---|
| **01 — First run** | Referenced by the other labs but not yet written |
| [**02 — Service Discovery with Eureka**](docs/labs/02-service-discovery.md) | **Archived.** Does not match the running stack; kept for the registry model and its AP/staleness trade-offs |
| [**03 — Edge Gateway and Load Balancing with HAProxy**](docs/labs/03-haproxy-load-balancing.md) | **Archived.** The gateway has been removed; kept for path routing, `server-template` and DNS re-resolution |
| [**04 — Retries, Circuit Breakers and Fallbacks**](docs/labs/04-resilience4j.md) | **Current**, with one caveat below |
| [**05 — The Five Patterns, and the Order They Run In**](docs/labs/05-resilience-patterns.md) | **Current**, with the same caveat |
| [**06 — Event-Driven Orders with Kafka, the Outbox and a Saga**](docs/labs/06-kafka.md) | **Current.** The saga, the outbox, at-least-once delivery and compensation |

Labs 02 and 03 describe layers this project has since taken back out — read them
for the model, not as a description of what runs today.

> **Caveat on Labs 04 and 05.** Both were written when `sales` called `inventory`
> and `payment` over HTTP. Lab 06 replaced those two calls with events and deleted
> their breakers, bulkheads, rate limiters and fallbacks. Everything both labs say
> about **`catalog` → `inventory`** and **`sales` → `catalog`** still runs exactly
> as described — that is where the time limiter, the thread-pool bulkhead and the
> fault-injection demos live. The sections describing `sales` → `inventory` and
> `sales` → `payment` now describe a path that no longer exists; Lab 06 §2 explains
> why removing them was the correct outcome rather than a regression.

Lab 04 covers why timeouts must come before circuit breakers, why write paths are
not retried, and a circuit that opens without a single error. Lab 05 covers the
difference between a rate limiter and a bulkhead, the three separate things called
"timeout", and the fixed aspect order that decides which layer sees a failure
first. Lab 06 covers the dual-write problem, why at-least-once is the only delivery
guarantee on offer, and who gives the stock back when a card is declined.

---

## Load testing

```bash
source .venv/bin/activate
cd test
locust -f locustfile.py --host=http://localhost:8081 -u 300 -r 10 -t 10s
```

The default task hits `/api/products/1/stock`, so every request costs one
`catalog` call plus one `catalog` → `inventory` hop over Docker DNS — load on
`catalog` one-for-one, split across the inventory pair behind it.

`test/monitor.sh OUT.csv DURATION` samples `docker stats` for the key containers
plus the Postgres connection count, for correlating load against resource use.

Load testing is more interesting with a fault applied — set `inventory` to
`SLOW` and watch the circuit open under load, with response times dropping as
requests stop leaving `catalog`.

---

## Operations

```bash
docker compose ps                          # health of everything
docker compose logs -f sales               # follow one service
docker compose stop inventory1             # simulate a replica loss
docker compose exec catalog getent hosts inventory   # what DNS answers right now

curl -s localhost:8081/actuator/circuitbreakers | python3 -m json.tool   # breaker state
curl -s localhost:8083/actuator/circuitbreakers | python3 -m json.tool   # catalog only, in sales
docker compose logs catalog | grep '\[r4j\]'                            # resilience decisions
docker compose logs catalog | grep <X-Request-Id>                       # one request, end to end
```

**Kafka and the saga:**

```bash
# follow ONE order across all four services -- the listeners set the MDC to order-<id>
docker compose logs sales inventory1 inventory2 payment | grep 'order=5'

# saga state transitions only
docker compose logs sales | grep '\[saga\]'
# outbox activity: queued (in the business transaction) then published (by the relay)
docker compose logs sales | grep '\[outbox\]'
# every record consumed, with topic/partition/offset/key
docker compose logs payment | grep '\[kafka\]'

docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list

# THE diagnostic: which consumer is behind, and by how much
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --all-groups

# read a topic by hand
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic stock.reserved --from-beginning \
  --timeout-ms 5000

# anything stuck? (should be 0; non-zero means the relay cannot reach the broker)
docker compose exec -T postgres psql -U postgres -d microservices \
  -c "SELECT count(*) FROM sales.outbox WHERE published_at IS NULL;"
```

Or open <http://localhost:8090> — kafka-ui shows the same thing, with per-group lag
on the Consumer Groups page.

**Proving the saga survives a dead consumer:**

```bash
docker compose stop payment
curl -s -X POST localhost:8083/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":3,"quantity":2}],"paymentMethod":"CARD"}'
# order parks at STOCK_RESERVED; the payment group shows LAG 1
docker compose start payment
# ...and it completes on its own, with nothing retried by hand
```

**Making things fail on purpose** — `inventory` exposes a lab-only fault switch
(per replica, in memory, enabled by `LAB_FAULT_INJECTION`):

```bash
curl -X POST localhost:9092/api/lab/fault -H 'Content-Type: application/json' \
     -d '{"mode":"ERROR"}'                    # ERROR | SLOW | FLAKY | OK
curl -X DELETE localhost:9092/api/lab/fault   # back to healthy
```

Guided walkthroughs of what that produces:

```bash
cd test && ./resilience-demo.sh errors    # also: slow, recover, business, order
```

`docker compose watch` rebuilds a service when its `src/` or `pom.xml` changes —
`develop.watch` is configured for all four application services.

Building outside Docker:

```bash
cd catalog && ./mvnw clean package
```

Each module is standalone; there is no aggregator POM, so build them
individually.

### Troubleshooting

| Symptom | Cause |
|---|---|
| `404` on every `GET` | Seed data never loaded — see [Quick start](#quick-start) |
| `POST /api/orders` returns `202` and `PENDING` | Working as designed. Poll `GET /api/orders/{id}`; the saga resolves in ~1–2s |
| Order stuck at `PENDING` | `inventory` is not consuming. Check `--describe --group inventory` for lag, and that both replicas are up |
| Order stuck at `STOCK_RESERVED` | `payment` is not consuming, or its outcome has not come back. Check `--describe --group payment` for lag |
| Order stuck at `PAYMENT_FAILED` | Compensation is in flight or failed — `inventory` has not consumed `order.cancelled`. `CANCELLED` is the finished state |
| `sales.outbox` backlog not draining | The relay cannot reach the broker. Check `docker compose ps kafka` and the relay's `IllegalStateException` in the logs |
| Both replicas reserve the same order | The two `inventory` containers have different `group-id`s. They must share one — see [Lab 06 §4](docs/labs/06-kafka.md) |
| One `inventory` replica consumes nothing | Fewer partitions than group members. Topics are created with 3 partitions; check with `--describe --topic order.placed` |
| `No qualifying bean of type 'KafkaTemplate'` | `spring-kafka` was used instead of `spring-boot-starter-kafka` — the auto-configuration module is missing |
| Records piling up in `*.dlt` | A consumer threw 4 times on the same record. The cause is logged as `[kafka] DEAD-LETTER` |
| Kafka unreachable from the host | Use `localhost:29092`, not `:9092` — 9092 is `inventory1` |
| `Connection refused` on `:80` or `:8404` | Correct. The gateway was removed; address services on their own ports |
| `Connection refused` on `:8084` | Correct. `payment` publishes no host port, and nothing calls it over HTTP any more — it consumes `stock.reserved` |
| `200` with `"stockStatus": "UNAVAILABLE"` | Working as designed. The inventory call failed and the fallback returned the product without stock rather than failing the whole request |
| `503` with `"circuitOpen": true` | The `catalog` breaker is open; the call was never attempted. It retries automatically after 10s. Only the pricing call can produce this now |
| All traffic on one replica | Expected — see [Services](#services). Balancing is per connection, not per request |
| `docker compose stop inventory2` changes nothing | Also expected. A stopped container refuses the connection immediately, so the caller re-resolves and lands on the other replica. A container that is *up but failing* is what the circuit breaker is for |
| Circuit stuck `OPEN` after clearing a fault | It needs 3 successful trial calls in `HALF_OPEN`. Send some traffic, or wait ~10s and retry |
| `@Retry`/`@CircuitBreaker` seem to do nothing | `spring-boot-starter-aspectj` is missing. Boot 4 renamed it from `-aop`, and without it the annotations are ignored silently |

---

## Known limitations

These are scope decisions, not bugs — most are the subject of a future lab.

- **The outbox relay polls; it does not tail the log.** Debezium reading Postgres'
  WAL would publish with no polling and no added latency. The 500ms interval is a
  floor on every saga hop.
- **Relay ordering is per row, not per key.** `FOR UPDATE SKIP LOCKED` lets two
  relays claim disjoint batches and publish out of order. Harmless only because
  each service emits at most one event per order per step.
- **Nothing prunes `processed_events`.** It grows forever and is pure overhead once
  past the topic retention window. (`outbox` growing is deliberate — it is an audit
  log.)
- **Dead-letter topics are a dumping ground.** Records land in `*.dlt` and nothing
  routes, alerts on or replays them. A dead-lettered `order.placed` is an order that
  will never be reserved, and nobody is told.
- **No schema registry.** "Add fields, never remove them" is enforced by convention
  and review rather than at publish time.
- **One broker, replication factor 1.** `acks=all` is a formality with a single
  in-sync replica; lose the volume and you lose every retained event.
- **No saga timeouts.** If a consumer never answers, the order waits forever. A real
  saga runs a per-step deadline that triggers compensation.
- **The order flow exists only in documentation.** That is choreography's real cost:
  no class describes the lifecycle, so the diagrams above can drift from what runs.
- **`PaymentService.processPayment` (the HTTP path) rolls back its own failed row.**
  It saves the `FAILED` payment and then throws inside the same transaction, so the
  row is discarded. Over HTTP the caller still learns the outcome from the `402`, so
  this only loses an audit record — but it makes the `FAILED` branch of that method's
  idempotency check unreachable. The event path (`chargeForOrder`) does not have this
  problem, which is why it returns an outcome instead of throwing.
- **Circuit breakers are per instance.** Each replica learns independently that a
  dependency is down; there is no shared state between them.
- **Fault injection is unauthenticated.** `/api/lab/fault` can take `inventory`
  down and is gated only by `LAB_FAULT_INJECTION`, which `docker-compose.yml`
  sets to true.
- **No authentication or authorization** anywhere, on any endpoint.
- **Credentials are hardcoded** in `docker-compose.yml` and `init.sql`.
- **No metrics backend.** `/actuator/circuitbreakerevents` keeps a small in-memory
  buffer and consumer lag is only visible in kafka-ui; nothing collects or alerts.

**Closed by [Lab 06](docs/labs/06-kafka.md)** — both were listed here for the life
of the project:

- ~~No compensating transactions.~~ A declined payment now emits `order.cancelled`,
  `inventory` releases the units and confirms with `stock.released`, and the order
  reaches `CANCELLED`.
- ~~Unknown payment outcomes are left unresolved.~~ The instruction to charge is a
  durable record on a topic and the outcome is written with the payment row in one
  transaction. An unreachable `payment` is now consumer lag, not an unanswerable
  question — stop the container, place an order, start it again and the order
  completes on its own.

### Security notes

pgAdmin runs with `PGADMIN_CONFIG_SERVER_MODE: "False"`, meaning **no login
prompt**, on host port `5050`. Anything that can reach this machine on that port
gets an unauthenticated database admin console. That is fine on a laptop and
unacceptable anywhere shared — bind it to `127.0.0.1:5050` or enable server mode
before exposing this stack.
