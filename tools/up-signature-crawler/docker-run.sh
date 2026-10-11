#!/usr/bin/env bash
set -euo pipefail

tool_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$tool_dir/../.." && pwd)"
image="${BILI_MAVEN_IMAGE:-maven:3.9-eclipse-temurin-17}"
if [[ -z "${BILI_MAVEN_IMAGE:-}" ]] && docker image inspect bili-crawler-runtime:local >/dev/null 2>&1; then
  image="bili-crawler-runtime:local"
fi

if [[ "${1:-}" == "build" ]]; then
  docker run --rm --init -v "$repo_dir:/workspace" -v "$HOME/.m2:/root/.m2" \
    -w /workspace "$image" mvn -B install
  docker run --rm --init -v "$repo_dir:/workspace" -v "$HOME/.m2:/root/.m2" \
    -w /workspace/tools/up-signature-crawler "$image" mvn -B package
else
  if [[ ! -f "$tool_dir/target/bili-up-signature-crawler.jar" ]]; then
    printf '%s\n' 'Build first: bash docker-run.sh build' >&2
    exit 1
  fi
  exec docker run --rm --init -v "$repo_dir:/workspace" \
    -w /workspace/tools/up-signature-crawler "$image" \
    java -jar target/bili-up-signature-crawler.jar "$@"
fi
