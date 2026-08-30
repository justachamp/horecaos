#!/usr/bin/env python3
"""Check that relative links in the repository's markdown resolve.

Documentation here is load-bearing — AGENTS.md, the ADRs, and the runbooks are
read during incidents and by agents mid-task — and a link that 404s is found at
exactly the wrong moment.

Two conventions are resolved specially rather than being excluded, so their links
are genuinely checked rather than merely skipped:

  intent/TEMPLATE.*.md    copied into intent/NNNN-slug/, so `../../docs/x` and a
                          bare `spec.md` are correct there, not where they sit.
  .claude/commands/*.md   prompts the agent reads with the repository root as its
                          working directory, so their paths are root-relative.

Run directly, or via `make lint`.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SKIP_DIRS = {".git", "target", "node_modules", "legacy-archive", ".claude/worktrees",
             "dist", ".vite", ".idea"}

LINK = re.compile(r"(?<!!)\[([^\]]*)\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
EXTERNAL = ("http://", "https://", "mailto:", "tel:", "#")

GREEN, RED, DIM, OFF = "\033[32m", "\033[31m", "\033[2m", "\033[0m"


def markdown_files() -> list[Path]:
    out = []
    for path in ROOT.rglob("*.md"):
        rel = path.relative_to(ROOT)
        if any(str(rel).startswith(s) for s in SKIP_DIRS):
            continue
        if any(part in SKIP_DIRS for part in rel.parts):
            continue
        out.append(path)
    return sorted(out)


def base_for(path: Path) -> Path:
    """The directory a file's relative links should resolve from."""
    rel = path.relative_to(ROOT)
    # Templates are checked as if already copied into a change directory.
    if rel.parent.name == "intent" and rel.name.startswith("TEMPLATE."):
        return ROOT / "intent" / "0000-placeholder"
    # Command prompts are read with the repository root as the working directory.
    if str(rel).startswith(".claude/commands/"):
        return ROOT
    return path.parent


def resolves(base: Path, target: str) -> bool:
    if (base / target).resolve().exists():
        return True
    # Inside a change directory the sibling artifacts do not exist yet; their
    # templates are what prove the name is right.
    if base.name == "0000-placeholder" and "/" not in target:
        return (ROOT / "intent" / f"TEMPLATE.{target}").exists()
    return False


def main() -> int:
    broken: list[str] = []
    checked = 0

    for path in markdown_files():
        base = base_for(path)
        rel = path.relative_to(ROOT)
        in_fence = False
        for n, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
            if line.lstrip().startswith("```"):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            # Strip code spans: a regex in prose is not a link.
            prose = re.sub(r"`[^`]*`", "", line)
            for text, target in LINK.findall(prose):
                if target.startswith(EXTERNAL):
                    continue
                target = target.split("#", 1)[0]
                if not target:
                    continue
                checked += 1
                if not resolves(base, target):
                    broken.append(f"{rel}:{n}: [{text}]({target})")

    if broken:
        print(f"{RED}FAIL{OFF} {len(broken)} broken link(s):")
        for b in broken:
            print(f"       {b}")
        print(f"\n{DIM}Checked {checked} relative links across "
              f"{len(markdown_files())} files.{OFF}")
        return 1

    print(f"{GREEN}ok{OFF}   doc links: {checked} relative links resolve "
          f"across {len(markdown_files())} files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
