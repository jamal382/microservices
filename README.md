# Spring Boot Microservices — Learning Project

A four-service e-commerce backend built to make distributed-systems concepts
concrete: service-to-service calls, an edge gateway, server-side vs. client-side
load balancing, schema-per-service data ownership, and the failure modes each of
those introduces.

It is deliberately not a template to copy into production. Several things are
simplified on purpose, and the interesting parts are the trade-offs — documented
inline in the code and worked through in [`docs/labs/`](docs/labs/).

---

## Architecture

```
                              ┌──────────────────────────┐
        client ─────────────▶ │   HAProxy :80            │   ← the only way in
                              │   routes by path prefix  │
                              │   stats :8404            │
                              └──┬────┬────┬────┬────┬───┘
                                 │    │    │    │    │
                        /products│    │    │    │    │/pgadmin
                                 ▼    │    │    ▼    ▼
                            catalog   │    │  payment  pgadmin
                          /stock     ▼    ▼   /orders
                            inventory×2  sales
                                 ▲    ▲         │
                                 │    └─────────┤  east–west calls go direct,
                                 └──────────────┘  via Docker DNS — not back
                                                   out through the gateway
```

**No service publishes a host port.** `catalog:8081`, `inventory:8082`,
`sales:8083`, `payment:8084` and `postgres:5432` are reachable only from inside
the `backend` Docker network. HAProxy is the single container with a host
binding, which is what makes the gateway non-optional rather than a convenience
you can route around.

**Two different resolution mechanisms, on purpose:**

| | North–south (outside → in) | East–west (service → service) |
|---|---|---|
| Handled by | HAProxy | Docker's embedded DNS (`127.0.0.11`) |
| In the request path | Yes, one hop | No — caller connects direct |
| Health-aware | **Yes** — `option httpchk` on `/actuator/health` | **No** — DNS reports existence, not readiness |
| Failure mode | one shared component; outage is total | per-caller; degrades independently |

`inventory1` and `inventory2` share the `inventory` network alias, so
`http://inventory:8082` resolves to **two** A records that Docker rotates. That
one name is how both HAProxy and `catalog` reach the replicas without either
naming a container.

> There is no service registry. This project previously ran Netflix Eureka; it
> was removed along with every Spring Cloud dependency. See
> [Lab 02](docs/labs/02-service-discovery.md) (archived) for what the registry
> did and which layer took over each of its jobs.

---

## Stack

| | |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.0 |
| Database | PostgreSQL 17 (`postgres:17-alpine`), one instance, schema per service |
| Migrations | Flyway, per service |
| Gateway | HAProxy 3.0 (`haproxy:3.0-alpine`) |
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
curl -s http://localhost/api/products/1
curl -s http://localhost/api/stock/1
curl -s -X POST http://localhost/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":5,"quantity":2}],"paymentMethod":"CARD"}'
```

The last one should return `201` with `"status":"CONFIRMED"`.

| URL | What |
|---|---|
| <http://localhost> | The gateway — all `/api/...` paths |
| <http://localhost/pgadmin> | pgAdmin (no login prompt — see [Security notes](#security-notes)) |
| <http://localhost:8404> | HAProxy stats dashboard |

Tear down (`-v` also drops the database volume):

```bash
docker compose down        # keep data
docker compose down -v     # reset to a clean database
```

---

## API

Everything is reached through `http://localhost` on port 80.

| Method | Path | Service | Notes |
|---|---|---|---|
| `GET` | `/api/products/{id}` | catalog | Product with category name |
| `GET` | `/api/products/{id}/stock` | catalog | **Cross-service** — catalog calls inventory |
| `GET` | `/api/stock/{productId}` | inventory | Stock levels direct |
| `POST` | `/api/stock/reserve` | inventory | Reserve units against an order |
| `POST` | `/api/orders` | sales | Place an order — orchestrates all three |
| `GET` | `/api/orders/{id}` | sales | Order with line items |
| `POST` | `/api/payments` | payment | Process a payment |
| `GET` | `/api/payments/order/{orderId}` | payment | Payment for an order |

Any path not matching a prefix above falls through to `default_backend
sales_backend` — a routing typo therefore lands on the wrong service rather than
failing loudly. That is deliberate and explored in Lab 03, Break 1.

### The order flow

`POST /api/orders` is the one request that exercises the whole system:

```
client → HAProxy → sales ─┬→ catalog        (fetch price, compute total)
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

Payments are idempotent per `orderId`: a second `POST` for an order that already
has a payment returns the existing record rather than charging twice.

> **Seed drift.** `seed.sql` uses `ON CONFLICT (id) DO NOTHING`, so re-running it
> restores *missing* rows but never resets rows that have changed. Once you have
> placed orders, product 9 and 10 stock will no longer match the table above. Use
> `docker compose down -v` for a genuinely clean slate.

---

## Services

| Service | Internal port | Schema | DB user | Replicas |
|---|---|---|---|---|
| `catalog` | 8081 | `catalog` | `catalog_user` | 1 |
| `inventory1`, `inventory2` | 8082 | `inventory` | `inventory_user` | **2**, sharing the `inventory` alias |
| `sales` | 8083 | `sales` | `sales_user` | 1 |
| `payment` | 8084 | `payment` | `payment_user` | 1 |

Every service exposes `/actuator/health`, `/actuator/info` and
`/actuator/metrics`. HAProxy health-checks the first of those; it is the contract
that decides whether an instance receives traffic.

**`X-Instance-Id`** — `inventory` stamps its container hostname on every
response. Neither balancing layer tells a caller which replica it reached, so
this header is the only way to observe distribution from the client side:

```bash
for i in $(seq 6); do
  curl -s -D- -o /dev/null http://localhost/api/stock/1 | grep -i x-instance-id
done
```

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
| [**03 — Edge Gateway and Load Balancing with HAProxy**](docs/labs/03-haproxy-load-balancing.md) | **Current.** Describes the system as it stands |

Lab 03 is the one to read. It covers path-based routing, `server-template` and
DNS re-resolution, why the host ports were removed, how a UI (pgAdmin) behind a
subpath proxy differs from an API, and four break-it-yourself exercises.

---

## Load testing

```bash
source .venv/bin/activate
cd test
locust -f locustfile.py --host=http://localhost -u 300 -r 10 -t 10s
```

The default task hits `/api/products/1/stock`, which crosses both balancing
layers in one request: HAProxy → `catalog`, then `catalog` → `inventory` over
Docker DNS.

`test/monitor.sh OUT.csv DURATION` samples `docker stats` for the key containers
plus the Postgres connection count, for correlating load against resource use.

---

## Operations

```bash
docker compose ps                          # health of everything
docker compose logs -f sales               # follow one service
docker compose restart haproxy             # reload gateway config after editing
docker compose stop inventory1             # simulate a replica loss
curl -s 'http://localhost:8404/;csv' | cut -d, -f1,2,18   # backend states
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
| `Connection refused` on `:8081`, `:9092`, `:5050` | Correct. Those host ports were removed; use `:80` |
| Slot shows `MAINT (resolution)` in stats | No DNS answer for that name — normal for unused template slots |
| Slot shows `DOWN` | Address resolves but `/actuator/health` failed |
| Fast `503` from the gateway | Every slot in that backend is down |
| Slow `503` with an `InventoryClient` log line | East–west call failed; no health check on that path to catch it early |
| pgAdmin loads unstyled with 404s for `/static/*` | `SCRIPT_NAME` not reaching the container |

---

## Known limitations

These are scope decisions, not bugs — most are the subject of a future lab.

- **No compensating transactions.** If payment fails after stock is reserved, the
  reservation is never released; the order just ends at `PAYMENT_FAILED`.
  `inventory` has no confirm/release endpoint. This is the gap a saga pattern
  would close, and it is flagged in `SalesService.placeOrder`.
- **No circuit breakers or retries.** The east–west path has no health checking
  at all — DNS will hand `catalog` the address of an `inventory` replica that is
  up but failing every request. Lab 03's Break 4 demonstrates it.
- **No authentication or authorization** anywhere, on any endpoint.
- **Credentials are hardcoded** in `docker-compose.yml` and `init.sql`.
- **`docs/PRD.md` is referenced** from code comments but does not exist in the
  repo.
- **Not a git repository** — there is no version history to fall back on.

### Security notes

pgAdmin runs with `PGADMIN_CONFIG_SERVER_MODE: "False"`, meaning **no login
prompt**, and it is served from the same `:80` as the API. Any firewall rule that
opens the API to a network also opens an unauthenticated database admin console
to it. That is fine on a laptop and unacceptable anywhere shared — restrict
`/pgadmin` with a `src` ACL in `infra/haproxy/haproxy.cfg`, or enable server
mode, before exposing this stack.
