# Spring Boot Microservices — Learning Project

A four-service e-commerce backend built to make distributed-systems concepts
concrete: service-to-service calls, client-side load balancing, schema-per-service
data ownership, retries and circuit breakers, and the failure modes each of those
introduces.

It is deliberately not a template to copy into production. Several things are
simplified on purpose, and the interesting parts are the trade-offs — documented
inline in the code and worked through in [`docs/labs/`](docs/labs/).

---

## Architecture

```
        client ──┬──▶ catalog :8081 ──┐
                 │                    │
                 ├──▶ sales   :8083 ──┼──▶ inventory ×2  (:9092 / :9093)
                 │         │          │
                 │         └──────────┴──▶ payment       (internal only)
                 │
                 └──▶ pgadmin :5050

        east–west calls resolve through Docker's embedded DNS;
        nothing sits in the request path between caller and callee
```

**There is no gateway and no service registry.** Clients address each service
directly on its own host port; services address each other by container name on
the `backend` Docker network. `payment:8084` and `postgres:5432` publish no host
port — `payment` is only ever called by `sales`, and the database is only ever
reached through a service or `docker compose exec`.

`inventory1` and `inventory2` share the `inventory` network alias, so
`http://inventory:8082` resolves to **two** A records that Docker rotates. That
one name is how `catalog` and `sales` reach the replicas without either naming a
container — client-side balancing with nothing in the middle.

**DNS reports existence, not readiness.** A replica that is up but failing every
request keeps receiving its share of traffic — nothing in the resolution path
health-checks anything. What stops that from taking callers down with it is
**Resilience4j**, applied at each caller rather than at the network layer:

| | |
|---|---|
| Timeouts | Every outbound call is bounded (1s connect, 2s read; 5s for payment) |
| Time limiter | A single 1.5s wall-clock deadline on `catalog` → `inventory` |
| Retry | Only on idempotent reads — `GET` product and stock |
| Circuit breaker | One per dependency, opening on failure rate **or** slow-call rate |
| Rate limiter | One per dependency, tightening toward the write paths (100/s → 25/s) |
| Bulkhead | One per dependency, capping how many threads a single callee can occupy |
| Fallback | Only where an honest degraded answer exists |

Two distinctions govern all of it. First, a 404, a 409 or a declined card are
**successful conversations with healthy services** — failures of the order, not
of the system, configured never to trip a breaker. Second, a rate-limiter or
bulkhead rejection is a call *this* service refused to make, so it is evidence
about us and not about the dependency; it is ignored by the breaker for the same
reason, and left unlisted it would quietly count as a **success** and prop up the
health metrics exactly when the system is saturated.

See [Lab 04](docs/labs/04-resilience4j.md) for retries and breakers, and
[Lab 05](docs/labs/05-resilience-patterns.md) for all five patterns and the fixed
order they compose in.

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
| Resilience | Resilience4j 2.4.0 (`resilience4j-spring-boot4`) |
| DB UI | pgAdmin 4 (`dpage/pgadmin4:9.13`) |
| Build | Maven wrapper (`./mvnw`) per module — no parent aggregator POM |
| Load testing | Locust |

Each service is an independent Maven project with its own `pom.xml` and
multi-stage `Dockerfile` (Maven build stage → `eclipse-temurin:21-jre-alpine`
runtime, running as a non-root `spring` user).

---

## Quick start

```bash
docker compose up -d --build
```

Wait for everything to report healthy (~40–60s on a cold build):

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
curl -s -X POST http://localhost:8083/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":5,"quantity":2}],"paymentMethod":"CARD"}'
```

The last one should return `201` with `"status":"CONFIRMED"`.

| URL | What |
|---|---|
| <http://localhost:8081> | catalog |
| <http://localhost:8083> | sales |
| <http://localhost:9092>, <http://localhost:9093> | the two inventory replicas, addressed individually |
| <http://localhost:5050> | pgAdmin (no login prompt — see [Security notes](#security-notes)) |

`payment` has no host port; it is reachable only from inside the network, which
means the only way to exercise it is through `sales` (or
`docker compose exec sales wget -qO- http://payment:8084/...`).

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
| `GET` | `/api/products/{id}/stock` | catalog | `:8081` | **Cross-service** — catalog calls inventory |
| `GET` | `/api/stock/{productId}` | inventory | `:9092` / `:9093` | Stock levels direct |
| `POST` | `/api/stock/reserve` | inventory | `:9092` / `:9093` | Reserve units against an order |
| `POST` | `/api/orders` | sales | `:8083` | Place an order — orchestrates all three |
| `GET` | `/api/orders/{id}` | sales | `:8083` | Order with line items |
| `POST` | `/api/payments` | payment | — | Process a payment |
| `GET` | `/api/payments/order/{orderId}` | payment | — | Payment for an order |
| `GET`/`POST`/`DELETE` | `/api/lab/fault` | inventory | `:9092` / `:9093` | **Lab only** — make this replica fail or stall on demand |

Hitting an inventory replica by its own port pins the request to that one
container. Callers inside the network use `http://inventory:8082` instead and
get whichever replica DNS hands back.

### The order flow

`POST /api/orders` is the one request that exercises the whole system:

```
client → sales ─┬→ catalog        (fetch price, compute total)
                ├→ inventory      (reserve stock)
                └→ payment        (charge)
```

Order status advances `PENDING → STOCK_RESERVED → CONFIRMED`, or terminates at
`REJECTED` (stock reservation failed) or `PAYMENT_FAILED`. `CANCELLED` exists in
the enum but nothing currently sets it.

`catalog` also makes its own call to `inventory` for
`GET /api/products/{id}/stock`, which is what gives the system a second,
independent east–west hop to observe.

---

## Test fixtures

The seed data contains deliberate edge cases:

| Fixture | Behaviour |
|---|---|
| **Any amount ending in `.13`** | Payment declines with `402` and `"Insufficient funds"` — see `PaymentService.processPayment` |
| **Product 10** (`13.13`, seeded with 0 stock) | Out-of-stock path; its price also triggers the decline rule |
| **Product 9** (seeded with 1 unit) | Low stock — for concurrent-reservation / race testing |
| **Product 11** (no row in `inventory`) | Stock lookup returns a genuine `404` — a business answer from a healthy service. Used in Lab 04 to show it never trips a circuit breaker |

Payments are idempotent per `orderId`: a second `POST` for an order that already
has a payment returns the existing record rather than charging twice.

> **Seed drift.** `seed.sql` uses `ON CONFLICT (id) DO NOTHING`, so re-running it
> restores *missing* rows but never resets rows that have changed. Once you have
> placed orders, product 9 and 10 stock will no longer match the table above. Use
> `docker compose down -v` for a genuinely clean slate.

---

## Services

| Service | Internal port | Host port | Schema | DB user | Replicas |
|---|---|---|---|---|---|
| `catalog` | 8081 | 8081 | `catalog` | `catalog_user` | 1 |
| `inventory1`, `inventory2` | 8082 | 9092, 9093 | `inventory` | `inventory_user` | **2**, sharing the `inventory` alias |
| `sales` | 8083 | 8083 | `sales` | `sales_user` | 1 |
| `payment` | 8084 | — | `payment` | `payment_user` | 1 |

Every service exposes `/actuator/health`, `/actuator/info` and
`/actuator/metrics`. Nothing currently consults them to route traffic — they are
there to be read, not acted on.

**`X-Instance-Id`** — `inventory` stamps its container hostname on every
response. DNS rotation does not tell a caller which replica it reached, so this
header is the only way to observe distribution. Addressing a replica from the
host pins it (`:9092` is always `inventory1`), so the interesting case is the
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

Both services also log one line per request (method, path, status, duration,
response body) via a `RequestResponseLoggingFilter`, with `/actuator` traffic
filtered out so health polling doesn't drown the log.

---

## Database

One PostgreSQL instance, four schemas, four users — each service owns its schema
and holds credentials for only that schema. `REVOKE ALL ON SCHEMA public FROM
PUBLIC` in `init.sql` enforces the boundary. No service reads another's tables;
they call each other's HTTP APIs instead, which is the whole point of the
arrangement.

Schema is managed by Flyway (`spring.jpa.hibernate.ddl-auto=validate` — Hibernate
verifies the mapping and never alters the schema):

```
catalog/src/main/resources/db/migration/    V1 categories, V2 products
inventory/src/main/resources/db/migration/  V1 stock_items, V2 stock_movements
sales/src/main/resources/db/migration/      V1 orders,      V2 order_items
payment/src/main/resources/db/migration/    V1 payments
```

Direct access:

```bash
docker compose exec postgres psql -U postgres -d microservices
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
| [**04 — Retries, Circuit Breakers and Fallbacks**](docs/labs/04-resilience4j.md) | **Current.** The three foundational patterns, in depth |
| [**05 — The Five Patterns, and the Order They Run In**](docs/labs/05-resilience-patterns.md) | **Current.** Adds rate limiters, bulkheads and time limiters |

Labs 02 and 03 describe layers this project has since taken back out — read them
for the model, not as a description of what runs today.

**Labs 04 and 05 describe what runs now.** Lab 04 covers why timeouts must come
before circuit breakers, why write paths are not retried, and a circuit that opens
without a single error. Lab 05 covers the difference between a rate limiter and a
bulkhead, the three separate things called "timeout", why a time limiter cancels
the waiting but not the work, and the fixed aspect order that decides which layer
sees a failure first.

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
curl -s localhost:8083/actuator/circuitbreakers | python3 -m json.tool   # all three in sales
docker compose logs catalog | grep '\[r4j\]'                            # resilience decisions
docker compose logs catalog | grep <X-Request-Id>                       # one request, end to end
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
`develop.watch` is configured for all four.

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
| `Connection refused` on `:80` or `:8404` | Correct. The gateway was removed; address services on their own ports |
| `Connection refused` on `:8084` | Correct. `payment` publishes no host port; call it through `sales` |
| `200` with `"stockStatus": "UNAVAILABLE"` | Working as designed. The inventory call failed and the fallback returned the product without stock rather than failing the whole request |
| `503` with `"circuitOpen": true` | The breaker is open; the call was never attempted. It retries automatically after 10s |
| All traffic on one replica | Expected — see [Services](#services). Balancing is per connection, not per request |
| `docker compose stop inventory2` changes nothing | Also expected. A stopped container refuses the connection immediately, so the caller re-resolves and lands on the other replica. A container that is *up but failing* is what the circuit breaker is for |
| Circuit stuck `OPEN` after clearing a fault | It needs 3 successful trial calls in `HALF_OPEN`. Send some traffic, or wait ~10s and retry |
| `@Retry`/`@CircuitBreaker` seem to do nothing | `spring-boot-starter-aspectj` is missing. Boot 4 renamed it from `-aop`, and without it the annotations are ignored silently |

---

## Known limitations

These are scope decisions, not bugs — most are the subject of a future lab.

- **No compensating transactions.** If payment fails after stock is reserved, the
  reservation is never released; the order just ends at `PAYMENT_FAILED`.
  `inventory` has no confirm/release endpoint. This is the gap a saga pattern
  would close, and it is flagged in `SalesService.placeOrder`.
- **Unknown payment outcomes are left unresolved.** If `payment` is unreachable
  the order stays at `STOCK_RESERVED` rather than `PAYMENT_FAILED` — that status
  would assert the charge did not happen, which nobody actually knows. Honest,
  but it needs reconciliation to finish the job.
- **No bulkheads.** A circuit breaker per dependency gives partial isolation, but
  one slow dependency can still occupy every request thread until its timeout.
- **Circuit breakers are per instance.** Each replica learns independently that a
  dependency is down; there is no shared state between them.
- **Fault injection is unauthenticated.** `/api/lab/fault` can take `inventory`
  down and is gated only by `LAB_FAULT_INJECTION`, which `docker-compose.yml`
  sets to true.
- **No authentication or authorization** anywhere, on any endpoint.
- **Credentials are hardcoded** in `docker-compose.yml` and `init.sql`.
- **No metrics backend.** `/actuator/circuitbreakerevents` keeps a small
  in-memory buffer; nothing collects or alerts on state transitions.

### Security notes

pgAdmin runs with `PGADMIN_CONFIG_SERVER_MODE: "False"`, meaning **no login
prompt**, on host port `5050`. Anything that can reach this machine on that port
gets an unauthenticated database admin console. That is fine on a laptop and
unacceptable anywhere shared — bind it to `127.0.0.1:5050` or enable server mode
before exposing this stack.
