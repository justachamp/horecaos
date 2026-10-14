#!/usr/bin/env python3
"""Proves the map-provider bake-off says what ADR 0145 decision 3 says it says (make geo-bakeoff-test).

The runner is a verdict on a provider, so the thing worth proving is that it can say no, and
that it says no for each of the reasons the record gives: too few addresses within 100 m, a
result outside the region's box that the provider did not flag, a result in another city. It
runs over a synthetic set and recorded answers (no network, no key, no customer's address),
and the one test that speaks HTTP talks to a server of its own.
"""
from __future__ import annotations

import http.server
import io
import json
import os
import re
import tempfile
import threading
import unittest
from unittest import mock
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

import bake_off as bo

HERE = Path(__file__).parent
FIXTURES = HERE / "fixtures"
TASHKENT_BOX = bo.BBox((41.15, 69.04), (41.47, 69.46))
BRANCH = (41.311081, 69.240562)


def golden(count: int = 200, city: str = "Tashkent") -> list[bo.Address]:
    """Synthetic addresses on a grid inside the box; the id is all that names one."""
    return [
        bo.Address(
            f"G-{i:03d}",
            f"Synthetic address {i:03d}",
            (round(41.20 + 0.001 * (i // 20), 6), round(69.10 + 0.001 * (i % 20), 6)),
            bo.KINDS[i % 3],
            city,
        )
        for i in range(count)
    ]


class Scripted:
    """A source whose answer for each address is a function of it, the way a recorded run is."""

    def __init__(self, answer):
        self._answer = answer

    def candidates(self, address: bo.Address) -> list[bo.Candidate]:
        return self._answer(address)


def north_of(address: bo.Address, meters: float, **kwargs) -> list[bo.Candidate]:
    """One result `meters` north of the surveyed door (a degree of latitude is 111.2 km)."""
    return [bo.Candidate(address.surveyed[0] + meters / 111_195.0, address.surveyed[1], **kwargs)]


def exact(**kwargs):
    return Scripted(lambda a: north_of(a, 20.0, confidence="HIGH", locality=a.city, **kwargs))


class VerdictTests(unittest.TestCase):
    def test_a_provider_that_finds_every_door_holds(self) -> None:
        report = bo.run(golden(), exact(), TASHKENT_BOX)

        self.assertTrue(report.holds)
        self.assertEqual(report.share_within, 1.0)

    def test_ninety_percent_within_a_hundred_metres_is_the_pass_and_one_fewer_is_not(self) -> None:
        def answer(misses: int):
            def inner(address: bo.Address) -> list[bo.Candidate]:
                index = int(address.id.split("-")[1])
                # 450 m north is a plausible street away: placed, in the box, in the city, and wrong.
                return north_of(address, 450.0 if index < misses else 20.0, confidence="HIGH", locality="Tashkent")

            return Scripted(inner)

        at_the_line = bo.run(golden(), answer(20), TASHKENT_BOX)  # 180 of 200 = exactly 90%
        under_it = bo.run(golden(), answer(21), TASHKENT_BOX)  # 179 of 200

        self.assertEqual(at_the_line.share_within, 0.90)
        self.assertTrue(at_the_line.holds)
        self.assertFalse(under_it.holds, "89.5% within 100 m is not a pass")

    def test_a_result_outside_the_box_that_the_provider_did_not_flag_fails_an_otherwise_perfect_run(self) -> None:
        # One street of the same name in another country, returned with a straight face. The record's
        # proposed pass is that every such result is LOW_CONFIDENCE, and one that is not fails it.
        def answer(address: bo.Address) -> list[bo.Candidate]:
            if address.id == "G-007":
                return [bo.Candidate(42.8746, 74.5698, "HIGH", "Tashkent")]
            return north_of(address, 20.0, confidence="HIGH", locality="Tashkent")

        report = bo.run(golden(), Scripted(answer), TASHKENT_BOX)

        self.assertEqual(report.outside_box, 1)
        self.assertEqual(report.outside_box_unflagged, 1)
        self.assertFalse(report.holds)

    def test_the_same_result_flagged_low_confidence_is_what_the_record_asks_for(self) -> None:
        def answer(address: bo.Address) -> list[bo.Candidate]:
            if address.id == "G-007":
                return [bo.Candidate(42.8746, 74.5698, "LOW_CONFIDENCE", "Tashkent")]
            return north_of(address, 20.0, confidence="HIGH", locality="Tashkent")

        report = bo.run(golden(), Scripted(answer), TASHKENT_BOX)

        self.assertEqual(report.outside_box, 1)
        self.assertEqual(report.outside_box_unflagged, 0)
        # 199 of 200 are within 100 m (99.5%): the flagged foreign street is a miss on distance but not a violation.
        self.assertTrue(report.holds)

    def test_a_result_that_moves_an_address_to_another_city_fails(self) -> None:
        def answer(address: bo.Address) -> list[bo.Candidate]:
            locality = "Samarkand" if address.id == "G-042" else "Tashkent"
            return north_of(address, 20.0, confidence="HIGH", locality=locality)

        report = bo.run(golden(), Scripted(answer), TASHKENT_BOX)

        self.assertEqual(report.wrong_city, 1)
        self.assertFalse(report.holds, "100 m from the door and in the wrong city is the failure the record names")

    def test_a_locality_that_only_contains_the_city_is_the_same_city(self) -> None:
        report = bo.run(
            golden(), Scripted(lambda a: north_of(a, 20.0, confidence="HIGH", locality="Tashkent city")), TASHKENT_BOX
        )

        self.assertEqual(report.wrong_city, 0)

    def test_an_address_with_no_result_is_a_miss_and_never_dropped_from_the_denominator(self) -> None:
        def answer(address: bo.Address) -> list[bo.Candidate]:
            return [] if int(address.id.split("-")[1]) < 21 else north_of(address, 20.0, confidence="HIGH")

        report = bo.run(golden(), Scripted(answer), TASHKENT_BOX)

        self.assertEqual(report.unplaced, 21)
        self.assertEqual(report.addresses, 200)
        self.assertAlmostEqual(report.share_within, 179 / 200)
        self.assertFalse(report.holds)

    def test_fewer_than_two_hundred_addresses_is_not_the_gate_however_good_the_provider(self) -> None:
        report = bo.run(golden(150), exact(), TASHKENT_BOX)

        self.assertEqual(report.share_within, 1.0)
        self.assertFalse(report.enough_addresses)
        self.assertFalse(report.holds, "the record defines the gate over 200 addresses")
        self.assertIn("too few addresses", bo.render(report))

    def test_the_best_result_is_the_first_one_not_the_closest(self) -> None:
        # A provider whose second candidate happens to be on the door has not found the door: the
        # first is what a person gets by default and what a screen pre-selects.
        def answer(address: bo.Address) -> list[bo.Candidate]:
            far = north_of(address, 900.0, confidence="HIGH", locality="Tashkent")
            near = north_of(address, 5.0, confidence="HIGH", locality="Tashkent")
            return far + near

        report = bo.run(golden(), Scripted(answer), TASHKENT_BOX)

        self.assertEqual(report.within, 0)

    def test_the_thresholds_applied_are_printed_so_a_different_pair_is_visible(self) -> None:
        report = bo.run(golden(), exact(), TASHKENT_BOX, within_meters=50.0, min_share_within=0.95)

        self.assertIn("within 50 m", bo.render(report))
        self.assertIn("limit 95%", bo.render(report))


class ReportTests(unittest.TestCase):
    def test_the_recorded_result_names_ids_and_numbers_and_never_a_query_or_a_point(self) -> None:
        addresses = golden(10)
        report = bo.run(addresses, exact(), TASHKENT_BOX, min_addresses=10)

        text = json.dumps(bo.to_json(report))

        for address in addresses:
            self.assertNotIn(address.query, text)
        self.assertNotRegex(text, r"\b4[01]\.\d{4,}")
        self.assertIn('"holds": true', text)
        self.assertEqual(json.loads(text)["outcomes"][0]["id"], "G-000")

    def test_it_breaks_the_result_down_by_the_kind_of_address(self) -> None:
        report = bo.run(golden(), exact(), TASHKENT_BOX)

        rendered = bo.render(report)

        for kind in bo.KINDS:
            self.assertIn(kind, rendered)


class GoldenSetTests(unittest.TestCase):
    def write(self, text: str) -> Path:
        handle = tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8")
        handle.write(text)
        handle.close()
        self.addCleanup(Path(handle.name).unlink)
        return Path(handle.name)

    def test_the_sample_fixture_reads(self) -> None:
        addresses = bo.read_golden_set(FIXTURES / "golden-sample.csv")

        self.assertEqual(len(addresses), 12)
        self.assertEqual({a.kind for a in addresses}, set(bo.KINDS))

    def test_a_set_without_the_columns_it_needs_is_unusable_not_empty(self) -> None:
        with self.assertRaises(bo.UnusableRun):
            bo.read_golden_set(self.write("id,query\nG-1,somewhere\n"))

    def test_a_repeated_id_is_refused_because_it_would_double_count_an_address(self) -> None:
        text = "id,query,surveyed_lat,surveyed_lon\nG-1,a,41.3,69.2\nG-1,b,41.4,69.3\n"
        with self.assertRaises(bo.UnusableRun):
            bo.read_golden_set(self.write(text))

    def test_a_kind_the_record_does_not_name_is_refused(self) -> None:
        text = "id,query,surveyed_lat,surveyed_lon,kind\nG-1,a,41.3,69.2,CUSTOMER\n"
        with self.assertRaises(bo.UnusableRun):
            bo.read_golden_set(self.write(text))

    def test_a_point_that_is_not_a_number_or_not_on_the_globe_is_refused(self) -> None:
        for point in ("abc,69.2", "141.3,69.2", "41.3,269.2"):
            text = f"id,query,surveyed_lat,surveyed_lon\nG-1,a,{point}\n"
            with self.subTest(point=point), self.assertRaises(bo.UnusableRun):
                bo.read_golden_set(self.write(text))

    def test_a_box_must_run_south_west_to_north_east(self) -> None:
        with self.assertRaises(bo.UnusableRun):
            bo.BBox.parse("41.47,69.46,41.15,69.04")
        with self.assertRaises(bo.UnusableRun):
            bo.BBox.parse("not,a,box")


class RecordedAnswersTests(unittest.TestCase):
    def test_the_recorded_fixture_scores_the_way_it_was_recorded(self) -> None:
        addresses = bo.read_golden_set(FIXTURES / "golden-sample.csv")
        source = bo.RecordedSource.load(FIXTURES / "recorded-answers.json")

        report = bo.run(addresses, source, TASHKENT_BOX, min_addresses=12)

        self.assertEqual(report.unplaced, 1, "G-011 was recorded as found nowhere")
        self.assertEqual(report.outside_box, 1, "G-012 was recorded in Bishkek")
        self.assertEqual(report.outside_box_unflagged, 0, "and flagged, as the record asks")
        self.assertEqual(report.wrong_city, 1)
        self.assertFalse(report.holds)

    def test_main_exits_one_for_a_run_that_does_not_hold_and_two_for_one_that_cannot_be_read(self) -> None:
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            failing = bo.main(
                [
                    str(FIXTURES / "golden-sample.csv"),
                    "--bbox",
                    "41.15,69.04,41.47,69.46",
                    "--recorded",
                    str(FIXTURES / "recorded-answers.json"),
                    "--min-addresses",
                    "12",
                ]
            )
            unusable = bo.main(
                [
                    str(FIXTURES / "golden-sample.csv"),
                    "--bbox",
                    "41.15,69.04,41.47,69.46",
                    "--recorded",
                    str(FIXTURES / "missing.json"),
                ]
            )

        self.assertEqual(failing, 1)
        self.assertEqual(unusable, 2)
        self.assertIn("DOES NOT HOLD", out.getvalue())
        self.assertIn("unusable", err.getvalue())

    def test_main_writes_the_recorded_result_when_asked(self) -> None:
        with tempfile.TemporaryDirectory() as directory, redirect_stdout(io.StringIO()):
            target = Path(directory) / "result.json"
            bo.main(
                [
                    str(FIXTURES / "golden-sample.csv"),
                    "--bbox",
                    "41.15,69.04,41.47,69.46",
                    "--recorded",
                    str(FIXTURES / "recorded-answers.json"),
                    "--min-addresses",
                    "12",
                    "--json",
                    str(target),
                ]
            )

            written = json.loads(target.read_text(encoding="utf-8"))

        self.assertEqual(written["addresses"], 12)
        self.assertFalse(written["holds"])


class PlatformSourceTests(unittest.TestCase):
    """The platform's resolutions endpoint, played by a server of the test's own."""

    def serve(self, handler_factory) -> str:
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler_factory)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.shutdown)
        self.addCleanup(server.server_close)
        return f"http://127.0.0.1:{server.server_address[1]}"

    def source(self, base: str, sleeps: list[float] | None = None) -> bo.PlatformSource:
        return bo.PlatformSource(
            base, "t-1", "b-1", "a-test-token", pause_seconds=0.0, sleep=(sleeps.append if sleeps is not None else lambda s: None)
        )

    def test_it_asks_the_resolutions_endpoint_with_the_text_in_the_body_and_the_token_in_the_header(self) -> None:
        seen: dict[str, object] = {}

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802
                seen["path"] = self.path
                seen["auth"] = self.headers.get("Authorization")
                seen["body"] = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                payload = json.dumps(
                    {
                        "status": "ANSWERED",
                        "results": [
                            {
                                "latitude": 41.3,
                                "longitude": 69.2,
                                "confidence": "HIGH",
                                "components": {"locality": "Tashkent"},
                            }
                        ],
                    }
                ).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args) -> None:  # noqa: D401
                return

        base = self.serve(Handler)

        found = self.source(base).candidates(bo.Address("G-1", "Some landmark", (41.3, 69.2)))

        self.assertEqual(seen["path"], "/api/v1/operations/tenants/t-1/brands/b-1/geocode/resolutions")
        self.assertEqual(seen["auth"], "Bearer a-test-token")
        self.assertEqual(seen["body"], {"text": "Some landmark"})
        self.assertEqual(found, [bo.Candidate(41.3, 69.2, "HIGH", "Tashkent")])
        self.assertNotIn("?", str(seen["path"]), "an address is never in a query string (ADR 0029)")

    def test_an_unavailable_provider_is_an_unusable_run_and_not_a_provider_that_found_nothing(self) -> None:
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802
                self.rfile.read(int(self.headers["Content-Length"]))
                payload = json.dumps({"status": "UNAVAILABLE", "reason": "NOT_CONFIGURED", "results": []}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args) -> None:
                return

        base = self.serve(Handler)

        with self.assertRaises(bo.UnusableRun) as raised:
            bo.run(golden(3), self.source(base), TASHKENT_BOX, min_addresses=3)

        self.assertIn("NOT_CONFIGURED", str(raised.exception))

    def test_a_rate_limit_waits_the_time_the_platform_names_and_asks_again(self) -> None:
        calls = {"n": 0}

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802
                self.rfile.read(int(self.headers["Content-Length"]))
                calls["n"] += 1
                if calls["n"] == 1:
                    payload = json.dumps({"code": "RATE_LIMIT_EXCEEDED", "retryAfterSeconds": 17}).encode()
                    self.send_response(429)
                else:
                    payload = json.dumps({"status": "ANSWERED", "results": []}).encode()
                    self.send_response(200)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args) -> None:
                return

        base = self.serve(Handler)
        sleeps: list[float] = []

        found = self.source(base, sleeps).candidates(bo.Address("G-1", "x", (41.3, 69.2)))

        self.assertEqual(found, [])
        self.assertEqual(calls["n"], 2)
        self.assertIn(17.0, sleeps, "a run that gave up at the limit would have measured the limiter")

    def test_any_other_refusal_is_unusable_and_does_not_quote_the_body(self) -> None:
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802
                self.rfile.read(int(self.headers["Content-Length"]))
                payload = b'{"detail":"the address Some landmark could not be read"}'
                self.send_response(403)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args) -> None:
                return

        base = self.serve(Handler)

        with self.assertRaises(bo.UnusableRun) as raised:
            self.source(base).candidates(bo.Address("G-1", "Some landmark", (41.3, 69.2)))

        self.assertIn("403", str(raised.exception))
        self.assertNotIn("Some landmark", str(raised.exception))

    def test_a_platform_run_without_a_token_in_the_environment_is_unusable_and_the_token_is_never_an_argument(self) -> None:
        err = io.StringIO()
        with mock.patch.dict(os.environ, {}, clear=False), redirect_stderr(err):
            os.environ.pop("BAKEOFF_TOKEN", None)
            code = bo.main(
                [
                    str(FIXTURES / "golden-sample.csv"),
                    "--bbox",
                    "41.15,69.04,41.47,69.46",
                    "--platform",
                    "http://127.0.0.1:9",
                    "--tenant",
                    "t",
                    "--brand",
                    "b",
                ]
            )

        self.assertEqual(code, 2)
        self.assertIn("BAKEOFF_TOKEN", err.getvalue())
        parser_source = (HERE / "bake_off.py").read_text(encoding="utf-8")
        self.assertNotIn('"--token"', parser_source)
        self.assertNotIn("'--token'", parser_source)


class NoKeyInTheRepositoryTests(unittest.TestCase):
    def test_nothing_under_tools_geo_carries_a_credential_or_a_real_address(self) -> None:
        # A UUID-shaped string or a long hex run is what a provider key looks like; the fixtures are
        # synthetic and the runner takes its token from the environment, so none belongs here.
        shaped = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{32,}")
        offenders = [
            str(path.relative_to(HERE))
            for path in HERE.rglob("*")
            if path.is_file()
            and path.suffix in {".py", ".csv", ".json", ".md"}
            and "__pycache__" not in path.parts
            and shaped.search(path.read_text(encoding="utf-8"))
        ]

        self.assertEqual(offenders, [])


if __name__ == "__main__":
    unittest.main()
