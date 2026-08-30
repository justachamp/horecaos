#!/usr/bin/env python3
"""Surface hygiene violations in the same turn that introduced them."""
import json
import subprocess
import sys
from pathlib import Path


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0
    path = (payload.get("tool_input") or {}).get("file_path") or ""
    if not path.endswith((".sql", ".java")):
        return 0

    # Anchored to this hook's own project directory, not the session cwd — a
    # monorepo session running from the repository root would otherwise never
    # find the checker.
    repo = Path(__file__).resolve().parents[2]
    checker = repo / "tools/checks/repo_hygiene.py"
    if not checker.exists():
        return 0

    proc = subprocess.run(
        [sys.executable, str(checker)], cwd=repo, capture_output=True, text=True
    )
    if proc.returncode != 0:
        failed = [l for l in proc.stdout.splitlines() if "FAIL" in l or l.startswith("   ")]
        print(
            "repo-hygiene found violations after this edit:\n"
            + "\n".join(failed[:20])
            + "\n\nFix them now — each maps to a rule in AGENTS.md. "
            "Run `python3 tools/checks/repo_hygiene.py` for the full report.",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
