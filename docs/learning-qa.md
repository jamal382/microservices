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
9. [Resilience4j in depth](#9-resilience4j-in-depth)
10. [Service discovery with Eureka](#10-service-discovery-with-eureka)
11. [Docker](#11-docker)
12. [Docker Compose and load balancing](#12-docker-compose-and-load-balancing)
13. [Performance and concurrency](#13-performance-and-concurrency)
14. [Testing](#14-testing)
15. [Questions about the project itself](#15-questions-about-the-project-itself)

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

The second half of that sentence is the part to be careful with. A registry fixes the
*address* problem and only half of the *health* problem: it knows an instance stopped
heartbeating, but not that one which is still heartbeating is failing every request.

*In this project:* discovery is done by **Docker's embedded DNS**, not a registry. Both
inventory replicas share an `inventory` network alias, so one name resolves to two A records
with nothing in the request path between caller and callee. Eureka was run here and then
removed — section 10 keeps the registry model and the reasoning for dropping it.

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

*In this project:* one breaker per dependency, opening on failure rate **or** slow-call
rate. With both replicas stalling, `catalog` drops from 5.13s per call to 0.012s once the
breaker opens — the same degraded answer, 400× faster, and `inventory` stops receiving
traffic it can't serve.

**Q: What's the difference between a retry and a circuit breaker? Are they safe together?**

A retry assumes the failure is transient; a breaker assumes it isn't. Together they're
useful but dangerous: retrying into an already-overloaded service adds load to the thing
that's failing. Retries need a bounded count, exponential backoff with jitter, and an
idempotent target.

**Q: Why is a timeout as important as the breaker?**

Without one, a "failure" that takes 60 seconds looks like success-in-progress, so the
breaker never trips and the caller's threads stay pinned. A timeout is what converts a hang
into a countable failure.

*In this project:* every RestClient is bounded at 1s connect / 2s read, with payment at 5s
because a card authorisation legitimately takes longer than a database read. Before those
existed the load test showed latency climbing to a 1.1s p99 with nothing shedding load.

**Q: What is a fallback, and is it always the right thing?**

A degraded answer when the dependency is unavailable — cached data, an empty list, a default.
It's a *product* decision, not a technical one: returning "stock unknown" is fine, returning
"in stock" when you don't know is a lie that ships an order you can't fulfil. Some calls
have no acceptable fallback and should just fail.

*In this project:* `catalog` falls back to a `degraded` stock lookup with `null` quantities
and a reason — the product data is ours and still useful. `sales` → `payment` has no
fallback at all, because there is no degraded version of "money moved".

**Q: Which failure modes appear in a distributed system that don't exist in a monolith?**

Partial failure (two of three steps succeeded), network partitions, timeouts where you don't
know if the work happened, duplicate delivery from retries, out-of-order messages, and
cascading failure. Each has a named remedy — sagas, idempotency keys, breakers, backoff —
and the remedies are most of what "microservices experience" means.

---

## 9. Resilience4j in depth

**Q: What is Resilience4j, and why it rather than Hystrix?**

A lightweight fault-tolerance library for Java 8+. Hystrix has been in maintenance mode
since 2018 and Spring Cloud dropped it; Resilience4j is the successor everyone moved to.
Practically, the differences that matter are that it's modular — you take only the patterns
you use, each as its own small jar with no dependency beyond Vavr-free core — and that it
decorates functions rather than requiring you to extend a `HystrixCommand` base class. It
also doesn't force a thread pool on you: Hystrix isolated everything on threads, whereas
Resilience4j's default bulkhead is a semaphore, which is far cheaper.

**Q: What are its core modules?**

Six: **CircuitBreaker**, **Retry**, **RateLimiter**, **Bulkhead**, **TimeLimiter**, and
**Cache**. Each is independent and each can be used as an annotation, a functional
decorator, or programmatically from the registry.

*In this project:* all five of the first ones are on the `catalog` → `inventory` call. No
cache — every read here needs to be current.

**Q: How do the annotations actually work?**

They're Spring AOP aspects. At startup a proxy is created around the bean and the aspects
wrap the method call. Two consequences follow that catch people out:

- **You need the AOP starter on the classpath.** Without it the annotations are *silently
  ignored* — no error, no warning, and every call runs completely unprotected. On Spring
  Boot 4 the artifact is `spring-boot-starter-aspectj`; it was `-aop` up to Boot 3.
- **Self-invocation bypasses everything.** Calling an annotated method from another method
  in the same class doesn't go through the proxy, so all the aspects are skipped, silently.

*In this project:* the join helper on `InventoryClient` is deliberately a `static` method
taking the future as an argument, precisely so nobody can write it as an internal call and
quietly strip all five patterns off the request path.

**Q: If you put `@Retry` and `@CircuitBreaker` on the same method, which one runs first?**

Retry is on the outside. The full order is fixed and set by each aspect's `Ordered` value —
lower value means further out:

```
Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead ( call ) ) ) ) )
```

The order you write the annotations in makes no difference. Each is overridable
(`resilience4j.retry.retryAspectOrder` and friends), but rarely worth touching.

Two consequences are worth saying out loud, because they're what the question is really
testing:

- **Every retry attempt is counted separately by the breaker**, since the breaker is inside
  the retry. Three attempts against a dead dependency put three failures in the sliding
  window, not one — so a retrying caller trips its own breaker about three times faster
  than the config suggests.
- **Once the breaker is open it throws instantly**, and the retry sitting outside would
  happily retry *that* — burning the whole budget on a call that never leaves the process.
  So `CallNotPermittedException` goes in the retry's `ignore-exceptions`.

**Q: What's the difference between a rate limiter and a bulkhead?**

They sound like the same idea and they're not. A **rate limiter** counts calls *started per
unit of time* and refills on a clock, regardless of whether earlier calls have finished. A
**bulkhead** counts calls *in flight right now* and refills only when one completes,
regardless of how fast they arrive.

The arithmetic makes it obvious: twenty concurrent calls that each take 5ms is about 4000
calls per second — comfortably inside a bulkhead of 20 and forty times over a limiter of
100/s. Twenty concurrent calls that each take 5 *seconds* is 4 calls per second — trivial
for the limiter and permanently at the bulkhead's ceiling. Neither implies the other.

The one-liner: **the rate limiter protects the callee, the bulkhead protects you.**

**Q: Semaphore bulkhead or thread-pool bulkhead?**

Semaphore by default. It's just a counter — the call runs on the caller's own thread, so it
costs almost nothing and `ThreadLocal` state (MDC, security context, transaction context)
survives for free. A thread-pool bulkhead hands the call to its own pool and returns a
future, which costs a thread hand-off and breaks every `ThreadLocal` you were relying on.

You pay for the thread pool when you need to **walk away from a call in progress** — which
is what a time limiter does, and it's the only reason to use one here.

*In this project:* `catalog` uses `THREADPOOL` because it has a `@TimeLimiter`; a semaphore
would release its permit the moment the method returned a future — i.e. immediately —
capping nothing at all. `sales` uses `SEMAPHORE` on all three paths, because nothing there
should be abandoned mid-flight.

**Q: What's a `TimeLimiter`, and why not just use it instead of a socket timeout?**

Because **a time limiter cancels the waiting, not the work.** It needs a
`CompletionStage`-returning method; when the deadline fires it completes the future
exceptionally and releases the caller. The thread actually blocked in the socket read stays
blocked — a thread parked on blocking I/O can't be interrupted — so it sits there until the
read timeout releases it.

That makes it a deadline you can state as one number and reason about, on top of socket
timeouts that bound each I/O step separately. It is not a replacement for them. Drop the
socket timeouts and keep the time limiter and you've built a system that reports fast
failures while silently accumulating blocked threads forever.

*In this project:* the deadline is 1.5s and the read timeout is 2s — deliberately below, so
the deadline is what fires first and the behaviour is unambiguous. The cost is visible: the
caller is released at 1.5s, the bulkhead thread stays occupied until 2s.

**Q: So how many things in your system are called "timeout"?**

Three, and they do different jobs. The **socket timeouts** (1s connect, 2s read) bound each
I/O step and are the only ones the blocked worker actually observes. The **time limiter**
(1.5s) bounds the whole attempt and is what the caller waits for. The
**slow-call-duration-threshold** (1s) bounds nothing at all — it just tells the breaker to
count a call as a failure even though it succeeded.

That third one catches the nastiest failure mode there is: a dependency that answers
correctly, just slowly enough to exhaust its callers' threads. There's no exception to catch,
so a breaker watching only for errors never fires.

**Q: How does the circuit breaker decide when to open?**

A sliding window of recent calls, either **count-based** (the last N calls) or
**time-based** (calls in the last N seconds). It opens when the failure rate *or* the
slow-call rate crosses its threshold — but only once `minimumNumberOfCalls` have been
recorded, so one unlucky failure at startup can't open it. After
`waitDurationInOpenState` it moves to HALF_OPEN and admits a few trial calls; they decide
whether it closes or opens again.

Time-based is usually the better production choice under bursty traffic. Count-based is used
here because it makes the demo deterministic — you can say exactly which request trips it.

**Q: `recordExceptions` vs `ignoreExceptions` — what's the difference?**

This is the question that separates people who've configured it from people who've read
about it. There are **three** buckets, not two:

| Bucket | Config | Effect |
|---|---|---|
| Failure | `recordExceptions` | pushes the breaker toward OPEN |
| Ignored | `ignoreExceptions` | not counted at all, as if the call never happened |
| Success | *anything unlisted* | holds the breaker CLOSED |

The trap is that `recordExceptions` is an **allow-list**: once you set it, everything you
didn't list silently becomes a *success*. So "I didn't list it, therefore it's neutral" is
wrong — not listing something is an active choice to count it as healthy.

*In this project:* a 404 for a product with no stock row, a 409 for insufficient stock and a
402 declined card are all **ignored**. They're successful conversations with healthy
services — failures of the *order*, not of the *system*. Count them and browsing unstocked
products would trip a breaker and cut off a service that was never unwell.

**Q: Should a rate-limiter or bulkhead rejection count as a circuit-breaker failure?**

No — and it's worth being precise about why. The dependency was never contacted, so the
rejection is evidence about *your* load, not its health. A call you refused to make tells
you nothing about whether the callee is up.

But "don't record it" isn't enough, because of the allow-list trap above. In Resilience4j's
fixed aspect order the rate limiter and bulkhead sit *inside* the breaker, so their
exceptions pass through its accounting on the way out. Leave `RequestNotPermitted` and
`BulkheadFullException` unlisted and they don't just fail to open the breaker — they count
as **successes** and prop the health metrics up at exactly the moment the system is
saturated. They have to be explicitly ignored.

*In this project:* a 45-order burst produced 3 bulkhead rejections alongside 15 real
timeouts. The breaker opened on the 15 and ignored the 3. Without those `ignore-exceptions`
lines the 3 would have diluted a 100% failure rate to 83%.

**Q: A metric that improves under load — why is that the worst kind of bug?**

Because it removes the signal at the moment you need it. Anything that counts self-inflicted
rejections as successes gets *better* the harder the system is pushed: more load, more
rejections, higher apparent success rate. The dashboard goes green as the service falls
over, alerts don't fire, and autoscaling doesn't trigger. A metric that's merely wrong is
recoverable; a metric that's inversely correlated with health is actively misleading.

**Q: When should you not retry?**

When the operation isn't idempotent. The key insight is that **a read timeout means the
response was lost, not the request** — the work may well have been committed on the other
side. Retry a non-idempotent write and you double it, silently, with nothing in the logs
tying the duplicate to its cause.

*In this project:* `sales` retries `GET /api/products/{id}` and nothing else. Reserving
stock and charging a card have no `@Retry` at all. `payment` does actually deduplicate by
`orderId`, so a retry would be safe *today* — the annotation is still omitted, because that
safety is a property of the callee's current implementation rather than a guarantee of its
contract, and a retry configured on that basis becomes a double-charge the day it changes.

**Q: How would you make those retries safe?**

An idempotency key: the caller generates a unique id per logical operation, sends it with
the request, and the callee stores it and returns the original result on a repeat. That
turns at-least-once delivery into effectively-once *at the callee*, which is the only place
it can be enforced. Retrying without one isn't courage, it's a bet.

**Q: Why exponential backoff, and why jitter?**

Backoff because a struggling dependency needs time, and a fixed interval just keeps hitting
it at a constant rate. Jitter because without it every caller that failed at the same moment
retries at the same moment — you've synchronised the whole fleet into a thundering herd, and
the retry storm becomes the outage.

*In this project:* 200ms base, multiplier 2, three attempts. Single-caller demo, so jitter
isn't configured — under real fan-out it should be.

**Q: What makes a good fallback?**

That it doesn't invent data the caller will act on. A fallback returning "stock unknown" is
honest; one returning "in stock" is a lie that ships an order you can't fulfil. When the only
available fallback would be a lie, failing is the correct behaviour.

*In this project:* `catalog` returns the product with `null` quantities and a
`degraded` flag. `sales` → `catalog` has no fallback, because there's no honest guess at a
price, and `sales` → `payment` has none, because there's no degraded version of a charge.

**Q: Where does breaker state live? Is it shared across instances?**

In memory, per JVM. Each instance learns independently that a dependency is down — so with
five replicas, five breakers each need their own `minimumNumberOfCalls` before any of them
reacts. The same is true of rate limiters: five replicas at 100/s is a real ceiling of
500/s. Anything genuinely global needs shared state — Redis, or a service mesh doing it at
the network layer.

**Q: How do you observe any of this in production?**

Resilience4j publishes events (`onError`, `onRetry`, `onStateTransition`, `onCallRejected`)
and Micrometer metrics, plus actuator endpoints — `/actuator/circuitbreakers`,
`/actuator/ratelimiters`, `/actuator/bulkheads`, `/actuator/timelimiters` and their
`*events` variants. The one thing to alert on is **state transitions**: `CLOSED → OPEN` is
the moment your system decided a dependency is unhealthy, and it should page someone.

*In this project:* a `RegistryEventConsumer` attaches loggers to every instance as it's
created — lazily-created ones included — so the log reads as a running commentary on the
library's reasoning. A correlation id ties the whole chain together, which took real work:
`MDC` is a `ThreadLocal`, so the thread-pool bulkhead needs a `ContextPropagator`, and so
does the time limiter's scheduler, because *that* is the thread that completes the future on
a deadline and therefore runs the breaker's accounting, the retry's decision and the
fallback.

**Q: Should this live in the application or in a service mesh?**

Timeouts, retries, rate limits and circuit breaking can all be done by a sidecar like Envoy,
and there's a real argument for it: uniform policy across languages, changeable without a
redeploy.

What a mesh can't do is the part that requires knowing what the call *means*. It sees a 402
and a 500 as two HTTP responses; it can't know that a declined card is a healthy provider
doing its job and must never trip a breaker, or that this POST is unsafe to retry while that
GET is fine, or that "stock unknown" is an acceptable answer and a guessed price isn't. That
judgement lives in the application. In practice you often want both — the mesh as a blanket
floor, the library where the semantics matter.

**Q: You've got five patterns on one call. Isn't that over-engineering?**

It would be if they were on everything, and they aren't. That method is the one path in the
system that's idempotent, safely abandonable and has an honest degraded answer — it can
afford every pattern. The three write paths in `sales` deliberately have fewer: no retry, no
time limiter, no fallback.

The framing I'd push back on is "more resilience is better". Four of the five patterns
*refuse calls that would otherwise have succeeded* — every one of them trades availability
now for the ability to keep serving later. That's a trade you make deliberately, per
dependency, which is why every limit in this project has a comment explaining its number.

---

## 10. Service discovery with Eureka

> **Archived — this project no longer runs Eureka.** It was built, run, and then removed
> in favour of Docker's DNS aliases; see [Lab 02](labs/02-service-discovery.md). The
> *In this project* notes below are written in the past tense and describe what the
> registry did **while it ran**, because the configuration decisions are still the useful
> part to be able to talk through. Section 12 describes what resolves service names today.
>
> Being able to say "we took it out, and here's what it was buying us" is a stronger answer
> than never having run one — so the last question in this section is the one to lead with.

**Q: What is Eureka, and — more importantly — what is it not?**

A registry: a service that holds a list of application names and the live instances behind
each one. Instances register on startup and heartbeat to stay listed; callers fetch the list
and resolve a name to an address themselves.

What it is *not* is a proxy. Eureka never sits in the request path, never forwards traffic,
and never sees your payloads. It answers a question ahead of time; the caller caches the
answer and then connects straight to the target. That single distinction explains most of
Eureka's behaviour, including why it can be down while traffic keeps flowing.

**Q: Walk me through the registration lifecycle.**

Four phases. **Register** — on startup the instance POSTs its application name, IP, port,
and instance ID. **Renew** — it heartbeats on an interval to keep the lease alive.
**Fetch** — callers pull the registry on their own interval and cache it locally.
**Evict** — if heartbeats stop for longer than the lease duration, the registry drops the
instance, and callers learn about it at their next fetch.

*In this project, while it ran:* inventory registered and heartbeated every 10s with a 30s
lease; catalog fetched every 10s and never registered.

**Q: If callers cache the registry, isn't the data always slightly stale?**

Yes, and that's the design rather than a defect. Every address the load balancer uses comes
from a local snapshot, not a live call, so there's a window — bounded by heartbeat interval
plus lease duration plus fetch interval — in which a caller will confidently dial an
instance that is already gone. You can shrink the window with tuning; you cannot close it.

The consequence worth stating out loud: **discovery guarantees you will sometimes be handed
a dead address**, which is exactly why timeouts, retries, and circuit breakers are not
optional once you adopt it. Discovery and resilience are a package.

**Q: `register-with-eureka` and `fetch-registry` are separate flags. Why?**

Because publishing your address and looking other people up are independent roles. A service
that is reached at a fixed address but calls scaled peers only needs to fetch; a service
that is scaled but calls nobody only needs to register. Setting both to `true` everywhere
out of habit hides the fact that they answer different questions.

*In this project, while it ran:* the pair was deliberately split to make that visible —
inventory was `register=true, fetch=false` (the scaled one, making no outbound calls) and
catalog was `register=false, fetch=true` (reached at a fixed port, and calls inventory).

**Q: Why does the Eureka server itself set both flags to `false`?**

A standalone registry has nobody to register with and nothing to fetch — itself is the
source of truth. Leaving the defaults on makes a single-node server try to replicate with a
peer that doesn't exist, which produces connection-refused noise in the log on a loop. In a
real HA deployment you'd flip both back on and point the nodes at each other, which is how
Eureka peers replicate.

**Q: What does `@LoadBalanced` actually do to a `RestClient.Builder`?**

It registers an interceptor. When a request goes out to a URL whose host is a single segment
with no dots — `http://inventory` — the interceptor treats that segment as a **service ID**,
not a hostname, asks Spring Cloud LoadBalancer for a live instance of that application, and
rewrites the URL to a real `ip:port` before the request leaves the JVM.

Worth being explicit in an interview: there is no DNS record for `inventory` in that flow.
`ping inventory` from the caller would be a different mechanism entirely.

*In this project, while it ran:* `RestClientConfig` built `inventoryRestClient` from the
load-balanced builder with `baseUrl("http://inventory")` — no port, because the registry
supplied one. The same class today builds a plain `RestClient` against
`http://inventory:8082`, an ordinary DNS name with an explicit port.

**Q: This is client-side load balancing. How does it differ from a load balancer?**

A server-side balancer (HAProxy, Nginx, a Kubernetes `Service`) is a box in the middle: one
address, one extra network hop, and it owns the choice of instance. Client-side balancing
puts the instance list *in the caller* and lets the caller choose — no extra hop, no shared
component to scale or fail, and the client can be smart about retries, zone affinity, or
preferring an instance it already has a warm connection to.

The cost is that the logic now lives in every client, so it's library-specific and
language-specific. That's a real reason teams move to a service mesh or plain Kubernetes
Services once more than one runtime is involved.

**Q: You scaled to two replicas and the dashboard showed one. What happened?**

They registered under the same instance ID and overwrote each other. Eureka keys instances
by ID within an application, and the default ID is derived from the hostname — which under
Docker is *sometimes* unique and sometimes not, which is worse than reliably broken because
you get intermittently wrong instance counts.

*In this project, while it ran:* `eureka.instance.instance-id=${spring.application.name}:${random.uuid}`.
The symptom to memorize: you scaled to N, the registry shows fewer, and all traffic lands on
one container.

**Q: Why `eureka.instance.prefer-ip-address=true` under Docker?**

By default an instance registers under its hostname, and a container's hostname is its short
container ID — resolvable inside that container and nowhere else. The caller receives an
address it cannot connect to, and you get a connection failure that looks like the target is
down when it's actually fine. Registering by IP gives an address that's valid on the shared
network.

**Q: What is self-preservation mode, and why is it off here?**

If Eureka loses more than a threshold share of expected heartbeats in a window, it assumes
the *network* broke rather than that every instance died, and stops evicting anything —
protecting you from mass-deregistering a healthy fleet during a partition. The trade is
that a genuinely dead instance stays in the registry indefinitely.

*In this project, while it ran:* it was disabled, plus a 5s eviction timer, purely because
this is a learning environment — with it on, a service you deliberately stopped lingers in
the dashboard and the exercise stops demonstrating anything. In production you leave it on.

**Q: How do you tune heartbeat and lease, and what's the trade-off?**

`lease-renewal-interval-in-seconds` is how often the instance heartbeats (default 30);
`lease-expiration-duration-in-seconds` is how long the registry waits before evicting
(default 90). Shorter means dead instances disappear faster; it also means more registry
traffic and a higher chance of evicting a healthy instance that had one slow moment. The
expiration should stay a comfortable multiple of the renewal interval.

*In this project, while it ran:* 10s and 30s — eviction in about 30s instead of 90.

**Q: What happens if the Eureka server dies?**

Much less than people expect. Callers keep their last-fetched snapshot and keep routing from
it, so existing traffic continues; what stops is *learning about change* — new instances go
unnoticed, dead ones stay in the list. A registry outage degrades freshness, not
availability. It becomes a real outage only when a caller cold-starts with an empty cache
and has nothing to fall back to.

**Q: You hit "No servers available for service: eureka" at startup. What causes that?**

A circular lookup. The Eureka client's own HTTP transport injects whatever
`RestClient.Builder` it can find *by type*; if the only one is `@LoadBalanced`, it tries to
resolve the registry's own address **through the registry** — before the registry client
exists.

*In this project, while it ran:* the builder was declared `@Bean(defaultCandidate = false)`,
which removes it from by-type resolution. Eureka's transport gets the plain auto-configured builder, while
injection points that ask by the `@LoadBalanced` qualifier still get the right one. It's a
good example of a bug that reads as a networking problem and is actually a wiring problem.

**Q: Why `builder.clone()` before setting `baseUrl`?**

Because `baseUrl` mutates the builder in place, and the builder is a shared singleton bean.
Setting it directly would pin every future consumer of that builder to the same base URL —
fine with one client, a confusing action-at-a-distance bug the moment a second one is added.

**Q: An instance is in the registry and its container is healthy, but it gets no traffic.
Where do you look?**

At its `status` in the registry, before you look at the container at all. Eureka carries a
per-instance status (`UP`, `DOWN`, `OUT_OF_SERVICE`) that is separate from whether the
process is running, and it can be changed at runtime through the actuator or the Eureka API.
Only `UP` instances are eligible for load balancing. Health and eligibility are independent —
which is also what makes graceful draining possible: mark an instance `OUT_OF_SERVICE`, let
traffic bleed off, then stop it.

**Q: You ran Eureka and Docker DNS round-robin side by side, then removed Eureka. What did
that comparison actually teach you?**

For a while catalog reached inventory through the registry while sales reached the same two
replicas through the `inventory` network alias — same targets, two mechanisms, and the
`X-Instance-Id` header on every inventory response showing which replica each path hit.

Two things came out of it. The first is that **DNS aliases are weaker than they look, but
not in the way I expected.** I assumed the weakness was the JVM's DNS cache. The real one is
coarser: `RestClient` resolves the name when it *opens a connection* and then keeps the
socket alive, so balancing happens per **connection**, not per request. Six consecutive
cross-service calls all land on the same replica. Docker's DNS genuinely does rotate — run
`getent hosts inventory` repeatedly and the address changes — but a long-lived caller can
sit on one replica indefinitely.

The second is that **the registry wasn't fixing the failure I actually had.** Eureka gave a
real instance list, an explicit strategy, and status awareness, which is a better answer on
paper. But the failure mode that hurt was a replica that was *up and failing* — and Eureka
would have kept that instance `UP` and kept sending it traffic, exactly like DNS. It reports
existence, not readiness. So the registry came out and Resilience4j stayed, because per-caller
timeouts and breakers are what actually stop a sick replica from taking its callers down.

That is the honest lesson: I could have named the problem before adding the component, and
the component I added didn't address it.

**Q: Would you choose Eureka for a new system today?**

Probably not, and the reason matters more than the answer. On Kubernetes the platform
already provides discovery — a `Service` gives you a stable DNS name, readiness-gated
endpoints, and load balancing without any client library — so running Eureka duplicates it
in your application layer. Eureka still earns its place outside Kubernetes, on VMs or plain
Docker, and in a Spring-only estate where client-side balancing and the ecosystem
integration are worth having. Consul is the middle ground: discovery plus KV and health
checking, language-neutral.

The transferable part is the model, not the product: register, heartbeat, cache, evict —
Consul, Kubernetes endpoints, and a service mesh's control plane are all the same four
phases with different names.

**Q: What does service discovery not solve?**

Everything after you have the address. It tells you where an instance *was* a few seconds
ago; it doesn't tell you the instance is healthy right now, doesn't retry, doesn't time out,
and doesn't stop you from hammering a failing replica. It also doesn't help with what to do
when *no* instance is available. Adopting discovery without timeouts and circuit breakers
mostly buys you a more dynamic way to fail.

*In this project:* this is precisely why the registry was removed and Resilience4j kept —
whichever mechanism resolves the name, **nothing in the resolution path health-checks
anything**, so the protection has to live at the caller. See sections 8 and 9.

---

## 11. Docker

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

## 12. Docker Compose and load balancing

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

*In this project:* **only option 1 runs today.** Both inventory replicas share an `inventory`
network alias, and every caller resolves that name through Docker's embedded DNS. Options 2
and 3 were both built and then removed — HAProxy as an edge gateway, Eureka as a registry
(section 10) — because at two replicas on one host neither was preventing a failure the
system actually had, and each was a component to run and operate.

The caveat worth stating with option 1 is sharper than "the JVM caches DNS": `RestClient`
resolves the name when it **opens a connection** and then reuses the socket, so balancing is
per-connection, not per-request. Six consecutive calls from the same caller land on the same
replica. It is real load balancing, but far coarser than the alias suggests.

**Q: Why would you use both an edge load balancer and client-side load balancing?**

They answer different questions. An edge balancer decides which instance an **external**
request reaches, and gives the outside world one address instead of four ports. Client-side
balancing decides which instance of a **peer** service an internal call goes to, with no
extra hop in the middle. External traffic and internal traffic are separate problems with
separate failure modes, which is why one component rarely covers both well.

*In this project:* neither is running. Both were tried; at this size the honest answer was
that four published host ports and a DNS alias were sufficient, and the interesting question
was never *which* instance a call reaches but what the caller does when that instance is
unwell — which is section 9.

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

*In this project:* the replicas are now two named services, `inventory1` and `inventory2`,
each publishing its own host port (`9092:8082`, `9093:8082`) and sharing an `inventory`
network alias so callers still address one name. Named replicas rather than `--scale`
because each needs a stable, individually reachable port for inspecting *which* instance
served a call.

That replaced an earlier `docker-compose.scale.yml` overlay, which is where the next
question comes from:

```yaml
services:
  inventory:
    ports: !override ["8092-8093:8082"]
```

**Q: Why was `!override` needed there?**

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

## 13. Performance and concurrency

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

## 14. Testing

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

## 15. Questions about the project itself

**Q: Tell me about this project.**

An e-commerce system split into four Spring Boot services — catalog, inventory, sales,
payment — over Postgres, deployed with Docker Compose. It's built in phases on purpose:
first make it work with the problems deliberately left in (hardcoded service URLs, no
resilience, no way to scale, no visibility), then fix them one at a time. The point of that
order is that each fix lands as a solution to a problem I'd already felt, rather than a
configuration ritual copied from a tutorial.

The part I'd actually lead with is that **two of those fixes were later removed**. Eureka
went in as a service registry, then HAProxy as an edge gateway, and both came back out —
Docker's DNS aliases already did the job at this scale, and each layer was adding a
component to operate for a problem the system didn't have. What stayed is Resilience4j,
because that solved something DNS genuinely cannot: a replica that is up and failing keeps
receiving its share of traffic, since nothing in the resolution path health-checks anything.

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

Bound every outbound call from the first commit. Timeouts went in late, and everything else
about resilience turns out to depend on them — a circuit breaker only counts calls that
*finish*, so before they existed the system happily accumulated a 1.1s tail with nothing
cutting it off and a breaker would have sat there CLOSED while the thread pool drained. It's
not a tuning knob, it's the precondition.

I'd also write the compensating action at the same time as the forward action rather than
treating it as a later phase, and I'd reach for infrastructure later than I did. Eureka and
HAProxy both went in because they're what the tutorials add next, and both came out again
once I could articulate what problem they were solving here — which was none. The honest
version of "what would you do differently" is: adopt a component when you can name the
failure it prevents, not when the architecture diagram looks like it's missing one.

**Q: What's the single idea from this that transfers?**

Every boundary you draw buys independence and charges you consistency, and the bill is not
optional. Foreign key becomes an HTTP call. Transaction becomes a compensating action.
Method call becomes a network failure. Local variable becomes a stale copy. The architecture
isn't the diagram — it's the set of prices you agreed to pay.
