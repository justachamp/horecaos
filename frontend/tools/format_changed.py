#!/usr/bin/env python3
"""Run `prettier --check` on the frontend files a change touched -- not on the tree.

What it is for
--------------
The prettier gate for the apps whose tree is not prettier-clean yet. `operations`,
`storefront` and `storefront-milliy` were each reformatted in one commit, so CI runs the plain
`npm run format:check` for them and this script is only a local shortcut there. `control-plane`
still has on the order of a hundred files that predate its prettier config, and a blanket
reformat while other branches are open would conflict with every one of them; for it CI runs
this script instead, so the ratchet is: a file a change adds or edits must be prettier-clean.
Once nothing is in flight, reformat the app in one commit, switch its CI step to
`npm run format:check`, and stop calling this script for it.

What "touched" means
--------------------
Files under <app>/src with an extension the app's own `format:check` script covers (ts, html,
css, json; the two SCSS storefronts add scss) that were added, copied, modified or renamed
between the merge base of <base> and HEAD -- plus, so the same command works on a developer
machine, uncommitted and untracked ones. On a CI checkout the working tree equals HEAD, so
this is exactly the change under test. Deleted files are skipped; files matched by the app's
.prettierignore are skipped by prettier itself.

<base> is the branch the change will be merged into (`origin/main`). A base that is empty,
all zeros (a new branch) or not in the clone falls back to the parent of HEAD, so the
check still covers the tip commit instead of silently passing on nothing.

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
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Sequence

FRONTEND = Path(__file__).resolve().parents[1]
REPO = FRONTEND.parent

# The extensions in package.json's format:check glob ("src/**/*.{ts,html,css,json}"). This is the
# default; an app whose own format:check glob covers more (the SCSS storefronts add scss) is
# checked for what its glob says -- see extensions_for.
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


def extensions_for(app_dir: Path) -> tuple[str, ...]:
    """The file extensions this app's own `format:check` script covers.

    Read from the script's `{ts,html,...}` glob, so the changed-files check can never cover
    less (a file the app's script would flag but this misses) or more (a file it would not
    look at) than `npm run format:check` does. An app with no package.json, no such script or
    no brace glob gets EXTENSIONS.
    """
    try:
        scripts = json.loads((app_dir / "package.json").read_text(encoding="utf-8")).get("scripts", {})
    except (OSError, ValueError):
        return EXTENSIONS
    glob = re.search(r"\{([A-Za-z0-9,]+)\}", scripts.get("format:check", ""))
    if glob is None:
        return EXTENSIONS
    return tuple("." + extension for extension in glob.group(1).split(",") if extension)


def changed_files(repo: Path, app: str, base: str | None) -> tuple[list[str], str]:
    """Paths relative to the app directory, sorted, plus the base-resolution note."""
    source = f"{app}/src"
    revision, note = resolve_base(repo, base)
    extensions = extensions_for(repo / app)
    tracked = _lines(git(repo, "diff", "--name-only", "-z", "--diff-filter=ACMR", revision, "--", source).stdout)
    untracked = _lines(git(repo, "ls-files", "--others", "--exclude-standard", "-z", "--", source).stdout)
    prefix = f"{app}/"
    files = sorted({path[len(prefix):] for path in tracked + untracked if path.endswith(extensions)})
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
        covered = "/".join(e.lstrip(".") for e in extensions_for(app_dir))
        print(f"format_changed: no {covered} file under frontend/{args.app}/src changed; nothing to check")
        return 0

    print(f"format_changed: checking {len(files)} changed file(s) in frontend/{args.app}")
    status = run_prettier(app_dir, files)
    if status != 0:
        print(
            "\nformat_changed: the [warn] files above are not prettier-formatted. Fix them with\n"
            f"  cd frontend/{args.app} && npx prettier --write <those files>\n"
            "(only files this change touched are checked here; `npm run format:check` checks the whole tree,"
            " which CI runs only for operations).",
            file=sys.stderr,
        )
    return status


if __name__ == "__main__":
    sys.exit(main())
