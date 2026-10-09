#!/usr/bin/env python3
"""Proves the routing accuracy gate says what ADR 0147 says it says (make routing-gate-test).

The gate is a verdict on a map, so the thing worth proving is that it can say no: a gate
that cannot fail is a ritual. Each test below fixes the engine's answers and the reference
and checks the verdict, the thresholds, and the detour factor it reports.
"""
from __future__ import annotations

import http.server
import json
import tempfile
import threading
import unittest
from pathlib import Path

import accuracy_gate as gate

BRANCH = (41.311081, 69.240562)


def trip(reference: float, north_offset: float = 0.02) -> gate.Trip:
    return gate.Trip(BRANCH, (BRANCH[0] + north_offset, BRANCH[1]), reference)


class EvaluateTests(unittest.TestCase):
    def test_an_engine_that_agrees_with_the_reference_holds(self) -> None:
        trips = [trip(3_000.0) for _ in range(100)]

        report = gate.evaluate(trips, lambda t: t.reference_meters * 1.02)

        self.assertTrue(report.holds)
        self.assertAlmostEqual(report.median_deviation, 0.02, places=6)

    def test_a_median_over_ten_percent_fails_the_gate(self) -> None:
        trips = [trip(3_000.0) for _ in range(100)]

        report = gate.evaluate(trips, lambda t: t.reference_meters * 1.12)

        self.assertFalse(report.holds, "a map that is 12% long on a typical trip is not good enough")

    def test_a_tail_over_twenty_five_percent_fails_even_when_the_median_is_fine(self) -> None:
        # Ninety trips are right and ten are 40% out: the median is perfect and the 95th
        # percentile is not, which is the street with the missing one-way that a band edge
        # turns into a price (ADR 0147's consequences).
        trips = [trip(3_000.0) for _ in range(100)]
        answers = iter([3_000.0] * 90 + [4_200.0] * 10)

        report = gate.evaluate(trips, lambda t: next(answers))

        self.assertAlmostEqual(report.median_deviation, 0.0, places=6)
        self.assertAlmostEqual(report.p95_deviation, 0.40, places=6)
        self.assertFalse(report.holds)

    def test_too_few_trips_is_not_a_verdict(self) -> None:
        report = gate.evaluate([trip(3_000.0)] * 30, lambda t: t.reference_meters)

        self.assertFalse(report.enough_trips)
        self.assertFalse(report.holds, "thirty perfect trips do not pass a gate defined over a hundred")

    def test_an_engine_shorter_than_the_reference_is_as_wrong_as_one_longer(self) -> None:
        trips = [trip(3_000.0) for _ in range(100)]

        report = gate.evaluate(trips, lambda t: t.reference_meters * 0.8)

        self.assertFalse(report.holds, "a road 20% shorter than the reference makes fees too low")

    def test_the_detour_factor_is_the_median_of_road_over_straight_line(self) -> None:
        # 0.02 degrees of latitude is about 2,224 m straight; a 3,000 m road is about 1.35.
        straight = gate.haversine_meters(BRANCH, (BRANCH[0] + 0.02, BRANCH[1]))
        trips = [trip(3_000.0) for _ in range(100)]

        report = gate.evaluate(trips, lambda t: 3_000.0)

        self.assertAlmostEqual(report.detour_factor, 3_000.0 / straight, places=6)
        self.assertGreater(report.detour_factor, 1.3)

    def test_nearest_rank_percentile_is_the_ninety_fifth_trip(self) -> None:
        self.assertEqual(gate.percentile([float(n) for n in range(1, 101)], 0.95), 95.0)
        self.assertEqual(gate.percentile([1.0], 0.95), 1.0)

    def test_an_empty_sample_is_unusable_not_a_pass(self) -> None:
        with self.assertRaises(gate.UnusableSample):
            gate.evaluate([], lambda t: 0.0)

    def test_the_report_prints_the_thresholds_it_applied_and_the_basis_points(self) -> None:
        report = gate.evaluate([trip(3_000.0)] * 100, lambda t: 3_000.0, 0.10, 0.25)

        text = gate.render(report)

        self.assertIn("limit 10%", text)
        self.assertIn("limit 25%", text)
        self.assertIn("road_factor_basis_points", text)


class SampleTests(unittest.TestCase):
    def write(self, text: str) -> Path:
        handle = tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8")
        handle.write(text)
        handle.close()
        self.addCleanup(lambda: Path(handle.name).unlink())
        return Path(handle.name)

    def test_trips_outside_one_to_ten_kilometres_are_not_part_of_the_gate(self) -> None:
        path = self.write(
            "origin_lat,origin_lon,dest_lat,dest_lon,reference_meters\n"
            "41.31,69.24,41.33,69.24,500\n"
            "41.31,69.24,41.33,69.24,3000\n"
            "41.31,69.24,41.50,69.24,25000\n"
        )

        self.assertEqual([t.reference_meters for t in gate.read_sample(path)], [3000.0])

    def test_a_sample_without_the_columns_is_unusable(self) -> None:
        with self.assertRaises(gate.UnusableSample):
            gate.read_sample(self.write("a,b\n1,2\n"))

    def test_a_non_numeric_line_names_its_line(self) -> None:
        path = self.write(
            "origin_lat,origin_lon,dest_lat,dest_lon,reference_meters\n41.3,69.2,41.4,69.2,not-a-number\n"
        )

        with self.assertRaisesRegex(gate.UnusableSample, "line 2"):
            gate.read_sample(path)


class EngineTests(unittest.TestCase):
    """The request the gate sends is the one the adapter sends, against a real HTTP server."""

    def serve(self, status: int, body: dict) -> str:
        requests: list[str] = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self) -> None:  # noqa: N802 - the stdlib's name
                requests.append(self.path)
                payload = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, *args: object) -> None:
                return

        server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        self.requests = requests
        return f"http://127.0.0.1:{server.server_address[1]}"

    def test_the_route_is_asked_longitude_first_with_no_geometry(self) -> None:
        url = self.serve(200, {"code": "Ok", "routes": [{"distance": 3_210.4, "duration": 400}]})

        metres = gate.engine_route(url, trip(3_000.0))

        self.assertAlmostEqual(metres, 3_210.4)
        self.assertTrue(self.requests[0].startswith("/route/v1/driving/69.240562,41.311081;"))
        self.assertIn("overview=false", self.requests[0])

    def test_the_legs_from_the_pins_to_the_road_are_part_of_the_distance(self) -> None:
        # The figure the platform charges is the route plus the two snap legs, so that is what
        # the gate has to compare with the reference: without them a map that leaves pins far
        # from any road would pass a gate that its fees then under-charge.
        url = self.serve(
            200,
            {
                "code": "Ok",
                "routes": [{"distance": 3_000.0, "duration": 400}],
                "waypoints": [{"distance": 120.5}, {"distance": 80.25}],
            },
        )

        self.assertAlmostEqual(gate.engine_route(url, trip(3_000.0)), 3_200.75)

    def test_a_trip_the_engine_cannot_route_is_unusable_evidence(self) -> None:
        url = self.serve(400, {"code": "NoRoute", "message": "Impossible route between points"})

        with self.assertRaises(gate.UnusableSample):
            gate.engine_route(url, trip(3_000.0))

    def test_main_exits_one_when_the_gate_fails_and_zero_when_it_holds(self) -> None:
        rows = "origin_lat,origin_lon,dest_lat,dest_lon,reference_meters\n" + "\n".join(
            "41.311081,69.240562,41.331081,69.240562,3000" for _ in range(100)
        )
        sample = Path(tempfile.mkdtemp()) / "sample.csv"
        sample.write_text(rows, encoding="utf-8")

        holds = self.serve(200, {"code": "Ok", "routes": [{"distance": 3_030.0, "duration": 400}]})
        self.assertEqual(gate.main([str(sample), "--engine", holds]), 0)

        fails = self.serve(200, {"code": "Ok", "routes": [{"distance": 4_000.0, "duration": 400}]})
        self.assertEqual(gate.main([str(sample), "--engine", fails]), 1)

        self.assertEqual(gate.main([str(sample), "--engine", "http://127.0.0.1:1"]), 2)


if __name__ == "__main__":
    unittest.main()
