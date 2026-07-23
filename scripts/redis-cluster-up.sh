#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"

docker compose up -d redis-cluster-1 redis-cluster-2 redis-cluster-3

for _ in {1..30}; do
  if docker compose exec -T redis-cluster-1 redis-cli -p 7001 ping 2>/dev/null | tr -d '\r' | rg -q '^PONG$'; then
    break
  fi
  sleep 1
done

if docker compose exec -T redis-cluster-1 redis-cli -p 7001 cluster info 2>/dev/null | tr -d '\r' | rg -q '^cluster_state:ok$'; then
  echo "Redis Cluster is already initialized"
  exit 0
fi

docker compose exec -T redis-cluster-1 redis-cli --cluster create \
  redis-cluster-1:7001 redis-cluster-2:7002 redis-cluster-3:7003 \
  --cluster-replicas 0 --cluster-yes

docker compose exec -T redis-cluster-1 redis-cli -p 7001 cluster info
