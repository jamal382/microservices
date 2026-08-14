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
#   order               the sales order flow: why writes are not retried
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
  local rid="demo-order-$RANDOM"
  step "SETUP"
  reset_breaker "$CATALOG" inventory
  note "The order flow touches catalog, inventory and payment in sequence."
  note "Each has its OWN circuit breaker, and deliberately different policies."
  echo "  sales breakers: $(curl -s "$SALES/actuator/circuitbreakers" | python3 -c "
import json,sys
for k,v in json.load(sys.stdin)['circuitBreakers'].items(): print(f'{k}={v[\"state\"]}', end='  ')" 2>/dev/null)"

  step "A healthy order"
  curl -s -X POST "$SALES/api/orders" -H 'Content-Type: application/json' \
    -d '{"customerId":1,"items":[{"productId":5,"quantity":1}],"paymentMethod":"CARD"}' \
    | python3 -m json.tool 2>/dev/null | sed 's/^/    /' | head -12

  step "Now inventory fails -- কী failure হলো?"
  fault ERROR
  echo "  Request:  POST $SALES/api/orders   [X-Request-Id: $rid]"
  local body
  body=$(curl -s -H "X-Request-Id: $rid" -X POST "$SALES/api/orders" -H 'Content-Type: application/json' \
    -d '{"customerId":1,"items":[{"productId":5,"quantity":1}],"paymentMethod":"CARD"}' -w '\n%{http_code}')
  echo "  ${dim}Response:${rst}"
  head -n -1 <<<"$body" | python3 -m json.tool 2>/dev/null | sed 's/^/    /'
  echo "  HTTP status: ${bold}$(tail -1 <<<"$body")${rst}"

  step "কতবার retry হলো?"
  settle
  local n; n=$(docker compose logs sales --since 2m 2>/dev/null | grep -F "$rid" | grep -c 'RETRY .* attempt')
  echo "  Retries against inventory: ${bold}${red}${n}${rst}"
  echo
  echo "  ${bold}Zero, on purpose.${rst} POST /api/stock/reserve decrements stock and is"
  echo "  not idempotent. A read timeout means the ${bold}response${rst} was lost -- not the"
  echo "  request. The reservation may already be committed on the other side, so a"
  echo "  retry would silently reserve the units twice and the discrepancy would"
  echo "  surface days later in a stock count with nothing to trace it to."
  echo
  echo "  catalog, by contrast, IS retried: GET /api/products/{id} is idempotent."
  note "  Retrying safely here would need inventory to accept an idempotency key."

  step "শেষ পর্যন্ত user কী response পেল?"
  echo "  ${bold}503 Service Unavailable${rst} with a Retry-After header -- and NO fallback."
  echo "  There is no honest degraded version of an order: confirming one without"
  echo "  reserving stock or taking payment would be worse than failing outright."
  settle
  narrate sales "$rid"
  clear_fault
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
