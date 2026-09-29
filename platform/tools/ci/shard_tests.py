#!/usr/bin/env python3
"""Deterministic partition of the platform's test classes into CI shards.

Why this exists
---------------
The Java job ran every test class on one runner and took about eighty minutes.
.github/workflows/ci.yml now runs N shards in parallel, and this script is what
decides which shard runs which class. The property everything else rests on is
that every test class runs in exactly one job: a class that lands in no shard is
a test that never runs and never fails, and nothing else would notice.

How the partition works
-----------------------
* Test classes are exactly the files Surefire would pick by default under
  src/test/java: Test*.java, *Test.java, *Tests.java, *TestCase.java (nested
  classes are reached through their outer class, as Surefire does).
* Classes listed in tools/ci/gate-tests.txt are removed from the shards and run in
  the one job that also owns the non-test verify steps (OpenAPI needs the
  document its contract test writes).
* The rest are assigned by longest-processing-time-first over recorded durations
  (tools/ci/test-durations.tsv), ties broken by class name. A class with no
  recorded duration gets the median, so a brand-new test is still assigned and
  the plan is a pure function of (source tree, durations file, shard count):
  no clock, no randomness, no dependence on directory listing order.
* Shard i receives a Surefire include file (-Dsurefire.includesFile). An EMPTY
  include file makes Surefire fall back to its defaults and run the whole suite,
  so the script refuses to write one.

After the run, `verify-reports` compares what the shards actually executed (the
Surefire XML each one uploads) against the plan. That closes the gap between "the
plan is exact" and "the runners did what the plan said".

Commands
--------
  check            plan for the workflow's shard count; fail unless it is exact
  plan             print the plan (classes and estimated seconds per shard)
  list             print every discovered test class
  includes         write one shard's (or the gate's) Surefire include file
  verify-reports   compare downloaded Surefire reports against the plan
  record           rebuild tools/ci/test-durations.tsv from Surefire reports

Run `python3 tools/ci/shard_tests.py <command> --help`, or `make shards-test` for
the tests. Standard library only, so it runs on a bare runner.
"""
from __future__ import annotations

import argparse
import datetime
import re
import statistics
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Sequence

HERE = Path(__file__).resolve().parent
PLATFORM = HERE.parents[1]
TEST_ROOT = PLATFORM / "src" / "test" / "java"
DURATIONS_FILE = HERE / "test-durations.tsv"
GATE_FILE = HERE / "gate-tests.txt"
WORKFLOW_FILE = PLATFORM.parent / ".github" / "workflows" / "ci.yml"

GATE_DIR = "test-results-gate"


def shard_dir(index: int) -> str:
    return f"test-results-shard-{index}"


# Surefire's defaults (maven-surefire-plugin `includes`), less its `**/*$*` exclusion.
_NAME_PATTERNS = (
    re.compile(r"^Test.*\.java$"),
    re.compile(r"^.*Test\.java$"),
    re.compile(r"^.*Tests\.java$"),
    re.compile(r"^.*TestCase\.java$"),
)
_TEST_ANNOTATION = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")
_EXTENDS = re.compile(r"\bclass\s+\w+(?:<[^>{]*>)?\s+extends\s+(\w+)")


class PartitionError(Exception):
    """The partition is not exact, or an input to it is unusable."""


@dataclass(frozen=True)
class TestClass:
    __test__ = False  # not a pytest/unittest collectable, despite the name

    fqcn: str
    rel: str  # posix path of the .java file under the test root
    expects_report: bool  # has (or inherits) test methods, so Surefire must write a report


@dataclass
class Plan:
    shards: list[list[TestClass]]
    gate: list[TestClass]
    loads: list[float] = field(default_factory=list)
    default_seconds: float = 0.0


# ----------------------------------------------------------------------------
# Discovery
# ----------------------------------------------------------------------------


def _matches_surefire_defaults(name: str) -> bool:
    return any(p.match(name) for p in _NAME_PATTERNS)


def discover(test_root: Path) -> list[TestClass]:
    """Every class Surefire would run by default, sorted by fully-qualified name."""
    sources: dict[str, str] = {}
    files: list[Path] = []
    for path in sorted(test_root.rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        sources[path.stem] = text
        if _matches_surefire_defaults(path.name) and "$" not in path.name:
            files.append(path)

    def has_tests(simple_name: str, seen: frozenset[str]) -> bool:
        text = sources.get(simple_name)
        if text is None or simple_name in seen:
            return False
        if _TEST_ANNOTATION.search(text):
            return True
        parent = _EXTENDS.search(text)
        return bool(parent) and has_tests(parent.group(1), seen | {simple_name})

    found: list[TestClass] = []
    for path in files:
        rel = path.relative_to(test_root).as_posix()
        fqcn = rel[: -len(".java")].replace("/", ".")
        found.append(TestClass(fqcn=fqcn, rel=rel, expects_report=has_tests(path.stem, frozenset())))
    return sorted(found, key=lambda c: c.fqcn)


def load_durations(path: Path) -> dict[str, float]:
    """`fqcn<TAB>seconds` rows; blank lines and `#` comments are ignored."""
    durations: dict[str, float] = {}
    if not path.exists():
        return durations
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 2:
            raise PartitionError(f"{path.name}:{number}: expected `class<TAB>seconds`, got {raw!r}")
        try:
            seconds = float(parts[1])
        except ValueError as error:
            raise PartitionError(f"{path.name}:{number}: {parts[1]!r} is not a number of seconds") from error
        if seconds < 0:
            raise PartitionError(f"{path.name}:{number}: a negative duration")
        durations[parts[0]] = seconds
    return durations


def load_gate(path: Path) -> set[str]:
    if not path.exists():
        return set()
    return {
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#")
    }


# ----------------------------------------------------------------------------
# Partition
# ----------------------------------------------------------------------------


def plan(classes: Sequence[TestClass], shards: int, durations: dict[str, float], gate: set[str]) -> Plan:
    if shards < 1:
        raise PartitionError("at least one shard is required")
    by_name = {c.fqcn: c for c in classes}
    missing_gate = sorted(gate - by_name.keys())
    if missing_gate:
        raise PartitionError(
            "tools/ci/gate-tests.txt names classes that do not exist (renamed or deleted?): " + ", ".join(missing_gate)
        )
    gate_classes = sorted((by_name[name] for name in gate), key=lambda c: c.fqcn)
    sharded = sorted((c for c in classes if c.fqcn not in gate), key=lambda c: c.fqcn)
    if len(sharded) < shards:
        raise PartitionError(
            f"{len(sharded)} test classes cannot fill {shards} shards; "
            "an empty include file would make Surefire run the whole suite"
        )

    known = sorted(durations[c.fqcn] for c in sharded if c.fqcn in durations)
    default_seconds = statistics.median(known) if known else 1.0
    # Integer milliseconds: the assignment must not hinge on float summation order.
    weighted = sorted(
        ((max(1, round(durations.get(c.fqcn, default_seconds) * 1000)), c) for c in sharded),
        key=lambda pair: (-pair[0], pair[1].fqcn),
    )
    loads = [0] * shards
    buckets: list[list[TestClass]] = [[] for _ in range(shards)]
    for weight, cls in weighted:
        target = min(range(shards), key=lambda i: (loads[i], len(buckets[i]), i))
        buckets[target].append(cls)
        loads[target] += weight
    return Plan(
        shards=[sorted(b, key=lambda c: c.fqcn) for b in buckets],
        gate=gate_classes,
        loads=[load / 1000 for load in loads],
        default_seconds=default_seconds,
    )


def assert_exact_partition(classes: Sequence[TestClass], the_plan: Plan) -> None:
    """Every discovered class is in exactly one shard or the gate; nothing else is."""
    placed: dict[str, list[str]] = {}
    for index, shard in enumerate(the_plan.shards, start=1):
        for cls in shard:
            placed.setdefault(cls.fqcn, []).append(f"shard {index}")
    for cls in the_plan.gate:
        placed.setdefault(cls.fqcn, []).append("the gate job")

    known = {c.fqcn for c in classes}
    problems: list[str] = []
    for name in sorted(known - placed.keys()):
        problems.append(f"{name} is in no shard, so it would never run")
    for name, where in sorted(placed.items()):
        if len(where) > 1:
            problems.append(f"{name} is in more than one job ({', '.join(where)})")
        if name not in known:
            problems.append(f"{name} is planned but is not a discovered test class")
    if problems:
        raise PartitionError("the test partition is not exact:\n  " + "\n  ".join(problems))


def includes_lines(classes: Iterable[TestClass]) -> list[str]:
    return sorted(c.rel for c in classes)


# ----------------------------------------------------------------------------
# Reports
# ----------------------------------------------------------------------------


def _read_suite(report: Path) -> tuple[str, float]:
    """(outer class name, seconds) of one Surefire XML report."""
    try:
        root = ET.parse(report).getroot()
    except ET.ParseError as error:
        raise PartitionError(f"{report}: not a readable Surefire report ({error})") from error
    suite = root.find("testsuite") if root.tag == "testsuites" else root
    if suite is None:
        suite = root
    name = suite.get("name") or report.name[len("TEST-") : -len(".xml")]
    try:
        seconds = float((suite.get("time") or "0").replace(",", ""))
    except ValueError:
        seconds = 0.0
    return name.split("$", 1)[0], seconds


def _reports_in(directory: Path) -> dict[str, float]:
    ran: dict[str, float] = {}
    for report in sorted(directory.rglob("TEST-*.xml")):
        name, seconds = _read_suite(report)
        ran[name] = ran.get(name, 0.0) + seconds
    return ran


def verify_reports(the_plan: Plan, reports: Path) -> list[str]:
    """Compare what each job executed with what the plan assigned it."""
    assigned: dict[str, str] = {}
    for index, shard in enumerate(the_plan.shards, start=1):
        for cls in shard:
            assigned[cls.fqcn] = shard_dir(index)
    for cls in the_plan.gate:
        assigned[cls.fqcn] = GATE_DIR
    label = {shard_dir(i): f"shard {i}" for i in range(1, len(the_plan.shards) + 1)}
    label[GATE_DIR] = "the gate job"

    problems: list[str] = []
    ran_in: dict[str, list[str]] = {}
    for directory in [shard_dir(i) for i in range(1, len(the_plan.shards) + 1)] + ([GATE_DIR] if the_plan.gate else []):
        where = reports / directory
        ran = _reports_in(where) if where.is_dir() else {}
        if not ran:
            problems.append(f"{label[directory]} produced no surefire reports ({where})")
        for name in ran:
            ran_in.setdefault(name, []).append(directory)

    expected = {c.fqcn for shard in the_plan.shards for c in shard if c.expects_report}
    expected |= {c.fqcn for c in the_plan.gate if c.expects_report}
    for name in sorted(expected):
        if name not in ran_in:
            problems.append(f"{name} was assigned to {label[assigned[name]]} but never ran")
    for name, directories in sorted(ran_in.items()):
        if name not in assigned:
            problems.append(f"{name} ran in {label[directories[0]]} but is not in the partition")
            continue
        for directory in directories:
            if directory != assigned[name]:
                problems.append(f"{name} ran in {label[directory]} but is assigned to {label[assigned[name]]}")
    return problems


def record_durations(directories: Iterable[Path]) -> dict[str, float]:
    """Sum Surefire's per-suite time by outer class across every directory."""
    total: dict[str, float] = {}
    for directory in directories:
        for name, seconds in _reports_in(directory).items():
            total[name] = total.get(name, 0.0) + seconds
    return {name: round(seconds, 3) for name, seconds in total.items()}


# ----------------------------------------------------------------------------
# Workflow wiring
# ----------------------------------------------------------------------------


def workflow_shard_count(workflow: Path) -> int:
    match = re.search(r'^\s*TEST_SHARDS:\s*"?(\d+)"?\s*$', workflow.read_text(encoding="utf-8"), re.MULTILINE)
    if not match:
        raise PartitionError(f"{workflow} does not define TEST_SHARDS")
    return int(match.group(1))


def workflow_jobs(text: str) -> dict[str, str]:
    """Job id -> its YAML block, for the `jobs:` mapping (enough for wiring checks)."""
    lines = text.splitlines()
    try:
        start = next(i for i, line in enumerate(lines) if line.rstrip() == "jobs:")
    except StopIteration as error:
        raise PartitionError("workflow has no `jobs:` mapping") from error
    jobs: dict[str, list[str]] = {}
    current: str | None = None
    for line in lines[start + 1 :]:
        if line and not line.startswith(" "):
            break  # next top-level key
        header = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
        if header:
            current = header.group(1)
            jobs[current] = []
        elif current is not None:
            jobs[current].append(line)
    return {name: "\n".join(body) for name, body in jobs.items()}


# ----------------------------------------------------------------------------
# CLI
# ----------------------------------------------------------------------------


def _summary(the_plan: Plan) -> str:
    rows = []
    for index, (shard, load) in enumerate(zip(the_plan.shards, the_plan.loads), start=1):
        rows.append(f"  shard {index}: {len(shard):4d} classes, ~{load / 60:6.1f} min of recorded time")
    if the_plan.gate:
        rows.append(f"  gate   : {len(the_plan.gate):4d} classes ({', '.join(c.fqcn.rsplit('.', 1)[-1] for c in the_plan.gate)})")
    rows.append(f"  classes without a recorded duration are weighted {the_plan.default_seconds:.2f}s (the median)")
    return "\n".join(rows)


def _build(args: argparse.Namespace) -> tuple[list[TestClass], Plan]:
    classes = discover(args.tests)
    the_plan = plan(classes, args.shards, load_durations(args.durations), load_gate(args.gate_file))
    return classes, the_plan


def _shards_default() -> int:
    return workflow_shard_count(WORKFLOW_FILE)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0])
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--tests", type=Path, default=TEST_ROOT, help="test source root")
    common.add_argument("--durations", type=Path, default=DURATIONS_FILE)
    common.add_argument("--gate-file", type=Path, default=GATE_FILE)
    common.add_argument("--shards", type=int, default=None, help="shard count (default: TEST_SHARDS in ci.yml)")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("check", parents=[common], help="plan and fail unless the partition is exact")
    sub.add_parser("plan", parents=[common], help="print the plan")
    sub.add_parser("list", parents=[common], help="print every discovered test class")
    inc = sub.add_parser("includes", parents=[common], help="write a Surefire include file")
    target = inc.add_mutually_exclusive_group(required=True)
    target.add_argument("--index", type=int, help="1-based shard number")
    target.add_argument("--gate", action="store_true", help="the classes the gate job runs")
    inc.add_argument("--out", type=Path, required=True)
    ver = sub.add_parser("verify-reports", parents=[common], help="compare Surefire reports with the plan")
    ver.add_argument("--reports", type=Path, required=True, help=f"directory holding {shard_dir(1)}, ..., {GATE_DIR}")
    rec = sub.add_parser("record", help="rebuild the durations file from Surefire reports")
    rec.add_argument("--reports", type=Path, nargs="+", required=True)
    rec.add_argument("--out", type=Path, default=DURATIONS_FILE)
    rec.add_argument("--tests", type=Path, default=TEST_ROOT)

    args = parser.parse_args(argv)
    try:
        if args.command == "record":
            existing = {c.fqcn for c in discover(args.tests)}
            recorded = {n: s for n, s in record_durations(args.reports).items() if n in existing}
            body = "".join(f"{name}\t{seconds}\n" for name, seconds in sorted(recorded.items()))
            args.out.parent.mkdir(parents=True, exist_ok=True)
            args.out.write_text(
                "# Recorded Surefire seconds per test class; weights for tools/ci/shard_tests.py.\n"
                f"# Recorded {datetime.date.today().isoformat()} from {len(recorded)} classes.\n"
                "# Rebuild: download the CI run's test-results-* artifacts (the aggregator also\n"
                "# uploads a ready-made test-durations artifact), then\n"
                "#   python3 tools/ci/shard_tests.py record --reports <dir>/test-results-*\n"
                "# Stale or missing rows only make the shards less even; they never change what runs.\n" + body,
                encoding="utf-8",
            )
            print(f"wrote {len(recorded)} durations to {args.out}")
            return 0

        if args.shards is None:
            args.shards = _shards_default()
        classes, the_plan = _build(args)

        if args.command == "list":
            for cls in classes:
                print(cls.fqcn)
        elif args.command == "plan":
            print(_summary(the_plan))
        elif args.command == "check":
            assert_exact_partition(classes, the_plan)
            stale = sorted(set(load_durations(args.durations)) - {c.fqcn for c in classes})
            print(f"ok: {len(classes)} test classes, each in exactly one of {args.shards} shards or the gate")
            print(_summary(the_plan))
            if stale:
                print(f"note: {len(stale)} rows in {args.durations.name} name classes that no longer exist")
        elif args.command == "includes":
            chosen = the_plan.gate if args.gate else the_plan.shards[args.index - 1] if 1 <= args.index <= args.shards else None
            if chosen is None:
                raise PartitionError(f"--index must be between 1 and {args.shards}")
            lines = includes_lines(chosen)
            if not lines:
                raise PartitionError("refusing to write an empty include file: Surefire would run every test")
            args.out.parent.mkdir(parents=True, exist_ok=True)
            which = "gate" if args.gate else f"shard {args.index} of {args.shards}"
            args.out.write_text(f"# {which}: {len(lines)} test classes (generated by tools/ci/shard_tests.py)\n" + "\n".join(lines) + "\n", encoding="utf-8")
            print(f"{which}: {len(lines)} test classes -> {args.out}")
        elif args.command == "verify-reports":
            assert_exact_partition(classes, the_plan)
            problems = verify_reports(the_plan, args.reports)
            if problems:
                print("the shards did not run what the partition assigned:", file=sys.stderr)
                for problem in problems:
                    print(f"  {problem}", file=sys.stderr)
                return 1
            ran = sum(len(s) for s in the_plan.shards) + len(the_plan.gate)
            print(f"ok: all {ran} assigned test classes reported in exactly the job they were assigned to")
        return 0
    except PartitionError as error:
        print(f"shard_tests: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
