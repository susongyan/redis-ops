#!/usr/bin/env bash
set -euo pipefail

docker_desktop_bin=/Applications/Docker.app/Contents/Resources/bin
if [[ -d "$docker_desktop_bin" ]]; then
  export PATH="${PATH}:${docker_desktop_bin}"
fi

versions=("$@")
if [[ ${#versions[@]} -eq 0 ]]; then
  versions=(5.0 6.2 7.4 8.0 8.4)
fi

source_name=redis-ops-sync-matrix-source
target_name=redis-ops-sync-matrix-target

cleanup() {
  docker rm -f "$source_name" "$target_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for version in "${versions[@]}"; do
  cleanup
  echo "Testing Redis ${version}"
  docker run -d --name "$source_name" -p 6390:6379 "redis:${version}-alpine" \
    redis-server --appendonly no >/dev/null
  docker run -d --name "$target_name" -p 6391:6379 "redis:${version}-alpine" \
    redis-server --appendonly no >/dev/null

  for container in "$source_name" "$target_name"; do
    ready=false
    for _ in $(seq 1 30); do
      if docker exec "$container" redis-cli ping 2>/dev/null | grep -q PONG; then
        ready=true
        break
      fi
      sleep 1
    done
    if [[ "$ready" != true ]]; then
      echo "Redis ${version} container ${container} did not become ready" >&2
      exit 1
    fi
  done

  REDIS_SYNC_IT=true mvn --batch-mode --no-transfer-progress \
    -pl governance-sync-service -am \
    -Dtest=StandaloneSyncTaskRunnerIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
done
