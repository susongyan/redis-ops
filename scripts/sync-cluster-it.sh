#!/usr/bin/env bash
set -euo pipefail

source_container="redis-ops-sync-cluster-source"
target_container="redis-ops-sync-cluster-target"
standalone_container="redis-ops-sync-cluster-standalone"
redis_image="${REDIS_SYNC_CLUSTER_IMAGE:-redis:7.4-alpine}"

cleanup() {
  docker rm -f "${source_container}" "${target_container}" "${standalone_container}" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

start_cluster_container() {
  local name="$1"
  local first_port="$2"
  local second_port="$3"
  local third_port="$4"
  docker run -d --name "${name}" \
    -p "${first_port}:${first_port}" \
    -p "${second_port}:${second_port}" \
    -p "${third_port}:${third_port}" \
    "${redis_image}" sh -c "
      redis-server --port ${first_port} --cluster-enabled yes \
        --cluster-config-file /tmp/nodes-${first_port}.conf --cluster-node-timeout 5000 \
        --appendonly no --save '' --protected-mode no --daemonize yes
      redis-server --port ${second_port} --cluster-enabled yes \
        --cluster-config-file /tmp/nodes-${second_port}.conf --cluster-node-timeout 5000 \
        --appendonly no --save '' --protected-mode no --daemonize yes
      redis-server --port ${third_port} --cluster-enabled yes \
        --cluster-config-file /tmp/nodes-${third_port}.conf --cluster-node-timeout 5000 \
        --appendonly no --save '' --protected-mode no --daemonize yes
      tail -f /dev/null
    " >/dev/null
}

start_cluster_container "${source_container}" 7101 7102 7103
start_cluster_container "${target_container}" 7201 7202 7203
docker run -d --name "${standalone_container}" \
  -p 7301:7301 -p 7302:7302 \
  "${redis_image}" sh -c "
    redis-server --port 7301 --appendonly no --save '' --protected-mode no --daemonize yes
    redis-server --port 7302 --appendonly no --save '' --protected-mode no --daemonize yes
    tail -f /dev/null
  " >/dev/null

wait_for_redis() {
  local container="$1"
  local port="$2"
  local attempts=0
  until docker exec "${container}" redis-cli -p "${port}" ping >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if [[ "${attempts}" -ge 100 ]]; then
      echo "Redis ${container}:${port} did not become ready" >&2
      return 1
    fi
    sleep 0.2
  done
}

for port in 7101 7102 7103; do
  wait_for_redis "${source_container}" "${port}"
done
for port in 7201 7202 7203; do
  wait_for_redis "${target_container}" "${port}"
done
for port in 7301 7302; do
  wait_for_redis "${standalone_container}" "${port}"
done

docker exec "${source_container}" redis-cli --cluster create \
  127.0.0.1:7101 127.0.0.1:7102 127.0.0.1:7103 \
  --cluster-replicas 0 --cluster-yes >/dev/null
docker exec "${target_container}" redis-cli --cluster create \
  127.0.0.1:7201 127.0.0.1:7202 127.0.0.1:7203 \
  --cluster-replicas 0 --cluster-yes >/dev/null

REDIS_SYNC_CLUSTER_IT=true mvn -pl governance-sync-service -am \
  -Dtest=ClusterSyncTaskRunnerIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
