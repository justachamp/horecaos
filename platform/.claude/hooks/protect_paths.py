#!/usr/bin/env python3
"""Block edits that are unsafe by policy. See protect-paths.sh."""
import json
import re
import subprocess
import sys
from pathlib import Path


def block(message: str) -> None:
    print(message, file=sys.stderr)
    sys.exit(2)


# A checklist box. `[~]` is the partially-done state -- ADR 0044 uses it, and a
# regex that knew only ` `, `x` and `X` made every one of those lines
# permanently uneditable, which is the stale-checklist failure again in a
# narrower form.
BOX = re.compile(r"^\s*[-*]\s+\[[ xX~]\]\s")
INDENTED = re.compile(r"^\s+\S")
FENCE = re.compile(r"^\s*```")


def _attached_above(text: str, old: str) -> bool:
    """True if the text being replaced sits under a checklist item in the file.

    An edit may begin partway into a wrapped item -- correcting only the
    continuation line -- in which case the chunk itself carries no box and the
    file is the only place the attachment can be read from.
    """
    index = text.find(old)
    if index < 0:
        return False
    for line in reversed(text[:index].splitlines()):
        if not line.strip():
            continue
        if BOX.match(line):
            return True
        if FENCE.match(line):
            return False
        if INDENTED.match(line):
            continue
        return False
    return False


def checklist_only(chunk: str, attached: bool) -> bool:
    """True when every line in this chunk is checklist state rather than prose.

    A checklist item's annotation frequently wraps onto an indented
    continuation line. That line is neither a box nor blank, so a rule that
    admits only boxes and blanks cannot correct a wrapped item at all -- two
    stale items in ADR 0019 stayed wrong for exactly that reason, which is the
    same "a stale checklist is read as a claim" problem the allowance was
    written to solve.

    A continuation is admitted only where it is attached to a checklist item:
    after one in this chunk, or after one directly above it in the file. Prose
    in an ADR is unindented, so it still cannot ride along, and a fenced block
    breaks the attachment so its body cannot either.
    """
    for line in chunk.splitlines():
        if not line.strip():
            continue
        if BOX.match(line):
            attached = True
            continue
        if FENCE.match(line):
            return False
        if attached and INDENTED.match(line):
            continue
        return False
    return True


def tracked_in_head(repo: Path, rel: str) -> bool:
    """True if the file is committed — i.e. it may already have run somewhere.

    The `./` prefix makes git resolve the path against `cwd`, not the repository
    root. Since the monorepo move, this project directory is not the git root,
    and a bare `HEAD:{rel}` would look for the file one level too high — and
    answer "not committed" for every committed migration, disarming rule 1
    without a sound.
    """
    try:
        r = subprocess.run(["git", "cat-file", "-e", f"HEAD:./{rel}"],
                           cwd=repo, capture_output=True)
        return r.returncode == 0
    except Exception:
        return False


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0  # never break the session on a malformed payload

    tool_input = payload.get("tool_input") or {}
    raw = tool_input.get("file_path") or tool_input.get("notebook_path") or ""
    if not raw:
        return 0

    # Anchor to where this hook lives, never to the session's cwd: in the
    # monorepo a session may run from the repository root, and a cwd-derived
    # root would leave every prefix rule below matching nothing.
    repo = Path(__file__).resolve().parents[2]
    path = Path(raw)
    try:
        rel = str(path.resolve().relative_to(repo))
    except ValueError:
        return 0  # outside this project; not ours to police

    # 1. Applied Flyway migrations are append-only.
    if rel.startswith("src/main/resources/db/migration/") and rel.endswith(".sql"):
        if tracked_in_head(repo, rel):
            nums = [
                int(m.group(1))
                for p in (repo / "src/main/resources/db/migration").glob("V*__*.sql")
                if (m := re.match(r"V(\d+)__", p.name))
            ]
            nxt = max(nums) + 1 if nums else 1
            block(
                f"BLOCKED: {rel} is a committed migration.\n"
                "Flyway migrations are append-only — environments that already ran this "
                "file will never see the change, and its checksum will fail.\n"
                f"Write src/main/resources/db/migration/V{nxt:04d}__<snake_case>.sql "
                "instead, and remember the GRANT for horecaos_application.\n"
                "See .claude/skills/flyway-migration/SKILL.md"
            )
        return 0

    # 2. An Accepted ADR is superseded, never rewritten.
    #
    # The optional directory segment matters: records are filed under
    # docs/adr/<status>/ by tools/file-adrs, and a pattern anchored to
    # docs/adr/NNNN would have stopped matching the moment the first one moved --
    # silently, leaving every Accepted ADR editable with nothing to say so.
    if re.match(r"docs/adr/(?:[a-z][a-z-]*/)?\d+.*\.md$", rel) and tracked_in_head(repo, rel):
        try:
            text = (repo / rel).read_text(errors="replace")
        except OSError:
            return 0
        if not re.search(r"decision status\s*:?\s*\**\s*accepted", text, re.I):
            return 0

        # Advancing Implementation status, or recording that this ADR has been
        # superseded, are required by ADR 0000 — they do not change the decision.
        edited = " ".join(
            str(tool_input.get(k, ""))
            for k in ("old_string", "new_string", "content")
        )
        allowed = re.compile(r"implementation status|superseded by", re.I)
        # A decision flip named anywhere in the edit disqualifies every
        # allowance below it, whichever one the edit is dressed as.
        decision_touched = re.search(
            r"decision status\s*:?\s*\**\s*(accepted|proposed|rejected)", edited, re.I)
        if edited and allowed.search(edited) and not decision_touched:
            return 0

        # A checklist box is implementation state, in exactly the same sense as
        # the Implementation status line above it: it records whether something
        # was built, not what was decided. Blocking it meant the boxes could only
        # ever go stale, and a stale checklist is worse than none — it is read as
        # a claim. Six boxes sat unchecked on work that had been finished for
        # weeks because the only way to correct them was to route around a guard.
        #
        # The allowance is deliberately narrow: every line the edit touches must
        # be a checklist item, an indented continuation of one, or blank. Prose
        # cannot ride along, because an unindented prose line matches none of
        # those. Whole-file writes are excluded, since `content` carries the
        # entire record and would always contain prose.
        if edited and "content" not in tool_input and not decision_touched:
            old = str(tool_input.get("old_string", ""))
            new = str(tool_input.get("new_string", ""))
            if old or new:
                attached = bool(old) and _attached_above(text, old)
                if (checklist_only(old, attached)
                        and checklist_only(new, attached)):
                    return 0

        block(
            f"BLOCKED: {rel} has Decision status: Accepted.\n"
            "An accepted decision is never edited in place — the record of what was "
            "believed, and when, is the point (ADR 0000).\n"
            "Write a new ADR and set 'Superseded by' on this one.\n\n"
            "Advancing 'Implementation status', or adding a 'Superseded by' line, is "
            "allowed and is not what this blocked — the edit touched other content.\n"
            "See .claude/skills/adr-discipline/SKILL.md"
        )
        return 0

    # 3. The legacy archive is evidence about the old system, not a workspace.
    if rel.startswith("legacy-archive/"):
        block(
            f"BLOCKED: {rel} is in legacy-archive/, a read-only record of the system "
            "being replaced. Changing it destroys migration evidence."
        )

    # 4. Secrets and build output.
    if re.search(r"(^|/)\.env($|\.)", rel) and not rel.endswith(".env.example"):
        block(
            f"BLOCKED: {rel} holds local credentials.\n"
            "Secrets live only in the secrets manager (ADR 0028). Edit .env.example if "
            "a new variable needs documenting."
        )
    if rel.startswith("target/") or "/target/" in rel:
        block(f"BLOCKED: {rel} is build output. Change the source instead.")

    return 0


if __name__ == "__main__":
    sys.exit(main())
