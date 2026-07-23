#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"

docker compose up -d redis redis-auth redis-sentinel-master redis-sentinel-replica redis-sentinel
"$repo_dir/scripts/redis-cluster-up.sh"

for _ in {1..40}; do
  standalone="$(docker compose exec -T redis redis-cli ping 2>/dev/null | tr -d '\r' || true)"
  authenticated="$(docker compose exec -T redis-auth redis-cli --user redis-reader -a phase1-test-password ping 2>/dev/null | tr -d '\r' || true)"
  sentinel_master="$(docker compose exec -T redis-sentinel redis-cli -p 26379 sentinel get-master-addr-by-name phase1-master 2>/dev/null | tr -d '\r' | head -1 || true)"
  cluster_state="$(docker compose exec -T redis-cluster-1 redis-cli -p 7001 cluster info 2>/dev/null | tr -d '\r' | sed -n 's/^cluster_state://p' || true)"
  if [[ "$standalone" == "PONG" && "$authenticated" == "PONG" &&
        "$sentinel_master" == "redis-sentinel-master" && "$cluster_state" == "ok" ]]; then
    break
  fi
  sleep 1
done

[[ "$standalone" == "PONG" ]] || { echo "Standalone Redis is not ready" >&2; exit 1; }
[[ "$authenticated" == "PONG" ]] || { echo "Authenticated Redis is not ready" >&2; exit 1; }
[[ "$sentinel_master" == "redis-sentinel-master" ]] || { echo "Redis Sentinel is not ready" >&2; exit 1; }
[[ "$cluster_state" == "ok" ]] || { echo "Redis Cluster is not ready" >&2; exit 1; }

echo "Redis asset test environment is ready"
echo "  Standalone: 127.0.0.1:6380"
echo "  ACL:        redis-reader@127.0.0.1:6384"
echo "  Sentinel:   phase1-master@127.0.0.1:26379"
echo "  Cluster:    127.0.0.1:7001,127.0.0.1:7002,127.0.0.1:7003"
