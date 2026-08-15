# Lab 05 — The Five Patterns, and the Order They Run In

**Prerequisites:** the stack runs healthy (`docker compose up -d --build`) and the
seed data is loaded. [Lab 04](04-resilience4j.md) covers retries, circuit breakers
and fallbacks in depth; this lab adds the remaining two patterns and — the part that
actually matters — explains what happens when all five are stacked on one call.

**What you will end up with:** the ability to answer, for any call in this system,
*which* layer refused it and *why that layer and not another one*.

```
Rate limiter   → too many calls per second
Bulkhead       → too many calls at once
Time limiter   → this call took too long
Circuit breaker→ too many calls have been failing lately
Retry          → that call failed, try again
```

Every number and every log line below was read out of the running stack.

---

## 1. Concept

### Five patterns, five different failures

The temptation is to treat these as a bundle of "resilience features" and turn them
all on. They are not a bundle. Each one stops a **different** failure, and a system
with four of them has a specific, nameable hole in it.

| Pattern | The question it answers | What it throws when it says no | Cost when it says yes |
|---|---|---|---|
| **Rate limiter** | Are we calling this thing too *often*? | `RequestNotPermitted` | An atomic counter read |
| **Bulkhead** | Are too many calls happening *at once*? | `BulkheadFullException` | A semaphore acquire, or a thread hand-off |
| **Time limiter** | Has *this* call taken too long? | `TimeoutException` | A scheduled task per call |
| **Circuit breaker** | Has this dependency been failing *lately*? | `CallNotPermittedException` | A ring-buffer write |
| **Retry** | Was that failure *transient*? | the last exception | Nothing, until it fires |

Read down the "throws" column: **four of the five refuse calls that would otherwise
have succeeded.** That is not a bug in the design, it is the design — each one trades
some availability now for the ability to keep serving later. Which is exactly why
turning them all up is wrong, and why every limit in this project has a comment
explaining the number.

### Rate limiter vs bulkhead — not the same limit

This is the pair people most often collapse into one, and the numbers make the
difference obvious.

- A **rate limiter** counts calls **started per unit of time**. It refills on a
  clock and does not care whether previous calls have finished.
- A **bulkhead** counts calls **in flight right now**. It refills only when a call
  completes and does not care how fast they arrive.

Twenty concurrent calls that each take 5ms is about **4000 calls per second** — well
inside a bulkhead of 20, and forty times over a limiter of 100/s. Twenty concurrent
calls that each take 5 *seconds* is **4 calls per second** — trivial for the limiter,
and permanently at the bulkhead's ceiling. Neither limit implies the other, and a
system protected by only one of them has an obvious way to fall over.

The mnemonic: **the rate limiter protects the callee, the bulkhead protects the
caller.** A limiter keeps you from flooding something downstream. A bulkhead keeps a
slow downstream from consuming all of *your* threads.

### The three names for "timeout"

There are three separate mechanisms in this project that a person would call a
timeout, they fire at different times, and they do different things.

| | Where | Bounds | Who observes it |
|---|---|---|---|
| **Socket timeouts** | `RestClientConfig` — 1s connect, 2s read | each I/O step separately | the **worker** thread, which is genuinely released |
| **Time limiter** | `@TimeLimiter` — 1.5s | the whole attempt, one number | the **caller**, which stops waiting |
| **Slow-call threshold** | breaker config — 1s | nothing | the **breaker**, which counts the call as a failure even though it succeeded |

The third one catches the failure with no error in it — a dependency that answers
correctly, just too slowly to be useful. See Lab 04, Break 1.

The relationship between the first two is the subtle part, and it is why the
deadline (1.5s) is set *below* the read timeout (2s):

> **A time limiter cancels the waiting. It does not cancel the work.**

When the deadline fires, the future completes exceptionally and the caller is
released. The thread that is actually blocked in the socket read **stays blocked** —
a thread parked on blocking I/O cannot be interrupted, so it sits there until the
2s read timeout releases it. For half a second, that request is consuming a bulkhead
thread that nobody is waiting for any more.

This has a real consequence: **the socket timeouts are still the load-bearing
bound.** The time limiter is a deadline you can state in one number and reason about;
it is not a replacement for bounding the I/O. Remove the socket timeouts and add a
time limiter, and you have built a system that reports fast failures while quietly
accumulating blocked threads forever.

---

## 2. How they compose

### The order is fixed, and it is not the order you write them in

Resilience4j orders its aspects by Spring's `Ordered` value. Lower value = higher
precedence = **further out**. From the 2.4.0 jars:

| Aspect | Default order | Position |
|---|---|---|
| `RetryAspect` | `MAX_VALUE - 5` | outermost |
| `CircuitBreakerAspect` | `MAX_VALUE - 4` | |
| `RateLimiterAspect` | `MAX_VALUE - 3` | |
| `TimeLimiterAspect` | `MAX_VALUE - 2` | |
| `BulkheadAspect` | `MAX_VALUE - 1` | innermost |

Which gives, for
[`catalog`'s `InventoryClient`](../../catalog/src/main/java/com/finalearth/catalog/client/InventoryClient.java):

```
Retry {                            ← sees every way the call can end
    CircuitBreaker {               ← counts outcomes, refuses when open
        RateLimiter {              ← spends a permit
            TimeLimiter {          ← starts the wall-clock deadline
                Bulkhead {         ← hands work to its own pool
                    the HTTP call  ← bounded by the socket timeouts
                }
            }
        }
    }
}
```

Writing the annotations in a different order changes nothing. Each is overridable
(`resilience4j.retry.retryAspectOrder`, etc.), but there is rarely a good reason.

### Four consequences, all of them traps

**1. Every retry attempt is counted separately by the breaker.** One user request
that retries three times puts *three* failures into the sliding window. A retrying
caller trips its own breaker roughly three times faster than reading the config
suggests. With `sliding-window-size=10` and `minimum-number-of-calls=5`, two failing
requests are enough.

**2. The breaker sits *outside* the rate limiter and the bulkhead**, so their
rejections pass straight through its accounting on the way out. This is the one that
silently corrupts your metrics if you miss it. `record-exceptions` is an allow-list —
anything unlisted defaults to **success**. So an unlisted `BulkheadFullException`
does not merely fail to open the breaker; it actively props the success rate *up*,
at precisely the moment the system is saturated. Both are in `ignore-exceptions` for
that reason:

```properties
resilience4j.circuitbreaker.instances.inventory.ignore-exceptions[1]=io.github.resilience4j.ratelimiter.RequestNotPermitted
resilience4j.circuitbreaker.instances.inventory.ignore-exceptions[2]=io.github.resilience4j.bulkhead.BulkheadFullException
```

The principle: **a call you refused to make is not evidence about the dependency.**
It is evidence about *you*.

**3. Retrying into a saturated limit makes it worse.** A retry after
`RequestNotPermitted` consumes another permit that a first-time caller could have
used, and is almost certain to be refused again. Both exception types are in the
retry's `ignore-exceptions` too, so they fail straight to the fallback.

**4. The rate limiter spends its permit before the bulkhead is consulted.** A call
rejected by a full bulkhead has already cost a permit it never used. Harmless at
these limits — but it is the reason the limiter is the looser of the two caps.

### The three buckets, extended

Lab 04 introduced Resilience4j's three-way sort of every call. The two new exception
types slot into it, and the reasoning for each is different:

| Bucket | Config key | Effect | Examples here |
|---|---|---|---|
| **Failure** | `record-exceptions` | pushes the breaker toward OPEN | `HttpServerErrorException`, `ResourceAccessException`, **`TimeoutException`** |
| **Ignored** | `ignore-exceptions` | not counted at all | `StockNotFoundException`, `DependencyBusinessException`, **`RequestNotPermitted`**, **`BulkheadFullException`** |
| **Success** | *(default for anything unlisted)* | holds the breaker CLOSED | a normal 200 |

`TimeoutException` is recorded as a **failure** — a dependency too slow to be useful
is a dependency that is unavailable, and the breaker should react to it. The two
rejection types are **ignored** — the dependency was never contacted, so they say
nothing about its health in either direction.

---

## 3. What is configured where

### The policy matrix

**Resilience is not a feature you turn all the way up.** Each dependency gets the
policy its semantics allow, and the differences below are all deliberate.

| Caller → callee | Method | Retry | Breaker | Rate limit | Bulkhead | Time limit | Fallback |
|---|---|---|---|---|---|---|---|
| `catalog` → `inventory` | GET stock | 3 attempts | yes | 100/s | **16 threads + 32 queue** | **1.5s** | degraded response |
| `sales` → `catalog` | GET product | 3 attempts | yes | 100/s | 20 concurrent | socket only | none → 503 |
| `sales` → `inventory` | POST reserve | **none** | yes | 50/s | 15 concurrent | socket only | none → 503 |
| `sales` → `payment` | POST charge | **none** | yes | **25/s** | **10 concurrent** | socket only | none → 503 |

### Why `catalog` gets the thread-pool bulkhead and `sales` does not

There are two bulkhead implementations and they are not interchangeable.

- **`SEMAPHORE`** (the default) is a counter. It runs the call on the caller's own
  thread and refuses to start more than `max-concurrent-calls` at once. Nearly free,
  and it keeps `ThreadLocal` state — MDC, transaction context, security context —
  intact for nothing.
- **`THREADPOOL`** hands the call to its own pool and returns a future. It costs a
  thread hand-off and a context switch on every call, and it breaks every
  `ThreadLocal` you were relying on.

You pay for `THREADPOOL` when you need to **walk away from a call in progress** —
which is exactly what a time limiter does. That is the only reason `catalog` uses it:
a semaphore bulkhead would release its permit the moment the method returned a
future, i.e. immediately, capping nothing at all.

`sales` has no such need. Nothing on its three paths should be abandoned mid-flight —
a half-abandoned charge is the worst outcome in the system — so all three use
`SEMAPHORE` and keep their socket timeouts as the bound.

### Why the limits get tighter down the order flow

The three numbers in `sales` are not arbitrary:

- **catalog, 100/s and 20 concurrent** — loosest. Every line of every order calls it,
  and it is a cheap idempotent read.
- **inventory, 50/s and 15 concurrent** — tighter. Reservations mutate shared state
  and contend on the same rows, and there is exactly one per order against several
  price lookups.
- **payment, 25/s and 10 concurrent** — tightest, and it is also the slowest call
  (5s read timeout), so each occupied slot is held far longer than one of catalog's.

Payment's number is also the one that is **not an engineering judgement**. A
provider's TPS ceiling is a figure in a contract, and exceeding it gets you throttled
by a rate limiter you do not control, on requests you cannot choose. Setting your own
limit just below theirs converts their enforcement — opaque, punitive, applied to
whichever requests happen to arrive last — into your own, which you can see, log, and
explain to a customer.

### Rejection as the *safest* failure

There is a reason worth stating for putting limits in front of the two write paths
that deliberately have **no retry**:

> A rate limiter or bulkhead rejection is the only failure on a write path where the
> state of the callee is not in doubt.

Nothing was reserved, because nothing was asked. Compare a read timeout, where the
reservation may well have been committed and only the response lost — the ambiguity
that forces `SalesService` to leave the order at `STOCK_RESERVED` rather than assert
a status it cannot know. The fallbacks say so explicitly:

```
[r4j] FALLBACK inventory order=42 reason=rate-limited -- self-imposed limit, NOTHING was reserved
[r4j] FALLBACK inventory order=42 reason=call-failed  -- reservation state on the callee is UNKNOWN
```

**A limit that turns an ambiguous failure into a definite one is worth having for
that reason alone**, before you get to the load it sheds.

---

## 4. What the async conversion cost

Adding `@TimeLimiter` to `catalog` meant returning `CompletableFuture`, and that has
three consequences that are not in any tutorial.

### The body still runs synchronously

`completedFuture` here wraps work that has *already happened*:

```java
@Bulkhead(name = "inventory", type = Bulkhead.Type.THREADPOOL)
public CompletableFuture<StockLookup> getStockByProductId(Long productId) {
    ...blocking RestClient call...
    return CompletableFuture.completedFuture(StockLookup.of(response.getBody()));
}
```

The bulkhead aspect is what makes it asynchronous — it submits the whole method to
its pool and joins the result. Writing `supplyAsync` here would add a *second* pool
and put the work outside the bulkhead's accounting.

### MDC breaks, and fixing it needs two propagators

`MDC` is a `ThreadLocal`, so the pool thread has never seen the request. Without a
`ContextPropagator` the `reqId` is blank on exactly the lines you most need to
correlate. Two separate registrations are required, because two different pools are
involved:

```properties
# the pool the call runs on
resilience4j.thread-pool-bulkhead.instances.inventory.context-propagators[0]=com.finalearth.catalog.config.MdcContextPropagator
# the scheduler that fires the deadline -- and therefore runs the breaker's
# accounting, the retry's decision and the fallback when a call times out
resilience4j.scheduled.executor.core-pool-size=4
resilience4j.scheduled.executor.context-propagators[0]=com.finalearth.catalog.config.MdcContextPropagator
```

Miss the second one and Resilience4j quietly builds its own plain executor: the
timeout path — the one you actually need to debug — is the only path whose log lines
have no request id.

[`MdcContextPropagator`](../../catalog/src/main/java/com/finalearth/catalog/config/MdcContextPropagator.java)
also has a deliberately empty `clear()`. Resilience4j calls it *before* the future
completes, and the aspects outside the bulkhead do their work in that future's
completion callbacks — so clearing there strips the id from the breaker, retry and
fallback lines. `copy()` clears unconditionally instead, which keeps a pool thread
from ever starting a task wearing the previous one's identity.

### The self-invocation trap gets more dangerous

The natural next step is a synchronous convenience wrapper on the same bean:

```java
// DO NOT DO THIS
public StockLookup getStock(Long id) {
    return getStockByProductId(id).join();   // internal call -- skips the Spring proxy
}
```

An internal `this.` call does not go through the proxy, so **all five aspects are
silently skipped**. No error, no warning, no log line — the resilience is simply not
there. This is why the join helper is a `static` method taking the future as an
argument: it cannot be written the wrong way.

---

## 5. Verify

Everything below is reproducible against the running stack.

### The whole chain on one timed-out call

```bash
for p in 9092 9093; do
  curl -s -X POST localhost:$p/api/lab/fault -H 'Content-Type: application/json' \
       -d '{"mode":"SLOW","delayMs":3000}' -o /dev/null
done
time curl -s localhost:8081/api/products/1/stock
```

**Expect ~5.1s**, a `200`, and `"stockNote":"inventory did not answer in time"`. The
log tells the whole story, and every line carries the same request id:

```
[catalog,2b507f5a] TIMELIMITER 'inventory' DEADLINE EXCEEDED -- the caller stopped waiting
[catalog,2b507f5a] CIRCUIT 'inventory' recorded a FAILURE after 1508 ms: TimeoutException
[catalog,2b507f5a] RETRY 'inventory' attempt 1 after TimeoutException: waiting 200 ms
[catalog,2b507f5a] TIMELIMITER 'inventory' DEADLINE EXCEEDED
[catalog,2b507f5a] CIRCUIT 'inventory' recorded a FAILURE after 1501 ms: TimeoutException
[catalog,2b507f5a] RETRY 'inventory' attempt 2 after TimeoutException: waiting 400 ms
[catalog,2b507f5a] TIMELIMITER 'inventory' DEADLINE EXCEEDED
[catalog,2b507f5a] CIRCUIT 'inventory' recorded a FAILURE after 1501 ms: TimeoutException
[catalog,2b507f5a] RETRY 'inventory' GAVE UP after 3 attempts
[catalog,2b507f5a] FALLBACK product=1 reason=deadline-exceeded
```

`1500 + 200 + 1500 + 400 + 1500 ≈ 5.1s`. Note the deadline fires at ~1501ms every
time and the 3000ms fault never completes — the socket read timeout at 2000ms is
what eventually frees each abandoned thread, half a second after the caller left.

Two more calls and the breaker gives up entirely:

```bash
for i in 1 2; do curl -s -o /dev/null localhost:8081/api/products/1/stock; done
curl -s localhost:8081/actuator/circuitbreakers | python3 -m json.tool
time curl -s localhost:8081/api/products/1/stock
```

```json
{"state": "OPEN", "failureRate": "100.0%", "slowCallRate": "100.0%",
 "bufferedCalls": 5, "failedCalls": 5, "notPermittedCalls": 2}
```

**The next call returns in 0.012s instead of 5.1s** — a 400× drop, with
`"stockNote":"inventory circuit is open"`. That collapse is the breaker's entire
value.

### The rate limiter

```bash
for p in 9092 9093; do curl -s -X DELETE localhost:$p/api/lab/fault -o /dev/null; done
sleep 12   # let the breaker close
seq 1 160 | xargs -P 160 -I{} curl -s -o /dev/null localhost:8081/api/products/1/stock
docker compose logs catalog --tail=300 | grep -c "RATELIMITER 'inventory' REJECTED"
```

**Expect ~35 rejections** out of 160 — 100 permits in the first second, more as the
window refreshes mid-burst. Every request still returns `200`: the rejected ones fall
back to `"stockNote":"stock lookups are rate limited"`, which is the honest answer.
The product data was never in doubt.

### The bulkhead

The delay is chosen to isolate the bulkhead from everything else — 800ms is under
the 1s slow-call threshold *and* under the 1.5s deadline, so nothing times out and
the breaker stays closed:

```bash
for p in 9092 9093; do
  curl -s -X POST localhost:$p/api/lab/fault -H 'Content-Type: application/json' \
       -d '{"mode":"SLOW","delayMs":800}' -o /dev/null
done
seq 1 70 | xargs -P 70 -I{} curl -s -o /dev/null localhost:8081/api/products/1/stock
docker compose logs catalog --tail=300 | grep "BULKHEAD 'inventory' REJECTED" | tail -2
```

```
BULKHEAD 'inventory' REJECTED a call -- pool and queue are full (threads=16/16 queueDepth=32/32)
```

Exactly the configured caps: 16 running, 32 queued, the rest refused in microseconds.

### Proving rejections do not corrupt the breaker

This is the one worth doing, because it validates the `ignore-exceptions` claim.
Fire 45 concurrent orders for the same product — enough row contention in `inventory`
to cause real timeouts *alongside* bulkhead rejections:

```bash
for p in 9092 9093; do curl -s -X DELETE localhost:$p/api/lab/fault -o /dev/null; done
seq 1 45 | xargs -P 45 -I{} curl -s -o /dev/null -X POST localhost:8083/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":5,"quantity":1}],"paymentMethod":"CARD"}'

docker compose logs sales --tail=500 | grep -oE "FALLBACK inventory .*reason=[a-z-]+" \
  | grep -oE "reason=[a-z-]+" | sort | uniq -c
```

A representative run:

```
     19 reason=circuit-open
     15 reason=call-failed
      3 reason=bulkhead-full
```

with, in between:

```
CIRCUIT 'inventory' IGNORED BulkheadFullException -- not counted as a failure or a success
```

**The breaker opened on the 15 real timeouts and ignored the 3 self-inflicted
rejections.** Remove those `ignore-exceptions` lines and the 3 would have counted as
*successes*, diluting a 100% failure rate to 83% — still enough to open here, but the
general case is a system that saturates, reports healthy, and never sheds load.

### Live state

```bash
curl -s localhost:8081/actuator/ratelimiters      | python3 -m json.tool
curl -s localhost:8081/actuator/bulkheads         | python3 -m json.tool
curl -s localhost:8081/actuator/timelimiters      | python3 -m json.tool
curl -s localhost:8081/actuator/bulkheadevents    | python3 -m json.tool
curl -s localhost:8083/actuator/ratelimiterevents | python3 -m json.tool
curl -s localhost:8081/actuator/health            | python3 -m json.tool   # includes rateLimiters
```

---

## 6. Break it yourself

### Break 1 — the semaphore bulkhead that caps nothing

In `catalog`, change the bulkhead to the semaphore type:

```java
@Bulkhead(name = "inventory")   // was: type = Bulkhead.Type.THREADPOOL
```

and add `resilience4j.bulkhead.instances.inventory.max-concurrent-calls=2`. Rebuild,
set a `SLOW` fault, and fire 50 concurrent requests.

**Expect zero rejections**, with a cap of 2. The semaphore is released when the
method *returns* — and the method returns a future immediately, before any work has
finished. A semaphore bulkhead on a future-returning method is a decoration. This is
the concrete reason the two bulkhead types are not interchangeable.

### Break 2 — put the deadline above the read timeout

Set `resilience4j.timelimiter.instances.inventory.timeout-duration=5s`, rebuild, and
set a `SLOW` fault of 3000ms.

**Expect** the call to fail at ~2s with `ResourceAccessException`, not at 5s with
`TimeoutException` — the socket read timeout beats the deadline and the time limiter
never fires at all. A deadline set above the sum of the socket timeouts is dead
configuration: it looks like a safety net and can never catch anything.

### Break 3 — let rejections count as successes

Comment out the two `ignore-exceptions` lines for `RequestNotPermitted` and
`BulkheadFullException` in `catalog`'s properties, rebuild, then run the bulkhead
saturation test from §5 with a `SLOW` fault of 1800ms (above the 1s slow threshold,
so real slow calls happen too).

```bash
curl -s localhost:8081/actuator/circuitbreakers | python3 -m json.tool
```

**Expect** `failureRate` and `slowCallRate` visibly *lower* than the run with the
lines in place, and more calls in `bufferedCalls`. The harder the system is pushed,
the more rejections there are, and the healthier it reports. **A metric that improves
under overload is worse than no metric.**

### Break 4 — retry into a rate limit

Remove `RequestNotPermitted` from the retry's `ignore-exceptions`, rebuild, and fire
the 160-request burst.

**Expect** the total number of `RATELIMITER ... REJECTED` lines to roughly triple
while the number of *successful* calls does not improve. Every rejected call now
consumes three permits instead of one, and the extra two are guaranteed to be
refused — the retry is not recovering anything, it is deepening the shortage it is
reacting to.

### Break 5 — the invisible one

Add a synchronous wrapper to `catalog`'s `InventoryClient` and call it from
`CatalogService`:

```java
public StockLookup getStock(Long id) {
    return getStockByProductId(id).join();
}
```

Rebuild and set both replicas to `ERROR`.

**Expect** the call to fail with a raw `500`, no `[r4j]` lines at all, and an empty
`/actuator/circuitbreakers` window. The internal call bypassed the proxy and all five
patterns went with it. Nothing warns you. **This is the most dangerous failure in
this lab, because it looks exactly like everything working.**

---

## 7. What is still missing

- **The limits are per instance, not shared.** Each replica has its own 100/s. Run
  three `catalog` containers and the real ceiling on `inventory` is 300/s. Any
  meaningful global limit needs shared state — Redis, a service mesh, or the callee
  enforcing its own.
- **No inbound limiting.** Every limit here is applied by the *caller*, voluntarily.
  A service that only defends itself when its callers cooperate is not defended;
  `inventory` should enforce its own ceiling and return `429`.
- **The bulkhead queue hides saturation.** 32 queued calls at 800ms each means the
  last one waits over a second before it even starts. A queue converts a rejection
  into latency, which is sometimes what you want and sometimes a way to fail more
  slowly. Setting `queue-capacity=0` is worth trying.
- **`TimeoutException` does not distinguish a slow dependency from a slow network.**
  Both surface identically, and the breaker treats them the same. Only the
  `X-Instance-Id` on successful calls hints at which replica is unwell.
- **No metrics backend.** The `/actuator/*events` endpoints keep a small in-memory
  buffer; nothing collects or alerts on any of this.

---

**Previous:** [Lab 04 — Retries, Circuit Breakers and Fallbacks](04-resilience4j.md)
— the three patterns this one builds on, in more depth.
