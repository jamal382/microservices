# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A learning project for Spring Boot microservice architecture, built in two phases:

- **Phase 1**: four independent Spring Boot services (`catalog`, `inventory`, `sales`, `payment`) over one PostgreSQL instance, deployed with Docker Compose. Deliberately includes a hardcoded inter-service call (`sales` → `catalog`/`inventory`/`payment` via a fixed URL) as unaddressed technical debt.
- **Phase 2**: HAProxy edge gateway, instance scaling, Eureka service discovery, Spring Cloud LoadBalancer (client-side LB), Resilience4j circuit breakers, Kafka-based choreographed saga, and a full observability stack (Prometheus/Grafana, Jaeger, Filebeat/Elasticsearch/Kibana).

**`docs/PRD.md` is the authoritative spec** — full REST API contracts, DDL, dependency lists, Docker/Compose configs, per-capability rationale, acceptance criteria, port allocations, and a glossary. Read the relevant section there before implementing anything; this file only orients you at the codebase level.

## Current state

Only bare Spring Initializr skeletons exist right now: each of the four service directories has a single `*Application.java`, a one-line `application.properties` (just `spring.application.name`), and `spring-boot-starter`/`spring-boot-starter-test` as its only dependencies. No domain code, no persistence, no Docker files, no git repo yet. Everything else described in `docs/PRD.md` (REST layers, Flyway migrations, `eureka-server` module, `infra/`, `docker-compose.yml`) is planned but not yet built — check whether a given piece exists before assuming it does.

## Commands

Each service is an **independent Maven project with its own wrapper** — there is no parent/aggregator POM (deliberate: see "No shared parent POM" below). Run commands from inside each service directory.

```bash
# From e.g. catalog/, inventory/, sales/, or payment/
./mvnw clean compile          # build
./mvnw test                   # run tests
./mvnw test -Dtest=ClassName#methodName   # run a single test
./mvnw spring-boot:run         # run locally (needs Postgres reachable per its application.properties)
./mvnw clean package           # build the jar (target/*.jar)
```

There is no root-level build command — build/test each service individually, or `for d in catalog inventory sales payment; do (cd "$d" && ./mvnw test); done` to run all four.

Once Docker artifacts exist (Phase 1 build-out): `docker compose up -d --build` from the repo root brings up all services + Postgres; see `docs/PRD.md` §4.7 and §4.9 for the full compose shape and acceptance checks.

## Architecture

### Service boundaries (bounded contexts)

| Service | Port | Schema | Owns |
|---|---|---|---|
| `catalog` | 8081 | `catalog` | products, categories |
| `inventory` | 8082 | `inventory` | stock levels, reservations |
| `sales` | 8083 | `sales` | orders |
| `payment` | 8084 | `payment` | payments |

All four share **one Postgres container** but each has its own schema and DB user, granted privileges only on its own schema — enforced by Postgres, not convention. **There are no foreign keys across schemas.** `sales.order_items.product_id` is a plain `BIGINT`, never `REFERENCES catalog.products(id)`. Cross-service references are validated at the application boundary (an HTTP call, later a circuit-breaker-wrapped one), not the database. Do not add a cross-schema FK even if it looks like a quick correctness win — see `docs/PRD.md` §3.2 for the full rationale.

`order_items` also **denormalizes** `product_name`/`unit_price` at order-creation time rather than joining to `catalog` on read — an order is a historical record and must not reflect today's price if the product changes tomorrow.

### No shared parent POM

Each service manages its own `spring-boot-starter-parent` and (in Phase 2) its own `spring-cloud-dependencies` BOM import. This is intentional: a shared parent would quietly recreate the coupling the architecture is meant to demonstrate the absence of. Expect repeated dependency blocks across the four POMs — that repetition is by design, not an oversight to refactor away.

### Layering convention (once code exists)

```
com.finalearth.<service>
├── config/       @Configuration
├── controller/   @RestController — HTTP only, no business logic
├── service/      @Service — business logic, @Transactional here (not on controllers/repos)
├── repository/   Spring Data JPA interfaces
├── entity/       @Entity — never returned from a controller
├── dto/          request/response records — the actual public contract
├── mapper/       entity ↔ DTO
├── client/       outbound calls to other services (sales only, in Phase 1)
└── exception/    domain exceptions + one @RestControllerAdvice per service, using ProblemDetail
```

Key rules: entities never cross the controller boundary; DTOs are Java `record`s; every service has exactly one `@RestControllerAdvice` returning RFC 7807 `ProblemDetail`.

### Config convention

`application.properties` should give every environment-specific value an env-var override with a local-friendly default (e.g. `${DB_HOST:localhost}`), so the same jar runs unmodified on the host and in a container — no separate `application-docker.properties`. Use `spring.jpa.hibernate.ddl-auto=validate` (never `update`) since Flyway owns the schema; use `spring.jpa.open-in-view=false`.

### The Phase 1 → Phase 2 seam

`sales` calling `catalog`/`inventory`/`payment` via a hardcoded `${CATALOG_URL:http://localhost:8081}`-style property and `RestClient` is **intentional technical debt**, not a bug to fix opportunistically. Phase 2 replaces it with Eureka + `@LoadBalanced` `RestClient` resolving `http://catalog` as a service ID, wrapped in a Resilience4j circuit breaker. If asked to work on Phase 1, leave this as-is; if asked to work on Phase 2's service-discovery/load-balancing/resilience milestones, this is exactly what gets replaced (`docs/PRD.md` §5.4–§5.5).

### Versions

Spring Boot **4.1.0**, Java **21** (`--release 21`, host JDK may be newer), Spring Cloud **2025.1.2 "Oakwood"** (the release train compatible with Boot 4.1.0 — do not pin individual `spring-cloud-*` artifact versions alongside it). Full version matrix and port allocation table: `docs/PRD.md` §6.1–§6.2.
