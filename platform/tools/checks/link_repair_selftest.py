#!/usr/bin/env python3
"""Prove that tools/file-adrs repairs a relative link of any shape.

The link repairer in `tools/file-adrs` exists because a move changes a record's
depth and breaks every relative link it carries. Its first version only matched
markdown targets, so a link to a migration `.sql` file or to a directory like
`../providers/` was invisible to it — the exact class of miss its own docstring
was written about, committed a second time inside the function meant to prevent
it. Three such links sat broken through runs that reported success.

`tools/checks/doc_links.py` would catch those three today, but only once they
are already committed and only if somebody reads the failure. This proves the
repairer's *coverage* instead: given a record whose links are all one `../`
short, every relative target is re-based whatever it points at, and nothing that
was already correct or was never ours to touch is disturbed.

The fixture is a throwaway tree in a temp directory, so this needs no database,
no JVM, and no particular state in the real repository.

Run directly, or as one of the checks in repo_hygiene.py (and so via `make lint`).
"""
from __future__ import annotations

import importlib.util
import re
import sys
import tempfile
from importlib.machinery import SourceFileLoader
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FILE_ADRS = ROOT / "tools" / "file-adrs"

GREEN, RED, DIM, OFF = "\033[32m", "\033[31m", "\033[2m", "\033[0m"


def load_file_adrs():
    """Import tools/file-adrs, which has no .py suffix because it is a command."""
    spec = importlib.util.spec_from_loader(
        "qoida_file_adrs", SourceFileLoader("qoida_file_adrs", str(FILE_ADRS))
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# Every link the fixture record carries, and what the repairer must make of it.
# `None` means "leave exactly as written".
#
# The record sits at docs/adr/partial/, and each broken link is written as if it
# still sat at docs/adr/ — one `../` short, which is precisely what re-filing a
# record a directory deeper does to it.
CASES: list[tuple[str, str, str | None]] = [
    # The defect this test exists for: targets with a non-markdown extension,
    # and targets with no extension at all.
    ("a migration",
     "../../src/main/resources/db/migration/V0001__x.sql",
     "../../../src/main/resources/db/migration/V0001__x.sql"),
    ("a directory, trailing slash kept",
     "../providers/", "../../providers/"),
    ("a directory, no trailing slash",
     "../providers", "../../providers"),
    ("an image",
     "../providers/logo.png", "../../providers/logo.png"),
    ("a file with no extension",
     "../providers/NOTES", "../../providers/NOTES"),

    # The markdown case the first version did handle, which must keep working.
    ("a markdown sibling", "TEMPLATE.md", "../TEMPLATE.md"),
    ("an anchor on a repaired link",
     "../providers/click.md#usage", "../../providers/click.md#usage"),

    # Left alone: already resolving, addressed at the world, or unresolvable.
    ("a link that already resolves", "../TEMPLATE.md", None),
    ("a link whose prefix matches a broken one", "../providers-note.md", None),
    ("an external URL", "https://example.invalid/docs/x.md", None),
    ("a mailto", "mailto:nobody@example.invalid", None),
    ("a bare anchor", "#a-heading", None),
    ("an absolute path", "/etc/hosts", None),
    ("a target that names nothing", "../nowhere/missing.txt", None),
]


def build_fixture(repo: Path) -> Path:
    """A miniature docs tree with one record whose links are all one ../ short."""
    (repo / "docs" / "adr" / "partial").mkdir(parents=True)
    (repo / "docs" / "providers").mkdir(parents=True)
    (repo / "src" / "main" / "resources" / "db" / "migration").mkdir(parents=True)

    (repo / "docs" / "adr" / "TEMPLATE.md").write_text("# template\n")
    (repo / "docs" / "adr" / "providers-note.md").write_text("# note\n")
    (repo / "docs" / "providers" / "click.md").write_text("# click\n")
    (repo / "docs" / "providers" / "logo.png").write_bytes(b"\x89PNG")
    (repo / "docs" / "providers" / "NOTES").write_text("notes\n")
    (repo / "src/main/resources/db/migration/V0001__x.sql").write_text("-- x\n")

    lines = ["# ADR 0001: fixture", "", "- Decision status: Accepted", ""]
    for name, target, _ in CASES:
        bang = "!" if "image" in name else ""
        lines.append(f"- {name}: {bang}[`{name}`]({target})")
    lines.append("")

    record = repo / "docs" / "adr" / "partial" / "0001-fixture.md"
    record.write_text("\n".join(lines), encoding="utf-8")
    return record


def targets_in(text: str) -> list[str]:
    return re.findall(r"\]\(([^)\s]+)\)", text)


def problems() -> list[str]:
    """Every way the repairer falls short of repairing all relative links."""
    found: list[str] = []
    adrs = load_file_adrs()

    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp) / "repo"
        repo.mkdir()
        record = build_fixture(repo)
        adr_root = repo / "docs" / "adr"

        repairs = adrs.outbound_repairs(adr_root, repo)
        if not repairs:
            return ["the repairer found nothing to fix in a record whose links "
                    "are all one ../ short"]
        for path, text, _ in repairs:
            path.write_text(text, encoding="utf-8")

        got = targets_in(record.read_text(encoding="utf-8"))
        want = [expected if expected is not None else target
                for _, target, expected in CASES]
        if len(got) != len(want):
            return [f"the repairer changed how many links the record has: "
                    f"{len(want)} written, {len(got)} after repair"]

        for (name, target, expected), actual in zip(CASES, got):
            wanted = expected if expected is not None else target
            if actual != wanted:
                verb = "left alone" if expected is None else f"repaired to {wanted}"
                found.append(f"{name}: {target} should be {verb}, got {actual}")

        # Every repaired link must actually resolve — a rewrite that is merely
        # different is not a repair.
        for _, target, expected in CASES:
            if expected is None:
                continue
            if not (record.parent / expected.split("#", 1)[0]).exists():
                found.append(f"repaired target does not exist: {expected}")

        # Idempotent: running again must find nothing, or a scheduled run would
        # rewrite the same links forever.
        if adrs.outbound_repairs(adr_root, repo):
            found.append("a second run wanted to change the record again")

    return found


def main() -> int:
    found = problems()
    name = "tools/file-adrs repairs relative links of every shape"
    if found:
        print(f"{RED}FAIL{OFF} {name}")
        for f in found:
            print(f"       {f}")
        return 1
    print(f"{GREEN}ok{OFF}   {name} ({len(CASES)} cases)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
