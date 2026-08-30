#!/usr/bin/env python3
"""Tests for the SDLC guardrail hooks.

A hook that blocks legitimate work is worse than no hook, because people route
around it. So every rule is tested in both directions: the thing it must block,
and the neighbouring thing it must not.

Run with `make hooks-test`, or directly.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

HOOKS = Path(__file__).resolve().parent
REPO = HOOKS.parents[1]
MIG = "src/main/resources/db/migration"

BLOCK, ALLOW = 2, 0
failures: list[str] = []


def run(hook: str, payload: dict, env: dict | None = None) -> int:
    proc = subprocess.run(
        [sys.executable, str(HOOKS / hook)],
        input=json.dumps(payload),
        capture_output=True,
        text=True,
        env={**os.environ, **(env or {})},
    )
    return proc.returncode


def edit(path: str, **kw) -> dict:
    return {"cwd": str(REPO), "tool_name": "Edit",
            "tool_input": {"file_path": str(REPO / path), **kw}}


def bash(command: str) -> dict:
    return {"cwd": str(REPO), "tool_name": "Bash", "tool_input": {"command": command}}


def check(name: str, expected: int, hook: str, payload: dict, env=None) -> None:
    got = run(hook, payload, env)
    verb = {BLOCK: "block", ALLOW: "allow"}
    if got == expected:
        print(f"\033[32mok\033[0m   {verb[expected]:<5} {name}")
    else:
        print(f"\033[31mFAIL\033[0m {verb[expected]:<5} {name}  (exit {got}, wanted {expected})")
        failures.append(name)


# --- protect-paths ----------------------------------------------------------
P = "protect_paths.py"

check("committed migration", BLOCK, P,
      edit(f"{MIG}/V0035__grant_application_access_to_ungranted_tables.sql"))
check("new, uncommitted migration", ALLOW, P, edit(f"{MIG}/V9999__brand_new.sql"))
check("ordinary Java source", ALLOW, P,
      edit("src/main/java/uz/horecaos/platform/catalog/application/CatalogPublicationService.java"))
check("accepted ADR, decision rewritten", BLOCK, P,
      edit("docs/adr/built/0001-platform-foundation.md",
           old_string="We will use PostgreSQL", new_string="We will use MySQL"))
check("accepted ADR, implementation status advanced", ALLOW, P,
      edit("docs/adr/built/0001-platform-foundation.md",
           old_string="Implementation status: Proposed",
           new_string="Implementation status: In progress"))
check("accepted ADR, decision flip hidden in a status edit", BLOCK, P,
      edit("docs/adr/built/0001-platform-foundation.md",
           old_string="Implementation status: Proposed",
           new_string="Implementation status: Done. Decision status: Rejected"))
check("local credentials file", BLOCK, P, edit(".env"))
check("the documented example env file", ALLOW, P, edit(".env.example"))
check("build output", BLOCK, P, edit("target/classes/whatever.class"))
check("a file outside the repo", ALLOW, P,
      {"cwd": str(REPO), "tool_name": "Edit", "tool_input": {"file_path": "/tmp/scratch.md"}})
check("malformed payload", ALLOW, P, {})

# --- deploy-gate ------------------------------------------------------------
D = "deploy_gate.py"
APPROVED = {"HORECAOS_RELEASE_APPROVAL": "CHG-1042"}
PROD_COMPOSE = "compose.production.yaml"
PROD_DEPLOY = "infra/production/deploy.sh"

# Whether pushing to main needs a human is a *configuration*, not a fact:
# deploy_gate.py arms it from HORECAOS_GATE_PUSH_TO_MAIN, and it is currently stood
# down while the AI-native SDLC is being folded into daily work. So the tests
# assert the setting behaves, not which setting is in force -- and every case
# pins the variable explicitly, so an armed shell cannot silently change what a
# test means. An empty string is falsy to the hook, i.e. stood down.
GATE_ARMED = {"HORECAOS_GATE_PUSH_TO_MAIN": "1"}
GATE_STOOD_DOWN = {"HORECAOS_GATE_PUSH_TO_MAIN": ""}
# Production approval is pinned the same way, so a shell that happens to hold a
# release approval does not turn the production cases green for the wrong reason.
UNAPPROVED = {"HORECAOS_RELEASE_APPROVAL": ""}

check("push to main, gate armed", BLOCK, D, bash("git push origin main"), GATE_ARMED)
check("push to HEAD:main, gate armed", BLOCK, D,
      bash("git push origin HEAD:main"), GATE_ARMED)
check("push to main, gate stood down", ALLOW, D,
      bash("git push origin main"), GATE_STOOD_DOWN)

# The risk of a configurable gate is that standing it down quietly widens more
# than intended. Standing it down may widen exactly one thing: an ordinary push
# to main. Everything below destroys data, reaches production, or rewrites
# shared history, and is blocked in BOTH settings -- so each case runs twice.
ALWAYS_BLOCKED = [
    ("production compose up", bash(f"docker compose -f {PROD_COMPOSE} up -d")),
    ("production deploy script", bash(f"./{PROD_DEPLOY}")),
    ("production deploy script via bash", bash(f"bash {PROD_DEPLOY} --tag v2")),
    ("kubectl rollout against prod",
     bash("kubectl rollout restart deployment/api --context prod")),
    ("dropping a table", bash("psql -c 'DROP TABLE ordering.orders'")),
    ("truncating a table", bash("psql -c 'TRUNCATE tenant.tenants'")),
    ("force push", bash("git push --force origin feature/x")),
    ("force push to main", bash("git push --force origin main")),
    ("compose down with volumes", bash("docker compose down -v")),
    ("hard reset", bash("git reset --hard origin/main")),
    ("deploy hidden in a heredoc fed to bash",
     bash(f"bash <<'SH'\n./{PROD_DEPLOY}\nSH")),
]

# Neither may standing the gate up narrow anything: ordinary work stays allowed
# in both settings too.
ALWAYS_ALLOWED = [
    ("reading the production compose file", bash(f"cat {PROD_COMPOSE}")),
    ("grepping the deploy script", bash(f"grep -n approval {PROD_DEPLOY}")),
    ("diffing production config", bash(f"git diff main -- {PROD_COMPOSE}")),
    ("writing a doc that names a production command",
     bash(f"cat > docs/notes.md <<'MD'\nRun ./{PROD_DEPLOY} to ship.\n"
          f"Then docker compose -f {PROD_COMPOSE} up -d\nMD")),
    ("local compose up", bash("docker compose up -d")),
    ("local compose down", bash("docker compose down")),
    ("the build", bash("make verify")),
    ("the test suite", bash("./mvnw test")),
    ("pushing a feature branch", bash("git push origin feature/courier-handover")),
    ("status", bash("git status")),
    ("a non-Bash tool", edit("README.md")),
]

for setting, gate in (("gate armed", GATE_ARMED), ("gate stood down", GATE_STOOD_DOWN)):
    for name, payload in ALWAYS_BLOCKED:
        check(f"{name}, {setting}", BLOCK, D, payload, {**gate, **UNAPPROVED})
    for name, payload in ALWAYS_ALLOWED:
        check(f"{name}, {setting}", ALLOW, D, payload, {**gate, **UNAPPROVED})

check("production deploy with approval", ALLOW, D, bash(f"./{PROD_DEPLOY}"), APPROVED)

# --- post-edit-check --------------------------------------------------------
C = "post_edit_check.py"
check("advisory only, never blocks", ALLOW, C,
      edit("src/main/java/uz/horecaos/platform/catalog/application/CatalogPublicationService.java"))
check("ignores non-code files", ALLOW, C, edit("README.md"))

print()
if failures:
    print(f"{len(failures)} hook test(s) failed:")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print(f"all hook tests passed")
