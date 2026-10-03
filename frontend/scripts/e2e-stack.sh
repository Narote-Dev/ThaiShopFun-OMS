#!/usr/bin/env bash
# Starts Postgres, mock-tsf, the OMS API (local profile), and the Vite app for Playwright.
set -euo pipefail
# Stay in Playwright's process group. A new group would survive the runner's kill and hold its stdout open.
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

if docker info >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif sudo docker info >/dev/null 2>&1; then
  COMPOSE=(sudo docker compose)
else
  echo "docker is not available" >&2
  exit 1
fi

"${COMPOSE[@]}" up -d --wait

mkdir -p "$ROOT/backend/target"
BACKEND_PID=""

cleanup() {
  if [[ -n "$BACKEND_PID" ]]; then
    kill "$BACKEND_PID" >/dev/null 2>&1 || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

if ! curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
  (
    cd "$ROOT/backend"
    ./mvnw -B spring-boot:run -Dspring-boot.run.profiles=local
  ) >"$ROOT/backend/target/e2e-backend.log" 2>&1 &
  BACKEND_PID=$!
  ready=0
  for _ in $(seq 1 150); do
    if curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
      ready=1
      break
    fi
    if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
      echo "backend exited before it was healthy" >&2
      tail -n 80 "$ROOT/backend/target/e2e-backend.log" >&2 || true
      exit 1
    fi
    sleep 2
  done
  if [[ "$ready" != 1 ]]; then
    echo "backend health timed out" >&2
    tail -n 80 "$ROOT/backend/target/e2e-backend.log" >&2 || true
    exit 1
  fi
fi

curl -fsS http://127.0.0.1:8090/actuator/health >/dev/null

# Step: Idempotent demo orders for Active Shop (T17 local/e2e only).
curl -fsS -X POST http://127.0.0.1:8090/control/demo/orders-seed >/dev/null || true

cd "$ROOT/frontend"
# Foreground so this process exits when Vite exits, and the runner can stop the whole group.
exec npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
