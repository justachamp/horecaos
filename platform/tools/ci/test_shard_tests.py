#!/usr/bin/env python3
"""Tests for the CI test-shard partition (tools/ci/shard_tests.py).

The shards exist so the Java job stops taking eighty minutes on one runner. The
one way that split can do real damage is silently: a class that lands in no
shard is a test that never runs and never fails. So the partition is tested
in both directions -- the property it must keep (every test class exactly once,
union == the full list) on a synthetic tree where each failure mode is planted
on purpose, and on the real src/test/java tree so the guarantee is about this
repository and not only about the algorithm.

Run with `make shards-test`, from CI's lint job, or directly.
"""
from __future__ import annotations

import random
import re
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import shard_tests as st  # noqa: E402  (path set up above)

PLATFORM = HERE.parents[1]
WORKFLOW = PLATFORM.parent / ".github" / "workflows" / "ci.yml"


def write(root: Path, rel: str, body: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")


JUNIT = "import org.junit.jupiter.api.Test;\nclass X { @Test void t() {} }\n"


def synthetic_tree(root: Path, count: int) -> list[str]:
    """A tree of `count` *Tests classes spread over a few packages."""
    names = []
    for i in range(count):
        pkg = f"uz/horecaos/platform/p{i % 4}"
        name = f"C{i:03d}Tests"
        write(root, f"{pkg}/{name}.java", JUNIT)
        names.append(f"{pkg.replace('/', '.')}.{name}")
    return sorted(names)


def report(directory: Path, fqcn: str, seconds: float = 1.0) -> None:
    write(
        directory,
        f"TEST-{fqcn}.xml",
        f'<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<testsuite name="{fqcn}" time="{seconds}" tests="1" errors="0" skipped="0" failures="0">'
        f'<testcase classname="{fqcn}" name="t" time="{seconds}"/></testsuite>\n',
    )


class DiscoveryTests(unittest.TestCase):
    def test_discovery_uses_surefires_default_patterns_and_nothing_else(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(root, "a/FooTests.java", JUNIT)
            write(root, "a/BarTest.java", JUNIT)
            write(root, "a/TestBaz.java", JUNIT)
            write(root, "a/QuxTestCase.java", JUNIT)
            write(root, "a/Helper.java", "class Helper {}\n")
            write(root, "a/FooIT.java", JUNIT)  # not a surefire default include
            write(root, "a/NotJava.txt", "FooTests")

            found = {c.fqcn for c in st.discover(root)}

            self.assertEqual({"a.FooTests", "a.BarTest", "a.TestBaz", "a.QuxTestCase"}, found)

    def test_a_helper_with_a_test_like_name_but_no_tests_expects_no_report(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(root, "support/TestDatabase.java", "class TestDatabase {}\n")
            write(root, "a/FooTests.java", JUNIT)

            by_name = {c.fqcn: c for c in st.discover(root)}

            self.assertFalse(by_name["support.TestDatabase"].expects_report)
            self.assertTrue(by_name["a.FooTests"].expects_report)

    def test_a_subclass_inherits_the_expectation_from_a_base_with_tests(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(root, "a/AbstractBase.java", JUNIT)
            write(root, "a/ChildTests.java", "class ChildTests extends AbstractBase {}\n")

            by_name = {c.fqcn: c for c in st.discover(root)}

            self.assertTrue(by_name["a.ChildTests"].expects_report)


class PartitionTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_every_class_lands_in_exactly_one_shard_for_every_shard_count(self) -> None:
        synthetic_tree(self.root, 47)
        classes = st.discover(self.root)
        for shards in (1, 2, 3, 4, 7, 13):
            with self.subTest(shards=shards):
                plan = st.plan(classes, shards, durations={}, gate=set())
                seen = [c.fqcn for shard in plan.shards for c in shard]
                self.assertEqual(sorted(c.fqcn for c in classes), sorted(seen))
                self.assertEqual(len(seen), len(set(seen)), "a class is in two shards")
                st.assert_exact_partition(classes, plan)

    def test_the_partition_does_not_depend_on_discovery_order(self) -> None:
        synthetic_tree(self.root, 40)
        classes = st.discover(self.root)
        durations = {c.fqcn: float((i * 7) % 23 + 1) for i, c in enumerate(classes)}
        baseline = st.plan(classes, 3, durations, gate=set())
        for seed in range(5):
            shuffled = list(classes)
            random.Random(seed).shuffle(shuffled)
            again = st.plan(shuffled, 3, durations, gate=set())
            self.assertEqual(
                [[c.fqcn for c in s] for s in baseline.shards],
                [[c.fqcn for c in s] for s in again.shards],
            )

    def test_heavy_classes_are_spread_by_recorded_duration(self) -> None:
        names = synthetic_tree(self.root, 9)
        durations = {n: 1.0 for n in names}
        heavy = names[:3]
        for n in heavy:
            durations[n] = 300.0
        plan = st.plan(st.discover(self.root), 3, durations, gate=set())
        for shard in plan.shards:
            self.assertEqual(1, sum(1 for c in shard if c.fqcn in heavy), "two heavy classes share a runner")

    def test_a_class_with_no_recorded_duration_gets_the_median_weight_and_is_still_assigned(self) -> None:
        names = synthetic_tree(self.root, 10)
        durations = {n: float(i + 1) for i, n in enumerate(names[:9])}  # names[9] is new
        classes = st.discover(self.root)
        plan = st.plan(classes, 3, durations, gate=set())
        self.assertIn(names[9], [c.fqcn for shard in plan.shards for c in shard])
        self.assertEqual(5.0, plan.default_seconds)

    def test_gate_classes_leave_the_shards_and_are_reported_separately(self) -> None:
        names = synthetic_tree(self.root, 12)
        gate = {names[4]}
        classes = st.discover(self.root)
        plan = st.plan(classes, 3, {}, gate=gate)
        self.assertEqual([names[4]], [c.fqcn for c in plan.gate])
        self.assertNotIn(names[4], [c.fqcn for shard in plan.shards for c in shard])
        st.assert_exact_partition(classes, plan)

    def test_a_gate_class_that_does_not_exist_is_refused(self) -> None:
        synthetic_tree(self.root, 6)
        with self.assertRaises(st.PartitionError) as caught:
            st.plan(st.discover(self.root), 2, {}, gate={"uz.horecaos.platform.Renamed"})
        self.assertIn("Renamed", str(caught.exception))

    def test_more_shards_than_classes_is_refused_because_an_empty_include_file_runs_everything(self) -> None:
        synthetic_tree(self.root, 2)
        with self.assertRaises(st.PartitionError):
            st.plan(st.discover(self.root), 3, {}, gate=set())

    def test_the_exactness_check_catches_a_dropped_class(self) -> None:
        synthetic_tree(self.root, 12)
        classes = st.discover(self.root)
        plan = st.plan(classes, 3, {}, gate=set())
        dropped = plan.shards[1].pop()
        with self.assertRaises(st.PartitionError) as caught:
            st.assert_exact_partition(classes, plan)
        self.assertIn(dropped.fqcn, str(caught.exception))
        self.assertIn("in no shard", str(caught.exception))

    def test_the_exactness_check_catches_a_duplicated_class(self) -> None:
        synthetic_tree(self.root, 12)
        classes = st.discover(self.root)
        plan = st.plan(classes, 3, {}, gate=set())
        twin = plan.shards[0][0]
        plan.shards[2].append(twin)
        with self.assertRaises(st.PartitionError) as caught:
            st.assert_exact_partition(classes, plan)
        self.assertIn(twin.fqcn, str(caught.exception))
        self.assertIn("more than one", str(caught.exception))

    def test_the_exactness_check_catches_a_class_in_a_shard_and_the_gate(self) -> None:
        synthetic_tree(self.root, 12)
        classes = st.discover(self.root)
        plan = st.plan(classes, 3, {}, gate=set())
        plan.gate.append(plan.shards[0][0])
        with self.assertRaises(st.PartitionError):
            st.assert_exact_partition(classes, plan)

    def test_includes_lines_are_surefire_path_patterns_for_exactly_that_shard(self) -> None:
        names = synthetic_tree(self.root, 6)
        plan = st.plan(st.discover(self.root), 2, {}, gate=set())
        lines = st.includes_lines(plan.shards[0])
        self.assertTrue(lines)
        for line in lines:
            self.assertRegex(line, r"^uz/horecaos/platform/p\d/C\d{3}Tests\.java$")
        expected = sorted(n.replace(".", "/") + ".java" for n in (c.fqcn for c in plan.shards[0]))
        self.assertEqual(expected, sorted(lines))
        other = set(st.includes_lines(plan.shards[1]))
        self.assertFalse(other & set(lines))
        self.assertEqual(len(names), len(lines) + len(other))


class DurationTests(unittest.TestCase):
    def test_durations_round_trip_through_the_tsv_format(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "d.tsv"
            path.write_text("# comment\n\na.B\t1.5\nc.D\t20\n", encoding="utf-8")
            self.assertEqual({"a.B": 1.5, "c.D": 20.0}, st.load_durations(path))

    def test_a_malformed_row_is_refused_rather_than_skipped(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "d.tsv"
            path.write_text("a.B\tnot-a-number\n", encoding="utf-8")
            with self.assertRaises(st.PartitionError):
                st.load_durations(path)

    def test_recording_sums_nested_reports_into_their_outer_class(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            report(directory, "a.Outer", 2.0)
            write(
                directory,
                "TEST-a.Outer$Inner.xml",
                '<testsuite name="a.Outer$Inner" time="3.5" tests="1"/>',
            )
            report(directory, "b.Other", 4.0)
            recorded = st.record_durations([directory])
            self.assertEqual({"a.Outer": 5.5, "b.Other": 4.0}, recorded)

    def test_recording_creates_the_output_directory_it_writes_into(self) -> None:
        # The CI aggregator runs `record --out target/ci/test-durations.tsv` in a
        # job that never creates target/ci; write_text on a missing directory
        # used to die with an uncaught FileNotFoundError and turn "Build and
        # test" red on every platform run.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            names = synthetic_tree(root / "src", 2)
            reports = root / "reports" / "test-results-shard-1"
            for name in names:
                report(reports, name, 2.0)
            out = root / "target" / "ci" / "test-durations.tsv"
            self.assertFalse(out.parent.exists())
            code = st.main(["record", "--reports", str(reports), "--tests", str(root / "src"), "--out", str(out)])
            self.assertEqual(0, code)
            self.assertEqual({name: 2.0 for name in names}, st.load_durations(out))


class ReportVerificationTests(unittest.TestCase):
    """The runtime half of the guarantee: what the shards actually executed."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name) / "tests"
        self.reports = Path(self._tmp.name) / "reports"
        self.names = synthetic_tree(self.root, 12)
        write(self.root, "support/TestDatabase.java", "class TestDatabase {}\n")
        self.classes = st.discover(self.root)
        self.gate = {self.names[0]}
        self.plan = st.plan(self.classes, 3, {}, gate=self.gate)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def populate(self) -> None:
        for index, shard in enumerate(self.plan.shards, start=1):
            for c in shard:
                if c.expects_report:
                    report(self.reports / f"test-results-shard-{index}", c.fqcn)
        for c in self.plan.gate:
            report(self.reports / "test-results-gate", c.fqcn)

    def test_a_complete_run_verifies(self) -> None:
        self.populate()
        self.assertEqual([], st.verify_reports(self.plan, self.reports))

    def test_a_class_that_never_ran_is_named(self) -> None:
        self.populate()
        victim = next(c for c in self.plan.shards[1] if c.expects_report)
        (self.reports / "test-results-shard-2" / f"TEST-{victim.fqcn}.xml").unlink()
        problems = st.verify_reports(self.plan, self.reports)
        self.assertEqual(1, len(problems))
        self.assertIn(victim.fqcn, problems[0])
        self.assertIn("never ran", problems[0])

    def test_a_class_that_ran_in_two_shards_is_named(self) -> None:
        self.populate()
        twin = self.plan.shards[0][0]
        report(self.reports / "test-results-shard-3", twin.fqcn)
        problems = st.verify_reports(self.plan, self.reports)
        self.assertTrue(any(twin.fqcn in p and "assigned to shard 1" in p for p in problems), problems)

    def test_a_shard_with_no_reports_at_all_is_a_failure(self) -> None:
        self.populate()
        for f in (self.reports / "test-results-shard-2").iterdir():
            f.unlink()
        problems = st.verify_reports(self.plan, self.reports)
        self.assertTrue(any("shard 2" in p and "no surefire reports" in p for p in problems), problems)

    def test_the_gate_class_must_have_run_in_the_gate_job(self) -> None:
        self.populate()
        (self.reports / "test-results-gate" / f"TEST-{self.names[0]}.xml").unlink()
        problems = st.verify_reports(self.plan, self.reports)
        self.assertTrue(any(self.names[0] in p for p in problems), problems)

    def test_a_helper_without_tests_is_not_required_to_report(self) -> None:
        self.populate()
        helper = "support.TestDatabase"
        self.assertIn(helper, [c.fqcn for shard in self.plan.shards for c in shard])
        self.assertEqual([], st.verify_reports(self.plan, self.reports))


class RealTreeTests(unittest.TestCase):
    """The guarantee, asserted about this repository's own test sources."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.classes = st.discover(st.TEST_ROOT)
        cls.durations = st.load_durations(st.DURATIONS_FILE)
        cls.gate = st.load_gate(st.GATE_FILE)

    def test_the_repository_has_a_test_tree_to_partition(self) -> None:
        self.assertGreater(len(self.classes), 400)

    def test_every_test_class_runs_exactly_once_for_every_plausible_shard_count(self) -> None:
        for shards in range(1, 9):
            with self.subTest(shards=shards):
                plan = st.plan(self.classes, shards, self.durations, self.gate)
                st.assert_exact_partition(self.classes, plan)
                self.assertTrue(all(plan.shards), "an empty shard would run the whole suite")
                union = {c.fqcn for s in plan.shards for c in s} | {c.fqcn for c in plan.gate}
                self.assertEqual({c.fqcn for c in self.classes}, union)

    def test_the_committed_plan_for_the_workflow_is_stable_between_two_computations(self) -> None:
        first = st.plan(self.classes, st.workflow_shard_count(WORKFLOW), self.durations, self.gate)
        second = st.plan(list(reversed(self.classes)), st.workflow_shard_count(WORKFLOW), self.durations, self.gate)
        self.assertEqual(
            [[c.fqcn for c in s] for s in first.shards],
            [[c.fqcn for c in s] for s in second.shards],
        )

    def test_the_shards_are_balanced_by_recorded_time(self) -> None:
        shards = st.workflow_shard_count(WORKFLOW)
        plan = st.plan(self.classes, shards, self.durations, self.gate)
        loads = plan.loads
        mean = sum(loads) / len(loads)
        self.assertLessEqual(max(loads) / mean, 1.10, f"unbalanced shards: {loads}")

    def test_the_gate_classes_exist(self) -> None:
        names = {c.fqcn for c in self.classes}
        self.assertTrue(self.gate)
        for gate in self.gate:
            self.assertIn(gate, names)

    def test_the_openapi_contract_test_is_the_gate_class(self) -> None:
        # The OpenAPI compatibility step reads target/openapi/*.json, which this
        # class writes; the job that diffs the clients must be the job that ran it.
        self.assertIn("uz.horecaos.platform.configuration.OpenApiContractTests", self.gate)


class WorkflowWiringTests(unittest.TestCase):
    """ci.yml and the partition must agree; nothing else compares them."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.text = WORKFLOW.read_text(encoding="utf-8")
        cls.jobs = st.workflow_jobs(cls.text)

    def test_the_matrix_lists_exactly_the_shards_the_partition_is_told_to_make(self) -> None:
        count = st.workflow_shard_count(WORKFLOW)
        block = self.jobs["test-shard"]
        listed = re.search(r"shard:\s*\[([^\]]+)\]", block)
        self.assertIsNotNone(listed, "test-shard has no `shard: [...]` matrix")
        assert listed is not None
        self.assertEqual(list(range(1, count + 1)), [int(x) for x in listed.group(1).split(",")])

    def test_every_shard_selects_its_classes_through_the_partition_script(self) -> None:
        block = self.jobs["test-shard"]
        self.assertIn("tools/ci/shard_tests.py includes", block)
        self.assertIn("surefire.includesFile", block)
        self.assertNotIn("-Dtest=", block, "-Dtest would bypass the include file")

    def test_the_gate_job_runs_only_the_gate_classes_and_owns_the_non_test_steps(self) -> None:
        block = self.jobs["verify-static"]
        self.assertIn("shard_tests.py includes --gate", block)
        self.assertIn("mvnw --batch-mode verify", block, "spotless, enforcer and packaging live in verify")
        self.assertIn("generate_types.py", block)
        for shard_only in ("jacoco:check", "verify-reports"):
            self.assertNotIn(shard_only, block)
        self.assertNotIn("generate_types.py", self.jobs["test-shard"])
        self.assertNotIn("spotless", self.jobs["test-shard"])

    def test_the_aggregator_is_called_build_and_test_and_waits_for_everything(self) -> None:
        block = self.jobs["verify"]
        self.assertRegex(block, r"name:\s*Build and test\b")
        needs = re.search(r"needs:\s*\[([^\]]+)\]", block)
        self.assertIsNotNone(needs)
        assert needs is not None
        self.assertEqual({"changes", "verify-static", "test-shard"}, {n.strip() for n in needs.group(1).split(",")})

    def test_the_aggregator_runs_even_when_a_shard_failed_and_then_fails_itself(self) -> None:
        # Without always(), a failed shard would leave this job *skipped*, and
        # publish-images treats skipped as "the path filter said no". That reads
        # a red suite as permission to publish an untested tree.
        block = self.jobs["verify"]
        self.assertRegex(block, r"if:\s*always\(\)\s*&&\s*needs\.changes\.outputs\.platform == 'true'")
        self.assertIn("needs.test-shard.result", block)
        self.assertIn("needs.verify-static.result", block)
        self.assertIn("verify-reports", block)
        self.assertIn("jacoco:check@jacoco-check", block, "the coverage floor must still gate")

    def test_publish_depends_on_the_aggregator_and_never_on_a_single_shard(self) -> None:
        block = self.jobs["publish-images"]
        needs = re.search(r"needs:\s*\[([^\]]+)\]", block)
        assert needs is not None
        depends = {n.strip() for n in needs.group(1).split(",")}
        self.assertIn("verify", depends)
        self.assertNotIn("test-shard", depends)
        self.assertNotIn("verify-static", depends)
        self.assertIn("needs.verify.result == 'success'", block)

    def test_shards_and_gate_run_with_the_same_jvm_flags_and_image_prepull(self) -> None:
        for job in ("test-shard", "verify-static"):
            with self.subTest(job=job):
                block = self.jobs[job]
                self.assertIn("pull-test-images.sh", block)
                self.assertIn("TEST_JVM_ARGS", block)
        self.assertIn("-Xmx3g", self.text)
        self.assertIn("MaxMetaspaceSize=1g", self.text)
        self.assertIn("spring.test.context.cache.maxSize=8", self.text)

    def test_the_duration_refresh_only_feeds_a_future_rebalance_so_it_never_gates(self) -> None:
        # The recorded durations change how evenly the shards split, never what
        # runs. A hiccup while rebuilding them must not turn the required
        # "Build and test" check red or stop images from publishing.
        block = self.jobs["verify"]
        for step in ("Rebuild the recorded test durations", "Publish refreshed durations"):
            with self.subTest(step=step):
                match = re.search(r"- name:\s*" + re.escape(step) + r"\n(.*?)(?=\n      - |\Z)", block, re.DOTALL)
                self.assertIsNotNone(match, f"no step named {step!r} in the aggregator")
                assert match is not None
                self.assertRegex(match.group(1), r"continue-on-error:\s*true")

    def test_every_job_that_produces_evidence_uploads_it(self) -> None:
        self.assertIn("test-results-shard-${{ matrix.shard }}", self.jobs["test-shard"])
        self.assertIn("test-results-gate", self.jobs["verify-static"])
        self.assertIn("jacoco-exec-shard-${{ matrix.shard }}", self.jobs["test-shard"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
