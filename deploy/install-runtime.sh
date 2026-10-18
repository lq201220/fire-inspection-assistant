#!/bin/bash
set -euo pipefail
base=/opt/fire-assistant
install -d -m 755 "$base" "$base/releases" "$base/install"
install -d -m 700 "$base/shared" "$base/backups"
install -d -m 755 "$base/shared/uploads" "$base/shared/downloads"
if ! command -v docker >/dev/null; then
  if [ ! -s "$base/install/docker.tgz" ]; then
    curl -fsSL --retry 2 --connect-timeout 15 --max-time 600 https://download.docker.com/linux/static/stable/x86_64/docker-29.6.2.tgz -o "$base/install/docker.tgz"
  fi
  tar -xzf "$base/install/docker.tgz" -C "$base/install"
  install -m 755 "$base"/install/docker/* /usr/local/bin/
  cat > /etc/systemd/system/docker.service <<'UNIT'
[Unit]
Description=Docker Container Engine
After=network-online.target
Wants=network-online.target
[Service]
Type=notify
ExecStart=/usr/local/bin/dockerd --host=unix:///var/run/docker.sock
Restart=on-failure
RestartSec=3
Delegate=yes
KillMode=process
LimitNOFILE=infinity
TasksMax=infinity
TimeoutStartSec=120
[Install]
WantedBy=multi-user.target
UNIT
  systemctl daemon-reload
  systemctl enable --now docker
fi
if ! docker compose version >/dev/null 2>&1; then
  install -d /usr/local/lib/docker/cli-plugins
  if [ ! -s "$base/install/docker-compose" ]; then
    curl -fsSL --retry 2 --connect-timeout 15 --max-time 300 https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 -o "$base/install/docker-compose"
  fi
  if [ ! -s "$base/install/compose.sha256" ]; then
    curl -fsSL --retry 2 --connect-timeout 15 --max-time 60 https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64.sha256 -o "$base/install/compose.sha256"
  fi
  expected=$(cut -d ' ' -f 1 "$base/install/compose.sha256")
  actual=$(sha256sum "$base/install/docker-compose" | cut -d ' ' -f 1)
  test "$expected" = "$actual"
  install -m 755 "$base/install/docker-compose" /usr/local/lib/docker/cli-plugins/docker-compose
fi
docker version --format '{{.Server.Version}}'
docker compose version
