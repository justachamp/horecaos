#!/usr/bin/env python3
"""Agent-configuration regression suite.

CLAUDE.md, the policy skills, and the hooks are configuration: changing one
changes how every future session behaves, and nothing in the Maven build notices.
This suite is what notices.

Each case in cases/ poses a task whose correct handling is already decided by
AGENTS.md or an ADR, and asserts on what the agent says it would do. Cases run
read-only — the agent gets Read, Grep, and Glob, never Edit or Bash — so a case
can never mutate the repository.

    python3 evals/run.py                 # all cases
    python3 evals/run.py migration-*     # by id glob
    python3 evals/run.py --min-pass 0.9  # CI gate

Every case carries a `why`: the real incident, migration, or ADR it protects. A
case nobody can justify should be deleted, not carried.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import fnmatch
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CASES = Path(__file__).resolve().parent / "cases"

GREEN, RED, YELLOW, DIM, OFF = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"

# The CLI failed to run at all — not the agent getting a policy question wrong.
INFRA_ERROR = re.compile(
    r"(Not logged in[^\n]*)"
    r"|(is not a model this version[^\n]*)"
    r"|(unrecognized_model)"
    r"|(Credit balance is too low[^\n]*)"
    r"|(rate.?limit[^\n]*)"
    r"|(Invalid API key[^\n]*)"
    r"|(ANTHROPIC_API_KEY[^\n]*not[^\n]*)",
    re.I,
)


def load_cases(patterns: list[str]) -> list[dict]:
    cases = []
    for path in sorted(CASES.glob("*.json")):
        case = json.loads(path.read_text())
        case.setdefault("id", path.stem)
        if patterns and not any(fnmatch.fnmatch(case["id"], p) for p in patterns):
            continue
        cases.append(case)
    return cases


def run_case(case: dict, timeout: int) -> dict:
    tools = case.get("tools", "Read,Grep,Glob")
    cmd = [
        "claude", "-p", case["prompt"],
        "--allowedTools", tools,
        "--permission-mode", "plan",
    ]
    started = time.monotonic()
    try:
        proc = subprocess.run(
            cmd, cwd=ROOT, capture_output=True, text=True, timeout=timeout
        )
        output = proc.stdout + proc.stderr
        error = None if proc.returncode == 0 else f"exit {proc.returncode}"
    except subprocess.TimeoutExpired:
        output, error = "", f"timed out after {timeout}s"
    except FileNotFoundError:
        output, error = "", "claude CLI not found"

    elapsed = time.monotonic() - started
    reasons: list[str] = []

    # An environment failure is not a policy failure. Reporting "the agent got the
    # migration rule wrong" when the CLI never authenticated would make the whole
    # suite untrustworthy, so these are surfaced as errors, never as FAIL.
    infra = INFRA_ERROR.search(output or "")
    if infra:
        return {**case, "passed": False, "error": infra.group(0).strip(),
                "reasons": [], "elapsed": elapsed, "output": output}

    if error and not output.strip():
        return {**case, "passed": False, "error": error, "reasons": [],
                "elapsed": elapsed, "output": output}

    for pattern in case.get("expect_all", []):
        if not re.search(pattern, output, re.I | re.S):
            reasons.append(f"missing: /{pattern}/")

    any_of = case.get("expect_any", [])
    if any_of and not any(re.search(p, output, re.I | re.S) for p in any_of):
        reasons.append("none of: " + ", ".join(f"/{p}/" for p in any_of))

    for pattern in case.get("forbid", []):
        if re.search(pattern, output, re.I | re.S):
            reasons.append(f"forbidden: /{pattern}/")

    return {**case, "passed": not reasons, "reasons": reasons, "elapsed": elapsed,
            "output": output}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("patterns", nargs="*", help="case id globs")
    ap.add_argument("--min-pass", type=float, default=1.0,
                    help="fail below this pass rate (default 1.0)")
    ap.add_argument("--timeout", type=int, default=300, help="per-case seconds")
    ap.add_argument("--jobs", type=int, default=4, help="cases in parallel")
    ap.add_argument("--verbose", action="store_true", help="print agent output")
    args = ap.parse_args()

    if not shutil.which("claude"):
        print(f"{YELLOW}skipped{OFF}: the claude CLI is not on PATH.")
        print("These evals exercise agent behaviour, so they need it. The "
              "deterministic checks run without it:")
        print("  make lint    # repository rules and guardrail hooks")
        return 0

    cases = load_cases(args.patterns)
    if not cases:
        print("No cases matched.")
        return 1

    print(f"Running {len(cases)} eval case(s) against the current agent configuration.\n")
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(run_case, c, args.timeout): c for c in cases}
        for future in concurrent.futures.as_completed(futures):
            r = future.result()
            results.append(r)
            if r.get("error"):
                mark = f"{YELLOW}ERROR{OFF}"
            elif r["passed"]:
                mark = f"{GREEN}pass{OFF} "
            else:
                mark = f"{RED}FAIL{OFF} "
            print(f"{mark} {r['id']:<28} {r['elapsed']:5.1f}s")
            if r.get("error"):
                print(f"        {r['error']}")
            elif not r["passed"]:
                for reason in r["reasons"]:
                    print(f"        {reason}")
                print(f"        {DIM}why this case exists: {r.get('why', '—')}{OFF}")
                if args.verbose:
                    print(f"{DIM}{r['output'][:2000]}{OFF}")

    errored = [r for r in results if r.get("error")]
    if errored:
        print(
            f"\n{YELLOW}{len(errored)} case(s) could not run.{OFF} This is an "
            "environment problem, not a policy result — the suite is inconclusive, "
            "so no pass rate is reported."
        )
        print(f"  first error: {errored[0]['error']}")
        print("  the claude CLI must be authenticated and on a supported model.")
        return 2

    passed = sum(1 for r in results if r["passed"])
    rate = passed / len(results)
    print(f"\n{passed}/{len(results)} passed ({rate:.0%}); gate is {args.min_pass:.0%}")

    if rate < args.min_pass:
        print(
            f"\n{RED}Below the gate.{OFF} A drop usually means a configuration change "
            "removed guidance something depended on — check the diff to CLAUDE.md, "
            ".claude/skills/, and .claude/hooks/ before merging."
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
