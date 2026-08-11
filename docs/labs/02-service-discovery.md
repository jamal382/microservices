# Lab 02 — Service Discovery with Eureka

**Prerequisites:** Lab 01 (first run) complete. `docker compose up -d --build` brings the stack up healthy.

**What you will end up with:** a registry on `:8761`, all four services registered, and every hardcoded service URL deleted from the codebase.

---

## 1. Concept

A microservice needs to call another one. To do that it needs an address. Where does the address come from?

In Phase 1 it came from a config file. That works, and it is the right place to start, because it fails in ways you need to feel:

- **It cannot represent two instances.** `http://inventory:8082` is one address. You have two inventory containers. The config has no way to say "either of these."
- **It cannot notice death.** If the container behind that address stops, the config keeps pointing at it. Every call fails until a human edits something.
- **It has to be edited whenever deployment changes.** Add a replica, move a service, change a port — go update every caller.

A **service registry** replaces that static answer with a live one. Every instance announces itself on startup and keeps saying "still here." Callers ask the registry "where is `inventory`?" and get back the list of instances that are currently alive.

```
                    ┌─────────────────┐
                    │  Eureka :8761   │   CATALOG   → [172.24.0.5:8081]
                    │   (registry)    │   INVENTORY → [172.24.0.6:8082,
                    └─────────────────┘                172.24.0.4:8082]
                       ▲           │                PAYMENT   → [172.24.0.7:8084]
          ① register   │           │  ③ fetch registry
             heartbeat │           ▼
                  ┌────┴────┐   ┌─────────┐
                  │inventory│   │ catalog │
                  └─────────┘   └────┬────┘
                       ▲             │  ④ call an instance directly
                       └─────────────┘     (Eureka is NOT in this path)
```

**The most important thing on this diagram is step ④.** Eureka is not a proxy. It does not sit between your services, it does not forward requests, and it never sees your traffic. It answered a question earlier, the caller cached the answer, and now the caller connects straight to the target. If Eureka dies right now, existing traffic keeps flowing — callers just stop learning about changes.

### The lifecycle

| Step | What happens | Your setting |
|---|---|---|
| **Register** | Client POSTs its own details to Eureka. Status `STARTING`, then `UP`. | — |
| **Renew** | Client sends a heartbeat. Eureka *never* calls the client to check. | `lease-renewal-interval-in-seconds=10` |
| **Expire** | No heartbeat for this long → the lease is dead. | `lease-expiration-duration-in-seconds=30` |
| **Evict** | A sweeper thread removes dead leases. | `eviction-interval-timer-in-ms=5000` |
| **Fetch** | Each client re-downloads the registry into a local cache. | `registry-fetch-interval-seconds=10` |

Note that heartbeats are the *instance asserting it is alive*, not Eureka verifying. A process can be wedged, deadlocked, or serving 500s to every request and still heartbeat happily. Eureka tells you what registered, not what works.

### Staleness is the design, not a defect

Trace what happens when an inventory container is killed:

| Layer | Delay |
|---|---|
| Stops heartbeating → lease expires | up to 30s |
| Eviction sweeper notices | up to 5s |
| Caller refreshes its local cache | up to 10s |

That is up to **45 seconds** during which `catalog` holds a dead address and will confidently send requests to it. Eureka is an **AP** system in CAP terms: it chose availability over consistency. It would rather hand you a possibly-stale answer than no answer.

**This is why Lab 04 (circuit breakers) is not optional.** Discovery will hand you addresses of dead instances. Something downstream has to survive that. If you take one idea from this lab, take this one.

---

## 2. Observe the problem

Before Eureka, `sales` looked like this:

```java
public CatalogClient(@Value("${catalog.service.url:http://localhost:8081}") String catalogUrl) {
    this.restClient = RestClient.builder().baseUrl(catalogUrl).build();
}
```

with, in `application.properties`:

```properties
catalog.service.url=${CATALOG_URL:http://localhost:8081}
inventory.service.url=${INVENTORY_URL:http://localhost:8082}
payment.service.url=${PAYMENT_URL:http://localhost:8084}
```

and in `docker-compose.yml`:

```yaml
environment:
  CATALOG_URL: http://catalog:8081
  INVENTORY_URL: http://inventory:8082
```

Three separate files have to agree about an address. Now try to answer, without changing any code:

1. You run two inventory containers, `inventory1` and `inventory2`. What do you put in `INVENTORY_URL`?
2. `inventory1` crashes. How does `catalog` find out?

There are no good answers. Question 1 has no answer at all — this is exactly the wall this project hits, because the compose file defines `inventory1` and `inventory2` but the config can only name one host. **That dead end is the reason for this lab.**

---

## 3. Apply the fix

### 3a. The registry itself

A fifth module, `eureka-server/`, with one dependency:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
</dependency>
```

one annotation:

```java
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication { ... }
```

and this config:

```properties
server.port=8761

# A standalone registry must not register with, or fetch from, itself.
eureka.client.register-with-eureka=false
eureka.client.fetch-registry=false

# Learning-only. See "Break it deliberately" for why this matters.
eureka.server.enable-self-preservation=false
eureka.server.eviction-interval-timer-in-ms=5000
```

### 3b. Every service becomes a client

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
```

```properties
eureka.client.service-url.defaultZone=${EUREKA_URL:http://localhost:8761/eureka/}

# Register by IP. A container's hostname is not resolvable from outside that
# container, so hostname-based registration breaks under Docker.
eureka.instance.prefer-ip-address=true

# Unique per instance -- REQUIRED for scaling.
eureka.instance.instance-id=${spring.application.name}:${random.uuid}

eureka.instance.lease-renewal-interval-in-seconds=10
eureka.instance.lease-expiration-duration-in-seconds=30
eureka.client.registry-fetch-interval-seconds=10
```

No `@EnableDiscoveryClient` annotation is needed — the starter on the classpath is enough.

### 3c. Resolve by service ID instead of by address

`spring-cloud-starter-loadbalancer` arrives transitively with the Eureka client. Register a load-balanced builder and build clients from it:

```java
@Bean(defaultCandidate = false)
@LoadBalanced
public RestClient.Builder loadBalancedRestClientBuilder() {
    return RestClient.builder();
}

@Bean
public RestClient inventoryRestClient(@LoadBalanced RestClient.Builder builder) {
    return builder.clone().baseUrl("http://inventory").build();
}
```

**`http://inventory` is not a hostname.** There is no DNS record for it and `ping inventory` would fail. The `@LoadBalanced` interceptor sees a single-segment host, treats `inventory` as a *service ID*, looks it up in its cached registry, picks an instance (round-robin by default), and rewrites the URL to a real `ip:port` before the request leaves the JVM.

Two details in that snippet are load-bearing and both are explained in "Break it deliberately": `defaultCandidate = false` and `clone()`.

### 3d. Delete the old config

Remove `catalog.service.url`, `inventory.service.url`, `payment.service.url` from properties, and `CATALOG_URL` / `INVENTORY_URL` / `PAYMENT_URL` from `docker-compose.yml`. Add `EUREKA_URL: http://eureka:8761/eureka/` to each service instead.

**Their removal is the deliverable.** If those properties still exist, you have added a registry without actually adopting it. Check with:

```bash
grep -rn "service\.url\|_URL" --include=*.properties --include=*.yml . | grep -v EUREKA
```

---

## 4. Verify

A helper worth putting in your shell profile:

```bash
reg() {
  curl -s -H 'Accept: application/json' http://localhost:8761/eureka/apps | python3 -c "
import sys,json
apps=json.load(sys.stdin)['applications'].get('application',[])
for a in sorted(apps,key=lambda x:x['name']):
    ins=a['instance'] if isinstance(a['instance'],list) else [a['instance']]
    print('%-12s %d'%(a['name'],len(ins)))
    for i in ins:
        print('   ',i['instanceId'],i['status'],i['ipAddr']+':'+str(i['port']['\$']))"
}
```

### Check 1 — everyone registered

```bash
reg
```

```
CATALOG      1
    catalog:21680885-b59f-432d-8ce1-ff8c1727f1a2 UP 172.24.0.5:8081
INVENTORY    2
    inventory:8e8e8b44-8975-4a3f-b654-bc6649535899 UP 172.24.0.6:8082
    inventory:235c4831-2156-4fd7-a4de-fa36d2c75ac4 UP 172.24.0.4:8082
PAYMENT      1
    payment:1a914b3c-cde2-4c22-894a-89aafe6bc6d1 UP 172.24.0.7:8084
SALES        1
    sales:f222af4f-6197-44c3-aee8-9b162c047dee UP 172.24.0.9:8083
```

Two INVENTORY entries with **different** instance IDs is the thing to confirm. Also open `http://localhost:8761` — the dashboard shows the same data.

### Check 2 — the client's own view

More instructive than the registry itself, because this is the cache your load balancer actually reads:

```bash
curl -s http://localhost:8081/actuator/health | python3 -m json.tool
```

```json
"eureka": { "details": { "applications": {
    "PAYMENT": 1, "CATALOG": 1, "INVENTORY": 2, "SALES": 1 } } }
```

Kill a container and run `reg` and this command in quick succession. **They will disagree for a few seconds.** That disagreement is the staleness window from section 1, made visible.

### Check 3 — load balancing actually distributes

```bash
for i in $(seq 6); do curl -s -o /dev/null http://localhost:8081/api/products/1/stock; done
docker compose logs catalog | grep "catalog->inventory" | tail -6
```

```
served-by=bd5612c92ad2
served-by=f6d84918b238
served-by=bd5612c92ad2
served-by=f6d84918b238
served-by=bd5612c92ad2
served-by=f6d84918b238
```

Perfect alternation. `X-Instance-Id` is stamped by inventory's logging filter; it is how the caller can tell which replica answered. Nothing in the code names either container.

### Check 4 — eviction and recovery

```bash
docker compose stop payment
sleep 45 && reg          # PAYMENT gone
docker compose start payment
sleep 40 && reg          # PAYMENT back
```

Measured on this stack: gone at 45s, back at 40s. Try it with `sleep 20` after stopping — payment is usually **still listed**. That is the 30s lease expiry, not a bug.

### Check 5 — the whole thing still works

```bash
curl -X POST http://localhost:8083/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1}],"paymentMethod":"CARD"}'
```

A `201` with `"status":"CONFIRMED"` means `sales` reached catalog, inventory, and payment — all three resolved through the registry, none of them configured with an address.

---

## 5. Break it deliberately

Do these. This is the part that turns trivia into knowledge.

### Break 1 — remove the unique instance ID

Comment out `eureka.instance.instance-id` in `inventory/src/main/resources/application.properties`, then:

```bash
docker compose up -d --build inventory1 inventory2 && sleep 30 && reg
```

**Expected:** INVENTORY collapses toward a single entry — the replicas overwrite each other's registration. The default instance ID is derived from the hostname, which in a container deployment is *sometimes* unique and sometimes not, which is worse than reliably broken because it produces intermittent wrong instance counts.

**The symptom to memorize:** you scaled to N replicas, the dashboard shows fewer, and traffic all lands on one container. Restore the line and confirm they separate again.

### Break 2 — let Eureka's own transport grab the load-balanced builder

Delete `defaultCandidate = false` from `RestClientConfig`, rebuild, and watch the logs:

```
BeanCurrentlyInCreationException: Error creating bean with name 'scopedTarget.eurekaClient'
No servers available for service: eureka
Cannot execute request on any known server
```

**Why:** the Eureka client's own HTTP transport injects whatever `RestClient.Builder` it can find *by type*. If it finds the load-balanced one, it tries to resolve the registry's address **through the registry** — a circular lookup, at startup, before the registry client exists. `defaultCandidate = false` removes the bean from by-type resolution, so Eureka gets the plain auto-configured builder while your `@LoadBalanced`-qualified injection points still get the right one.

This one is genuinely confusing when you meet it cold, because the stack trace never mentions your config class.

### Break 3 — drop the `clone()`

`RestClient.Builder.baseUrl()` mutates the builder in place and returns `this`. Three clients built from one shared singleton builder are three clients fighting over one object's state. Remove `.clone()` and reason about what each client's base URL ends up as. (In `catalog`, with only one downstream, it appears to work — which is exactly how this kind of bug survives to production.)

### Break 4 — take a healthy instance out of rotation

```bash
INST=$(curl -s -H 'Accept: application/json' http://localhost:8761/eureka/apps/INVENTORY \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['application']['instance'][0]['instanceId'])")

curl -X PUT "http://localhost:8761/eureka/apps/INVENTORY/$INST/status?value=OUT_OF_SERVICE"
```

Wait a few seconds, then re-run Check 3. All six requests land on the *other* replica.

The container is still running. It is still healthy. `docker ps` says it is fine. Its healthcheck passes. It receives **zero traffic**, because one field in the registry says `OUT_OF_SERVICE`. This is how blue/green deploys and drain-before-restart actually work.

To restore:

```bash
curl -X PUT "http://localhost:8761/eureka/apps/INVENTORY/$INST/status?value=UP"
```

⚠️ **`curl -X DELETE .../status` does not restore it.** The DELETE clears the *override* but leaves the instance at `status=UNKNOWN`, which the load balancer also excludes — so it stays dark and you will think the PUT broke something permanently. Verified on this stack: after the DELETE, status sat at `UNKNOWN` and all traffic kept going to one replica until an explicit `PUT ...?value=UP`. Status writes take single-digit seconds to appear (measured ~6s), so re-read before concluding anything.

**Lesson:** health and eligibility are independent in Eureka. When one replica is idle while its siblings work, check `status` in the registry *before* you go looking at the container.

### Break 5 — kill the registry

```bash
docker compose stop eureka
curl -X POST http://localhost:8083/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1}],"paymentMethod":"CARD"}'
```

Orders still work. Callers are serving from their cached registry snapshot. Now restart it and watch how long re-registration takes.

This is the AP tradeoff paying off: the registry is not a single point of failure for *traffic*, only for *change*.

---

## 6. Questions to answer

If you cannot answer these from memory, re-read section 1.

1. When `sales` calls `catalog`, how many network hops are there, and does Eureka see the request?
2. An inventory container is `kill -9`'d. What is the worst-case time before `catalog` stops trying to call it? Which three settings add up to that number?
3. Why does Eureka not health-check your services itself? What can it therefore *not* tell you?
4. Why must `eureka.instance.instance-id` be unique? What exactly breaks if it is not, and what does the symptom look like?
5. `http://inventory` has no DNS record. Explain what turns it into a real address, and at what moment.
6. Why does the registry itself set `register-with-eureka=false`?
7. An instance is `UP` in the registry but every request to it returns 500. Does Eureka notice? What would have to be added for the system to cope?
8. Two load balancers are coming in this project (HAProxy in Lab 03, this one). Which handles traffic *entering* the system, and which handles service-to-service traffic? Why is it wrong to route internal calls through the edge gateway?

---

## Reference

**Endpoints**

| | |
|---|---|
| `http://localhost:8761` | Dashboard |
| `GET /eureka/apps` | Whole registry (send `Accept: application/json`, default is XML) |
| `GET /eureka/apps/{APP}` | One application — app names are **uppercased** |
| `PUT /eureka/apps/{APP}/{INSTANCE}/status?value=UP\|OUT_OF_SERVICE` | Force status |
| `DELETE /eureka/apps/{APP}/{INSTANCE}` | Deregister (heartbeats will re-register it) |
| `GET /actuator/health` on any client | That client's cached view |

**Symptom → cause**

| Symptom | Cause |
|---|---|
| N replicas show as 1 | `eureka.instance.instance-id` not unique — Break 1 |
| `No servers available for service: eureka` at startup | Eureka's transport grabbed the `@LoadBalanced` builder — Break 2 |
| `UnknownHostException: inventory` | `@LoadBalanced` wiring lost; the URL was treated as a real hostname |
| Registry shows container hostnames, calls fail | `prefer-ip-address` not set |
| One replica idle, siblings busy | Its registry `status` is not `UP` — Break 4 |
| Stopped service never disappears | Self-preservation is on |

**Next:** Lab 03 — scaling and server-side load balancing with HAProxy (PRD §5.2–§5.3).
