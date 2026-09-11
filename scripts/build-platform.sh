#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

exec mvn --batch-mode --no-transfer-progress \
  -Dredis-ops.formatter.file="${REPO_DIR}/config/formatter/eclipse-java-redis-ops.xml" \
  -f "${REPO_DIR}/build/platform/pom.xml" clean verify "$@"
