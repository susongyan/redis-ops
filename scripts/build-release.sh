#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
SKIP_TESTS=false
OUTPUT_DIR="${PROJECT_DIR}/release"

usage() {
  cat <<'EOF'
Usage: ./scripts/build-release.sh [--skip-tests] [--output-dir DIR]

Builds backend JARs and frontend assets, then creates a portable redis-ops archive.
EOF
}

while (($#)); do
  case "$1" in
    --skip-tests)
      SKIP_TESTS=true
      shift
      ;;
    --output-dir)
      (($# >= 2)) || { usage; exit 1; }
      OUTPUT_DIR="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage
      exit 1
      ;;
  esac
done

for command in java mvn node npm tar; do
  command -v "${command}" >/dev/null 2>&1 || {
    printf 'Required command not found: %s\n' "${command}" >&2
    exit 1
  }
done

java_major="$(java -version 2>&1 | sed -n '1{s/.*version "\([0-9][0-9]*\).*/\1/p;}')"
[[ "${java_major:-}" =~ ^[0-9]+$ ]] && ((java_major >= 17)) \
  || { printf 'JDK 17+ is required\n' >&2; exit 1; }

node_major="$(node --version | sed 's/^v//' | cut -d. -f1)"
[[ "${node_major}" =~ ^[0-9]+$ ]] && ((node_major >= 20)) \
  || { printf 'Node.js 20+ is required\n' >&2; exit 1; }

cd "${PROJECT_DIR}"
# Local multi-repository bundle; production repositories publish independently.
mvn --batch-mode --no-transfer-progress -f redis-ops-sync-contract/pom.xml clean install
if [[ "${SKIP_TESTS}" == true ]]; then
  "${SCRIPT_DIR}/build-platform.sh" -DskipTests
  "${SCRIPT_DIR}/build-sync-worker.sh" -DskipTests
else
  "${SCRIPT_DIR}/build-platform.sh"
  "${SCRIPT_DIR}/build-sync-worker.sh"
fi

(
  cd redis-ops-frontend
  npm ci --prefer-offline
  npm run build
)

platform_jars=(redis-ops-platform/governance-bootstrap/target/governance-bootstrap-*.jar)
worker_jars=(redis-ops-sync-worker/governance-sync-service/target/governance-sync-service-*.jar)
(( ${#platform_jars[@]} == 1 )) || { printf 'Expected exactly one Platform JAR\n' >&2; exit 1; }
(( ${#worker_jars[@]} == 1 )) || { printf 'Expected exactly one Sync Worker JAR\n' >&2; exit 1; }
platform_jar="${platform_jars[0]}"
worker_jar="${worker_jars[0]}"
[[ -f "${platform_jar}" ]] || { printf 'Platform JAR was not produced\n' >&2; exit 1; }
[[ -f "${worker_jar}" ]] || { printf 'Sync Worker JAR was not produced\n' >&2; exit 1; }
version="${platform_jar##*/governance-bootstrap-}"
version="${version%.jar}"
[[ -n "${version}" ]] || { printf 'Could not determine project version from Platform JAR\n' >&2; exit 1; }
[[ "${worker_jar}" == *"governance-sync-service-${version}.jar" ]] \
  || { printf 'Platform and Sync Worker versions do not match\n' >&2; exit 1; }
archive_version="${version%-SNAPSHOT}"
stage_root="$(mktemp -d)"
trap 'rm -rf "${stage_root}"' EXIT
stage="${stage_root}/redis-ops"

mkdir -p "${stage}/app" "${stage}/bin" "${stage}/conf" "${stage}/systemd" \
  "${stage}/frontend" "${stage}/var/run" "${stage}/var/log" "${stage}/var/data/sync" \
  "${stage}/var/nginx"

cp "${platform_jar}" "${stage}/app/platform.jar"
cp "${worker_jar}" "${stage}/app/sync-worker.jar"
cp -R redis-ops-frontend/dist/. "${stage}/frontend/"
cp deploy/bin/redis-opsctl "${stage}/bin/"
cp deploy/conf/redis-ops.env.example deploy/conf/nginx.conf.template "${stage}/conf/"
cp deploy/systemd/*.template "${stage}/systemd/"
cp docs/platform-deployment.md docs/sync-worker-deployment.md "${stage}/"
chmod 0755 "${stage}/bin/redis-opsctl"

mkdir -p "${OUTPUT_DIR}"
archive="${OUTPUT_DIR}/redis-ops-${archive_version}.tar.gz"
tar -C "${stage_root}" -czf "${archive}" redis-ops
printf 'Release created: %s\n' "${archive}"
