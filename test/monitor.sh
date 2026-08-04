#!/usr/bin/env bash
OUT="$1"
DURATION="$2"
echo "timestamp,container,cpu_pct,mem_usage,mem_pct" > "$OUT"
END=$((SECONDS + DURATION))
while [ $SECONDS -lt $END ]; do
  TS=$(date +%s)
  docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}}' microservices-postgres-1 microservices-inventory-1 2>/dev/null | while read -r line; do
    echo "$TS,$line" >> "$OUT"
  done
  CONN=$(docker exec microservices-postgres-1 psql -U postgres -d microservices -tAc "SELECT count(*) FROM pg_stat_activity WHERE datname='microservices';" 2>/dev/null | tr -d '[:space:]')
  echo "$TS,pg_connections,,,${CONN}" >> "$OUT"
  sleep 5
done
