#!/usr/bin/env bash
# PreToolUse guard on Bash. Production is an authorised action, not an inferred
# one: the agent may prepare a release, a person authorises it.
exec python3 "$(dirname "$0")/deploy_gate.py"
