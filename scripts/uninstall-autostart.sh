#!/usr/bin/env bash
# Stops JobPilot from starting by itself. Your data folder is not touched.
set -euo pipefail

systemctl --user disable --now jobpilot.service 2>/dev/null || true
rm -f "$HOME/.config/systemd/user/jobpilot.service"
rm -rf "$HOME/.local/share/jobpilot"
systemctl --user daemon-reload
echo "JobPilot will no longer start by itself. Your jobs and resume in data/ are unchanged."
