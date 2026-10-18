#!/bin/bash
set -euo pipefail
base=/opt/fire-assistant
cd "$base/current"
export COMPOSE_ENV_FILES="$base/shared/production.env"
stamp=$(date +%Y%m%d-%H%M%S)
umask 077
docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF fire_assistant' | gzip > "$base/backups/database-$stamp.sql.gz"
tar -czf "$base/backups/uploads-$stamp.tgz" -C "$base/shared" uploads
printf 'Backup saved: %s\n' "$stamp"
