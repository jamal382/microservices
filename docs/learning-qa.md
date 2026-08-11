# Interview Q&A — Java, Spring Boot, Microservices, Docker

Common interview questions, answered the way you'd answer them out loud. Each answer is
short first, then anchored with **"In this project"** — a concrete example from this
codebase, which is what turns a memorized definition into a credible answer.

1. [Core Java](#1-core-java)
2. [Spring Core and Spring Boot](#2-spring-core-and-spring-boot)
3. [REST APIs with Spring MVC](#3-rest-apis-with-spring-mvc)
4. [Spring Data JPA and Hibernate](#4-spring-data-jpa-and-hibernate)
5. [Transactions](#5-transactions)
6. [Database and migrations](#6-database-and-migrations)
7. [Microservices architecture](#7-microservices-architecture)
8. [Inter-service communication and resilience](#8-inter-service-communication-and-resilience)
9. [Docker](#9-docker)
10. [Docker Compose and load balancing](#10-docker-compose-and-load-balancing)
11. [Performance and concurrency](#11-performance-and-concurrency)
12. [Testing](#12-testing)
13. [Questions about the project itself](#13-questions-about-the-project-itself)

---

## 1. Core Java

**Q: What is a Java `record` and when would you use one?**

An immutable data carrier. The compiler generates the constructor, accessors, `equals`,
`hashCode`, and `toString` from the header. Use it for data that is defined entirely by its
values — DTOs, value objects, API request/response bodies. Don't use it where you need
mutability, inheritance, or JPA entities (Hibernate needs a no-arg constructor and mutable
fields).

*In this project:* every DTO is a record — `ProductDto`, `CreateOrderRequest`, `OrderDto`.
The API contract is a set of immutable values, and entities stay separate, mutable classes.

**Q: Why `BigDecimal` for money and never `double`?**

`double` is binary floating point and cannot represent `0.1` exactly, so money arithmetic
accumulates error — `0.1 + 0.2 != 0.3`. `BigDecimal` is arbitrary-precision decimal with
explicit scale and rounding, so results match what an accountant expects.

*In this project:* prices and totals are `BigDecimal` end to end, and the DDL uses
`NUMERIC(12,2)`. The payment decline rule depends on exact decimal comparison:
`amount.remainder(BigDecimal.ONE).compareTo(new BigDecimal("0.13")) == 0`.

**Q: Why compare `BigDecimal` with `compareTo` instead of `equals`?**

`equals` compares scale as well as value, so `new BigDecimal("1.0").equals(new
BigDecimal("1.00"))` is `false`. `compareTo` compares numeric value only, which is almost
always what you mean.

**Q: Checked vs unchecked exceptions — which do you use and why?**

Checked exceptions force the caller to handle or declare them; unchecked ones don't. Modern
Spring code favours unchecked, because a checked exception thrown four layers down pollutes
every signature between there and the handler, and callers usually can't do anything
meaningful with it anyway. Spring itself wraps `SQLException` (checked) into
`DataAccessException` (unchecked) for exactly this reason.

*In this project:* `InsufficientStockException` and `PaymentDeclinedException` are
unchecked, and one `@RestControllerAdvice` per service turns them into HTTP responses.

**Q: `==` vs `equals` for objects, and the `equals`/`hashCode` contract?**

`==` compares references; `equals` compares logical value. If you override `equals` you must
override `hashCode` so that equal objects have equal hash codes — otherwise the object
breaks in `HashMap` and `HashSet` (you put it in and can't find it again).

**Q: What is `Optional` for, and how should it not be used?**

It's a return type that makes "may be absent" explicit at compile time instead of a `null`
that blows up at runtime. Use it as a return value; don't use it for fields or method
parameters, and don't call `.get()` without checking.

*In this project:* `stockItemRepository.findByProductId(id)` returns
`Optional<StockItem>`, and the caller uses `orElseThrow(...)` to convert absence into a
404 or an `InsufficientStockException` at the point where the decision belongs.

**Q: Difference between `List`, `Set`, and `Map`?**

`List` is ordered and allows duplicates; `Set` is unordered (unless it's `LinkedHashSet` /
`TreeSet`) and rejects duplicates; `Map` is key→value with unique keys. Choose by the
invariant you want the collection itself to enforce.

**Q: What does the Streams API give you over a `for` loop?**

Declarative transformation — you describe what you want rather than how to iterate, and the
pipeline (`filter`/`map`/`collect`) composes. It's not automatically faster; for simple
loops it can be slower. Use it for clarity on transformations, not as a reflex.

*In this project:* mapping entities to DTOs —
`items.stream().map(i -> new OrderItemDto(...)).toList()`.

**Q: What's new in Java 21 that you actually use?**

Records, sealed types, pattern matching for `switch`, text blocks, and virtual threads. In
Spring Boot, virtual threads matter most: `spring.threads.virtual.enabled=true` lets a
blocking web app handle far more concurrent requests without a large platform-thread pool,
because a blocked virtual thread doesn't pin an OS thread.

---

## 2. Spring Core and Spring Boot

**Q: What is Inversion of Control and Dependency Injection?**

IoC means the framework, not your code, decides when objects are created and wired. DI is
how it delivers them: you declare what you need, the container supplies it. The benefit is
that a class depends on an interface it can be handed, so it can be tested with a stub and
reconfigured without editing it.

**Q: Constructor injection vs field injection — which and why?**

Constructor injection. It makes dependencies explicit and mandatory, allows `final` fields,
fails fast at startup if something is missing, and lets you build the object in a unit test
with `new` and no Spring at all. Field injection (`@Autowired` on a field) hides
dependencies and requires reflection to test.

*In this project:* every service and client uses constructor injection —
`SalesService(OrderRepository, CatalogClient, InventoryClient, PaymentClient)`.

**Q: `@Component`, `@Service`, `@Repository`, `@Controller` — what's the real difference?**

`@Service` and `@Controller` are `@Component` with a semantic label; they behave the same
for scanning. `@Repository` is the exception — it adds exception translation, converting
vendor-specific `SQLException`s into Spring's `DataAccessException` hierarchy. The rest is
communication of intent to the next developer.

**Q: What does `@SpringBootApplication` do?**

It's three annotations: `@Configuration` (this class can define beans),
`@ComponentScan` (scan this package and below), and `@EnableAutoConfiguration` (configure
beans based on what's on the classpath).

**Q: How does auto-configuration work?**

Boot ships `@AutoConfiguration` classes guarded by `@Conditional` annotations. At startup it
evaluates them: if `spring-boot-starter-data-jpa` and a Postgres driver are on the
classpath and no `DataSource` bean exists, it creates one from your properties. It is always
conditional and always backs off if you define the bean yourself. `--debug` prints the
conditions report showing what matched and what didn't.

**Q: What is a "starter"?**

A curated dependency aggregate. `spring-boot-starter-web` pulls in Spring MVC, Jackson, and
embedded Tomcat at versions known to work together, so you manage one coordinate instead of
twenty.

*In this project:* `web`, `data-jpa`, `validation`, `actuator`, `flyway`, plus the Postgres
driver at `runtime` scope.

**Q: Default bean scope, and when would you change it?**

Singleton — one instance per application context. That's why singleton beans must be
stateless or use only immutable state; mutable instance fields on a `@Service` are a
concurrency bug waiting for load. Other scopes (`prototype`, `request`, `session`) exist but
are rare in a well-designed service layer.

**Q: How do you externalize configuration, and what's the precedence order?**

Command-line args beat environment variables, which beat `application-{profile}.properties`,
which beat `application.properties`, which beat defaults in code. Anything
environment-specific should be a placeholder with a sane default.

*In this project:* `spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/...`
— the same jar runs on a laptop against `localhost` and in Compose against `postgres`,
because Compose sets `DB_HOST=postgres`. No `application-docker.properties` to drift.

**Q: `@Value` vs `@ConfigurationProperties`?**

`@Value` injects one property; `@ConfigurationProperties` binds a group of related
properties onto a typed object, with relaxed binding and validation support. Use `@Value`
for one or two values, `@ConfigurationProperties` once a prefix has several.

**Q: What is Spring Boot Actuator and which endpoints matter?**

Production-readiness endpoints over HTTP: `/actuator/health` (liveness/readiness, used by
orchestrators), `/actuator/metrics` and `/actuator/prometheus` (monitoring), `/actuator/info`.
Expose deliberately — `management.endpoints.web.exposure.include=health,info,metrics` — never
`*` in production.

*In this project:* the Compose healthchecks call `/actuator/health`, which is what makes
`depends_on: condition: service_healthy` work.

**Q: How would you handle different environments?**

Spring profiles (`@Profile`, `application-prod.properties`, `SPRING_PROFILES_ACTIVE`), or —
preferred here — environment variables with defaults so there's exactly one artifact and one
property file. Fewer files means fewer chances for a value to be right in one and wrong in
another.

---

## 3. REST APIs with Spring MVC

**Q: `@Controller` vs `@RestController`?**

`@RestController` = `@Controller` + `@ResponseBody`, so return values are serialized to the
response body (JSON) rather than resolved as view names.

**Q: Why not return JPA entities straight from a controller?**

Because then your database schema *is* your public API. Rename a column and every client
breaks; add a sensitive field and you leak it; lazy associations serialize into extra
queries or exceptions. A DTO is a contract you change on purpose.

*In this project:* a hard rule — entities never cross the controller boundary, and every DTO
is a record.

**Q: How do you return proper status codes?**

`ResponseEntity` for explicit control, or `@ResponseStatus`. The ones that come up here:

| Code | Meaning |
|---|---|
| 200 / 201 | OK / created |
| 400 | Malformed or invalid request |
| 404 | Resource doesn't exist |
| 409 | Valid request, current state refuses it — e.g. insufficient stock |
| 402 | Payment declined |
| 503 | Downstream dependency unreachable |

The 409-vs-400 distinction is worth being able to defend: the caller sent a perfectly valid
request, so it isn't their fault — the *state* made it impossible.

**Q: How do you do centralized exception handling?**

One `@RestControllerAdvice` per service with `@ExceptionHandler` methods, returning RFC 7807
`ProblemDetail` so every error has the same machine-readable shape (`status`, `title`,
`detail`) regardless of which endpoint produced it.

*In this project:* each service has exactly one handler covering `ResponseStatusException`,
`MethodArgumentNotValidException`, and a catch-all `Exception`.

**Q: How does request validation work?**

`spring-boot-starter-validation` (Jakarta Bean Validation): annotate the DTO
(`@NotNull`, `@Min`, `@Positive`), put `@Valid` on the controller parameter, and a violation
throws `MethodArgumentNotValidException` — which the advice turns into a 400 listing the
offending fields.

**Q: `@RequestParam` vs `@PathVariable` vs `@RequestBody`?**

`@PathVariable` identifies the resource (`/api/products/{id}`), `@RequestParam` filters or
pages it (`?page=2`), `@RequestBody` carries the payload for POST/PUT.

**Q: What makes an API idempotent, and why does it matter?**

An idempotent operation has the same effect whether applied once or five times. GET, PUT,
and DELETE should be; POST usually isn't. It matters because in a distributed system the
caller can time out without knowing whether the work happened, so it retries — and a
non-idempotent POST becomes a double charge.

*In this project:* `PaymentService.processPayment` looks up an existing payment by `orderId`
first and returns it instead of charging again.

**Q: `RestTemplate` vs `WebClient` vs `RestClient`?**

`RestTemplate` is the legacy blocking client (maintenance mode). `WebClient` is reactive and
non-blocking. `RestClient` (Boot 3.2+) is the modern *synchronous* client with a fluent API —
the right default for blocking MVC code, which is what all four services here are.

*In this project:* every outbound call uses `RestClient.builder().baseUrl(...).build()`.

**Q: How would you version a REST API?**

URL versioning (`/api/v1/...`) is the most visible and easiest to route at a gateway; header
or media-type versioning keeps URLs clean but is easier to get wrong. Better than either:
make changes additive so you rarely need a version — add fields, never remove or repurpose
them.

---

## 4. Spring Data JPA and Hibernate

**Q: JPA vs Hibernate vs Spring Data JPA?**

JPA is the specification, Hibernate is the implementation Boot ships by default, and Spring
Data JPA is a layer on top that generates repository implementations from interfaces.

**Q: How do Spring Data repositories work with no implementation class?**

At startup Spring creates a proxy for each repository interface and derives queries from the
method names — `findByProductId` parses to `WHERE product_id = ?`. Anything the parser can't
express you write yourself with `@Query`.

**Q: What is the N+1 select problem and how do you fix it?**

You load N orders with one query, then touching each order's items fires one more query per
order — N+1 round trips. Fixes: a `JOIN FETCH` query, an `@EntityGraph`, or batch fetching
(`hibernate.default_batch_fetch_size`). Diagnose it by turning on
`spring.jpa.show-sql` and counting.

**Q: `FetchType.LAZY` vs `EAGER` — what's your default?**

LAZY for every association. EAGER means every load of the parent drags the children along,
including in the many places you didn't want them, and it's invisible at the call site. LAZY
plus an explicit fetch when you need it keeps the cost where you can see it. (`@OneToMany`
is LAZY by default; `@ManyToOne` is EAGER by default, which is worth overriding.)

**Q: What does `spring.jpa.open-in-view=false` do and why disable it?**

Open-in-view keeps the persistence context open through view rendering, so a lazy collection
touched during JSON serialization silently fires extra queries outside any transaction. It
hides N+1 problems and holds a database connection for the whole request. Turning it off
surfaces the mistake as a `LazyInitializationException` in development.

*In this project:* set to `false` in all four services.

**Q: Explain `CascadeType.ALL` and `orphanRemoval`.**

Cascade propagates operations from parent to children — persisting an order persists its
items. `orphanRemoval = true` deletes a child that's removed from the parent's collection.
Together they model "the children have no life of their own outside the parent", which is
true of order items and false of most other relationships.

*In this project:* `Order` has
`@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)` plus an
`addItem()` helper that sets both sides of the relationship — forgetting the back-reference
is a classic bug that shows up as a null FK.

**Q: What is dirty checking?**

Inside a transaction, Hibernate compares each managed entity against its loaded snapshot at
flush time and issues UPDATEs for what changed. That's why modifying an entity inside a
`@Transactional` method persists without an explicit `save()`.

**Q: Optimistic vs pessimistic locking?**

Optimistic uses a `@Version` column: readers don't block, and the second writer to commit
gets an `OptimisticLockException` and retries. Pessimistic takes a database lock
(`SELECT ... FOR UPDATE`) and blocks other writers. Optimistic suits low-contention,
short-transaction workloads; pessimistic suits high contention on a hot row.

*In this project:* `stock_items` carries a `version` column. Without it, two concurrent
reservations both read `quantity_available = 10`, both write `7`, and one reservation is
silently lost.

**Q: What are the entity lifecycle states?**

Transient (new, not in the persistence context), managed (attached, tracked by dirty
checking), detached (was managed, context closed), removed (scheduled for delete).

**Q: `save()` vs `saveAndFlush()`?**

`save` stages the change; the SQL goes out at flush, usually at commit. `saveAndFlush`
forces the SQL immediately — needed when you must read your own write through a native query
or want a constraint violation to surface at that exact line.

---

## 5. Transactions

**Q: What does `@Transactional` actually do?**

Spring wraps the bean in a proxy that opens a transaction before the method, commits on
normal return, and rolls back on an unchecked exception.

**Q: Which layer do you put it on, and why not the controller or repository?**

The service layer. That's where a unit of business work is defined — a controller shouldn't
know about transactions, and a repository is a single operation that's usually too small to
be the boundary. Putting it on the service means "reserve stock for all items" is atomic as
a whole.

**Q: The classic `@Transactional` gotcha?**

Self-invocation. Calling a `@Transactional` method from another method *of the same class*
bypasses the proxy entirely, so no transaction starts and no error is reported. Also: by
default only unchecked exceptions trigger rollback — a checked exception commits unless you
declare `rollbackFor`.

**Q: What is `@Transactional(readOnly = true)` worth?**

It hints the driver and lets Hibernate skip dirty-checking snapshots, and it documents
intent. On a read-heavy service class the usual pattern is `readOnly = true` at class level
with the writing methods overriding it.

*In this project:* `InventoryService` and `PaymentService` are both
`@Transactional(readOnly = true)` at class level, with `reserveStock` and `processPayment`
marked `@Transactional`.

**Q: Name the propagation types you'd actually use.**

`REQUIRED` (default — join the existing transaction or start one), `REQUIRES_NEW` (suspend
and start an independent one; useful for audit writes that must survive a rollback), and
`SUPPORTS`/`NOT_SUPPORTED` occasionally. The rest are rare enough to look up.

**Q: What are the isolation levels and which do you use?**

READ_UNCOMMITTED, READ_COMMITTED, REPEATABLE_READ, SERIALIZABLE — trading concurrency for
protection against dirty reads, non-repeatable reads, and phantom reads. Postgres defaults
to READ_COMMITTED, which is right for almost everything; raise it only for a specific proven
anomaly.

**Q: Can a `@Transactional` method roll back an HTTP call it made?**

No — and this is the single most important thing to be able to say in a microservices
interview. A database transaction covers one database. Once your service calls another
service over HTTP, that service has committed its own transaction in its own database, and
your rollback cannot reach it. You need a **compensating action** — a second call that
semantically undoes the first.

*In this project:* `SalesService.placeOrder` reserves stock in inventory, then calls
payment. If payment declines, the local order row rolls back to `PAYMENT_FAILED`, but the
reservation in inventory's database is already committed and stays there. It's the exact gap
a saga is designed to close.

---

## 6. Database and migrations

**Q: Why Flyway instead of `hibernate.ddl-auto=update`?**

Because two components cannot both own the schema. Flyway applies versioned, ordered,
checksummed SQL files and records them in `flyway_schema_history`, so every environment gets
the same schema by the same path and you can review the change in a pull request.
`ddl-auto=update` mutates tables at startup based on entity state — it never drops anything,
it can't do data migrations, and the result depends on what started when.

*In this project:* Flyway owns the schema; `spring.jpa.hibernate.ddl-auto=validate` makes
Hibernate verify the entities match at boot and fail fast if they don't.

**Q: How do you run four services' migrations against one Postgres instance?**

Give each its own schema and its own history table:

```properties
spring.flyway.schemas=catalog
spring.flyway.default-schema=catalog
```

Without this all four fight over a single shared `flyway_schema_history`.

**Q: How do you enforce that one service can't read another's tables?**

Database permissions, not code review. Each service gets its own Postgres user granted
privileges only on its own schema.

*In this project:* `infra/postgres/init.sql` creates `catalog_user`, `inventory_user`,
`sales_user`, `payment_user`, grants each only its own schema, and revokes `public`. A
cross-schema query fails at the database, not in review.

**Q: When would you add an index, and what does it cost?**

Add one for columns in `WHERE`, `JOIN`, and `ORDER BY` on tables large enough for a
sequential scan to hurt. It costs write throughput and disk, because every insert and update
maintains it. Verify with `EXPLAIN ANALYZE` rather than guessing.

*In this project:* `idx_order_items_order` on `order_items(order_id)`, because items are
always fetched by order.

**Q: What is a connection pool and why does pool size matter more than you'd think?**

The pool keeps a fixed set of open connections and hands them out, because opening a
Postgres connection is expensive. The size caps concurrency at the database — beyond it,
threads queue. Bigger is not better: past the point where the database's CPU and disk are
saturated, more connections add context switching and lock contention, and throughput goes
*down*.

*In this project:* under a 1,000-user load test, Postgres connections held flat at **41**
from idle to peak. The queue formed in front of the pool, inside the application, not at the
database.

---

## 7. Microservices architecture

**Q: Monolith vs microservices — when is each right?**

A monolith is simpler to build, deploy, test, and debug, and it has real transactions.
Microservices buy independent deployment, independent scaling, team autonomy, and fault
isolation — and charge you network failures, eventual consistency, distributed debugging,
and operational overhead. Start with a monolith unless you already have the scale or the
team structure that pays for the split.

**Q: How do you decide service boundaries?**

By business capability and data ownership, not by technical layer. A "database service" or
"validation service" is the wrong cut — every feature would touch all of them. The test:
does a typical change land in one service? If most changes span three, the boundaries are
wrong.

*In this project:* catalog owns products, inventory owns stock, sales owns orders, payment
owns payments — each is the sole writer of its data.

**Q: What is a bounded context?**

The scope within which a term has one unambiguous meaning. "Product" means price and
description in catalog, a quantity in inventory, and a name-and-price snapshot in sales.
Rather than forcing one shared `Product` class, each context keeps its own model — the
boundary is what lets them differ.

**Q: Database-per-service — why, and what does it cost?**

Why: a shared database is a shared schema, and a shared schema means nobody can change a
column without coordinating with everyone. Cost: no joins across services, no foreign keys
across services, no distributed transactions. You trade queries for calls and consistency
for autonomy.

*In this project:* one Postgres container, but schema-per-service with per-service users, so
the isolation is enforced even though the process is shared.

**Q: If you can't have a foreign key across services, what replaces it?**

Validation at the application boundary. The referencing service asks the owning service
whether the ID exists and refuses the operation if not.

*In this project:* `sales.order_items.product_id` is a plain `BIGINT`, and sales calls
`GET /api/products/{id}` on catalog before accepting the item. The check moved from the
database to an HTTP call — which can also be slow, fail, or return stale data. That's the
trade.

**Q: Why does `order_items` store `product_name` and `unit_price` instead of joining?**

An order is a historical record. If the price changes tomorrow, last week's order must still
show what was actually paid. This denormalization isn't a caching optimization — the join
would give a *wrong* answer, not a slow one.

**Q: What is a distributed transaction, and why not use two-phase commit?**

2PC has a coordinator ask every participant to prepare, then commit. It gives atomicity but
requires all participants available at once, holds locks across the network, and the
coordinator is a single point of failure. In practice, microservices use sagas instead.

**Q: Explain the saga pattern.**

Break a distributed transaction into a sequence of local transactions, each with a
compensating action that semantically undoes it. If step 3 fails, run the compensations for
steps 2 and 1. **Orchestrated** sagas have a coordinator that tells each service what to do;
**choreographed** sagas have services react to each other's events with no central brain.

*In this project:* the order flow is the saga — reserve stock, then charge. The compensating
release on payment decline is the piece not yet implemented, which is precisely why a
declined payment currently leaks a reservation.

**Q: What is eventual consistency and how do you explain it to a product owner?**

Different services can disagree briefly, and converge shortly after. Practically: the
order page may say "confirmed" a second before the stock page reflects the decrement. The
question to answer is not "is this acceptable in general" but "what's the worst thing a user
can do in that window".

**Q: CAP theorem, in one honest sentence?**

When the network partitions you must choose between staying consistent and staying
available; the rest of the time you get both, and most real systems are choosing between
latency and consistency, not between C and A.

**Q: Sync vs async communication — how do you choose?**

Synchronous (HTTP) when the caller needs the answer to proceed: sales cannot price an order
without catalog's price. Asynchronous (Kafka) when the caller only needs to announce that
something happened: "order confirmed" → notify, analytics, ship. Async decouples
availability — the consumer can be down and catch up — at the cost of no immediate answer
and much harder debugging.

**Q: What is service discovery and what problem does it solve?**

A registry (Eureka, Consul) where instances register on startup and callers look up by
logical name. It solves the problem that a hardcoded URL holds exactly one address: you
can't scale behind it, and it can't tell you the target is down.

*In this project:* sales resolves `${CATALOG_URL:http://localhost:8081}` from an environment
variable — deliberate technical debt, with Eureka plus a `@LoadBalanced` client resolving
`http://catalog` as the planned replacement.

**Q: What does an API gateway do?**

One entry point in front of many services: routing, TLS termination, authentication, rate
limiting, and a stable public surface that hides how many services exist behind it. Without
one, every client needs to know every service's address.

---

## 8. Inter-service communication and resilience

**Q: Walk me through a request that crosses services in your system.**

`POST /api/orders` on sales → for each item, `GET /api/products/{id}` on catalog for the
authoritative price and name → save the order as `PENDING` → `POST /api/stock/reserve` on
inventory (409 → `REJECTED`) → `POST /api/payments` on payment (402 → `PAYMENT_FAILED`) →
`CONFIRMED`. Four services, three network hops, and no shared transaction anywhere in it.

**Q: What is a circuit breaker and what are its states?**

A wrapper that stops calling a failing dependency. **CLOSED**: calls pass through, failures
counted. **OPEN**: the failure rate crossed a threshold, so calls fail immediately without
touching the network. **HALF_OPEN**: after a wait, a few trial calls are allowed — success
closes it, failure opens it again. The value is that a slow dependency stops consuming the
caller's threads, so one sick service doesn't cascade into a dead system.

**Q: What's the difference between a retry and a circuit breaker? Are they safe together?**

A retry assumes the failure is transient; a breaker assumes it isn't. Together they're
useful but dangerous: retrying into an already-overloaded service adds load to the thing
that's failing. Retries need a bounded count, exponential backoff with jitter, and an
idempotent target.

**Q: Why is a timeout as important as the breaker?**

Without one, a "failure" that takes 60 seconds looks like success-in-progress, so the
breaker never trips and the caller's threads stay pinned. A timeout is what converts a hang
into a countable failure.

*In this project:* the RestClient calls have no explicit timeouts yet — a known gap, and
under the load test it showed up as latency climbing to a 1.1s p99 with nothing shedding
load.

**Q: What is a fallback, and is it always the right thing?**

A degraded answer when the dependency is unavailable — cached data, an empty list, a default.
It's a *product* decision, not a technical one: returning "stock unknown" is fine, returning
"in stock" when you don't know is a lie that ships an order you can't fulfil. Some calls
have no acceptable fallback and should just fail.

**Q: Which failure modes appear in a distributed system that don't exist in a monolith?**

Partial failure (two of three steps succeeded), network partitions, timeouts where you don't
know if the work happened, duplicate delivery from retries, out-of-order messages, and
cascading failure. Each has a named remedy — sagas, idempotency keys, breakers, backoff —
and the remedies are most of what "microservices experience" means.

---

## 9. Docker

**Q: Container vs virtual machine?**

A VM virtualizes hardware and runs a full guest OS; a container shares the host kernel and
isolates processes with namespaces and cgroups. Containers start in milliseconds and cost
megabytes, VMs take seconds and gigabytes — the trade is weaker isolation.

**Q: Image vs container?**

An image is the immutable, layered template; a container is a running instance of it with a
writable layer on top. Same relationship as class and object.

**Q: What is a multi-stage build and why use one?**

Build in one stage, copy only the artifact into a clean runtime stage. The final image ships
a JRE and a jar rather than a JDK, Maven, the source tree, and the entire `~/.m2` cache —
smaller image, faster pulls, smaller attack surface.

*In this project:*

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
COPY --from=build /build/target/*.jar app.jar
```

**Q: Why copy `pom.xml` and resolve dependencies before copying `src`?**

Layer caching. Each instruction is a layer keyed by its inputs; when `src` changes, only
layers after the `COPY src` are rebuilt. Put the slow, rarely-changing step (dependency
download) above the fast, always-changing one (your code) and rebuilds drop from minutes to
seconds. The same principle applies to `package.json` before `npm install` in Node.

**Q: Why create a non-root user in the image?**

Containers run as root by default, and a process that escapes a root container is a far
worse incident than one that escapes an unprivileged one. `addgroup -S spring && adduser -S
spring -G spring` plus `USER spring` costs nothing.

**Q: Why does a JVM in a container need `-XX:MaxRAMPercentage`?**

The JVM sizes its heap from the memory it believes is available. Under a container memory
limit, default sizing can let the heap grow past the limit and the kernel OOM-kills the
process — with no Java stack trace, which makes it maddening to diagnose.
`-XX:MaxRAMPercentage=75.0` sizes the heap against the container's limit and leaves headroom
for metaspace, thread stacks, and direct buffers.

**Q: `CMD` vs `ENTRYPOINT`?**

`ENTRYPOINT` is the executable; `CMD` provides default arguments that `docker run` can
override. For a service you want a fixed `ENTRYPOINT`.

**Q: How do you get data to survive a container restart?**

Named volumes. The container filesystem is ephemeral; a volume is managed by Docker and
outlives it.

*In this project:* `postgres_data:/var/lib/postgresql/data`. Which leads directly to the
next question.

**Q: A trap you hit with Postgres init scripts.**

`/docker-entrypoint-initdb.d/*.sql` runs **only when the data directory is empty**. Edit
`init.sql` after the first startup and nothing happens — no error, no warning, just the old
schema and a very confusing hour. The fix is `docker compose down -v` to destroy the volume
before bringing it back up.

---

## 10. Docker Compose and load balancing

**Q: How do you load balance across service instances using Docker Compose?**

Three answers, in increasing order of seriousness:

**1. Docker's built-in DNS round-robin.** Scale a service and its name resolves to multiple
container IPs:

```bash
docker compose up -d --scale inventory=3
```

Any container calling `http://inventory:8082` gets one of the three addresses back from
Docker's embedded DNS. This is free, and it's weak: it is not health-aware, distribution
depends on client-side DNS caching (the JVM caches aggressively, so a Java client can pin
itself to one instance and never move), and there's no way to change the strategy or drain
an instance.

**2. A real load balancer in front — HAProxy or Nginx.** Put it in the Compose file, expose
only its port, and let it distribute. The key detail is that with `--scale` you don't know
the replicas in advance, so a static `server` list breaks as soon as the count changes.
Use Docker's DNS as a resolver and a `server-template` so HAProxy discovers replicas:

```
resolvers docker
  nameserver dns 127.0.0.11:53

backend inventory_back
  balance roundrobin
  server-template inv 5 inventory:8082 check resolvers docker init-addr none
```

This gives health checks, real balancing algorithms, and a single stable entry point.

**3. Client-side load balancing via a registry.** Instances register with Eureka; the caller
holds the instance list and picks one itself with Spring Cloud LoadBalancer, calling
`http://inventory` as a logical service ID. No extra network hop, and the client can be
smart about retries and zone affinity.

*In this project:* option 1 is what's running today (`--scale inventory=2`), with HAProxy and
Eureka specified in `docs/PRD.md` as the Phase 2 replacements.

**Q: Why would you use both an edge load balancer and client-side load balancing?**

They answer different questions. HAProxy at the edge decides which instance an **external**
request reaches, and gives the outside world one address instead of four ports. Client-side
balancing decides which instance of a **peer** service an internal call goes to, using the
registry, with no extra hop in the middle. External traffic and internal traffic are separate
problems with separate failure modes.

**Q: You ran `--scale inventory=2` and got "port is already allocated". Why, and how do you
fix it?**

Because the base file maps a fixed host port:

```yaml
ports:
  - "8082:8082"
```

A host port can be claimed once, so the second replica has nowhere to bind. Fixed host ports
and horizontal scaling are mutually exclusive. Three fixes:

- Drop `ports:` and use `expose:` — replicas are reachable inside the network only, which is
  what you want once a load balancer fronts them.
- Publish only the container port (`- "8082"`) and let Docker assign random host ports.
- Map a host **range**, so each replica takes the next port.

*In this project:* an overlay file, `docker-compose.scale.yml`:

```yaml
services:
  inventory:
    ports: !override ["8092-8093:8082"]
```

**Q: Why is `!override` needed there?**

Because Compose **merges** lists across files by default — without the tag, the range is
appended to the existing `8082:8082` mapping and the collision remains. `!override` replaces
the list instead of adding to it. (Sharp edge found the hard way: written as a block list
under the tag, a YAML formatter can drop the list entirely, leaving `ports: !override` with
nothing after it and a validation error that points nowhere useful. Inline flow syntax
survives.)

**Q: How do containers find each other?**

Docker's embedded DNS on the user-defined network resolves **service names**. From the host
you use `localhost:8082`; from another container you use `inventory:8082`. Container name,
service name, and published host port are three different addressing systems, and confusing
them is most of what goes wrong on day one.

**Q: `depends_on` doesn't actually wait for the database. How do you fix that?**

Plain `depends_on` waits for the container to *start*, not to be *ready* — so services boot,
Flyway can't connect, and they die. Pair a healthcheck with a condition:

```yaml
postgres:
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U postgres -d microservices"]
    interval: 10s
    retries: 5

sales:
  depends_on:
    postgres: { condition: service_healthy }
    catalog:  { condition: service_healthy }
```

**Q: Two healthcheck details that bite people.**

`start_period` — Spring Boot takes tens of seconds to start, and without a grace window the
early failures burn the retry budget and the container is marked unhealthy before it ever
had a chance. And the check must use a binary that exists in the image: the Alpine runtime
has busybox `wget`, not `curl`, so `wget -qO- http://localhost:8081/actuator/health` works
and the curl version silently never passes.

**Q: How do you keep the same image working locally and in Compose?**

Environment variables with local-friendly defaults in `application.properties`, injected by
Compose. `${DB_HOST:localhost}` becomes `postgres`; `${INVENTORY_URL:http://localhost:8082}`
becomes `http://inventory:8082`. One artifact, no second property file to drift out of sync.

**Q: How do you keep an optional stack (monitoring, logging) out of the way?**

Compose profiles. Tag the heavy services and they only start when asked:

```bash
docker compose --profile observability --profile logging up -d
```

**Q: Compose vs Kubernetes?**

Compose is a single-host development and demo tool. Kubernetes is a multi-host orchestrator
with scheduling, self-healing, rolling updates, autoscaling, and service abstractions. Where
Compose gives you `--scale` and DNS round-robin, Kubernetes gives you a `Service` with real
load balancing, readiness gating, and a `Deployment` that replaces failed pods.

**Q: Most useful Compose commands in day-to-day work?**

```bash
docker compose up -d --build          # start, rebuilding images
docker compose ps                     # what's running and healthy
docker compose logs -f sales          # follow one service
docker compose down -v                # stop and wipe volumes (clean DB reset)
docker compose exec -T postgres psql -U postgres -d microservices < infra/postgres/seed.sql
```

---

## 11. Performance and concurrency

**Q: How would you load test a service, and what do you measure?**

Drive realistic traffic at it with a ramp, and record throughput (req/s), latency
percentiles, error rate, and resource usage on both the app and the database — all on the
same timeline, so you can see which one moved first.

*In this project:* Locust ramping in steps of 50 to 1,000 concurrent users against the
cross-service endpoint, with a `docker stats` sampler capturing CPU, memory, and Postgres
connection count every 5 seconds.

**Q: Why percentiles rather than averages?**

An average hides the tail. Here the mean was 442 ms but the p99 was ~1.1 s and the max 2.3 s
— and the p99 is what the loudest 1% of users experience. Averages also can't be aggregated
meaningfully across services; percentiles at least tell you the shape.

**Q: What did your load test show?**

279,383 requests, **zero failures**, ~1,050 req/s at 1,000 concurrent users, median 450 ms,
p95 810–870 ms, p99 up to 1.1 s. The interesting part: Postgres connections stayed pinned at
**41** from idle through peak, and Postgres CPU peaked around 28%. Load did not reach the
database as connections — HikariCP's fixed pool is a gate, and the queue formed in front of
it inside the application. Latency rose linearly while throughput stayed flat, which is the
signature of a saturated fixed-capacity resource with requests queueing behind it.

**Q: Zero failures at 1,000 users — is that a good result?**

Less good than it looks. Every request succeeded *eventually*; a p99 of 1.1 s on a stock
lookup would be unacceptable in production, and no client had a timeout that would have cut
a slow call short. A success rate with no latency budget attached isn't a health metric. It
also shows what's missing: nothing sheds load or fails fast, which is what circuit breakers
and timeouts are for.

**Q: Anything you'd caveat about that test?**

Locust logged `CPU usage was too high at some point during the test`. Past that point the
load generator is part of what's being measured, so the tail numbers are an upper bound. Always
check whether the instrument is saturated before believing the measurement.

**Q: How do you find a bottleneck?**

Layer by layer, top down: is the load generator saturated? Is the app CPU-bound (profile) or
waiting (thread dump)? Are threads blocked on pool acquisition, or on the query itself?
Is the database CPU-bound, I/O-bound, or lock-bound? The answer is usually visible in which
metric flattened first.

**Q: Why must singleton Spring beans be stateless?**

One instance serves every concurrent request, each on its own thread. A mutable instance
field on a `@Service` is shared mutable state without synchronization — it will be corrupted
under load and will look fine in every test you write on your laptop.

**Q: How do you handle two users buying the last item at the same time?**

A version column with optimistic locking so the second write fails and retries, or a
pessimistic `SELECT ... FOR UPDATE` on the stock row, or a database `CHECK
(quantity_available >= 0)` as the last line of defence. What you must not do is check
availability in application code and write the decrement in a separate statement without
one of those protections — the gap between them is where the oversell lives.

*In this project:* `stock_items` has both a `version` column and
`CHECK (quantity_available >= 0)`.

---

## 12. Testing

**Q: Unit vs integration test — where do you draw the line?**

Unit tests exercise one class with its collaborators stubbed, run in milliseconds, and can
be numerous. Integration tests wire real components — Spring context, real database — are
slower, and should cover the paths where the wiring is the risk. Most bugs in a service like
this live in the wiring, so a thin layer of integration tests earns its runtime.

**Q: `@SpringBootTest` vs `@WebMvcTest` vs `@DataJpaTest`?**

`@SpringBootTest` starts the whole context — thorough and slow. `@WebMvcTest` loads only the
web layer with services mocked — good for controller mapping, serialization, and status
codes. `@DataJpaTest` loads only JPA with an in-memory or containerized database — good for
repository queries. Use the narrowest slice that covers what you're testing.

**Q: How do you test against a real database without the flakiness of a shared one?**

Testcontainers — spin up the real Postgres image per test run, so you're testing the same
engine, dialect, and constraints you deploy on. An in-memory H2 with a Postgres compatibility
mode will happily accept SQL that production rejects.

**Q: How do you test a service that calls another service over HTTP?**

Mock the client in unit tests. For integration, use WireMock or `MockRestServiceServer` to
stand up a fake endpoint — and specifically test the failure paths: 404, 500, timeout,
connection refused. Those are the paths that actually break in production, and they're
untested by default.

**Q: What would you test first in this system?**

The failure paths, because they're where the design decisions are: insufficient stock →
409 with no partial reservation; payment declined → 402 with the order left in
`PAYMENT_FAILED`; catalog unreachable → 503, not a 500 stack trace. Then a concurrency test
that proves the optimistic lock actually prevents an oversell.

---

## 13. Questions about the project itself

**Q: Tell me about this project.**

An e-commerce system split into four Spring Boot services — catalog, inventory, sales,
payment — over Postgres, deployed with Docker Compose. It's built in two phases on purpose:
Phase 1 makes it work with the problems deliberately left in (hardcoded service URLs, no
resilience, no way to scale, no visibility), and Phase 2 fixes them one at a time with
Eureka, HAProxy, Resilience4j, Kafka, and an observability stack. The point of building it in
that order is that each fix lands as a solution to a problem I'd already felt, rather than a
configuration ritual copied from a tutorial.

**Q: What was the hardest problem you hit?**

Scaling a service and getting `port is already allocated`. It looks like a Docker quirk and
it isn't — it's the architecture telling you that a fixed host port and horizontal scaling
are mutually exclusive, and that a hardcoded address can only ever describe one instance.
That one error message is the entire case for service discovery and load balancing.

**Q: What surprised you?**

That database connections never moved under load — flat at 41 from idle to 1,000 concurrent
users. I'd assumed load propagated to the database; it doesn't. The pool is a gate and the
queue forms in front of it, which changes where you look when latency climbs.

**Q: What's the biggest weakness in it right now?**

A declined payment doesn't release the reserved stock. Sales marks the order
`PAYMENT_FAILED` and stops, because inventory has no release endpoint yet — so every decline
leaks a reservation. It's the missing compensating action in the saga, and it's a good
illustration of the real lesson: once you cross a service boundary you don't get a rollback,
you get whatever undo you remembered to write.

**Q: What would you do differently?**

Add timeouts to the HTTP clients from day one — everything else about resilience depends on
them, and the load test showed the system happily accumulating a 1.1 s tail with nothing
cutting it off. I'd also write the compensating action at the same time as the forward
action, rather than treating it as a later phase.

**Q: What's the single idea from this that transfers?**

Every boundary you draw buys independence and charges you consistency, and the bill is not
optional. Foreign key becomes an HTTP call. Transaction becomes a compensating action.
Method call becomes a network failure. Local variable becomes a stale copy. The architecture
isn't the diagram — it's the set of prices you agreed to pay.
