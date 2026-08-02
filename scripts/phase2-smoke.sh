#!/usr/bin/env bash
set -euo pipefail
port="${PHASE2_SMOKE_PORT:-18082}"
log="$(mktemp -t redis-ops-phase2.XXXXXX)"
pid=""
cleanup(){ [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true; }
trap cleanup EXIT
export REDIS_OPS_CREDENTIAL_KEYS="${REDIS_OPS_CREDENTIAL_KEYS:-v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=}"
export DB_URL="${DB_URL:-jdbc:mysql://localhost:3306/redis_governance?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC}"
export DB_USERNAME="${DB_USERNAME:-redis_governance}"
export DB_PASSWORD="${DB_PASSWORD:-redis_governance}"
export WORKER_ENABLED=true
java -jar governance-bootstrap/target/governance-bootstrap-0.1.0-SNAPSHOT.jar --server.port="$port" >"$log" 2>&1 & pid=$!
for _ in {1..60}; do curl -fsS "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1 && break; sleep .5; done
curl -fsS "http://127.0.0.1:$port/actuator/health" >/dev/null || { tail -100 "$log"; exit 1; }
docker compose exec -T redis redis-cli SET phase2:ttl-demo value EX 3600 >/dev/null
docker compose exec -T redis redis-cli SET phase2:no-ttl-demo value >/dev/null
docker compose exec -T redis sh -c "dd if=/dev/zero bs=1048576 count=2 2>/dev/null | tr '\\0' x | redis-cli -x SET phase2:large-demo" >/dev/null
created="$(curl -fsS -X POST "http://127.0.0.1:$port/api/v1/risk-scan-tasks" -H 'Content-Type: application/json' -H "Idempotency-Key: phase2-smoke-$(date +%s)" -d '{"clusterId":4,"databaseNo":0,"includePattern":"phase2:*","checkLargeKey":true,"checkNoTtl":true,"largeKeyThresholdBytes":1048576,"scanRatePerSecond":1000,"maxFindings":100}')"
id="$(jq -r '.data.id' <<<"$created")"; version="$(jq -r '.data.version' <<<"$created")"
curl -fsS -X POST "http://127.0.0.1:$port/api/v1/risk-scan-tasks/$id/start" -H 'Content-Type: application/json' -H "Idempotency-Key: phase2-smoke-start-$id" -H "If-Match: $version" -d '{}' >/dev/null
for _ in {1..40}; do detail="$(curl -fsS "http://127.0.0.1:$port/api/v1/risk-scan-tasks/$id")"; [[ "$(jq -r '.data.task.status' <<<"$detail")" != RUNNING ]] && break; sleep .5; done
echo "$detail"
curl -fsS "http://127.0.0.1:$port/api/v1/risk-scan-tasks/$id/findings?page=1&size=20"
