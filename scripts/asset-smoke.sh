#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
api_log="$(mktemp -t redis-ops-api.XXXXXX)"
api_pid=""
smoke_port="${ASSET_SMOKE_PORT:-18080}"
base="http://127.0.0.1:${smoke_port}/api/v1"
region_id=""
region_version=""
idc_id=""
idc_version=""
app_id=""
app_version=""
cleanup_done=0
declare -a cluster_ids=()

cleanup() {
  if [[ "$cleanup_done" != "1" && -n "$api_pid" ]]; then
    if [[ -n "$app_id" ]]; then
      for cluster_id in "${cluster_ids[@]}"; do
        curl -sS -X DELETE -H "X-Operator: cleanup" \
          -H "Idempotency-Key: cleanup-unbind-$app_id-$cluster_id" \
          "$base/applications/$app_id/clusters/$cluster_id" >/dev/null 2>&1 || true
      done
      current_app_version="$(curl -sS "$base/applications/$app_id" | jq -r '.data.version // empty' 2>/dev/null || true)"
      if [[ -n "$current_app_version" ]]; then
        curl -sS -X DELETE -H "X-Operator: cleanup" \
          -H "Idempotency-Key: cleanup-app-$app_id" -H "If-Match: $current_app_version" \
          "$base/applications/$app_id" >/dev/null 2>&1 || true
      fi
    fi
    for cluster_id in "${cluster_ids[@]}"; do
      current_cluster_version="$(curl -sS "$base/clusters/$cluster_id" | jq -r '.data.cluster.version // empty' 2>/dev/null || true)"
      if [[ -n "$current_cluster_version" ]]; then
        curl -sS -X DELETE -H "X-Operator: cleanup" \
          -H "Idempotency-Key: cleanup-cluster-$cluster_id" -H "If-Match: $current_cluster_version" \
          "$base/clusters/$cluster_id" >/dev/null 2>&1 || true
      fi
    done
    if [[ -n "$idc_id" && -n "$idc_version" ]]; then
      curl -sS -X DELETE -H "X-Operator: cleanup" \
        -H "Idempotency-Key: cleanup-idc-$idc_id" -H "If-Match: $idc_version" \
        "$base/idcs/$idc_id" >/dev/null 2>&1 || true
    fi
    if [[ -n "$region_id" && -n "$region_version" ]]; then
      curl -sS -X DELETE -H "X-Operator: cleanup" \
        -H "Idempotency-Key: cleanup-region-$region_id" -H "If-Match: $region_version" \
        "$base/regions/$region_id" >/dev/null 2>&1 || true
    fi
  fi
  if [[ -n "$api_pid" ]]; then kill "$api_pid" 2>/dev/null || true; fi
}
trap cleanup EXIT

fail() {
  echo "asset acceptance failed: $1" >&2
  tail -100 "$api_log" >&2 || true
  exit 1
}

cd "$repo_dir"
[[ -f governance-bootstrap/target/governance-bootstrap-0.1.0-SNAPSHOT.jar ]] ||
  fail "build the application with 'mvn package' first"
"$repo_dir/scripts/redis-asset-test-up.sh"

if [[ -z "${REDIS_OPS_CREDENTIAL_KEYS:-}" ]]; then
  export REDIS_OPS_CREDENTIAL_KEYS="acceptance:$(openssl rand -base64 32)"
fi
SERVER_PORT="$smoke_port" java -jar governance-bootstrap/target/governance-bootstrap-0.1.0-SNAPSHOT.jar >"$api_log" 2>&1 &
api_pid=$!

for _ in {1..60}; do
  if curl -fsS "http://127.0.0.1:${smoke_port}/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 0.5
done
curl -fsS "http://127.0.0.1:${smoke_port}/actuator/health" >/dev/null ||
  fail "API did not become healthy on port $smoke_port"

suffix="$(date +%s)-$$"
operator="asset-acceptance"

post() {
  curl -fsS -H 'Content-Type: application/json' -H "X-Operator: $operator" \
    -H "Idempotency-Key: $1" -d "$2" "$3"
}
put() {
  curl -fsS -X PUT -H 'Content-Type: application/json' -H "X-Operator: $operator" \
    -H "Idempotency-Key: $1" -H "If-Match: $2" -d "$3" "$4"
}
delete_resource() {
  curl -fsS -X DELETE -H "X-Operator: $operator" -H "Idempotency-Key: $1" \
    -H "If-Match: $2" "$3"
}
test_connection() {
  curl -fsS -H 'Content-Type: application/json' -H "X-Operator: $operator" \
    -d "$1" "$base/clusters/connection-tests"
}
discover() {
  local cluster_id="$1" key="$2"
  curl -fsS -X POST -H 'Content-Type: application/json' -H "X-Operator: $operator" \
    -H "Idempotency-Key: $key" -d '{}' "$base/clusters/$cluster_id/discoveries"
}
wait_for_job() {
  local job_id="$1" status=""
  for _ in {1..40}; do
    status="$(curl -fsS "$base/jobs/$job_id" | jq -r '.data.status')"
    [[ "$status" == "SUCCEEDED" || "$status" == "FAILED" ]] && break
    sleep 0.5
  done
  [[ "$status" == "SUCCEEDED" ]] || fail "discovery job $job_id ended as $status"
}
wait_for_failed_job() {
  local job_id="$1" status=""
  for _ in {1..50}; do
    status="$(curl -fsS "$base/jobs/$job_id" | jq -r '.data.status')"
    [[ "$status" == "FAILED" || "$status" == "SUCCEEDED" ]] && break
    sleep 0.5
  done
  [[ "$status" == "FAILED" ]] || fail "discovery job $job_id expected FAILED, got $status"
}

region_body="{\"code\":\"acceptance-region-$suffix\",\"name\":\"Acceptance Region\",\"status\":\"ACTIVE\"}"
region_response="$(post "region-create-$suffix" "$region_body" "$base/regions")"
region_id="$(jq -r '.data.id' <<<"$region_response")"
region_version="$(jq -r '.data.version' <<<"$region_response")"
[[ "$(post "region-create-$suffix" "$region_body" "$base/regions" | jq -r '.data.id')" == "$region_id" ]] ||
  fail "region create was not idempotent"

idc_body="{\"code\":\"acceptance-idc-$suffix\",\"name\":\"Acceptance IDC\",\"regionId\":$region_id,\"status\":\"ACTIVE\"}"
idc_response="$(post "idc-create-$suffix" "$idc_body" "$base/idcs")"
idc_id="$(jq -r '.data.id' <<<"$idc_response")"
idc_version="$(jq -r '.data.version' <<<"$idc_response")"

declare -a expected_nodes=()
create_cluster() {
  local label="$1" mode="$2" endpoint="$3" auth_json="$4" expected="$5"
  local connection_body cluster_body response cluster_id replayed_id
  connection_body="{\"mode\":\"$mode\",\"endpoint\":\"$endpoint\"$auth_json}"
  [[ "$(test_connection "$connection_body" | jq -r '.data.reachable')" == "true" ]] ||
    fail "$label connection test failed"
  cluster_body="{\"name\":\"acceptance-$label-$suffix\",\"environment\":\"acceptance\",\"businessLine\":\"platform\",\"owner\":\"asset-acceptance\",\"mode\":\"$mode\",\"redisVersion\":\"7.4\",\"endpoint\":\"$endpoint\",\"idcId\":$idc_id,\"status\":\"ACTIVE\"$auth_json}"
  response="$(post "cluster-$label-$suffix" "$cluster_body" "$base/clusters")"
  cluster_id="$(jq -r '.data.id' <<<"$response")"
  replayed_id="$(post "cluster-$label-$suffix" "$cluster_body" "$base/clusters" | jq -r '.data.id')"
  [[ "$replayed_id" == "$cluster_id" ]] || fail "$label cluster create was not idempotent"
  cluster_ids+=("$cluster_id")
  expected_nodes+=("$expected")
}

create_cluster "standalone" "STANDALONE" "127.0.0.1:6380" ',"authEnabled":false' 1
create_cluster "acl" "STANDALONE" "127.0.0.1:6384" ',"authEnabled":true,"username":"redis-reader","password":"phase1-test-password"' 1
create_cluster "sentinel" "SENTINEL" "phase1-master@127.0.0.1:26379" ',"authEnabled":false' 2
create_cluster "cluster" "CLUSTER" "127.0.0.1:7001,127.0.0.1:7002,127.0.0.1:7003" ',"authEnabled":false' 3

wrong_password_response="$(curl -sS -H 'Content-Type: application/json' -H "X-Operator: $operator" \
  -d '{"mode":"STANDALONE","endpoint":"127.0.0.1:6384","authEnabled":true,"username":"redis-reader","password":"wrong-password"}' \
  "$base/clusters/connection-tests")"
[[ "$(jq -r '.code' <<<"$wrong_password_response")" == "REDIS_AUTHENTICATION_FAILED" ]] ||
  fail "wrong password did not return REDIS_AUTHENTICATION_FAILED: $wrong_password_response"

acl_cluster_id="${cluster_ids[1]}"
plaintext_hits="$(docker compose exec -T mysql mysql -N -uredis_governance -predis_governance redis_governance \
  -e "SELECT COUNT(*) FROM redis_cluster_secret WHERE cluster_id=$acl_cluster_id AND HEX(encrypted_secret) LIKE CONCAT('%',HEX('phase1-test-password'),'%')")"
[[ "$plaintext_hits" == "0" ]] || fail "plaintext password was found in redis_cluster_secret"
acl_detail="$(curl -fsS "$base/clusters/$acl_cluster_id")"
[[ "$acl_detail" != *"phase1-test-password"* && "$acl_detail" != *"encryptedSecret"* ]] ||
  fail "cluster detail exposed a secret"

for index in "${!cluster_ids[@]}"; do
  cluster_id="${cluster_ids[$index]}"
  key="discovery-$cluster_id-$suffix"
  submission="$(discover "$cluster_id" "$key")"
  job_id="$(jq -r '.data.job.id' <<<"$submission")"
  replayed_job_id="$(discover "$cluster_id" "$key" | jq -r '.data.job.id')"
  [[ "$replayed_job_id" == "$job_id" ]] || fail "discovery submission was not idempotent"
  wait_for_job "$job_id"
  node_count="$(curl -fsS "$base/clusters/$cluster_id/nodes" | jq '.data | length')"
  [[ "$node_count" == "${expected_nodes[$index]}" ]] ||
    fail "cluster $cluster_id expected ${expected_nodes[$index]} nodes, got $node_count"
done

standalone_cluster_id="${cluster_ids[0]}"
unreachable_body="{\"name\":\"acceptance-standalone-$suffix\",\"environment\":\"acceptance\",\"businessLine\":\"platform\",\"owner\":\"asset-acceptance\",\"mode\":\"STANDALONE\",\"redisVersion\":\"7.4\",\"endpoint\":\"127.0.0.1:6398\",\"idcId\":$idc_id,\"status\":\"ACTIVE\",\"authEnabled\":false}"
unreachable_update="$(put "cluster-unreachable-$suffix" 0 "$unreachable_body" "$base/clusters/$standalone_cluster_id")"
unreachable_version="$(jq -r '.data.version' <<<"$unreachable_update")"
failed_submission="$(discover "$standalone_cluster_id" "discovery-failure-$suffix")"
wait_for_failed_job "$(jq -r '.data.job.id' <<<"$failed_submission")"
preserved_node_count="$(curl -fsS "$base/clusters/$standalone_cluster_id/nodes" | jq '.data | length')"
[[ "$preserved_node_count" == "1" ]] ||
  fail "failed discovery did not preserve the previous successful node snapshot"

restored_body="{\"name\":\"acceptance-standalone-$suffix\",\"environment\":\"acceptance\",\"businessLine\":\"platform\",\"owner\":\"asset-acceptance\",\"mode\":\"STANDALONE\",\"redisVersion\":\"7.4\",\"endpoint\":\"127.0.0.1:6380\",\"idcId\":$idc_id,\"status\":\"ACTIVE\",\"authEnabled\":false}"
restored_first="$(put "cluster-restore-$suffix" "$unreachable_version" "$restored_body" "$base/clusters/$standalone_cluster_id")"
restored_second="$(put "cluster-restore-$suffix" "$unreachable_version" "$restored_body" "$base/clusters/$standalone_cluster_id")"
[[ "$(jq -r '.data.version' <<<"$restored_first")" == "$(jq -r '.data.version' <<<"$restored_second")" ]] ||
  fail "cluster update was not idempotent"

app_body="{\"code\":\"acceptance-app-$suffix\",\"name\":\"Acceptance App\",\"owner\":\"asset-acceptance\",\"businessLine\":\"platform\",\"status\":\"ACTIVE\"}"
app_response="$(post "app-create-$suffix" "$app_body" "$base/applications")"
app_id="$(jq -r '.data.id' <<<"$app_response")"
app_version="$(jq -r '.data.version' <<<"$app_response")"
binding_body='{"clientType":"Lettuce","clientVersion":"6.3","poolConfig":"{\"maxTotal\":16}"}'
binding_url="$base/applications/$app_id/clusters/${cluster_ids[0]}"
for _ in 1 2; do
  curl -fsS -X PUT -H 'Content-Type: application/json' -H "X-Operator: $operator" \
    -H "Idempotency-Key: bind-$suffix" -d "$binding_body" "$binding_url" >/dev/null
done
binding_count="$(curl -fsS "$base/applications/$app_id" | jq '.data.bindings | length')"
[[ "$binding_count" == "1" ]] || fail "application binding was duplicated"

updated_app_body="{\"code\":\"acceptance-app-$suffix\",\"name\":\"Acceptance App Updated\",\"owner\":\"asset-acceptance\",\"businessLine\":\"platform\",\"status\":\"ACTIVE\"}"
first_update="$(put "app-update-$suffix" "$app_version" "$updated_app_body" "$base/applications/$app_id")"
second_update="$(put "app-update-$suffix" "$app_version" "$updated_app_body" "$base/applications/$app_id")"
[[ "$(jq -r '.data.version' <<<"$first_update")" == "$(jq -r '.data.version' <<<"$second_update")" ]] ||
  fail "application update replay returned a different version"
app_version="$(jq -r '.data.version' <<<"$first_update")"

audit_response="$(curl -fsS "$base/audits?operator=$operator&resourceType=APPLICATION&resourceId=$app_id&limit=100")"
[[ "$(jq '[.data[] | select(.action=="APPLICATION_UPDATE")] | length' <<<"$audit_response")" == "1" ]] ||
  fail "idempotent application update produced duplicate audit records"
[[ "$audit_response" != *"phase1-test-password"* ]] || fail "audit response exposed a password"

for _ in 1 2; do
  curl -fsS -X DELETE -H "X-Operator: $operator" -H "Idempotency-Key: unbind-$suffix" \
    "$binding_url" >/dev/null
done
for _ in 1 2; do
  delete_resource "app-delete-$suffix" "$app_version" "$base/applications/$app_id" >/dev/null
done

for cluster_id in "${cluster_ids[@]}"; do
  cluster_version="$(curl -fsS "$base/clusters/$cluster_id" | jq -r '.data.cluster.version')"
  for _ in 1 2; do
    delete_resource "cluster-delete-$cluster_id-$suffix" "$cluster_version" "$base/clusters/$cluster_id" >/dev/null
  done
done
for _ in 1 2; do
  delete_resource "idc-delete-$suffix" "$idc_version" "$base/idcs/$idc_id" >/dev/null
done
for _ in 1 2; do
  delete_resource "region-delete-$suffix" "$region_version" "$base/regions/$region_id" >/dev/null
done
cleanup_done=1

if rg -q 'phase1-test-password' "$api_log"; then
  fail "plaintext password was found in application logs"
fi

echo "asset acceptance passed"
echo "  modes: Standalone, ACL, Sentinel, Cluster"
echo "  topology nodes: 1, 1, 2, 3"
echo "  verified: API integration, async discovery, failed-snapshot preservation"
echo "            idempotency, audit, authentication failure, secret redaction"
