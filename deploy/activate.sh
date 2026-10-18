#!/bin/bash
set -euo pipefail
release=$(readlink -f "${1:?release folder required}")
case "$release" in /opt/fire-assistant/releases/*) ;; *) exit 2;; esac
base=/opt/fire-assistant
export COMPOSE_ENV_FILES="$base/shared/production.env"
cd "$release"
docker compose config --quiet
docker compose up -d --wait db
install -d -m 755 "$base/shared/uploads" "$base/shared/downloads"
chown -R 10001:10001 "$base/shared/uploads"
chmod 755 "$base/shared/uploads"
chmod 644 app.jar
docker compose up -d api
healthy=false
for attempt in $(seq 1 90); do
  if curl -fsS --max-time 2 http://127.0.0.1:8081/api/health > /dev/null; then healthy=true; break; fi
  sleep 2
done
if [ "$healthy" != true ]; then
  docker compose logs --tail 60 api
  exit 1
fi
docker compose up -d web
healthy=false
for attempt in $(seq 1 30); do
  if curl -fsS --max-time 3 http://127.0.0.1/api/health > /dev/null 2>&1; then healthy=true; break; fi
  sleep 2
done
if [ "$healthy" != true ]; then
  docker compose logs --tail 60 web
  docker compose stop web
  exit 1
fi
ln -sfn "$release" "$base/current"
docker compose ps
