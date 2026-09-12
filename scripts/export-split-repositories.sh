#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
OUTPUT_ARG="${1:-}"

if [[ -z "${OUTPUT_ARG}" ]]; then
  echo "Usage: $0 <empty-output-directory>" >&2
  exit 2
fi

mkdir -p "${OUTPUT_ARG}"
OUTPUT_DIR="$(cd -- "${OUTPUT_ARG}" && pwd)"
if [[ -n "$(find "${OUTPUT_DIR}" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  echo "Output directory must be empty: ${OUTPUT_DIR}" >&2
  exit 2
fi

# Export the actual independent roots, including uncommitted source changes.
# Never overlay stale POM templates onto the repositories being validated.
for repository in redis-ops-sync-contract redis-ops-platform redis-ops-sync-worker redis-ops-frontend; do
  rsync -a --exclude target --exclude node_modules --exclude dist --exclude .git \
    --exclude .DS_Store --exclude .env --exclude '*.log' \
    "${REPO_DIR}/${repository}/" "${OUTPUT_DIR}/${repository}/"
done

cp "${REPO_DIR}/scripts/fixtures/maven-settings.xml.template" \
  "${OUTPUT_DIR}/maven-settings.xml.template"

echo "Exported independent repositories to ${OUTPUT_DIR}"
