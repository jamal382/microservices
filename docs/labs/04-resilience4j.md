# Lab 04 — Retries, Circuit Breakers and Fallbacks with Resilience4j

**Prerequisites:** the stack runs healthy (`docker compose up -d --build`) and the
seed data is loaded. Nothing from Labs 02 or 03 is required — both describe layers
this project has since removed.

**What you will end up with:** the ability to answer, for any failure, this chain:

```
কী failure হলো?            What actually failed?
       ↓
Resilience4j কী করল?       What did the library decide to do?
       ↓
কতবার retry হলো?           How many times did it try again?
       ↓
কখন circuit open হলো?      At which call did it stop trying?
       ↓
কখন fallback হলো?          When did the degraded path take over?
       ↓
শেষ পর্যন্ত user কী পেল?    What did the caller actually receive?
```

Every answer below is read back out of the running system — from Resilience4j's own
event publisher and from the actuator — not narrated from assumptions.

---

## 1. Concept

### The problem, stated precisely

Before this lab, `catalog` called `inventory` like this:

```java
restClient.get().uri("/api/stock/{id}", id).retrieve().toEntity(StockResponse.class);
```

No timeout, no retry, no limit on consecutive failures. Three separate things go
wrong with that, and they go wrong in a specific order.

**1. An unbounded wait is worse than an error.** With no read timeout the calling
thread waits as long as the callee feels like taking. Tomcat has a finite thread
pool; a dependency that hangs for 30 seconds under load will drain it, and then
`catalog` stops serving requests that have nothing to do with `inventory`. One
sick service has made a healthy one sick. This is the cascading failure that
circuit breakers are famous for preventing — but a breaker **cannot** prevent it,
because a breaker only counts calls that *finish*. A call that never returns is
never recorded, so the circuit stays cheerfully closed while the service dies.

> **Timeouts come first.** They are what converts an unbounded hang into an
> ordinary, countable error. Everything else in this lab is built on top of them.

**2. Retrying is sometimes right and sometimes catastrophic.** A dropped packet
deserves a second attempt. A declined card does not. A stock reservation that may
already have been committed *really* does not.

**3. Retrying a service that is genuinely down makes things worse.** If `inventory`
is overloaded, three callers each retrying three times is nine requests where
there were three. Retries amplify load precisely when the dependency can least
afford it. Something has to notice the pattern and stop.

### The three patterns, and the order they run in

Resilience4j applies its aspects in a fixed order. With both annotations present:

```
Retry {                        ← outermost
    CircuitBreaker {
        the actual HTTP call   ← innermost, bounded by the RestClient timeout
    }
}
```

> The `catalog` → `inventory` call now stacks five aspects rather than two. The two
> consequences below still hold unchanged; the other three are in
> [Lab 05 §2](05-resilience-patterns.md#2-how-they-compose).

Two consequences follow, and both surprise people:

- **Every retry attempt is counted separately by the breaker.** One user request
  that retries three times puts *three* failures into the sliding window, not one.
  A retrying caller trips its own breaker about three times faster than reading
  the config suggests.
- **Once the breaker is open it throws instantly**, and the retry layer sitting
  outside it would happily retry *that* — burning the whole retry budget on calls
  that never leave the process. Hence `CallNotPermittedException` is in the retry's
  `ignore-exceptions`.

### The distinction the whole configuration turns on

Not every exception is a failure of the *system*. Resilience4j sorts each call into
one of **three** buckets, and most misconfigurations come from thinking there are two:

| Bucket | Config key | Effect | Example here |
|---|---|---|---|
| **Failure** | `record-exceptions` | Pushes the breaker toward OPEN | `HttpServerErrorException`, `ResourceAccessException` |
| **Ignored** | `ignore-exceptions` | Not counted at all, as if it never happened | `StockNotFoundException`, `DependencyBusinessException` |
| **Success** | *(default for anything unlisted)* | Holds the breaker CLOSED | a normal 200 |

"Product 999 does not exist", "not enough stock", "card declined" are **successful
conversations with healthy services**. They are failures of the *order*, not of the
*system*. Count them and browsing unstocked products will trip a breaker and cut
off a service that was never unwell.

Note that ignoring is not the same as counting as success. A burst of 404s is not
evidence of a problem — but it is not evidence of health either, and letting it pad
the success rate would mask real failures happening alongside it.

---

## 2. What was added

### Dependencies — the Boot 4 trap

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot4</artifactId>
    <version>2.4.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aspectj</artifactId>
</dependency>
```

Two things here will bite anyone copying a pre-2026 tutorial:

- **`resilience4j-spring-boot4`, not `-spring-boot3`.** Spring Boot 4 split the
  monolithic autoconfigure jar into per-feature modules and relocated the actuator
  autoconfiguration packages. The Boot 3 starter references the old paths. Version
  2.4.0 (March 2026) is the first release with a Boot 4 module.
- **`spring-boot-starter-aspectj`, not `-aop`.** Boot 4 renamed it. The annotations
  are AOP aspects; without this on the classpath `@Retry` and `@CircuitBreaker`
  are **silently ignored** and every call runs unprotected — no error, no warning.

### Timeouts

[`RestClientConfig`](../../catalog/src/main/java/com/finalearth/catalog/config/RestClientConfig.java)
now builds every client with an explicit request factory:

```java
factory.setConnectTimeout(Duration.ofMillis(1000));  // TCP handshake
factory.setReadTimeout(Duration.ofMillis(2000));     // waiting for the response
```

`payment` gets 5000ms instead: a card authorisation legitimately takes longer than a
database read, and a timeout set below a dependency's normal latency is not
resilience — it is a self-inflicted outage that also risks abandoning work the
callee actually completed.

> **Updated in [Lab 05](05-resilience-patterns.md).** `catalog` → `inventory` has
> since gained a `@TimeLimiter` as well, which required returning
> `CompletableFuture` from that one method. The socket timeouts remain the
> load-bearing bound everywhere — a time limiter cancels the *waiting*, not the
> work — and the three `sales` paths still rely on the request factory alone.

### The policy matrix

This is the part worth internalising. **Resilience is not a feature you turn all the
way up** — each dependency gets the policy its semantics allow.

| Caller → callee | Method | Retry | Breaker | Fallback | Why |
|---|---|---|---|---|---|
| `catalog` → `inventory` | GET stock | **3 attempts** | yes | **degraded response** | Idempotent read; the product data is still useful without stock |
| `sales` → `catalog` | GET product | **3 attempts** | yes | **none → 503** | Idempotent, but there is no honest guess at a price |
| `sales` → `inventory` | POST reserve | **none** | yes | **none → 503** | Not idempotent — a retry can double-reserve |
| `sales` → `payment` | POST charge | **none** | yes | **none → 503** | Not idempotent — a retry can double-charge |

> Every path has since also gained a **rate limiter** and a **bulkhead**, and the
> `catalog` → `inventory` read a **time limiter**. See the full matrix in
> [Lab 05 §3](05-resilience-patterns.md#3-what-is-configured-where).

**Why the write paths do not retry.** A read timeout means the *response* was lost,
not the *request*. The reservation may already be committed on the other side. Retry
it and you silently reserve twice; the customer's order holds stock nobody can sell,
and the discrepancy surfaces days later in a stock count with nothing to trace it to.

`payment` does in fact deduplicate by `orderId`, so a retry would be safe *today*.
The annotation is still omitted, because that safety is a property of the callee's
current implementation rather than a guarantee of its contract — and a retry
configured on that basis quietly becomes a double-charge the day the implementation
changes.

Retrying safely would need an idempotency key the callee deduplicates on. That is
the missing piece, not courage.

**Why some paths have no fallback.** A fallback must not invent data the caller will
act on. There is no degraded version of "what does this cost" or "money moved" —
returning a plausible number would produce an order at the wrong amount, and a fake
payment success would confirm an order nobody paid for. When the only available
fallback is a lie, failing is the correct behaviour.

### Fault injection

`inventory` gained a lab-only endpoint, off unless `LAB_FAULT_INJECTION=true`:

```bash
curl -X POST localhost:9092/api/lab/fault -H 'Content-Type: application/json' \
     -d '{"mode":"ERROR"}'                          # every request 500s
     -d '{"mode":"SLOW","delayMs":1500}'            # answers correctly, slowly
     -d '{"mode":"FLAKY","failureRate":40}'         # fails 40% at random
curl -X DELETE localhost:9092/api/lab/fault          # back to normal
```

This exists because **nothing else in the stack can open a circuit.** Stopping a
container is not a substitute: a stopped container refuses connections instantly, so
the caller re-resolves DNS and lands on the healthy replica without ever failing.
The failures that matter — slow replies, intermittent 500s, a process that is up but
not working — have no `docker compose` equivalent.

State is per-replica and in-memory, so `:9092` can be poisoned while `:9093` stays
healthy. `/actuator/**` is deliberately exempt, so the container still reports
healthy while serving errors — which is exactly the scenario a health check cannot
catch and a circuit breaker can.

---

## 3. Verify

```bash
cd test
./resilience-demo.sh errors      # retries, then the circuit opens
./resilience-demo.sh slow        # the circuit opens on SLOW calls with zero errors
./resilience-demo.sh recover     # OPEN → HALF_OPEN → CLOSED
./resilience-demo.sh business    # a real 404 trips nothing
./resilience-demo.sh order       # why write paths are not retried
```

### Following one request

Every log line carries a request id, and it propagates across services:

```bash
curl -H 'X-Request-Id: my-trace' localhost:8081/api/products/1/stock
docker compose logs catalog | grep my-trace
```

### The six questions, answered from one failed call

With both replicas set to `ERROR`, a single `GET /api/products/1/stock` produces:

```
[r4j] CIRCUIT 'inventory' recorded a FAILURE after 6 ms: InternalServerError
[r4j] RETRY   'inventory' attempt 1 after InternalServerError: waiting 200 ms before trying again
[r4j] CIRCUIT 'inventory' recorded a FAILURE after 2 ms: InternalServerError
[r4j] RETRY   'inventory' attempt 2 after InternalServerError: waiting 400 ms before trying again
[r4j] CIRCUIT 'inventory' recorded a FAILURE after 2 ms: InternalServerError
[r4j] RETRY   'inventory' GAVE UP after 3 attempts
[r4j] FALLBACK product=1 reason=call-failed -- all attempts exhausted
```

| Question | Answer | Where it comes from |
|---|---|---|
| কী failure হলো? | `HttpServerErrorException$InternalServerError` | first `CIRCUIT ... FAILURE` line |
| Resilience4j কী করল? | retried twice, then fell back | the sequence itself |
| কতবার retry হলো? | **3 attempts** (1 original + 2 retries) | `attempt 1`, `attempt 2`, `GAVE UP after 3` |
| কখন circuit open হলো? | on the 5th recorded failure | `CLOSED -> OPEN (failureRate=100.0%)` |
| কখন fallback হলো? | after retries were exhausted | the `FALLBACK` line |
| শেষ পর্যন্ত user কী পেল? | `200 OK`, `stockStatus: UNAVAILABLE` | the response body |

### Watch the cost collapse

```
request 1 -> http 200 in 0.62s   [CLOSED]   ← 3 attempts + 200ms + 400ms backoff
request 2 -> http 200 in 0.61s   [OPEN]
request 3 -> http 200 in 0.005s  [OPEN]     ← 120× faster
```

**That drop is the circuit breaker's entire value.** Once open, the call stops
leaving the process. The user gets a degraded answer in 5ms instead of waiting
620ms to be told the same thing, and `inventory` stops receiving traffic it cannot
serve — which is what gives it room to recover.

### Live state

```bash
curl -s localhost:8081/actuator/circuitbreakers      | python3 -m json.tool
curl -s localhost:8081/actuator/circuitbreakerevents | python3 -m json.tool
curl -s localhost:8081/actuator/retryevents          | python3 -m json.tool
curl -s localhost:8083/actuator/circuitbreakers      | python3 -m json.tool   # all three
```

---

## 4. Break it yourself

### Break 1 — the circuit that opens without a single error

```bash
curl -X POST localhost:9092/api/lab/fault -d '{"mode":"SLOW","delayMs":1500}' -H 'Content-Type: application/json'
curl -X POST localhost:9093/api/lab/fault -d '{"mode":"SLOW","delayMs":1500}' -H 'Content-Type: application/json'
for i in $(seq 6); do curl -s -o /dev/null -w '%{http_code} %{time_total}s\n' localhost:8081/api/products/1/stock; done
curl -s localhost:8081/actuator/circuitbreakers | python3 -m json.tool
```

**Expect:** six `200`s, and an `OPEN` circuit with `failureRate: 0.0%` and
`slowCallRate: 100.0%`.

Every call *succeeded*. 1500ms is under the 2000ms read timeout, so nothing threw —
but it is over the 1s `slow-call-duration-threshold`, so the breaker counted them as
slow and opened anyway. This is the failure mode with no error to catch, and the one
most likely to take a system down in production: a dependency that works, slowly
enough to exhaust its callers' threads. **A breaker that only watches for exceptions
will not save you from it.**

### Break 2 — prove business errors are not system errors

```bash
curl -X DELETE localhost:9092/api/lab/fault; curl -X DELETE localhost:9093/api/lab/fault
for i in $(seq 10); do curl -s -o /dev/null -w '%{http_code} ' localhost:8081/api/products/11/stock; done
curl -s localhost:8081/actuator/circuitbreakers | python3 -m json.tool
```

**Expect:** ten `404`s and `bufferedCalls: 0` — the calls are absent from the window
entirely.

Now comment out the `ignore-exceptions` line for `StockNotFoundException` in
`catalog/src/main/resources/application.properties`, rebuild, and repeat. The 404s
now land in the window as *successes* (`bufferedCalls: 10`). Move
`StockNotFoundException` into `record-exceptions` instead and the breaker opens after
five — you have just cut off a completely healthy `inventory` because somebody
browsed unstocked products.

### Break 3 — retry amplification

Set `mode: FLAKY, failureRate: 40` on both replicas, then compare `inventory`'s
request count with and without `max-attempts=3`:

```bash
docker compose logs inventory1 inventory2 --since 1m | grep -c 'GET /api/stock'
```

**Expect** noticeably more inbound requests than you sent. Retries multiply load on
a dependency that is *already* struggling. This is why the retry backs off
exponentially, and why the breaker exists to eventually stop it altogether.

### Break 4 — remove the timeouts

Set `clients.read-timeout-ms` to something enormous (or delete the `requestFactory`
call), rebuild `catalog`, then:

```bash
curl -X POST localhost:9092/api/lab/fault -d '{"mode":"SLOW","delayMs":60000}' -H 'Content-Type: application/json'
curl -X POST localhost:9093/api/lab/fault -d '{"mode":"SLOW","delayMs":60000}' -H 'Content-Type: application/json'
for i in $(seq 30); do curl -s -o /dev/null localhost:8081/api/products/1/stock & done
curl -s -m 5 localhost:8081/api/products/1    # a request that needs no inventory at all
```

**Expect** the last call — which touches only `catalog`'s own database — to hang or
time out, and the circuit to stay `CLOSED` throughout.

This is the whole argument in one command. The breaker never fires because no call
ever *finishes* to be counted. Thirty threads are parked on a dependency that is not
coming back, and an endpoint with no dependency at all is now unavailable.
**Timeouts are not an optimisation; they are the precondition for everything else
in this lab.**

### Break 5 — retry something that must not be retried

Add `@Retry(name = "inventory")` to `reserveStock` in
[`sales/.../InventoryClient.java`](../../sales/src/main/java/com/finalearth/sales/client/InventoryClient.java),
add a matching `resilience4j.retry.instances.inventory.*` block, then set
`mode: SLOW, delayMs: 3000` (above the 2s read timeout) and place an order.

```bash
docker compose exec -T postgres psql -U postgres -d microservices \
  -c "SELECT order_id, movement_type, quantity FROM inventory.stock_movements ORDER BY id DESC LIMIT 5;"
```

**Expect** several `RESERVE` movements for a single order. The order failed from the
customer's point of view, and the stock is gone anyway. Reverting that annotation is
the lesson.

---

## 5. What is still missing

- **No compensating transactions.** If payment fails after stock is reserved, the
  reservation is never released. Resilience4j keeps a request from making things
  worse; it cannot undo a partial success. That is a saga's job.
- **Unknown payment outcomes are left unresolved.** When `payment` is unreachable the
  order stays at `STOCK_RESERVED` rather than being marked `PAYMENT_FAILED` — that
  status would assert the charge did not happen, which nobody knows. Honest, but it
  needs reconciliation to finish the job.
- ~~**No bulkheads.**~~ Added in [Lab 05](05-resilience-patterns.md), along with
  rate limiters and a time limiter.
- **The breakers are per instance, not shared.** Each `catalog` replica learns
  independently that `inventory` is down.
- **No metrics backend.** `/actuator/circuitbreakerevents` keeps a small in-memory
  buffer; there is nothing collecting or alerting on state transitions.

---

**Next:** [Lab 05 — The Five Patterns, and the Order They Run In](05-resilience-patterns.md)
— rate limiters, bulkheads and time limiters, and what changes when all five are
stacked on one call.

**After that:** the failure modes Resilience4j deliberately does not solve —
compensating transactions and the saga pattern.
