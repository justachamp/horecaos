#!/usr/bin/env python3
"""Require explicit human authorisation before an agent touches production.

Matches *invocation*, not mention. Three things stay allowed, because gating
them would make the hook something people route around:

  - reading about production (cat, grep, git diff)
  - writing a file whose text happens to name a production command
  - everything in local and staging environments

Only commands that would actually run against production, destroy data, or
rewrite shared history are gated.
"""
import json
import os
import re
import sys

# Reading about production is not deploying to it.
READ_ONLY_LEAD = re.compile(
    r"^\s*(cat|bat|less|more|head|tail|grep|rg|ag|wc|ls|find|stat|file|diff|"
    r"git\s+(diff|log|show|status|blame|grep)|sed\s+-n|awk|jq|yq)\b"
)

# Commands that reach production.
PRODUCTION = [
    # docker compose driven by the production file, doing something stateful
    re.compile(r"\bdocker(\s+-\S+)*\s+compose\b(?=[^|;&]*\bcompose\.production\.ya?ml\b)"
               r"[^|;&]*\b(up|down|restart|start|stop|exec|run|rm|pull)\b", re.I),
    # the production deploy script actually being executed. re.M so a command on
    # its own line — inside a heredoc fed to an interpreter, say — still counts.
    re.compile(r"(^|[|;&]\s*|\bsudo\s+|\bbash\s+|\bsh\s+)\.?/?infra/production/deploy\.sh\b",
               re.M),
    re.compile(r"\bkubectl\b[^|;&]*\b(apply|delete|rollout|scale|exec|drain)\b"
               r"[^|;&]*\b(prod|production)\b", re.I),
    re.compile(r"\bflyway\b[^|;&]*\b(migrate|clean|repair)\b[^|;&]*\b(prod|production)\b", re.I),
]

# Whether pushing to main is a human-only action.
#
# OFF for now, deliberately and temporarily. The rule was written for a workflow
# where an agent prepares a release and a person authorises it, and it did its
# job -- it stopped an agent push and handed the decision back. It is off while
# the AI-native SDLC is being folded into daily work, because during that stretch
# the agent is the one doing the day's commits and a gate that fires on every one
# of them is a gate people learn to route around, which is worse than not having
# it.
#
# Turn it back on by setting this to True. Everything below it stays armed
# meanwhile: a force push still cannot rewrite what is already shared, and no
# amount of workflow change makes DROP TABLE something to infer.
#
# Also honours HORECAOS_GATE_PUSH_TO_MAIN=1 in the environment, so it can be armed
# for one session without editing the file.
PUSH_TO_MAIN_NEEDS_A_HUMAN = os.environ.get("HORECAOS_GATE_PUSH_TO_MAIN") == "1"

# Commands that destroy data or rewrite shared history.
DESTRUCTIVE = [
    re.compile(r"\bDROP\s+(TABLE|SCHEMA|DATABASE)\b", re.I),
    re.compile(r"\bTRUNCATE\s+(TABLE\s+)?[a-z_]", re.I),
    # A force push is not covered by the toggle above and never will be. The
    # ordinary push publishes work; this one deletes somebody else's.
    re.compile(r"\bgit\s+push\b[^|;&]*\s(--force|-f)\b"),
    re.compile(r"\bdocker(\s+-\S+)*\s+compose\b[^|;&]*\bdown\b[^|;&]*\s(-v|--volumes)\b"),
    re.compile(r"\bgit\s+(reset\s+--hard|clean\s+-[a-z]*f)"),
]

if PUSH_TO_MAIN_NEEDS_A_HUMAN:
    DESTRUCTIVE.append(
        re.compile(r"\bgit\s+push\b[^|;&]*\s(origin\s+)?(HEAD:)?main\b"))

# Heredocs fed to an interpreter are executed; heredocs fed to a file writer are
# data. Only the latter can be stripped before matching.
HEREDOC = re.compile(r"<<-?\s*(['\"]?)([A-Za-z_][A-Za-z0-9_]*)\1")
INTERPRETER = re.compile(
    r"(^|[|;&]\s*)(sudo\s+)?(bash|sh|zsh|ksh|dash|python3?|perl|ruby|node|psql|mysql|"
    r"docker\s+exec|kubectl\s+exec)\b[^<]*$"
)


def strip_data_heredocs(command: str) -> str:
    """Remove heredoc bodies that are being written to a file rather than run."""
    out, pos = [], 0
    for m in HEREDOC.finditer(command):
        lead = command[: m.start()]
        # The last line before the operator decides who consumes the body.
        last_line = lead.rsplit("\n", 1)[-1]
        body_start = command.find("\n", m.end())
        if body_start == -1:
            break
        delimiter = m.group(2)
        end = re.search(rf"^\s*{re.escape(delimiter)}\s*$", command[body_start:], re.M)
        if not end:
            break
        body_end = body_start + end.end()
        if INTERPRETER.search(last_line):
            continue  # executed — leave the body in place to be scanned
        out.append(command[pos:body_start])
        pos = body_end
    out.append(command[pos:])
    return "".join(out)


def matches(patterns, command: str):
    for p in patterns:
        if p.search(command):
            return p
    return None


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0
    if payload.get("tool_name") != "Bash":
        return 0
    raw = ((payload.get("tool_input") or {}).get("command") or "").strip()
    if not raw:
        return 0

    command = strip_data_heredocs(raw)
    if READ_ONLY_LEAD.match(command.strip()):
        return 0

    if matches(PRODUCTION, command) and not os.environ.get("HORECAOS_RELEASE_APPROVAL"):
        print(
            "BLOCKED: this command runs against production and no release approval is "
            f"set.\n  {raw[:200]}\n\n"
            "Production deployment is authorised by a person, not inferred by an agent "
            "(ADR 0023). Route:\n"
            "  1. Work through docs/runbooks/deploy.md\n"
            "  2. The release manager exports HORECAOS_RELEASE_APPROVAL=<change-ref>\n"
            "  3. Re-run in that shell\n\n"
            "Preparing the release, opening the PR, and rehearsing rollback in staging "
            "need no approval. Reading these files is not blocked.",
            file=sys.stderr,
        )
        return 2

    if matches(DESTRUCTIVE, command):
        print(
            "BLOCKED: destructive or history-rewriting command.\n"
            f"  {raw[:200]}\n\n"
            "Ask the user to run it themselves if it is genuinely intended. Dropping or "
            "truncating a table belongs in a Flyway migration, and legacy data is never "
            "deleted during cutover (AGENTS.md, migration rules).",
            file=sys.stderr,
        )
        return 2

    return 0


if __name__ == "__main__":
    sys.exit(main())
