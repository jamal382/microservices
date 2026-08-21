# Lab 03 — Edge Gateway and Server-Side Load Balancing with HAProxy

**Prerequisites:** Lab 01 (first run) complete. `docker compose up -d --build` brings the stack up healthy.

> Lab 02 (Eureka) is **archived** — the stack no longer runs a registry. This lab
> does not depend on it and does not assume you did it. Where Lab 02 used a
> registry to answer "where is `inventory`?", this system answers it two ways:
> HAProxy for traffic coming from outside, Docker's embedded DNS for traffic
> between services.

**What you will end up with:** exactly one door into the system — `:80` — with a
live stats dashboard on `:8404`. No service publishes a host port. Every client
API request is routed by path and load-balanced outside the JVMs.

---

## 1. Concept

Before this lab, reaching the system meant knowing four ports:

```
curl http://localhost:8081/api/products/1
curl http://localhost:8083/api/orders
curl http://localhost:8084/api/payments/order/1
curl http://localhost:9092/api/stock/1   # or 9093 — whichever replica you feel like hitting
```

Every one of those is an internal fact leaking outward: how many services exist,
which port each one picked, which host ports the compose file happened to
publish, and — worst — that `inventory` is two containers you are expected to
choose between. It also means TLS, access logging, and rate limiting would each
have to be implemented four times.

**A reverse proxy replaces four doors with one.** Every request arrives at `:80`;
HAProxy reads the path and decides which backend gets it.

```
                          ┌──────────────────────────┐
        client ─────────▶ │   HAProxy :80            │
                          │   ACL: path_beg /api/... │
                          │   stats :8404            │
                          └───┬────┬────┬────┬───────┘
                              │    │    │    │
                     /products│    │    │    │/payments
                              ▼    │    │    ▼
                          catalog  │    │  payment
                     /stock       ▼    ▼      /orders
                          inventory×2  sales
```

This is **server-side load balancing**: one shared component, outside every
application, decides where a request goes.

### The compose change that makes it real

This lab does not just *add* HAProxy — it takes the alternatives away. In
`docker-compose.yml`, **every `ports:` block except HAProxy's is gone**:

```yaml
catalog:
  # no ports:
  networks: [backend]
```

A container with no `ports:` is still fully reachable *inside* the `backend`
network by its name — `catalog:8081` works from any other container. It is only
unreachable from the host. That distinction is the whole mechanism: the service
did not become more private to its peers, only to the outside.

Three things follow, and they are the reason this is worth doing:

1. **The gateway stops being optional.** While `:8081` was published, HAProxy was
   a convenience you could route around — and any client that routed around it
   also routed around the health checks. Now there is no path that skips it.
2. **Replicas stop fighting over host ports.** `inventory1` and `inventory2` used
   to need hand-assigned host ports (`9092`, `9093`) purely because two
   containers cannot bind the same one. That mapping was a scaling ceiling
   written into the compose file: a third replica meant inventing `9094`. With no
   host ports at all, replica count is a free variable.
3. **`inventory` becomes genuinely anonymous.** `9092` vs `9093` was the last
   place a caller could name an individual replica. Gone, there is no client-side
   way to address one — which is what lets HAProxy's health checks actually
   decide who serves.

The cost is real and worth stating: you can no longer `curl :8081` to isolate a
service. Reaching a single container is now a `docker compose exec` away — see
Check 3.

### Routing a UI, not an API — `pgadmin`

`pgadmin` lost its `5050` mapping like everything else, but it is a browser UI,
so `docker compose exec` is no substitute. It goes through the gateway instead,
at `http://localhost/pgadmin` — and it is the one backend that needs more than an
ACL and a `server` line, for reasons that generalise to any UI behind a proxy.

**An API does not care where it is mounted; a UI does.** `catalog` answers
`/api/products/1` and returns JSON containing no URLs. pgAdmin returns HTML full
of links, redirects, form actions, and `<script src=...>` tags — every one of
which has to be an address the *browser* can resolve. Mount it at `/pgadmin` and
tell it nothing, and it emits `href="/browser/browser.css"`; the browser
dutifully requests `/browser/browser.css` at the gateway, which matches no ACL,
falls through to `default_backend sales_backend`, and you get a blank page and a
console full of 404s.

Two ways to fix that, and the choice matters:

1. **Strip the prefix at the proxy** (`http-request replace-path /pgadmin/?(.*) /\1`)
   — the app never learns it is mounted anywhere. Works for the first request and
   breaks on every self-referential URL it generates afterwards.
2. **Tell the app its own mount point** — `SCRIPT_NAME: /pgadmin` in
   `docker-compose.yml`. pgAdmin then prefixes everything it generates, and
   HAProxy passes the path through **unchanged**.

This lab uses (2). Verify it is actually doing the work:

```bash
curl -sL http://localhost/pgadmin | grep -o 'href="/[^"]*"' | sort -u | head -3
```

```
href="/pgadmin/browser/browser.css?ver=91300"
href="/pgadmin/favicon.ico?ver=91300"
href="/pgadmin/static/js/generated/style.css?ver=91300"
```

The prefix is in the markup pgAdmin generated. That is the whole mechanism — no
rewriting rule could produce this, because the rewrite happens before HAProxy
ever sees the response body.

The backend also raises `timeout client`/`timeout server` to `300s`, overriding
the 30s in `defaults`. Those defaults are sized for API calls; a UI that holds
connections open for query execution and polling would get cut off mid-flight.

**One `server` line, not a `server-template`.** Every other backend can scale;
this one deliberately cannot. pgAdmin holds local session state, so balancing
across two replicas would log you out at random. `resolvers docker` still applies,
so a restarted container is re-resolved rather than pinned to a dead IP — the
same reason the templates use it, minus the scaling.

> **Note on exposure.** pgAdmin runs with `PGADMIN_CONFIG_SERVER_MODE: "False"`,
> which means no login prompt. It now sits behind the same `:80` as the API, so
> any rule that opens the API to a network opens an unauthenticated database
> admin console to it as well. Fine on a laptop; restrict it with
> `acl is_pgadmin path_beg /pgadmin` plus a `src` match, or turn server mode on,
> before this stack goes anywhere shared.

### North–south and east–west are still different problems

HAProxy handles every request that *enters* the system. It deliberately does not
handle requests *between* services:

| | HAProxy (north–south) | Docker DNS (east–west) |
|---|---|---|
| Handles | outside → in | service → service |
| Sits | in the request path, as a hop | nothing in the path; caller connects direct |
| Knows instances via | DNS + its own health checks | DNS only |
| Health-aware | **Yes** — `option httpchk` | **No** — DNS reports existence, not readiness |
| Extra network hop | Yes | No |
| Failure mode | one shared component; its outage is total | per-caller; degrades independently |

`sales → catalog`, `sales → inventory`, `sales → payment`, and
`catalog → inventory` all use plain container names or the `inventory` network
alias. `http://inventory:8082` resolves to **two** A records, because
`inventory1` and `inventory2` share that alias, and Docker's DNS rotates them.

**Why not send internal calls through the gateway too?** It would work, and it
would cost a hop through one shared process for traffic that never needed to
leave the network — plus it would make HAProxy a single point of failure for
internal traffic, not just external. Break 3 makes that concrete.

The honest asymmetry: the east–west path has **no health checking at all**. DNS
will happily hand `catalog` the address of an `inventory` replica that is up but
returning 500s to everything. That gap is not an oversight to fix by routing
internal traffic through HAProxy — it is the gap circuit breakers exist to close.

---

## 2. Apply the fix

### 2a. The config — `infra/haproxy/haproxy.cfg`

```haproxy
resolvers docker
    nameserver dns1 127.0.0.11:53
    hold valid 5s
    ...

frontend api_gateway
    bind *:80
    acl is_catalog   path_beg /api/products
    acl is_inventory path_beg /api/stock
    acl is_sales     path_beg /api/orders
    acl is_payment   path_beg /api/payments
    use_backend catalog_backend   if is_catalog
    use_backend inventory_backend if is_inventory
    use_backend sales_backend     if is_sales
    use_backend payment_backend   if is_payment
    default_backend sales_backend

backend inventory_backend
    balance roundrobin
    option httpchk GET /actuator/health
    server-template inventory 5 inventory:8082 check resolvers docker init-addr none
```

(`catalog_backend`, `sales_backend`, `payment_backend` follow the identical
shape — see the full file.)

**`resolvers docker` points at `127.0.0.11`.** That is Docker's embedded DNS
server, injected into every container on a user-defined bridge network. It is the
same resolver the services themselves use for `http://inventory:8082` — HAProxy
is not doing anything privileged here, just asking the same question from
outside.

**`server-template N name:port` instead of a static `server` line.** A static
`server inventory1 inventory1:8082 check` resolves the hostname **once, at
startup**, and pins that IP forever — kill and replace that container and HAProxy
keeps hammering a dead address permanently. `server-template` pre-allocates `N`
slots and **re-resolves them continuously** against the `resolvers docker`
section, on the `hold valid` interval (5s here). Slots above however many
addresses currently resolve sit in `MAINT (resolution)` — not an error, just an
empty slot waiting for a scale-up. `5` is a ceiling: you cannot exceed it without
editing the file.

**One `inventory_backend` covers both replicas without naming either container.**
`inventory1` and `inventory2` share the network alias `inventory`, Docker's DNS
returns both A records for that one name, and `server-template` fills a slot per
address it gets back. Note that this is the *same* mechanism `catalog` uses for
its own internal call — one resolves it inside the JVM per request, the other
caches it into numbered slots and health-checks them.

**`option httpchk` is what makes this a load balancer instead of a blind
splitter.** Without it, HAProxy will happily route a third of your traffic into a
container that is up but returning 500s on every request. This is the single
capability the east–west path does not have.

### 2b. The compose service

```yaml
haproxy:
  image: haproxy:3.0-alpine
  ports:
    - "80:80"
    - "8404:8404"
  volumes:
    - ./infra/haproxy/haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro
  depends_on:
    catalog: {condition: service_healthy}
    inventory1: {condition: service_healthy}
    inventory2: {condition: service_healthy}
    sales: {condition: service_healthy}
    payment: {condition: service_healthy}
  networks: [backend]
```

The only `ports:` block left in the file. Everything else — `catalog`,
`inventory1`, `inventory2`, `sales`, `payment`, `postgres`, `pgadmin` — has none.

---

## 3. Verify

### Check 1 — one door, four destinations

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/api/products/1        # catalog
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/api/stock/1           # inventory
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1}],"paymentMethod":"CARD"}'  # sales
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/api/payments/order/1  # payment
```

All through port 80. No caller needs to know that inventory is two containers or
that catalog and payment are not.

### Check 2 — the old doors are actually gone

```bash
curl -s -o /dev/null -w '%{http_code}\n' --max-time 3 http://localhost:8081/api/products/1
docker compose port catalog 8081
```

**Expected:** `curl` fails with `Connection refused` (exit 7, not a timeout), and
`docker compose port` prints nothing and exits non-zero. Refused-not-timeout is
the informative part: nothing is listening on the host at all, as opposed to
something listening and dropping you.

Now confirm the container is still perfectly reachable from *inside* the network:

```bash
docker compose exec haproxy wget -qO- http://catalog:8081/actuator/health
```

`{"status":"UP",...}`. Same service, same port — the only thing that changed is
which side of the network boundary you asked from.

### Check 3 — reach one specific replica

The `9092`/`9093` trick is gone, but you can still get a shell-level answer out
of a named container:

```bash
docker compose exec inventory1 wget -qO- http://localhost:8082/api/stock/1
docker compose exec inventory2 wget -qO- http://localhost:8082/api/stock/1
```

Useful when a replica misbehaves and you need to talk to it without a balancer
in the way. Note this is a *diagnostic* path, not a client path — it requires
Docker access to the host, which is exactly the privilege boundary you want.

### Check 4 — the stats page

```bash
curl -s 'http://localhost:8404/;csv' | cut -d, -f1,2,18
```

```
catalog_backend,catalog1,UP
catalog_backend,catalog2,MAINT (resolution)
...
inventory_backend,inventory1,UP
inventory_backend,inventory2,UP
inventory_backend,inventory3,MAINT (resolution)
...
```

`inventory_backend` fills **two** slots; every other backend fills one. That
asymmetry is the two-replica setup made visible from the edge — and with no host
ports left, this dashboard is now the *only* place the replica count is visible
from outside the network. Open `http://localhost:8404` in a browser for the live
version; it also shows request counts per server, useful for confirming
distribution.

### Check 5 — see round-robin actually happening

```bash
for i in $(seq 6); do
  curl -s -D- -o /dev/null http://localhost/api/stock/1 | grep -i x-instance-id
done
```

`X-Instance-Id` is stamped by inventory's own logging filter with its container
hostname. Six calls should alternate between the two ids. This header exists
precisely because neither balancing layer tells the caller where it landed.

### Check 6 — kill a replica, watch the slot, not the traffic

```bash
docker compose stop inventory1
sleep 8
curl -s 'http://localhost:8404/;csv' | grep inventory_backend | cut -d, -f1,2,18
for i in 1 2 3 4; do curl -s -o /dev/null -w '%{http_code}\n' http://localhost/api/stock/1; done
```

Measured on this stack: within ~8s the resolver drops the stale address, one slot
goes to `MAINT (resolution)`, and all four calls still return `200` — served
entirely by the surviving replica. **The slot count shrinks; it does not go
`DOWN`.** `DOWN` is what a health-check failure on a *resolved* address looks
like — an address that resolves but stops answering the check. `MAINT
(resolution)` is what a *vanished* address looks like — DNS stopped returning it
at all. Different signatures, different causes.

```bash
docker compose start inventory1
sleep 15
curl -s 'http://localhost:8404/;csv' | grep inventory_backend | cut -d, -f1,2,18
```

Both slots return to `UP`. Notice the slot *label* is not tied to the container:
`inventory1`'s address may land in whichever numbered slot HAProxy had free.
Slots are a resolution cache, not container identity.

### Check 7 — the whole order flow, through the gateway only

```bash
curl -X POST http://localhost/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1}],"paymentMethod":"CARD"}'
```

A `201` means: client → HAProxy → `sales` (the one server-side hop) → `sales`
resolves catalog/inventory/payment by container name through Docker DNS →
`catalog` resolves `inventory` the same way. One request, one gateway hop, three
direct internal hops, and the gateway saw exactly one of the four.

---

## 4. Break it deliberately

### Break 1 — route by the wrong prefix

Edit an ACL in `infra/haproxy/haproxy.cfg`:

```haproxy
acl is_inventory path_beg /api/stok    # typo, was /api/stock
```

Reload (`docker compose restart haproxy`) and call `/api/stock/1` through `:80`.
It falls through every `use_backend` and lands on `default_backend
sales_backend` — `sales` has no `/api/stock` route, so you get whatever `sales`
returns for an unmapped path (its own 404), **not** inventory's 404 and **not** a
gateway-level error. HAProxy's ACLs are string prefix matches with no awareness
of what routes exist behind a backend; a routing typo silently redirects traffic
to the wrong service instead of failing loudly. Restore the ACL.

### Break 2 — stop every replica behind a backend

```bash
docker compose stop inventory1 inventory2
sleep 10
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/api/stock/1
```

**Expected:** `503` from HAProxy itself, quickly — not a hang, not a connection
reset. That is what `option httpchk` buys: HAProxy knows before you call that the
backend is empty and answers immediately instead of forwarding into a black hole.

Now compare the *same underlying event* on the east–west path, while both
replicas are still stopped:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/api/products/1/stock
docker compose logs --tail 5 catalog
```

This request enters through HAProxy (fine — `catalog` is healthy), then `catalog`
tries its own DNS-resolved call to `inventory` and gets a connection failure it
only discovers by attempting it. The result is a `503` too, but produced *after* a
failed connection rather than *instead of* one, and the log line comes from
`InventoryClient`'s catch block, not from a proxy. Health-checked and
non-health-checked paths fail differently: one refuses in advance, the other finds
out the hard way.

```bash
docker compose start inventory1 inventory2
```

### Break 3 — route service-to-service traffic through the gateway

This is the mistake §1 warns about — worth causing once. Point `catalog`'s
inventory client at the gateway instead of the service's own address, in
`docker-compose.yml`:

```yaml
catalog:
  environment:
    INVENTORY_URL: http://haproxy    # was http://inventory:8082
```

`docker compose up -d catalog`, then `curl http://localhost/api/products/1/stock`.
It works — `/api/stock/...` matches HAProxy's inventory ACL, so the path routes
correctly. But now:

- Every `catalog → inventory` call adds a hop through one shared process, for
  traffic that never leaves the network.
- Internal traffic now depends on the gateway. Prove it:
  ```bash
  docker compose stop haproxy
  docker compose exec catalog wget -qO- http://localhost:8081/api/products/1/stock
  ```
  This call is entirely internal and involves no external client — and it now
  fails, because you put the edge in the middle. With `INVENTORY_URL` set back to
  `http://inventory:8082`, the same call succeeds with HAProxy stopped.
- `catalog` is now exposed to gateway-level routing: an ACL typo (Break 1) breaks
  an *internal* code path, not just an external one.

Restore `INVENTORY_URL: http://inventory:8082`, `docker compose up -d catalog`,
and `docker compose start haproxy`. The value of this break is diagnostic: it
makes the cost of collapsing the two layers concrete instead of abstract.

### Break 4 — a healthy container that serves garbage

The gap in the east–west path, made visible. Stop the database out from under
one replica's dependency and watch which layer notices:

```bash
docker compose pause inventory2
sleep 20
curl -s 'http://localhost:8404/;csv' | grep inventory_backend | cut -d, -f1,2,18
for i in $(seq 6); do curl -s -o /dev/null -w '%{http_code}\n' http://localhost/api/stock/1; done
for i in $(seq 6); do curl -s -o /dev/null -w '%{http_code}\n' http://localhost/api/products/1/stock; done
docker compose unpause inventory2
```

A paused container still holds its IP, so DNS keeps returning it — but it answers
nothing. HAProxy's health check times out and the slot goes `DOWN` (not `MAINT`:
the address still resolves), and the direct `/api/stock` calls all stay `200`.
The `/api/products/1/stock` calls route through `catalog`, which has no health
check of its own, so roughly half of them hang until timeout and surface as
`503`. Same dead replica, two layers, two very different client experiences.

---

## 5. Questions to answer

1. `inventory_backend` has 5 template slots and 2 real containers. What do the
   other 3 show as, and why is that not an error state?
2. Stopping one `inventory` replica changes the slot count at the gateway. What
   resolves that change, and how often does it re-check — which config line
   controls it?
3. `catalog` has no `ports:` block, yet `sales` calls it successfully on
   `catalog:8081`. Explain precisely what a `ports:` mapping does and does not
   affect.
4. Removing the `9092`/`9093` host ports is described as removing a "scaling
   ceiling." What exactly was the ceiling, and what would you have had to edit to
   run a third replica before this change?
5. Both `catalog` and `sales` reach `inventory` through Docker DNS rather than
   through HAProxy, even though HAProxy is running and could route those calls.
   Give the two distinct costs of routing them through the gateway instead.
6. Stopping all replicas behind a backend produces a fast `503` at the gateway,
   but the same outage reaches `catalog` as a slow failure. What accounts for the
   difference, and which piece of config is responsible?
7. Break 4 produces a `DOWN` slot rather than `MAINT (resolution)`. What does that
   distinction tell you about the container's state that the other one would not?
8. The east–west path has no health checking. Name the specific failure it cannot
   detect, and say what would have to be added to cope with it.
9. `catalog` needs no equivalent of `SCRIPT_NAME`, but `pgadmin` does. What
   property of the two responses accounts for that, and why can no HAProxy
   rewriting rule substitute for it?
10. `pgadmin_backend` uses a single `server` line where every other backend uses
    `server-template`. Give the reason, and say what a user would actually
    experience if you "fixed" it to a template with two replicas.

---

## Reference

**Endpoints**

| | |
|---|---|
| `http://localhost` (`:80`) | The gateway — every `/api/...` path. The only way in. |
| `http://localhost/pgadmin` | pgAdmin UI, routed through the same gateway |
| `http://localhost:8404` | Stats dashboard |
| `GET /;csv` on `:8404` | Same data, machine-readable |

**Path → backend**

| Path prefix | Backend | Real service |
|---|---|---|
| `/api/products` | `catalog_backend` | `catalog` |
| `/api/stock` | `inventory_backend` | `inventory1` + `inventory2` |
| `/api/orders` | `sales_backend` | `sales` |
| `/api/payments` | `payment_backend` | `payment` |
| `/pgadmin` | `pgadmin_backend` | `pgadmin` (prefix passed through, not stripped) |
| anything else | `sales_backend` (default) | — |

**Reaching things that no longer publish a port**

| Want | Command |
|---|---|
| One specific service | `docker compose exec <svc> wget -qO- http://localhost:<port>/...` |
| A service from another service | `docker compose exec haproxy wget -qO- http://catalog:8081/...` |
| Postgres | `docker compose exec postgres psql -U postgres -d microservices` |
| pgadmin | `http://localhost/pgadmin` — through the gateway, like everything else |

**Symptom → cause**

| Symptom | Cause |
|---|---|
| Slot shows `MAINT (resolution)` | No DNS answer for that name right now — normal for unused template slots, or a container that just stopped |
| Slot shows `DOWN` (not `MAINT`) | Address resolves, but `/actuator/health` failed the check — Break 4 |
| Request routed to the wrong service | ACL `path_beg` typo — Break 1 |
| Fast `503` from the gateway itself | Every slot in the matched backend is down/in-MAINT — Break 2 |
| Slow `503` with an `InventoryClient` log line | East–west call failed; no health check on that path to catch it early |
| `Connection refused` on `:8081`/`:9092` | Correct — those host ports were removed in this lab. Use `:80` |
| pgAdmin loads as an unstyled blank page, console shows 404s for `/browser/*`, `/static/*` | `SCRIPT_NAME` not reaching the container, so pgAdmin emits unprefixed URLs that fall through to `default_backend` |
| pgAdmin drops a long-running query at ~30s | `timeout server` override missing from `pgadmin_backend`; it inherited the API-sized `defaults` |
| Internal call breaks when HAProxy is stopped | Service-to-service traffic was wrongly routed through the gateway — Break 3 |

**Next:** Lab 04 — scaling `inventory` past two replicas now that no host port
mapping constrains it, watching HAProxy fill template slots with no config
change, followed by circuit breakers for the east–west failure mode Break 4
exposes.
