# Spring Boot Microservices — Learning Project

A small e-commerce system built in two phases to learn microservice architecture from the problems up, rather than from a finished `docker-compose.yml` down.

**Stack:** Spring Boot 4.1.0 · Java 21 · PostgreSQL · Docker Compose · HAProxy · Eureka · Resilience4j · Kafka · Prometheus/Grafana · Jaeger · Elasticsearch/Kibana

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

| Service | Port | Owns |
|---|---|---|
| catalog | 8081 | Products and categories |
| inventory | 8082 | Stock levels and reservations |
| sales | 8083 | Orders |
| payment | 8084 | Payments |

---

## Quick start

> Requires Docker and ~8 GB RAM free.

```bash
# 1. Build and start everything
docker compose up -d --build
docker compose ps

# 2. Seed categories, products, and stock
docker compose exec -T postgres psql -U postgres -d microservices < infra/postgres/seed.sql

# 3. Verify
curl -s http://localhost:8081/api/products | jq
```

pgAdmin is at `localhost:5050` (`admin@example.com` / `admin`, override via `PGADMIN_EMAIL` / `PGADMIN_PASSWORD`). Add a server pointing at host `postgres`, port `5432`.

---

## Building

```bash
# All services, from the project root
for d in catalog inventory payment sales; do (cd "$d" && ./mvnw clean compile); done

# One service, packaged as a JAR
cd catalog && ./mvnw clean package -DskipTests
```

## Running

```bash
docker compose up -d --build        # start, rebuilding images
docker compose ps                   # container status
docker compose stop                 # stop, keep data
docker compose down -v              # stop and wipe volumes (clean DB reset)

# Two inventory replicas, to watch client-side load balancing
docker compose -f docker-compose.yml -f docker-compose.scale.yml up -d --scale inventory=2

# Full observability stack (~7 GB)
docker compose --profile observability --profile logging up -d
```

---

## Exercising the API

```bash
# Products
curl -s http://localhost:8081/api/products | jq

# Stock for product 1 — direct, and via catalog's call into inventory
curl -s http://localhost:8082/api/stock/1 | jq
curl -s http://localhost:8081/api/products/1/stock | jq

# Stock movement audit trail
curl -s http://localhost:8082/api/stock/1/movements | jq

# Happy path — places an order
curl -i -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": 100, "items": [{"productId": 1, "quantity": 2}], "paymentMethod": "CARD"}'
```

Two failure paths worth running, because they show where the interesting design decisions are:

```bash
# Out of stock → 409 Conflict, no reservation held
curl -i -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": 100, "items": [{"productId": 10, "quantity": 100}], "paymentMethod": "CARD"}'

# Payment declined → 402, and the stock reservation is compensated back
# (prices ending in .13 are rigged to decline)
curl -i -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": 100, "items": [{"productId": 10, "quantity": 1}], "paymentMethod": "CARD"}'
```

After the decline, re-check `/api/stock/1/movements` — the release should be there as its own entry.

---

## Watching what happens

```bash
docker compose logs -f                      # everything, live
docker compose logs -f sales                # one service
docker logs -f microservices-inventory-1    # one specific replica
docker compose logs --tail 50 catalog       # recent history instead of following
docker compose logs --since 5m inventory

# Just request/response lines, no Spring startup noise
docker compose logs -f | grep RequestResponseLoggingFilter

# Which inventory replica served each call
docker compose logs -f catalog | grep 'catalog->inventory'

# Replica IPs, to match against the log lines above
docker inspect -f '{{.Name}} {{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' \
  microservices-inventory-1 microservices-inventory-2
```

---

## Where things are

| | |
|---|---|
| **[docs/PRD.md](docs/PRD.md)** | **Start here.** Requirements, architecture, data model, and rationale for every decision |
| `docs/architecture.md` | Diagrams and decision records |
| `docs/labs/` | Hands-on exercises — one per Phase 2 capability |
| `docs/troubleshooting.md` | Symptom → cause → fix |
| `infra/` | HAProxy, Postgres init and seed, Prometheus, Grafana, Filebeat configs |

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
| Phase 1 — four Dockerized services | Working end to end, including compensating release on payment decline |
| Phase 2 — distributed infrastructure | In progress — inventory scaling and request logging in place |
