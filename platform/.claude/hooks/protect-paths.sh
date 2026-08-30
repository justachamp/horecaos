#!/usr/bin/env bash
# PreToolUse guard on Edit/Write. Blocks changes that are unsafe by policy rather
# than by judgement. Exit 2 blocks the tool call and shows stderr to Claude.
#
# Rules (each maps to AGENTS.md / an ADR):
#   1. A committed Flyway migration is history. Append a new one instead.
#   2. An Accepted ADR is never edited to change its decision. Supersede it.
#   3. legacy-archive/ is a read-only source of truth about the old system.
#   4. Secrets and build output are never edited by an agent.
exec python3 "$(dirname "$0")/protect_paths.py"
