#!/usr/bin/env bash
# Runs the same checks as CI on a laptop with only Docker installed.
#   scripts/test-local.sh backend   - MySQL 8 + `mvn -B clean verify` (all JUnit tests, real Flyway migrations)
#   scripts/test-local.sh pub       - refresh flutter/pubspec.lock after a pubspec.yaml change
#   scripts/test-local.sh flutter   - flutter analyze + flutter test
#   scripts/test-local.sh ai        - ai-service pytest
# Throw-away containers and the network are removed at the end; nothing touches the
# running docker compose stack or its database.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NET=jn-test-net

backend() {
  docker network create "$NET" >/dev/null 2>&1 || true
  docker rm -f jn-test-mysql >/dev/null 2>&1 || true
  docker run -d --name jn-test-mysql --network "$NET" -e MYSQL_ROOT_PASSWORD=test-root \
    -e MYSQL_DATABASE=jannet_ai -e MYSQL_USER=jannet_ci -e MYSQL_PASSWORD=test-pass mysql:8.0 >/dev/null
  trap 'docker rm -f jn-test-mysql >/dev/null 2>&1; docker network rm '"$NET"' >/dev/null 2>&1' EXIT
  echo "waiting for MySQL..."
  until docker exec jn-test-mysql mysqladmin ping -h 127.0.0.1 -uroot -ptest-root --silent 2>/dev/null; do sleep 3; done
  # maven cache kept in a named volume so the second run is fast
  docker run --rm --network "$NET" -v "$ROOT":/repo -v jn-m2:/root/.m2 -w /repo/backend \
    -e DB_HOST=jn-test-mysql -e DB_PORT=3306 -e DB_NAME=jannet_ai -e DB_USERNAME=jannet_ci -e DB_PASSWORD=test-pass \
    -e JWT_SECRET=local-test-jwt-secret-0123456789abcdef0123456789 -e AI_SERVICE_ENABLED=false \
    -e NOTIFICATION_ASYNC_ENABLED=false maven:3.9.9-eclipse-temurin-21 mvn -B clean verify 2>&1 | tail -40
}

flutter_sdk() {
  docker build -q -f "$ROOT/docker/Dockerfile.flutter-web" --target sdk -t jn-flutter-sdk "$ROOT" >/dev/null
}

case "${1:-}" in
  backend) backend ;;
  pub)
    flutter_sdk
    docker run --rm -v "$ROOT/flutter":/src -w /src jn-flutter-sdk sh -c 'flutter pub get && git config --global --add safe.directory /src >/dev/null 2>&1; true'
    echo "flutter/pubspec.lock updated - commit it with pubspec.yaml" ;;
  flutter)
    flutter_sdk
    # errors / warnings printed in full (infos only counted); then the tests, failures shown with their output
    docker run --rm -v "$ROOT/flutter":/src -w /src jn-flutter-sdk sh -c 'flutter pub get --enforce-lockfile >/dev/null &&
      { flutter analyze --no-fatal-infos > /tmp/analyze.txt 2>&1; echo "analyze: $(grep -c " error •" /tmp/analyze.txt) errors, $(grep -c " warning •" /tmp/analyze.txt) warnings, $(grep -c " info •" /tmp/analyze.txt) infos"; grep " error •\| warning •" /tmp/analyze.txt; } ;
      flutter test --reporter expanded > /tmp/test.txt 2>&1; tail -3 /tmp/test.txt; grep -B2 -A25 "\[E\]\|Error:" /tmp/test.txt | head -60' ;;
  ai)
    docker build -q -f "$ROOT/docker/Dockerfile.ai-service" -t jn-ai "$ROOT" >/dev/null
    docker run --rm -u 0 -e YOLO_CONFIG_DIR=/tmp -v "$ROOT/ai-service/tests":/app/tests:ro \
      -v "$ROOT/ai-service/scripts":/app/scripts:ro jn-ai sh -c 'cd /app && python -m pytest -q -p no:cacheprovider tests 2>&1 | tail -3' ;;
  *) echo "usage: $0 backend|pub|flutter|ai"; exit 2 ;;
esac
