#!/usr/bin/env python3
"""Tests for how platform/Makefile reaches Maven.

Every Maven invocation in this single-module project compiles into the same
target/, so the Makefile sends them through tools/mvn-serial, which holds a
per-worktree lock for as long as the command runs. That is right for a build
and wrong for `make run`, whose Maven process is the server: held for its whole
life, the lock would keep `make test` and `make arch` queued behind a dev
server, and mvn-serial's stale limit would then break it after 30 minutes
anyway, so the queueing would buy nothing. `make run` therefore compiles under
the lock and serves outside it.

These read the recipes with `make -n` (print, do not run), from a directory that
is not platform/, so they also prove the recipes do not depend on the caller's
working directory. No JVM, no Docker.

Run with `make makefile-test`, from CI's lint job, or directly.
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

PLATFORM = Path(__file__).resolve().parents[2]
MAKEFILE = PLATFORM / "Makefile"
MVN_SERIAL = str(PLATFORM / "tools" / "mvn-serial")


def recipe(target: str, *overrides: str) -> list[str]:
    """The commands `make <target>` would run, in order, from a foreign cwd."""
    with tempfile.TemporaryDirectory() as elsewhere:
        result = subprocess.run(
            ["make", "-n", "-f", str(MAKEFILE), target, *overrides],
            cwd=elsewhere,
            capture_output=True,
            text=True,
            check=True,
        )
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


class MavenTargetsRunUnderTheBuildLock(unittest.TestCase):
    def test_build_and_test_targets_go_through_mvn_serial(self):
        for target in ("verify", "test", "arch", "build", "format"):
            with self.subTest(target=target):
                commands = recipe(target)
                self.assertTrue(commands, f"make {target} prints no command")
                for command in commands:
                    self.assertTrue(
                        command.startswith(MVN_SERIAL + " "),
                        f"make {target} reaches Maven without the lock: {command}",
                    )

    def test_the_lock_can_still_be_turned_off_for_a_container_that_builds_once(self):
        commands = recipe("test", "MVN=./mvnw")

        self.assertEqual(len(commands), 1)
        self.assertTrue(commands[0].startswith("./mvnw "), commands[0])


class RunDoesNotHoldTheLockWhileServing(unittest.TestCase):
    def test_run_compiles_under_the_lock_and_then_serves_without_it(self):
        commands = recipe("run")

        self.assertEqual(len(commands), 2, commands)
        compile_step, serve_step = commands
        self.assertTrue(compile_step.startswith(MVN_SERIAL + " "), compile_step)
        self.assertIn("test-compile", compile_step)
        self.assertNotIn(
            "mvn-serial",
            serve_step,
            "the server's Maven must not sit inside the lock for as long as it is up",
        )
        self.assertIn("./mvnw spring-boot:run", serve_step)
        self.assertIn("-Dspring-boot.run.profiles=local", serve_step)

    def test_run_finds_the_project_from_any_working_directory(self):
        serve_step = recipe("run")[-1]

        self.assertTrue(
            serve_step.startswith(f"cd {PLATFORM}/ && "),
            f"the server step must cd to platform/ first, as mvn-serial does: {serve_step}",
        )


if __name__ == "__main__":
    unittest.main(argv=[sys.argv[0], "-v"] if "-v" in sys.argv else sys.argv[:1])
