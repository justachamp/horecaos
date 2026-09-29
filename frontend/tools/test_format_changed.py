#!/usr/bin/env python3
"""Tests for format_changed.py and for how ci.yml wires the frontend hygiene steps.

Run: python3 frontend/tools/test_format_changed.py

The selection logic is exercised against throwaway git repositories, so no Node.js is
needed; the prettier invocation itself is proved by a local dry run (see the README's
"Formatting" section) and, in CI, by the step that runs the script.
"""
from __future__ import annotations

import contextlib
import io
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import format_changed as fc  # noqa: E402

REPO = HERE.parents[1]
WORKFLOW = REPO / ".github" / "workflows" / "ci.yml"
APP = "frontend/operations"


class TempRepo:
    """A git repository in a temp directory, driven through plain git commands."""

    def __init__(self) -> None:
        self._dir = tempfile.TemporaryDirectory()
        self.path = Path(self._dir.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "ci@example.invalid")
        self.git("config", "user.name", "CI")
        self.git("config", "commit.gpgsign", "false")

    def git(self, *args: str) -> str:
        done = subprocess.run(["git", "-C", str(self.path), *args], capture_output=True, text=True)
        if done.returncode != 0:
            raise AssertionError(f"git {' '.join(args)}: {done.stderr}")
        return done.stdout.strip()

    def write(self, rel: str, text: str = "x\n") -> None:
        target = self.path / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def commit(self, message: str = "c") -> str:
        self.git("add", "-A")
        self.git("commit", "-q", "--allow-empty", "-m", message)
        return self.git("rev-parse", "HEAD")

    def close(self) -> None:
        self._dir.cleanup()


class ChangedFilesTests(unittest.TestCase):
    def setUp(self) -> None:
        self.repo = TempRepo()
        self.addCleanup(self.repo.close)

    def files(self, base: str | None) -> list[str]:
        return fc.changed_files(self.repo.path, APP, base)[0]

    def note(self, base: str | None) -> str:
        return fc.changed_files(self.repo.path, APP, base)[1]

    def test_lists_added_and_modified_source_files_of_the_app_only(self) -> None:
        r = self.repo
        r.write(f"{APP}/src/old.ts")
        r.write(f"{APP}/src/untouched.ts")
        r.write(f"{APP}/package.json", "{}\n")
        r.write("frontend/storefront/src/other.ts")
        base = r.commit("base")
        r.write(f"{APP}/src/old.ts", "y\n")
        r.write(f"{APP}/src/app/new.css")
        r.write(f"{APP}/src/app/new.html")
        r.write(f"{APP}/src/app/data.json", "{}\n")
        r.write(f"{APP}/src/app/new.spec.ts")
        r.write(f"{APP}/package.json", '{"a": 1}\n')  # outside src/
        r.write("frontend/storefront/src/other.ts", "y\n")  # another app
        r.write("platform/Makefile", "y\n")  # not frontend at all
        r.commit("change")
        self.assertEqual(
            ["src/app/data.json", "src/app/new.css", "src/app/new.html", "src/app/new.spec.ts", "src/old.ts"],
            self.files(base),
        )

    def test_ignores_extensions_the_format_check_does_not_cover(self) -> None:
        r = self.repo
        base = r.commit("base")
        for name in ("notes.md", "logo.svg", "font.woff2", "build.js", "styles.scss", "tsconfig.json.bak"):
            r.write(f"{APP}/src/{name}")
        r.commit("change")
        self.assertEqual([], self.files(base))

    def test_a_deleted_file_is_not_checked(self) -> None:
        r = self.repo
        r.write(f"{APP}/src/gone.ts")
        r.write(f"{APP}/src/kept.ts")
        base = r.commit("base")
        (r.path / APP / "src" / "gone.ts").unlink()
        r.write(f"{APP}/src/kept.ts", "y\n")
        r.commit("change")
        self.assertEqual(["src/kept.ts"], self.files(base))

    def test_a_renamed_file_is_checked_under_its_new_name(self) -> None:
        r = self.repo
        r.write(f"{APP}/src/before.ts", "a large enough body so git sees a rename\n" * 20)
        base = r.commit("base")
        r.git("mv", f"{APP}/src/before.ts", f"{APP}/src/after.ts")
        r.commit("rename")
        self.assertEqual(["src/after.ts"], self.files(base))

    def test_paths_with_spaces_survive(self) -> None:
        r = self.repo
        base = r.commit("base")
        r.write(f"{APP}/src/my component/my file.ts")
        r.commit("change")
        self.assertEqual(["src/my component/my file.ts"], self.files(base))

    def test_measures_from_the_merge_base_not_the_tip_of_the_base_branch(self) -> None:
        # A pull request must not be blamed for what main gained after it branched.
        r = self.repo
        r.write(f"{APP}/src/shared.ts")
        r.commit("fork point")
        r.git("checkout", "-q", "-b", "feature")
        r.write(f"{APP}/src/feature.ts")
        r.commit("feature work")
        r.git("checkout", "-q", "main")
        r.write(f"{APP}/src/shared.ts", "main edited the file the fork point had\n")
        r.write(f"{APP}/src/landed-on-main.ts")
        r.commit("main moved on")
        r.git("checkout", "-q", "feature")
        # A plain two-dot diff against main's tip would also list shared.ts.
        self.assertEqual(["src/feature.ts"], self.files("main"))
        self.assertEqual("", self.note("main"))

    def test_uncommitted_and_untracked_files_count_so_a_developer_can_dry_run(self) -> None:
        r = self.repo
        r.write(f"{APP}/src/tracked.ts")
        base = r.commit("base")
        r.write(f"{APP}/src/tracked.ts", "edited\n")
        r.write(f"{APP}/src/untracked.ts")
        self.assertEqual(["src/tracked.ts", "src/untracked.ts"], self.files(base))

    def test_a_git_ignored_file_is_not_a_change(self) -> None:
        r = self.repo
        r.write(".gitignore", "generated.ts\n")
        base = r.commit("base")
        r.write(f"{APP}/src/generated.ts")
        r.write(f"{APP}/src/real.ts")
        self.assertEqual(["src/real.ts"], self.files(base))

    def test_an_all_zero_base_falls_back_to_the_tip_commit_and_says_so(self) -> None:
        # `github.event.before` on the first push of a branch.
        r = self.repo
        r.write(f"{APP}/src/first.ts")
        r.commit("first")
        r.write(f"{APP}/src/second.ts")
        r.commit("second")
        self.assertEqual(["src/second.ts"], self.files(fc.NULL_SHA))
        self.assertIn("new branch", self.note(fc.NULL_SHA))

    def test_a_base_missing_from_the_clone_falls_back_to_the_tip_commit(self) -> None:
        # A force push replaces `before` with a commit this clone never fetched.
        r = self.repo
        r.write(f"{APP}/src/first.ts")
        r.commit("first")
        r.write(f"{APP}/src/second.ts")
        r.commit("second")
        missing = "1" * 40
        self.assertEqual(["src/second.ts"], self.files(missing))
        self.assertIn("not in this clone", self.note(missing))

    def test_an_empty_base_falls_back_to_the_tip_commit(self) -> None:
        r = self.repo
        r.write(f"{APP}/src/first.ts")
        r.commit("first")
        r.write(f"{APP}/src/second.ts")
        r.commit("second")
        self.assertEqual(["src/second.ts"], self.files(""))
        self.assertEqual(["src/second.ts"], self.files(None))
        self.assertIn("no base was given", self.note(None))

    def test_a_root_commit_with_no_base_checks_everything_rather_than_nothing(self) -> None:
        r = self.repo
        r.write(f"{APP}/src/a.ts")
        r.write(f"{APP}/src/b.css")
        r.commit("only commit")
        self.assertEqual(["src/a.ts", "src/b.css"], self.files(None))
        self.assertIn("no parent", self.note(None))

    def test_a_merge_commit_checked_against_its_first_parent_lists_the_merged_files(self) -> None:
        # The pull_request event checks out a merge commit of the PR into its base.
        r = self.repo
        r.write(f"{APP}/src/base.ts")
        r.commit("base")
        r.git("checkout", "-q", "-b", "pr")
        r.write(f"{APP}/src/pr.ts")
        r.commit("pr")
        r.git("checkout", "-q", "main")
        r.write(f"{APP}/src/main-only.ts")
        main_tip = r.commit("main")
        r.git("merge", "-q", "--no-ff", "-m", "merge", "pr")
        self.assertEqual(["src/pr.ts"], self.files(main_tip))


class CommandLineTests(unittest.TestCase):
    def run_main(self, *argv: str) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = fc.main(list(argv))
        return code, out.getvalue(), err.getvalue()

    def test_list_prints_the_files_and_runs_nothing(self) -> None:
        repo = TempRepo()
        self.addCleanup(repo.close)
        repo.write(f"{APP}/package.json", "{}\n")
        base = repo.commit("base")
        repo.write(f"{APP}/src/a.ts")
        repo.commit("change")
        code, out, _ = self.run_main("--app", "operations", "--base", base, "--repo", str(repo.path), "--list")
        self.assertEqual(0, code)
        self.assertEqual("src/a.ts\n", out)

    def test_nothing_changed_is_a_pass_with_a_message(self) -> None:
        repo = TempRepo()
        self.addCleanup(repo.close)
        repo.write(f"{APP}/package.json", "{}\n")
        base = repo.commit("base")
        repo.write("platform/Makefile")
        repo.commit("backend only")
        code, out, _ = self.run_main("--app", "operations", "--base", base, "--repo", str(repo.path))
        self.assertEqual(0, code)
        self.assertIn("nothing to check", out)

    def test_an_unknown_app_is_an_error_not_a_pass(self) -> None:
        repo = TempRepo()
        self.addCleanup(repo.close)
        repo.commit("empty")
        code, _, err = self.run_main("--app", "nope", "--repo", str(repo.path))
        self.assertEqual(2, code)
        self.assertIn("not a frontend app", err)

    def test_the_checked_extensions_match_the_apps_own_format_check_glob(self) -> None:
        scripts = json.loads((REPO / APP / "package.json").read_text(encoding="utf-8"))["scripts"]
        glob = re.search(r"\{([a-z,]+)\}", scripts["format:check"])
        self.assertIsNotNone(glob, scripts["format:check"])
        assert glob is not None
        self.assertEqual({"." + ext for ext in glob.group(1).split(",")}, set(fc.EXTENSIONS))


class WorkflowWiringTests(unittest.TestCase):
    """The steps that make lint and the format ratchet real must stay in ci.yml."""

    @classmethod
    def setUpClass(cls) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        match = re.search(r"^  frontend-build:\n(.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)", text, re.MULTILINE | re.DOTALL)
        assert match is not None, "ci.yml has no frontend-build job"
        cls.block = match.group(1)

    def step(self, name: str) -> str:
        found = re.search(rf"^      - name: {re.escape(name)}\n(.*?)(?=^      - |\Z)", self.block, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(found, f"frontend-build has no step named {name!r}")
        assert found is not None
        return found.group(1)

    def test_the_checkout_has_history_so_the_merge_base_exists(self) -> None:
        self.assertRegex(self.block, r"fetch-depth:\s*0\b")

    def test_operations_lint_runs_in_ci_and_only_for_operations(self) -> None:
        step = self.step("Lint (operations)")
        self.assertIn("if: matrix.app == 'operations'", step)
        self.assertIn("npm run lint", step)
        self.assertIn("npm run lint:rules", step)
        scripts = json.loads((REPO / APP / "package.json").read_text(encoding="utf-8"))["scripts"]
        self.assertIn("lint", scripts)
        self.assertIn("lint:rules", scripts)

    def test_the_format_check_looks_only_at_changed_files(self) -> None:
        step = self.step("Format check on changed files (operations)")
        self.assertIn("if: matrix.app == 'operations'", step)
        commands = step.split("run: |", 1)[1]  # the comments may name format:check; the commands must not run it
        self.assertIn("frontend/tools/format_changed.py --app operations", commands)
        self.assertIn('--base "$FORMAT_BASE"', commands)
        self.assertNotIn("format:check", commands, "the whole-tree check would fail on the unformatted backlog")
        self.assertIn("github.event.before", step)
        self.assertIn("github.base_ref", step)

    def test_lint_and_format_run_after_install_and_before_the_slow_tests(self) -> None:
        names = re.findall(r"^      - name: (.+)$", self.block, re.MULTILINE)
        order = {name: index for index, name in enumerate(names)}
        install = order["Install"]
        for gate in ("Lint (operations)", "Format check on changed files (operations)"):
            self.assertGreater(order[gate], install)
            self.assertLess(order[gate], order["Test and build"])

    def test_the_selection_tests_run_in_ci(self) -> None:
        self.assertIn("frontend/tools/test_format_changed.py", self.block)


if __name__ == "__main__":
    unittest.main()
