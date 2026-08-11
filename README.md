# Spring Boot Microservices — Learning Project

A small e-commerce system built in two phases to learn microservice architecture from the problems up, rather than from a finished `docker-compose.yml` down.

**In place:** Spring Boot 4.1.0 · Java 21 · PostgreSQL · Docker Compose · Eureka (Spring Cloud 2025.1.2)
**Planned:** HAProxy · Resilience4j · Kafka · Prometheus/Grafana · Jaeger · Elasticsearch/Kibana

---

## The idea

**Phase 1** builds four services that work, with deliberate problems left in: hardcoded service URLs, no resilience, no way to scale, no visibility.

**Phase 2** fixes those problems one at a time. Each fix is a lesson — and it lands because you felt the problem first.

```
catalog ──┐
inventory ├── 4 Spring Boot services ── 1 Postgres (schema per service)
sales     │
payment ──┘
```

| Service | Host port | Owns |
|---|---|---|
| catalog | 8081 | Products and categories |
| inventory | 9092, 9093 | Stock levels and reservations — **two replicas** |
| sales | 8083 | Orders |
| payment | 8084 | Payments |
| eureka | 8761 | Service registry (no domain of its own) |

Inventory is the scaled service, so it has no single address. Both replicas listen on 8082 *inside* the network and share the `inventory` alias; the two host ports exist only so you can address a specific replica from your laptop.

---

## Quick start

> Requires Docker and ~4 GB RAM free — the eight containers measure about 2.4 GB at idle.

```bash
# 1. Build and start everything
docker compose up -d --build
docker compose ps

# 2. Seed categories, products, and stock
docker compose exec -T postgres psql -U postgres -d microservices < infra/postgres/seed.sql

# 3. Verify
curl -s http://localhost:8081/api/products/1 | jq
```

pgAdmin is at `localhost:5050` (`admin@example.com` / `admin`, override via `PGADMIN_EMAIL` / `PGADMIN_PASSWORD`). Add a server pointing at host `postgres`, port `5432`.

The Eureka dashboard is at `localhost:8761`.

---

## Building

```bash
# All services, from the project root
for d in eureka-server catalog inventory payment sales; do (cd "$d" && ./mvnw clean compile); done

# One service, packaged as a JAR
cd catalog && ./mvnw clean package -DskipTests
```

## Running

```bash
docker compose up -d --build        # start, rebuilding images
docker compose ps                   # container status
docker compose stop                 # stop, keep data
docker compose down -v              # stop and wipe volumes (clean DB reset)

# Drop one inventory replica, to watch the registry evict it
docker compose stop inventory2
```

Both inventory replicas start by default — they are declared as two services (`inventory1`, `inventory2`) sharing an `inventory` network alias, not via `--scale`, so each keeps a stable host port you can curl individually.

---

## Exercising the API

There is no collection endpoint anywhere — every read is by ID. That's the current surface:

```bash
# A product, and the same product with stock joined in from inventory
curl -s http://localhost:8081/api/products/1 | jq
curl -s http://localhost:8081/api/products/1/stock | jq

# Stock, straight from a specific inventory replica
curl -s http://localhost:9092/api/stock/1 | jq
curl -s http://localhost:9093/api/stock/1 | jq

# Reserve stock directly, bypassing sales
curl -i -X POST http://localhost:9092/api/stock/reserve \
  -H "Content-Type: application/json" \
  -d '{"orderId": 999, "items": [{"productId": 1, "quantity": 1}]}'

# Happy path — places an order across all four services
curl -i -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": 100, "items": [{"productId": 1, "quantity": 2}], "paymentMethod": "CARD"}'

# Read it back
curl -s http://localhost:8083/api/orders/1 | jq
```

Two failure paths worth running, because they show where the interesting design decisions are:

```bash
# Out of stock → 409 Conflict, order left REJECTED
# (product 10 seeds with zero stock; a large quantity fails on any product)
curl -i -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": 100, "items": [{"productId": 10, "quantity": 100}], "paymentMethod": "CARD"}'

# Payment declined → 402, order left PAYMENT_FAILED
# The rule is "order total ends in .13", so this needs arithmetic:
# 87 × 59.99 = 5219.13. Product 8 is seeded with 100 in stock.
curl -i -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": 100, "items": [{"productId": 8, "quantity": 87}], "paymentMethod": "CARD"}'

# Or hit the decline rule directly, without the order flow
curl -i -X POST http://localhost:8084/api/payments \
  -H "Content-Type: application/json" \
  -d '{"orderId": 999, "amount": 10.13, "method": "CARD"}'
```

**After the decline, re-check the stock.** The reservation from step 5 is still held — sales marks the order `PAYMENT_FAILED` and stops, because inventory has no release endpoint. That leak is the missing compensating action in the saga, and it is the most instructive thing in the codebase right now.

---

## Service discovery

Catalog resolves inventory through Eureka; sales still reaches it through the Docker DNS alias. Same two replicas, two mechanisms, on purpose.

```bash
# Who is registered
curl -s -H 'Accept: application/json' http://localhost:8761/eureka/apps | jq \
  '.applications.application[] | {name, instances: [.instance[] | {instanceId, ipAddr, port: .port."$", status}]}'

# Catalog's own view — the discoveryComposite component is its registry client
curl -s http://localhost:8081/actuator/health | jq '.components.discoveryComposite'

# Watch round-robin: repeat this and the served-by instance alternates
for i in $(seq 6); do curl -s http://localhost:8081/api/products/1/stock > /dev/null; done
docker compose logs --tail 6 catalog | grep 'catalog->inventory'

# Stop a replica and watch it leave the registry (~30s: 10s heartbeat, 30s lease)
docker compose stop inventory2
```

Full walkthrough, including the deliberate breakages: [docs/labs/02-service-discovery.md](docs/labs/02-service-discovery.md).

---

## Watching what happens

```bash
docker compose logs -f                      # everything, live
docker compose logs -f sales                # one service
docker compose logs -f inventory1           # one specific replica
docker compose logs --tail 50 catalog       # recent history instead of following
docker compose logs --since 5m inventory1

# Just request/response lines, no Spring startup noise
docker compose logs -f | grep RequestResponseLoggingFilter

# Which inventory replica served each call — the X-Instance-Id header
# is stamped by inventory and logged by the caller
docker compose logs -f catalog | grep 'catalog->inventory'

# Replica IPs, to match against what the registry reports
docker inspect -f '{{.Name}} {{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \
  inventory1 inventory2
```

---

## Where things are

| | |
|---|---|
| **[docs/PRD.md](docs/PRD.md)** | **Start here.** Requirements, architecture, data model, and rationale for every decision |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Diagrams and the Phase 1 request flows |
| [docs/labs/](docs/labs/) | Hands-on exercises — one per Phase 2 capability |
| [docs/learning-qa.md](docs/learning-qa.md) | Interview Q&A, each answer anchored to code in this repo |
| `infra/postgres/` | Schema/user init and seed data |
| `test/` | Locust load test and the `docker stats` sampler used for the numbers in the Q&A |

## What you should be able to explain when done

- Why there are two load balancers in the request path, and what each is for
- Why services must not share database tables, and what replaces the foreign key you gave up
- What a circuit breaker does when half-open, and why fallbacks are a design decision
- When events beat synchronous calls — and what you give up in exchange
- Which of metrics, traces, and logs answers which question

## Status

| Phase | State |
|---|---|
| PRD | Complete |
| Phase 1 — four Dockerized services | Working end to end. Known gap: a declined payment does not release reserved stock |
| Phase 2 — service discovery | Eureka registry running; catalog resolves `inventory` via `@LoadBalanced` client-side load balancing |
| Phase 2 — HAProxy, Resilience4j, Kafka, observability | Not started |
