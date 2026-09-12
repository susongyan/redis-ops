#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

exec mvn --batch-mode --no-transfer-progress \
  -f "${REPO_DIR}/redis-ops-sync-worker/pom.xml" clean verify "$@"
