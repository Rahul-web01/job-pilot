#!/usr/bin/env bash
# Makes JobPilot start by itself when you log in (Linux with systemd, e.g. Ubuntu).
# Run it again after pulling new code to update the running app.
# To undo: scripts/uninstall-autostart.sh
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
INSTALL_DIR="$HOME/.local/share/jobpilot"
UNIT_DIR="$HOME/.config/systemd/user"
JAVA_BIN="$(command -v java)"

echo "Building JobPilot..."
cd "$APP_DIR"
./mvnw -q -DskipTests package

mkdir -p "$INSTALL_DIR" "$UNIT_DIR"
cp target/job-pilot-*.jar "$INSTALL_DIR/job-pilot.jar"

cat > "$UNIT_DIR/jobpilot.service" <<EOF
[Unit]
Description=JobPilot job search assistant (http://localhost:8080)
After=graphical-session.target network-online.target
PartOf=graphical-session.target

[Service]
# Runs from the project folder so it keeps using the same ./data (jobs, resume, settings)
WorkingDirectory=$APP_DIR
ExecStart=$JAVA_BIN -jar $INSTALL_DIR/job-pilot.jar
Restart=always
RestartSec=10

[Install]
WantedBy=graphical-session.target
EOF

systemctl --user daemon-reload
systemctl --user enable jobpilot.service >/dev/null
systemctl --user restart jobpilot.service

echo "Done. JobPilot now starts when you log in: open http://localhost:8080"
echo "Status:  systemctl --user status jobpilot"
echo "Logs:    journalctl --user -u jobpilot -f"
