#!/usr/bin/env bash
# Thin wrapper so `make eval` stays stable if the runner changes.
exec python3 "$(dirname "$0")/run.py" "$@"
