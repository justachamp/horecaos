#!/usr/bin/env python3
"""Run `prettier --check` on the frontend files a change touched -- not on the tree.

Why this exists
---------------
`npm run format:check` in an app checks every file under src/, and frontend/operations
has about a hundred files that were never formatted, so CI cannot run it whole without
turning red on code nobody changed. Reformatting the tree is a single sweeping commit
that conflicts with every open branch, so it is scheduled for the end of a batch, not
done piecemeal. Until then this script is the ratchet: a file a change adds or edits must
already be formatted, and nothing else is looked at. The unformatted backlog can shrink
but cannot grow.

What "touched" means
--------------------
Files under <app>/src with an extension `format:check` covers (ts, html, css, json) that
were added, copied, modified or renamed between the merge base of <base> and HEAD --
plus, so the same command works on a developer machine, uncommitted and untracked ones.
On a CI checkout the working tree equals HEAD, so this is exactly the change under
test. Deleted files are skipped; files matched by the app's .prettierignore are skipped
by prettier itself.

<base> is the pull request's base branch (`origin/main`) or, on a push, the commit the
push replaced (`github.event.before`). A base that is empty, all zeros (a new branch) or
not in the clone (a force push) falls back to the parent of HEAD, so the check still
covers the tip commit instead of silently passing on nothing.

Usage
-----
  python3 frontend/tools/format_changed.py --app operations --base origin/main
  python3 frontend/tools/format_changed.py --app operations --base origin/main --list
  python3 frontend/tools/test_format_changed.py

`--list` prints the files that would be checked and runs nothing, so it works without
node_modules. Standard library only.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Sequence

FRONTEND = Path(__file__).resolve().parents[1]
REPO = FRONTEND.parent

# The extensions in package.json's format:check glob ("src/**/*.{ts,html,css,json}").
EXTENSIONS = (".ts", ".html", ".css", ".json")
NULL_SHA = "0" * 40
# Keep each prettier invocation well under the operating system's argument limit.
CHUNK = 100


class FormatError(Exception):
    """The set of files to check could not be worked out."""


def git(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        raise FormatError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result


def _is_commit(repo: Path, rev: str) -> bool:
    return git(repo, "rev-parse", "--verify", "--quiet", f"{rev}^{{commit}}", check=False).returncode == 0


def resolve_base(repo: Path, base: str | None) -> tuple[str, str]:
    """(revision to diff from, a note for the log when the requested base was unusable)."""
    requested = (base or "").strip()
    if requested and requested != NULL_SHA and _is_commit(repo, requested):
        merge_base = git(repo, "merge-base", requested, "HEAD", check=False)
        if merge_base.returncode == 0 and merge_base.stdout.strip():
            return merge_base.stdout.strip(), ""
        return requested, ""  # unrelated histories: compare with the base itself
    reason = "no base was given" if not requested else "a new branch has no previous tip" if requested == NULL_SHA else f"{requested} is not in this clone"
    if _is_commit(repo, "HEAD^"):
        return "HEAD^", f"{reason}; checking the files the tip commit changed"
    # The tree of a repository with no files, so the diff lists every file.
    empty_tree = git(repo, "hash-object", "-t", "tree", "/dev/null").stdout.strip()
    return empty_tree, f"{reason} and HEAD has no parent; checking every file"


def _lines(output: str) -> list[str]:
    return [item for item in output.split("\0") if item]


def changed_files(repo: Path, app: str, base: str | None) -> tuple[list[str], str]:
    """Paths relative to the app directory, sorted, plus the base-resolution note."""
    source = f"{app}/src"
    revision, note = resolve_base(repo, base)
    tracked = _lines(git(repo, "diff", "--name-only", "-z", "--diff-filter=ACMR", revision, "--", source).stdout)
    untracked = _lines(git(repo, "ls-files", "--others", "--exclude-standard", "-z", "--", source).stdout)
    prefix = f"{app}/"
    files = sorted({path[len(prefix):] for path in tracked + untracked if path.endswith(EXTENSIONS)})
    return files, note


def run_prettier(app_dir: Path, files: Sequence[str]) -> int:
    npx = shutil.which("npx")
    if npx is None:
        print("format_changed: npx is not on PATH; install Node.js and run `npm ci` in the app.", file=sys.stderr)
        return 2
    if not (app_dir / "node_modules" / ".bin" / "prettier").exists():
        print(f"format_changed: {app_dir.name} has no node_modules; run `npm ci` there first.", file=sys.stderr)
        return 2
    status = 0
    for start in range(0, len(files), CHUNK):
        chunk = list(files[start : start + CHUNK])
        completed = subprocess.run([npx, "--no-install", "prettier", "--check", *chunk], cwd=app_dir)
        status = max(status, completed.returncode)
    return status


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0])
    parser.add_argument("--app", required=True, help="directory name under frontend/, e.g. operations")
    parser.add_argument("--base", default="", help="revision the change is measured against (default: HEAD^)")
    parser.add_argument("--list", action="store_true", help="print the files that would be checked and stop")
    parser.add_argument("--repo", type=Path, default=REPO, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    app_dir = args.repo / "frontend" / args.app
    if not (app_dir / "package.json").is_file():
        print(f"format_changed: {app_dir} is not a frontend app (no package.json)", file=sys.stderr)
        return 2
    try:
        files, note = changed_files(args.repo, f"frontend/{args.app}", args.base)
    except FormatError as error:
        print(f"format_changed: {error}", file=sys.stderr)
        return 2
    if note:
        print(f"format_changed: {note}")

    if args.list:
        print("\n".join(files))
        return 0
    if not files:
        print(f"format_changed: no {'/'.join(e.lstrip('.') for e in EXTENSIONS)} file under frontend/{args.app}/src changed; nothing to check")
        return 0

    print(f"format_changed: checking {len(files)} changed file(s) in frontend/{args.app}")
    status = run_prettier(app_dir, files)
    if status != 0:
        print(
            "\nformat_changed: the [warn] files above are not prettier-formatted. Fix them with\n"
            f"  cd frontend/{args.app} && npx prettier --write <those files>\n"
            "(only files this change touched are checked; the rest of the tree is reformatted once, in a dedicated commit).",
            file=sys.stderr,
        )
    return status


if __name__ == "__main__":
    sys.exit(main())
