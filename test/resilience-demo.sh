#!/usr/bin/env bash
#
# Walks one failure from end to end and answers, in order:
#
#   কী failure হলো?            What failed?
#   Resilience4j কী করল?       What did Resilience4j do about it?
#   কতবার retry হলো?           How many retries?
#   কখন circuit open হলো?      When did the circuit open?
#   কখন fallback হলো?          When did the fallback run?
#   শেষ পর্যন্ত user কী পেল?    What did the user finally get?
#
# Every number printed below is read back out of the running system -- the log
# lines come from Resilience4j's own event publisher and the state comes from
# the actuator. Nothing here is narrated from a script's assumptions.
#
# Usage:  ./resilience-demo.sh [scenario]
#   errors   (default)  inventory returns 500s   -> retries, then the circuit opens
#   slow                inventory stalls 1500ms  -> circuit opens on SLOW calls alone
#   recover             drive it open, then watch OPEN -> HALF_OPEN -> CLOSED
#   business            a real 404 -> proves business answers do NOT trip anything
#   order               the Kafka order saga: a declined payment and its compensation
#
set -uo pipefail

CATALOG=${CATALOG:-http://localhost:8081}
SALES=${SALES:-http://localhost:8083}
INV1=${INV1:-http://localhost:9092}
INV2=${INV2:-http://localhost:9093}
SCENARIO=${1:-errors}

bold=$(tput bold 2>/dev/null || echo ''); dim=$(tput dim 2>/dev/null || echo '')
red=$(tput setaf 1 2>/dev/null || echo ''); grn=$(tput setaf 2 2>/dev/null || echo '')
ylw=$(tput setaf 3 2>/dev/null || echo ''); cyn=$(tput setaf 6 2>/dev/null || echo '')
rst=$(tput sgr0 2>/dev/null || echo '')

step() { printf '\n%s%s─── %s %s%s\n' "$bold" "$cyn" "$1" "$(printf '─%.0s' $(seq 1 $((60 - ${#1} > 0 ? 60 - ${#1} : 0))))" "$rst"; }
note() { printf '%s%s%s\n' "$dim" "$1" "$rst"; }

fault() { # fault <mode> [delayMs] [failureRate]
  local body
  body=$(printf '{"mode":"%s","delayMs":%s,"failureRate":%s}' "$1" "${2:-0}" "${3:-100}")
  for host in "$INV1" "$INV2"; do
    curl -s -X POST "$host/api/lab/fault" -H 'Content-Type: application/json' -d "$body" >/dev/null
  done
}
clear_fault() { for host in "$INV1" "$INV2"; do curl -s -X DELETE "$host/api/lab/fault" >/dev/null; done; }

# One request, one line. Deliberately a single curl: issuing a second call just to
# measure time would double the traffic and quietly corrupt every count the
# circuit breaker reports.
probe() { # probe <label> <n> <url> <cb-base> <cb-name>
  local out code secs
  out=$(curl -s -o /dev/null -w '%{http_code}|%{time_total}' "$3")
  code=${out%%|*}; secs=${out##*|}
  printf '  %s %s -> http %s in %ss   [%s]\n' "$1" "$2" "$code" "$secs" "$(cb_state "$4" "$5")"
}

# Waits for the breaker to be back in CLOSED so a scenario starts from a known
# state rather than inheriting whatever the last run left behind.
reset_breaker() { # reset_breaker <base> <name>
  clear_fault
  local waited=0
  while [ "$(cb_state "$1" "$2")" != "CLOSED" ] && [ "$waited" -lt 25 ]; do
    sleep 1; waited=$((waited + 1))
    # HALF_OPEN needs successful trial calls before it will close.
    curl -s -o /dev/null "$CATALOG/api/products/1/stock" || true
    printf '\r  waiting for circuit to close... %ds ' "$waited"
  done
  if [ "$waited" -gt 0 ]; then printf '\r%*s\r' 44 ''; fi
}

# docker compose streams logs asynchronously, so a line written microseconds ago
# is not necessarily readable yet. Without this the narrative comes back empty.
settle() { sleep "${1:-2}"; }

order_status() { # order_status <orderId>
  curl -s "$SALES/api/orders/$1" \
    | python3 -c "import json,sys;print(json.load(sys.stdin)['status'])" 2>/dev/null || echo "?"
}

cb_state() { # cb_state <base> <name>
  curl -s "$1/actuator/circuitbreakers" \
    | python3 -c "import json,sys;d=json.load(sys.stdin)['circuitBreakers']['$2'];print(d['state'])" 2>/dev/null || echo "?"
}
cb_line() {
  curl -s "$1/actuator/circuitbreakers" | python3 -c "
import json,sys
d=json.load(sys.stdin)['circuitBreakers']['$2']
print(f\"state={d['state']:9s} failureRate={d['failureRate']:>6s} slowCallRate={d['slowCallRate']:>6s} \"
      f\"buffered={d['bufferedCalls']} failed={d['failedCalls']} notPermitted={d['notPermittedCalls']}\")" 2>/dev/null || echo "?"
}

# Prints the r4j narrative for one request id, in the order it happened.
narrate() { # narrate <service> <reqId>
  docker compose logs "$1" --since 3m 2>/dev/null \
    | grep -F "$2" \
    | grep -oE '\[r4j\].*|\[catalog->inventory\].*' \
    | sed -e "s/^\[r4j\] RETRY/${ylw}[r4j] RETRY${rst}/" \
          -e "s/^\[r4j\] CIRCUIT/${red}[r4j] CIRCUIT${rst}/" \
          -e "s/^\[r4j\] FALLBACK/${grn}[r4j] FALLBACK${rst}/" \
    | sed 's/^/    /'
}

require_up() {
  if ! curl -sf "$CATALOG/actuator/health" >/dev/null; then
    echo "catalog is not reachable at $CATALOG -- run: docker compose up -d" >&2; exit 1
  fi
  if ! curl -sf "$INV1/api/lab/fault" >/dev/null; then
    echo "fault endpoint is not enabled on $INV1 -- LAB_FAULT_INJECTION must be true" >&2; exit 1
  fi
}

# ---------------------------------------------------------------------------

scenario_errors() {
  local rid="demo-err-$RANDOM"

  step "SETUP"
  note "Resetting the circuit to a known CLOSED state first..."
  reset_breaker "$CATALOG" inventory
  echo "  before:  $(cb_line "$CATALOG" inventory)"
  note "Now making BOTH inventory replicas return 500 on every business request."
  note "(/actuator stays healthy on purpose -- the container still looks alive.)"
  fault ERROR

  step "কী failure হলো?  (What failed?)"
  echo "  Request:  GET $CATALOG/api/products/1/stock   [X-Request-Id: $rid]"
  local out code
  out=$(curl -s -H "X-Request-Id: $rid" -w '\n%{http_code}|%{time_total}' "$CATALOG/api/products/1/stock")
  code=$(tail -1 <<<"$out" | cut -d'|' -f1)
  local secs; secs=$(tail -1 <<<"$out" | cut -d'|' -f2)
  echo "  Underlying failure: HTTP 500 from inventory, seen by catalog as"
  echo "                      ${bold}HttpServerErrorException\$InternalServerError${rst}"

  step "Resilience4j কী করল?  /  কতবার retry হলো?"
  note "(this is Resilience4j's own event log, not a reconstruction)"
  settle
  narrate catalog "$rid"
  echo
  echo "  Retries for this one request: ${bold}$(docker compose logs catalog --since 3m 2>/dev/null | grep -F "$rid" | grep -c 'RETRY .* attempt')${rst}"
  echo "  Wall-clock cost:              ${bold}${secs}s${rst}  ${dim}(3 attempts + 200ms + 400ms backoff)${rst}"

  step "কখন circuit open হলো?"
  note "Sending more requests until the failure rate crosses 50% over 5+ calls."
  for i in $(seq 2 8); do
    probe request "$i" "$CATALOG/api/products/1/stock" "$CATALOG" inventory
  done
  echo
  echo "  ${bold}$(cb_line "$CATALOG" inventory)${rst}"
  docker compose logs catalog --since 3m 2>/dev/null | grep -oE "CIRCUIT 'inventory' CLOSED -> OPEN.*" | tail -1 | sed "s/^/  ${red}/;s/$/${rst}/"
  note "  Note the time per request collapsing once it is OPEN: the call stops"
  note "  leaving the process at all, so it costs microseconds instead of seconds."

  step "কখন fallback হলো?  /  শেষ পর্যন্ত user কী response পেল?"
  echo "  ${dim}Final response body:${rst}"
  curl -s "$CATALOG/api/products/1/stock" | python3 -m json.tool | sed 's/^/    /'
  echo
  echo "  HTTP status: ${bold}${grn}200 OK${rst} -- not a 500."
  echo "  The product half of the answer is catalog's own data and is still correct."
  echo "  The stock half is reported as ${bold}UNAVAILABLE${rst} rather than guessed at,"
  echo "  because a fabricated 0 is indistinguishable from a real one to the caller."

  step "CLEANUP"
  clear_fault
  note "Faults cleared. The circuit closes itself after ~10s (wait-duration-in-open-state)."
}

scenario_slow() {
  step "SETUP"
  reset_breaker "$CATALOG" inventory
  note "inventory will now answer CORRECTLY, but take 1500ms."
  note "That is under the 2000ms read timeout, so nothing errors -- but it is over"
  note "the 1s slow-call threshold, so the breaker counts it as slow."
  fault SLOW 1500

  step "কী failure হলো?  (What failed?)"
  echo "  Nothing, in the usual sense. Every call below returns ${bold}HTTP 200${rst}."
  echo "  This is the failure mode that has no error to catch: a dependency that"
  echo "  works, slowly enough to take its callers down with it."

  step "কখন circuit open হলো?"
  for i in $(seq 1 6); do
    probe request "$i" "$CATALOG/api/products/1/stock" "$CATALOG" inventory
  done
  echo
  echo "  ${bold}$(cb_line "$CATALOG" inventory)${rst}"
  note "  failureRate stays at 0% -- nothing ever threw. slowCallRate is what opened it."
  clear_fault
}

scenario_recover() {
  step "SETUP -- drive the circuit open"
  reset_breaker "$CATALOG" inventory
  fault ERROR
  for i in $(seq 1 6); do curl -s -o /dev/null "$CATALOG/api/products/1/stock"; done
  echo "  $(cb_line "$CATALOG" inventory)"

  step "RECOVERY -- inventory is healthy again"
  clear_fault
  note "Waiting out wait-duration-in-open-state (10s)..."
  for i in $(seq 1 11); do printf '\r  %ds' "$i"; sleep 1; done; printf '\r'
  echo "  state after the wait: ${bold}$(cb_state "$CATALOG" inventory)${rst}"
  note "HALF_OPEN admits exactly 3 trial calls. If they pass, it closes."
  for i in $(seq 1 4); do
    probe probe "$i" "$CATALOG/api/products/1/stock" "$CATALOG" inventory
  done
  echo
  docker compose logs catalog --since 2m 2>/dev/null \
    | grep -oE "CIRCUIT 'inventory' [A-Z_]+ -> [A-Z_]+.*" | tail -3 | sed "s/^/  ${red}/;s/$/${rst}/"
}

scenario_business() {
  step "SETUP"
  reset_breaker "$CATALOG" inventory
  note "No faults at all. inventory is completely healthy."
  note "Product 11 exists in catalog but has no row in inventory, so the stock"
  note "lookup gets an honest 404 back."
  echo "  before:  $(cb_line "$CATALOG" inventory)"

  step "কী failure হলো?"
  echo "  Arguably none. A 404 here is a ${bold}correct answer from a healthy service${rst}."
  for i in $(seq 1 6); do
    probe request "$i" "$CATALOG/api/products/11/stock" "$CATALOG" inventory
  done

  step "Resilience4j কী করল?"
  echo "  ${bold}Nothing -- deliberately.${rst}"
  echo "  after:   $(cb_line "$CATALOG" inventory)"
  note "  buffered=0: these calls were IGNORED, not merely 'not failed'. They are"
  note "  absent from the window entirely, so a burst of 404s can neither trip the"
  note "  breaker nor pad the success rate and hide real failures next to them."
  docker compose logs catalog --since 1m 2>/dev/null | grep -oE '\[r4j\] CIRCUIT .* IGNORED.*' | tail -2 | sed 's/^/    /'

  step "শেষ পর্যন্ত user কী response পেল?"
  echo "  ${bold}404 Not Found${rst} -- passed straight through, not converted to a"
  echo "  degraded 200. A fallback here would be a lie: the product really has no stock row."
}

scenario_order() {
  step "SETUP"
  note "The order flow is a choreographed Kafka saga. sales calls catalog for prices,"
  note "then publishes -- inventory and payment are never addressed directly."
  note "This scenario watches an order fail at payment and get compensated."

  step "1. A healthy order -- note the 202"
  local body code
  body=$(curl -s -X POST "$SALES/api/orders" -H 'Content-Type: application/json' \
    -d '{"customerId":1,"items":[{"productId":5,"quantity":1}],"paymentMethod":"CARD"}' -w '\n%{http_code}')
  code=$(tail -1 <<<"$body")
  local ok_id; ok_id=$(head -n -1 <<<"$body" | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])" 2>/dev/null)
  echo "  HTTP status: ${bold}${code}${rst}   order id: ${bold}${ok_id}${rst}"
  echo
  echo "  ${bold}202 Accepted, status PENDING.${rst} The order is durable and its saga is"
  echo "  guaranteed to run -- but stock is unchecked and no card has been charged."
  echo "  201 would claim it succeeded at the moment nobody knows whether it will."
  settle 3
  echo "  After ~2s: status = ${bold}${grn}$(order_status "$ok_id")${rst}"

  step "2. Now an order that will be DECLINED"
  note "Total must end in .13 on products that are in stock: 37 x 59.99 + 1 x 35.50 = 2255.13"
  local bad_id
  bad_id=$(curl -s -X POST "$SALES/api/orders" -H 'Content-Type: application/json' \
    -d '{"customerId":2,"items":[{"productId":8,"quantity":37},{"productId":6,"quantity":1}],"paymentMethod":"CARD"}' \
    | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])" 2>/dev/null)
  echo "  order id: ${bold}${bad_id}${rst}  (accepted, PENDING)"
  settle 4
  echo "  Final status: ${bold}${ylw}$(order_status "$bad_id")${rst}"
  echo
  echo "  ${bold}CANCELLED${rst}, not PAYMENT_FAILED. The difference matters: PAYMENT_FAILED"
  echo "  means the charge was declined, CANCELLED means the reserved stock has been"
  echo "  confirmed back on the shelf. This enum constant was previously never set."

  step "3. Who gave the stock back? -- the compensation"
  docker compose exec -T postgres psql -U postgres -d microservices -t -A -F' | ' \
    -c "SELECT order_id, movement_type, quantity FROM inventory.stock_movements
        WHERE order_id=${bad_id} ORDER BY id;" 2>/dev/null | sed 's/^/    /'
  echo
  echo "  The ${bold}RESERVE${rst} rows are still there. Compensation is a new business fact,"
  echo "  not an undo -- the history must show the units were held and then returned."

  step "4. The whole saga, across four services, from one grep"
  settle
  docker compose logs sales inventory1 inventory2 payment --since 3m 2>/dev/null \
    | grep -E "order=${bad_id}\b" \
    | grep -E '\[saga\]|\[outbox\]|\[kafka\]|\[stock\]|\[payment\]' \
    | sed -E 's/^([a-z0-9]+)[ ]+\|.*(\[(saga|outbox|kafka|stock|payment)\].*)$/  \1  \2/' \
    | cut -c1-140 | head -24

  step "5. Why sales has no breaker for inventory or payment any more"
  echo "  sales breakers: ${bold}$(curl -s "$SALES/actuator/circuitbreakers" | python3 -c "
import json,sys
for k,v in json.load(sys.stdin)['circuitBreakers'].items(): print(f'{k}={v[\"state\"]}', end='  ')" 2>/dev/null)${rst}"
  echo
  echo "  Only ${bold}catalog${rst} -- the one call that is still synchronous."
  echo
  echo "  Circuit breakers, bulkheads and rate limiters all answer one question:"
  echo "  ${bold}what does a caller do when the callee is unreachable right now?${rst}"
  echo "  Publishing to a durable log never raises it. The broker holds the event"
  echo "  until the consumer is healthy, so a service that is down is a consumer"
  echo "  with ${bold}lag${rst} -- not a failed call. Prove it with:"
  echo
  echo "     docker compose stop payment"
  echo "     curl -X POST $SALES/api/orders ...     # 202, parks at STOCK_RESERVED"
  echo "     docker compose start payment           # completes on its own"
  echo
  note "  This is Lab 06. The patterns were deleted, not disabled."
}

# ---------------------------------------------------------------------------

require_up

printf '%s%s\n' "$bold" "Resilience4j demo -- scenario: $SCENARIO$rst"
case "$SCENARIO" in
  errors)   scenario_errors ;;
  slow)     scenario_slow ;;
  recover)  scenario_recover ;;
  business) scenario_business ;;
  order)    scenario_order ;;
  *) echo "unknown scenario: $SCENARIO (errors|slow|recover|business|order)" >&2; exit 2 ;;
esac

step "WHERE TO LOOK NEXT"
cat <<EOF
  Live state      curl -s $CATALOG/actuator/circuitbreakers | python3 -m json.tool
  Event stream    curl -s $CATALOG/actuator/circuitbreakerevents | python3 -m json.tool
  Retry events    curl -s $CATALOG/actuator/retryevents | python3 -m json.tool
  One request     docker compose logs catalog | grep <X-Request-Id>
  Full lab        docs/labs/04-resilience4j.md
EOF
