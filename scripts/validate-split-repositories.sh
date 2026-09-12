#!/usr/bin/env bash
set -euo pipefail

EXPORT_ARG="${1:-}"
if [[ -z "${EXPORT_ARG}" ]]; then
  echo "Usage: $0 <export-directory>" >&2
  exit 2
fi

EXPORT_DIR="$(cd -- "${EXPORT_ARG}" && pwd)"
CONTRACT_DIR="${EXPORT_DIR}/redis-ops-sync-contract"
PLATFORM_DIR="${EXPORT_DIR}/redis-ops-platform"
WORKER_DIR="${EXPORT_DIR}/redis-ops-sync-worker"
FRONTEND_DIR="${EXPORT_DIR}/redis-ops-frontend"

for repository_dir in "${CONTRACT_DIR}" "${PLATFORM_DIR}" "${WORKER_DIR}" "${FRONTEND_DIR}"; do
  if [[ ! -d "${repository_dir}" ]]; then
    echo "Missing exported repository: ${repository_dir}" >&2
    exit 2
  fi
done

for runtime_dir in "${PLATFORM_DIR}" "${WORKER_DIR}" "${FRONTEND_DIR}"; do
  test -x "${runtime_dir}/deploy/bin/redis-opsctl"
  test -f "${runtime_dir}/deploy/conf/redis-ops.env.example"
  bash -n "${runtime_dir}/deploy/bin/redis-opsctl"
done
test -f "${FRONTEND_DIR}/deploy/conf/nginx.conf.template"

if [[ -d "${PLATFORM_DIR}/redis-ops-sync-contract" \
   || -d "${PLATFORM_DIR}/governance-sync-protocol" \
   || -d "${PLATFORM_DIR}/governance-sync-service" ]]; then
  echo "Platform export contains contract or Worker source" >&2
  exit 1
fi
if [[ -d "${WORKER_DIR}/redis-ops-sync-contract" \
   || -d "${WORKER_DIR}/governance-common" \
   || -d "${WORKER_DIR}/governance-domain" \
   || -d "${WORKER_DIR}/governance-application" \
   || -d "${WORKER_DIR}/governance-infrastructure" \
   || -d "${WORKER_DIR}/governance-api" \
   || -d "${WORKER_DIR}/governance-bootstrap" ]]; then
  echo "Worker export contains contract or Platform source" >&2
  exit 1
fi

VALIDATION_DIR="$(mktemp -d /tmp/redis-ops-split-validation.XXXXXX)"
CONTRACT_LOCAL_REPO="${VALIDATION_DIR}/contract-m2"
PLATFORM_LOCAL_REPO="${VALIDATION_DIR}/platform-m2"
WORKER_LOCAL_REPO="${VALIDATION_DIR}/worker-m2"
CONTRACT_REGISTRY="${VALIDATION_DIR}/contract-registry"
SETTINGS_FILE="${VALIDATION_DIR}/settings.xml"
mkdir -p "${CONTRACT_LOCAL_REPO}" "${PLATFORM_LOCAL_REPO}" "${WORKER_LOCAL_REPO}" "${CONTRACT_REGISTRY}"

sed "s|@CONTRACT_REPOSITORY_URL@|file://${CONTRACT_REGISTRY}|g" \
  "${EXPORT_DIR}/maven-settings.xml.template" > "${SETTINGS_FILE}"

mvn --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="${CONTRACT_LOCAL_REPO}" \
  -f "${CONTRACT_DIR}/pom.xml" clean deploy \
  -DaltDeploymentRepository="redis-ops-contract::file://${CONTRACT_REGISTRY}"

mvn --settings "${SETTINGS_FILE}" --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="${PLATFORM_LOCAL_REPO}" \
  -f "${PLATFORM_DIR}/pom.xml" clean verify
mvn --settings "${SETTINGS_FILE}" --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="${PLATFORM_LOCAL_REPO}" \
  -f "${PLATFORM_DIR}/pom.xml" dependency:tree \
  -Dincludes=io.github.redisops > "${VALIDATION_DIR}/platform-dependencies.txt"

mvn --settings "${SETTINGS_FILE}" --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="${WORKER_LOCAL_REPO}" \
  -f "${WORKER_DIR}/pom.xml" clean verify
mvn --settings "${SETTINGS_FILE}" --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="${WORKER_LOCAL_REPO}" \
  -f "${WORKER_DIR}/pom.xml" dependency:tree \
  -Dincludes=io.github.redisops > "${VALIDATION_DIR}/worker-dependencies.txt"

if rg -q 'governance-sync-(protocol|service)' "${VALIDATION_DIR}/platform-dependencies.txt"; then
  echo "Platform dependency tree contains Worker artifacts" >&2
  exit 1
fi
if rg -q 'governance-(common|domain|application|infrastructure|api|bootstrap)' \
  "${VALIDATION_DIR}/worker-dependencies.txt"; then
  echo "Worker dependency tree contains Platform artifacts" >&2
  exit 1
fi

npm --prefix "${FRONTEND_DIR}" ci
npm --prefix "${FRONTEND_DIR}" run build

echo "Split repository validation passed. Evidence: ${VALIDATION_DIR}"
