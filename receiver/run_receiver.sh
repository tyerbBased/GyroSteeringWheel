#!/usr/bin/env bash
cd "$(dirname "$0")" || exit 1
if [ -x "$HOME/pedal/venv/bin/python" ]; then
    PY="$HOME/pedal/venv/bin/python"
else
    PY=python3
fi
exec "$PY" -u steering_receiver_linux.py "$@"
