#!/usr/bin/env python3
"""Tests for format_changed.py and for how ci.yml wires the frontend hygiene steps.

Run: python3 frontend/tools/test_format_changed.py

The selection logic is exercised against throwaway git repositories, so no Node.js is
needed. The prettier invocation is exercised twice: against a stub `npx` and `prettier`
that record how they were called (so the exit status of a failing check, a passing one, a
missing install and a chunked run are all pinned without Node.js), and, when the app's
node_modules is installed, against the real prettier and the app's real config.
"""
from __future__ import annotations

import contextlib
import io
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import format_changed as fc  # noqa: E402

REPO = HERE.parents[1]
WORKFLOW = REPO / ".github" / "workflows" / "ci.yml"
APP = "frontend/operations"
APPS = ("operations", "control-plane", "storefront", "storefront-milliy")


def code(step: str) -> str:
    """A workflow step without its comment lines: what the runner reads, not what a reader is told."""
    return "\n".join(line for line in step.split("\n") if not line.strip().startswith("#"))


def run_lines(step: str) -> list[str]:
    """The commands of a workflow step's `run:`, one per line, without the step's comments.

    Handles `run: cmd` and a `run: |` block; blank lines and `#` lines inside the block are
    dropped. A substring test over the whole step also matches its comments, so the tests
    below look only at what would actually be executed.
    """
    lines = step.split("\n")
    for index, line in enumerate(lines):
        match = re.match(r"^        run:\s*(.*)$", line)
        if match is None:
            continue
        inline = match.group(1).strip()
        if inline not in ("|", "|-", ">", ">-"):
            return [inline]
        body: list[str] = []
        for following in lines[index + 1 :]:
            if following.strip() and not following.startswith("          "):
                break
            if following.strip() and not following.strip().startswith("#"):
                body.append(following.strip())
        return body
    return []


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


def run_cli(*argv: str) -> tuple[int, str, str]:
    """format_changed.main(argv) with stdout and stderr captured: (exit status, stdout, stderr)."""
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = fc.main(list(argv))
    return code, out.getvalue(), err.getvalue()


class CommandLineTests(unittest.TestCase):
    def run_main(self, *argv: str) -> tuple[int, str, str]:
        return run_cli(*argv)

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

    def test_the_checked_extensions_match_each_apps_own_format_check_glob(self) -> None:
        # One source of truth: the app's `npm run format:check` glob. A changed-files check
        # that covered fewer extensions would pass a file the app's own script rejects.
        for app in APPS:
            with self.subTest(app=app):
                scripts = json.loads((REPO / "frontend" / app / "package.json").read_text(encoding="utf-8"))["scripts"]
                glob = re.search(r"\{([a-z,]+)\}", scripts["format:check"])
                self.assertIsNotNone(glob, scripts["format:check"])
                assert glob is not None
                self.assertEqual(
                    {"." + ext for ext in glob.group(1).split(",")},
                    set(fc.extensions_for(REPO / "frontend" / app)),
                )

    def test_the_scss_storefronts_check_scss_and_operations_does_not(self) -> None:
        self.assertIn(".scss", fc.extensions_for(REPO / "frontend" / "storefront"))
        self.assertIn(".scss", fc.extensions_for(REPO / "frontend" / "storefront-milliy"))
        self.assertNotIn(".scss", fc.extensions_for(REPO / "frontend" / "operations"))

    def test_an_app_with_no_format_script_gets_the_default_extensions(self) -> None:
        r = TempRepo()
        self.addCleanup(r.close)
        r.write("frontend/bare/package.json", '{"scripts": {}}\n')
        self.assertEqual(fc.EXTENSIONS, fc.extensions_for(r.path / "frontend" / "bare"))
        self.assertEqual(fc.EXTENSIONS, fc.extensions_for(r.path / "frontend" / "missing"))
        r.write("frontend/broken/package.json", "{not json")
        self.assertEqual(fc.EXTENSIONS, fc.extensions_for(r.path / "frontend" / "broken"))

    def test_scss_is_listed_for_an_app_whose_format_check_covers_it(self) -> None:
        r = TempRepo()
        self.addCleanup(r.close)
        script = '{"scripts": {"format:check": "prettier --check \\"src/**/*.{ts,scss}\\""}}\n'
        r.write("frontend/styled/package.json", script)
        base = r.commit("base")
        r.write("frontend/styled/src/app/a.scss")
        r.write("frontend/styled/src/app/b.html")  # not in this app's glob
        r.commit("change")
        self.assertEqual(["src/app/a.scss"], fc.changed_files(r.path, "frontend/styled", base)[0])


STUB_PRETTIER = """#!/bin/sh
# Stands in for prettier: records each call, fails on a file that contains UNFORMATTED.
[ "$1" = "--check" ] || { echo "stub prettier: expected --check first, got: $*" >&2; exit 64; }
shift
echo "$*" >> "$(dirname "$0")/calls.log"
status=0
for file in "$@"; do
  if grep -q UNFORMATTED "$file"; then echo "[warn] $file" >&2; status=1; fi
done
exit $status
"""

STUB_NPX = """#!/bin/sh
# Stands in for npx: the script must run the LOCAL prettier and never download one.
[ "$1" = "--no-install" ] || { echo "stub npx: expected --no-install first, got: $*" >&2; exit 64; }
shift
tool="$1"
shift
exec "$PWD/node_modules/.bin/$tool" "$@"
"""


def write_executable(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR)


class PrettierGateTests(unittest.TestCase):
    """What the script does with prettier's verdict: the part that makes it a gate.

    A stub prettier fails on any file whose text contains UNFORMATTED and records every
    call in node_modules/.bin/calls.log. If run_prettier or main() stopped propagating a
    non-zero status, every "fails" test below would go red.
    """

    def setUp(self) -> None:
        self.repo = TempRepo()
        self.addCleanup(self.repo.close)
        self.repo.write(".gitignore", "node_modules/\nbin/\n")
        self.repo.write(f"{APP}/package.json", "{}\n")
        write_executable(self.repo.path / APP / "node_modules" / ".bin" / "prettier", STUB_PRETTIER)
        write_executable(self.repo.path / "bin" / "npx", STUB_NPX)
        self.base = self.repo.commit("base")
        path = f"{self.repo.path / 'bin'}{os.pathsep}{os.environ.get('PATH', '')}"
        patcher = mock.patch.dict(os.environ, {"PATH": path})
        patcher.start()
        self.addCleanup(patcher.stop)

    def check(self) -> tuple[int, str, str]:
        return run_cli("--app", "operations", "--base", self.base, "--repo", str(self.repo.path))

    def calls(self) -> list[str]:
        log = self.repo.path / APP / "node_modules" / ".bin" / "calls.log"
        return log.read_text(encoding="utf-8").splitlines() if log.exists() else []

    def test_an_unformatted_changed_file_fails_the_gate_and_says_how_to_fix_it(self) -> None:
        self.repo.write(f"{APP}/src/bad.ts", "UNFORMATTED\n")
        self.repo.commit("bad")
        code, out, err = self.check()
        self.assertEqual(1, code)
        self.assertIn("checking 1 changed file", out)
        self.assertIn("not prettier-formatted", err)
        self.assertIn("npx prettier --write", err)

    def test_a_formatted_changed_file_passes(self) -> None:
        self.repo.write(f"{APP}/src/good.ts", "formatted\n")
        self.repo.commit("good")
        code, _, err = self.check()
        self.assertEqual(0, code, err)
        self.assertEqual(["src/good.ts"], self.calls()[0].split())

    def test_only_the_changed_files_reach_prettier(self) -> None:
        # An unformatted file this change did not touch is not this change's problem here.
        self.repo.write(f"{APP}/src/backlog.ts", "UNFORMATTED\n")
        self.repo.commit("backlog")
        touched_base = self.repo.git("rev-parse", "HEAD")
        self.repo.write(f"{APP}/src/touched.ts", "formatted\n")
        self.repo.commit("touch")
        code, _, err = run_cli("--app", "operations", "--base", touched_base, "--repo", str(self.repo.path))
        self.assertEqual(0, code, err)
        self.assertEqual(["src/touched.ts"], self.calls()[0].split())

    def test_a_failure_in_any_chunk_fails_the_gate_and_every_chunk_still_runs(self) -> None:
        for index in range(5):
            self.repo.write(f"{APP}/src/f{index}.ts", "UNFORMATTED\n" if index == 0 else "formatted\n")
        self.repo.commit("five files")
        with mock.patch.object(fc, "CHUNK", 2):
            code, _, _ = self.check()
        self.assertEqual(1, code, "the failing file is in the first chunk; later chunks must not overwrite its status")
        self.assertEqual(3, len(self.calls()), "5 files in chunks of 2 is 3 prettier runs")

    def test_a_late_failing_chunk_fails_the_gate(self) -> None:
        for index in range(5):
            self.repo.write(f"{APP}/src/f{index}.ts", "UNFORMATTED\n" if index == 4 else "formatted\n")
        self.repo.commit("five files")
        with mock.patch.object(fc, "CHUNK", 2):
            code, _, _ = self.check()
        self.assertEqual(1, code)

    def test_a_missing_install_is_an_error_not_a_pass(self) -> None:
        shutil.rmtree(self.repo.path / APP / "node_modules")
        self.repo.write(f"{APP}/src/any.ts", "formatted\n")
        self.repo.commit("change")
        code, _, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("npm ci", err)

    def test_a_missing_npx_is_an_error_not_a_pass(self) -> None:
        self.repo.write(f"{APP}/src/any.ts", "formatted\n")
        self.repo.commit("change")
        with mock.patch.object(fc.shutil, "which", return_value=None):
            code, _, err = self.check()
        self.assertEqual(2, code)
        self.assertIn("npx is not on PATH", err)


REAL_PRETTIER = REPO / APP / "node_modules" / ".bin" / "prettier"


@unittest.skipUnless(REAL_PRETTIER.exists() and shutil.which("npx"), "needs `npm ci` in frontend/operations")
class RealPrettierTests(unittest.TestCase):
    """The same gate against the real prettier and the app's real .prettierrc.

    CI installs the app before it runs this file, so this is where the exact invocation
    (`npx --no-install prettier --check <files>`) is proved rather than assumed.
    """

    def setUp(self) -> None:
        self.repo = TempRepo()
        self.addCleanup(self.repo.close)
        app = self.repo.path / APP
        app.mkdir(parents=True)
        (app / "package.json").write_text("{}\n", encoding="utf-8")
        shutil.copy(REPO / APP / ".prettierrc", app / ".prettierrc")
        (app / "node_modules").symlink_to(REPO / APP / "node_modules", target_is_directory=True)
        self.repo.write(".gitignore", "node_modules\n")
        self.base = self.repo.commit("base")

    def check(self) -> int:
        return run_cli("--app", "operations", "--base", self.base, "--repo", str(self.repo.path))[0]

    def test_an_unformatted_file_fails_and_the_formatted_one_passes(self) -> None:
        self.repo.write(f"{APP}/src/ok.ts", "export const answer = 'x';\n")
        self.repo.commit("formatted")
        self.assertEqual(0, self.check())
        self.repo.write(f"{APP}/src/bad.ts", "export   const answer=\"x\"\n")
        self.repo.commit("unformatted")
        self.assertNotEqual(0, self.check())


OTHER_APPS = ("control-plane", "storefront", "storefront-milliy")
OTHER_LINT_STEP = "Lint (control-plane, storefront, storefront-milliy)"
OTHER_FORMAT_STEP = "Format check on changed files (control-plane, storefront, storefront-milliy)"


class WorkflowWiringTests(unittest.TestCase):
    """The steps that make lint and the format checks real must stay in ci.yml.

    operations: lint plus the whole-tree `npm run format:check`. The other three apps: lint
    plus the changed-files check (their trees are not prettier-clean yet, see format_changed.py).
    """

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

    def assert_only_for_operations(self, step: str) -> None:
        self.assertRegex(code(step), r"(?m)^        if: matrix\.app == 'operations'$")

    def assert_only_for_the_other_apps(self, step: str) -> None:
        self.assertRegex(code(step), r"(?m)^        if: matrix\.app != 'operations'$")

    def scripts(self, app: str) -> dict[str, str]:
        return json.loads((REPO / "frontend" / app / "package.json").read_text(encoding="utf-8"))["scripts"]

    def assert_runs_in_the_app_directory(self, step: str) -> None:
        self.assertRegex(code(step), r"(?m)^        working-directory: frontend/\$\{\{ matrix\.app \}\}$")

    def test_operations_lint_runs_in_ci_and_only_for_operations(self) -> None:
        # Each command must be a line of the step's `run:` block. The step's comments name
        # `npm run lint`, and `npm run lint:rules` contains it as a substring, so a substring
        # test over the step passes after the real `npm run lint` has been deleted -- which
        # leaves only the rule's own fixture run and lets a raw px font-size back in.
        step = self.step("Lint (operations)")
        self.assert_only_for_operations(step)
        self.assert_runs_in_the_app_directory(step)
        commands = run_lines(step)
        self.assertIn("npm run lint", commands)
        self.assertIn("npm run lint:rules", commands)
        scripts = json.loads((REPO / APP / "package.json").read_text(encoding="utf-8"))["scripts"]
        self.assertIn("lint", scripts)
        self.assertIn("lint:rules", scripts)

    def test_the_format_check_covers_the_whole_tree(self) -> None:
        # A changed-files-only check misses a file whose push was cancelled by a later one and
        # a `before` commit missing from the clone; the whole-tree check cannot.
        step = self.step("Format check (operations)")
        self.assert_only_for_operations(step)
        self.assert_runs_in_the_app_directory(step)
        self.assertEqual(["npm run format:check"], run_lines(step))
        self.assertNotIn("format_changed", code(step))
        self.assertNotIn("github.event.before", code(step))
        self.assertNotIn("github.base_ref", code(step))

    def test_lint_and_format_run_after_install_and_before_the_slow_tests(self) -> None:
        names = re.findall(r"^      - name: (.+)$", self.block, re.MULTILINE)
        order = {name: index for index, name in enumerate(names)}
        install = order["Install"]
        for gate in ("Lint (operations)", "Format check (operations)", OTHER_LINT_STEP, OTHER_FORMAT_STEP):
            self.assertGreater(order[gate], install)
            self.assertLess(order[gate], order["Test and build"])

    def test_every_other_app_lints_in_ci_with_a_rules_check(self) -> None:
        # `npm run lint` alone passes for a config that lost its rules; `lint:rules`
        # (frontend/tools/lint-config.test.mjs) is what fails then.
        step = self.step(OTHER_LINT_STEP)
        self.assert_only_for_the_other_apps(step)
        self.assert_runs_in_the_app_directory(step)
        self.assertEqual(["npm run lint", "npm run lint:rules"], run_lines(step))
        for app in OTHER_APPS:
            with self.subTest(app=app):
                scripts = self.scripts(app)
                self.assertTrue(scripts["lint"].startswith("eslint "), scripts["lint"])
                self.assertEqual("node ../tools/lint-config.test.mjs", scripts["lint:rules"])
                self.assertTrue((REPO / "frontend" / app / "eslint.config.mjs").is_file())

    def test_every_other_app_is_format_checked_on_the_files_a_change_touched(self) -> None:
        step = self.step(OTHER_FORMAT_STEP)
        self.assert_only_for_the_other_apps(step)
        self.assertEqual(
            ['python3 frontend/tools/format_changed.py --app ${{ matrix.app }} --base "$FORMAT_BASE"'],
            run_lines(step),
        )
        # The base is the PR's base branch, or on a push the tip the push replaced.
        self.assertIn("github.base_ref", code(step))
        self.assertIn("github.event.before", code(step))
        for app in OTHER_APPS:
            with self.subTest(app=app):
                scripts = self.scripts(app)
                self.assertTrue(scripts["format"].startswith("prettier --write "), scripts["format"])
                self.assertTrue(scripts["format:check"].startswith("prettier --check "), scripts["format:check"])

    def test_the_changed_files_check_can_see_the_merge_base(self) -> None:
        # A depth-1 checkout has no merge base, and format_changed.py would then fall back to
        # "every file" and fail on trees nobody has touched.
        checkout = re.search(r"- uses: actions/checkout@v4\n(.*?)(?=^      - )", self.block, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(checkout)
        assert checkout is not None
        self.assertRegex(code(checkout.group(1)), r"(?m)^          fetch-depth: 0$")

    def test_every_app_in_the_matrix_has_a_lint_and_a_format_gate(self) -> None:
        matrix = re.search(r"app: \[([^\]]+)\]", self.block)
        self.assertIsNotNone(matrix)
        assert matrix is not None
        apps = {name.strip() for name in matrix.group(1).split(",")}
        self.assertEqual(set(APPS), apps, "a new app in the matrix needs its own lint and format wiring")

    def test_control_plane_diffs_its_vendored_token_sheet_in_ci(self) -> None:
        # control-plane's lint config ignores src/design-system/tokens.css -- the one file
        # allowed a raw px font-size, because it is where the type scale is defined -- on the
        # strength of `npm run check:tokens` keeping it a copy of the canonical sheet. A guard
        # nothing runs leaves that file unlinted, unformatted and uncompared.
        step = self.step("Design tokens drift check (control-plane)")
        self.assertRegex(code(step), r"(?m)^        if: matrix\.app == 'control-plane'$")
        self.assert_runs_in_the_app_directory(step)
        self.assertEqual(["npm run check:tokens"], run_lines(step))
        self.assertEqual("node scripts/check-tokens.mjs", self.scripts("control-plane")["check:tokens"])
        # The script passes quietly when the sheet it compares against is absent, so the
        # sheet must be there for the step to mean anything.
        self.assertTrue((REPO / "frontend" / "design-tokens" / "tokens.css").is_file())
        names = re.findall(r"^      - name: (.+)$", self.block, re.MULTILINE)
        order = {name: index for index, name in enumerate(names)}
        self.assertGreater(order["Design tokens drift check (control-plane)"], order["Install"])
        self.assertLess(order["Design tokens drift check (control-plane)"], order["Test and build"])
        # The exemption names the guard; if the exemption goes, so may the step.
        config = (REPO / "frontend" / "control-plane" / "eslint.config.mjs").read_text(encoding="utf-8")
        self.assertIn("src/design-system/tokens.css", config)
        self.assertIn("check:tokens", config)

    def test_this_file_runs_in_ci_because_it_also_guards_the_wiring(self) -> None:
        step = self.step("Tooling tests (operations)")
        self.assert_only_for_operations(step)
        self.assertEqual(["python3 frontend/tools/test_format_changed.py"], run_lines(step))


if __name__ == "__main__":
    unittest.main()
