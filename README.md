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

## Services

| Service | Port | Owns |
|---|---|---|
| catalog | 8081 | Products and categories |
| inventory | 8082 | Stock levels and reservations |
| sales | 8083 | Orders |
| payment | 8084 | Payments |

## Quick start

> Requires Docker, ~8 GB RAM free. Nothing is implemented yet — see [docs/PRD.md](docs/PRD.md) for the build plan.

```bash
# Phase 1 — core services only
docker compose up -d --build
curl localhost:8081/api/products

# pgAdmin — web UI for Postgres, at localhost:5050
# login: admin@example.com / admin (override via PGADMIN_EMAIL / PGADMIN_PASSWORD)
# add a server there pointing at host "postgres", port 5432

# Phase 2 — with the gateway, scaled catalog
docker compose up -d --scale catalog=3
curl localhost/api/products

# Phase 2 — with the full observability stack (~7 GB)
docker compose --profile observability --profile logging up -d
```

## Where things are

| | |
|---|---|
| **[docs/PRD.md](docs/PRD.md)** | **Start here.** Full requirements, architecture, data model, and rationale for every decision |
| `docs/architecture.md` | Diagrams and decision records |
| `docs/labs/` | Hands-on exercises — one per Phase 2 capability |
| `docs/troubleshooting.md` | Symptom → cause → fix |
| `docs/glossary.md` | Every term this project introduces |
| `infra/` | HAProxy, Postgres init, Prometheus, Grafana, Filebeat configs |

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
| Phase 1 — four Dockerized services | Not started |
| Phase 2 — distributed infrastructure | Not started |

Current repository contents are four Spring Initializr skeletons. Everything else is specified in the PRD and not yet built.
