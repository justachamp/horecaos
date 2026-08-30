#!/usr/bin/env bash
# PostToolUse. Runs the deterministic repository rules after a schema or Java
# edit so a violation surfaces in the same turn that introduced it, rather than
# in CI twenty minutes later. Advisory: exit 0 either way.
exec python3 "$(dirname "$0")/post_edit_check.py"
