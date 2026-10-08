#!/usr/bin/env python3
"""The routing engine's accuracy gate and the measured detour factor (ADR 0147, decision 7).

Reads a reference sample of trips, asks the engine for the same trips, and reports two
things from the one sample:

* the gate: the engine's median absolute deviation from the reference, and its 95th
  percentile, both as a fraction of the reference distance. ADR 0147's defaults, closed
  on 2026-10-07 when the owner accepted the record as written, are a median of at most
  10% and a 95th percentile of at most 25%, over 100 trips of between 1 and 10 km;
* the detour factor: the median of road distance over straight-line distance, which
  replaces ADR 0037's unmeasured 1.30 as the region's default once it is measured.

Standard library only, so it runs on a bare CI runner or an operator's laptop.

The reference is route distance from a second source for the same endpoints, or the GPS
track of delivered orders. The track is evidence for this gate and never pays a courier
(ADR 0042); a CSV of it is read here and never written anywhere.

Input CSV, one trip per line, a header row, no personal data: coordinates only, and never
a customer's address or an order id.

    origin_lat,origin_lon,dest_lat,dest_lon,reference_meters

Usage:

    accuracy_gate.py sample.csv --engine http://localhost:5000
    accuracy_gate.py sample.csv --engine http://localhost:5000 --median 0.10 --p95 0.25

Exit status: 0 the gate holds, 1 it does not, 2 the sample or the engine is unusable.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Sequence

# ADR 0147's gate defaults. A different pair is a decision to be recorded, not a flag to
# be passed quietly, which is why the report prints the thresholds it applied.
DEFAULT_MEDIAN = 0.10
DEFAULT_P95 = 0.25
DEFAULT_MIN_TRIPS = 100
# The trips the gate is defined over: short enough to be deliveries, long enough that a
# few metres of snapping is not the whole answer.
MIN_REFERENCE_METERS = 1_000
MAX_REFERENCE_METERS = 10_000

EARTH_RADIUS_METERS = 6_371_008.8


@dataclass(frozen=True)
class Trip:
    origin: tuple[float, float]
    destination: tuple[float, float]
    reference_meters: float


@dataclass(frozen=True)
class Report:
    trips: int
    median_deviation: float
    p95_deviation: float
    detour_factor: float
    median_limit: float
    p95_limit: float
    min_trips: int

    @property
    def enough_trips(self) -> bool:
        return self.trips >= self.min_trips

    @property
    def holds(self) -> bool:
        return (
            self.enough_trips
            and self.median_deviation <= self.median_limit
            and self.p95_deviation <= self.p95_limit
        )


class UnusableSample(Exception):
    """The sample or the engine cannot support a verdict, which is not the same as failing it."""


def haversine_meters(a: tuple[float, float], b: tuple[float, float]) -> float:
    """Great-circle distance, the same arithmetic as the platform's own Haversine."""
    lat1, lon1, lat2, lon2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * EARTH_RADIUS_METERS * math.asin(math.sqrt(h))


def read_sample(path: Path) -> list[Trip]:
    trips: list[Trip] = []
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        required = {"origin_lat", "origin_lon", "dest_lat", "dest_lon", "reference_meters"}
        if reader.fieldnames is None or not required.issubset(reader.fieldnames):
            raise UnusableSample(f"the sample needs the columns {sorted(required)}")
        for number, row in enumerate(reader, start=2):
            try:
                trip = Trip(
                    (float(row["origin_lat"]), float(row["origin_lon"])),
                    (float(row["dest_lat"]), float(row["dest_lon"])),
                    float(row["reference_meters"]),
                )
            except (TypeError, ValueError) as error:
                raise UnusableSample(f"line {number} is not numeric: {error}") from error
            if not (MIN_REFERENCE_METERS <= trip.reference_meters <= MAX_REFERENCE_METERS):
                # The gate is defined over deliveries of 1 to 10 km. A trip outside that is
                # a different question, and quietly averaging it in would move the verdict.
                continue
            trips.append(trip)
    return trips


def engine_route(base_url: str, trip: Trip, timeout: float = 5.0) -> float:
    """The engine's driving distance in metres for one trip, as the adapter asks it."""
    (olat, olon), (dlat, dlon) = trip.origin, trip.destination
    url = (
        f"{base_url.rstrip('/')}/route/v1/driving/{olon:.6f},{olat:.6f};{dlon:.6f},{dlat:.6f}"
        "?overview=false&steps=false&alternatives=false"
    )
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:  # noqa: S310 - the engine's own URL
            body = json.load(response)
    except urllib.error.HTTPError as error:
        # NoRoute and NoSegment arrive as 400. A trip the engine cannot route is evidence
        # about the engine, and it counts against it rather than being dropped.
        raise UnusableSample(f"the engine would not route a reference trip ({error.code})") from error
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        raise UnusableSample(f"the engine did not answer: {error}") from error
    if body.get("code") != "Ok" or not body.get("routes"):
        raise UnusableSample("the engine answered with no route for a reference trip")
    return float(body["routes"][0]["distance"])


def percentile(values: Sequence[float], fraction: float) -> float:
    """Nearest-rank percentile, so the 95th of 100 trips is the 95th trip and not an interpolation."""
    ordered = sorted(values)
    rank = max(1, math.ceil(fraction * len(ordered)))
    return ordered[rank - 1]


def evaluate(
    trips: Iterable[Trip],
    route: Callable[[Trip], float],
    median_limit: float = DEFAULT_MEDIAN,
    p95_limit: float = DEFAULT_P95,
    min_trips: int = DEFAULT_MIN_TRIPS,
) -> Report:
    deviations: list[float] = []
    detours: list[float] = []
    for trip in trips:
        measured = route(trip)
        deviations.append(abs(measured - trip.reference_meters) / trip.reference_meters)
        straight = haversine_meters(trip.origin, trip.destination)
        if straight > 0:
            detours.append(measured / straight)
    if not deviations:
        raise UnusableSample("no trip in the sample is between 1 and 10 km")
    return Report(
        trips=len(deviations),
        median_deviation=statistics.median(deviations),
        p95_deviation=percentile(deviations, 0.95),
        detour_factor=statistics.median(detours),
        median_limit=median_limit,
        p95_limit=p95_limit,
        min_trips=min_trips,
    )


def render(report: Report) -> str:
    verdict = "HOLDS" if report.holds else "DOES NOT HOLD"
    lines = [
        f"trips                {report.trips} (the gate wants at least {report.min_trips})",
        f"median deviation     {report.median_deviation:6.1%}   limit {report.median_limit:.0%}",
        f"95th percentile      {report.p95_deviation:6.1%}   limit {report.p95_limit:.0%}",
        f"detour factor        {report.detour_factor:.3f}   (road over straight line, median)",
        f"gate                 {verdict}",
    ]
    if not report.enough_trips:
        lines.append(f"                     too few trips: a verdict on {report.trips} is not the gate")
    # ADR 0037 stores the factor as basis points and CHECKs it is at least 10000.
    lines.append(
        f"as basis points      {round(report.detour_factor * 10_000)}"
        "   (road_factor_basis_points; ADR 0037's unmeasured default is 13000)"
    )
    return "\n".join(lines)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("sample", type=Path, help="CSV of reference trips")
    parser.add_argument("--engine", required=True, help="the OSRM base URL, for example http://localhost:5000")
    parser.add_argument("--median", type=float, default=DEFAULT_MEDIAN)
    parser.add_argument("--p95", type=float, default=DEFAULT_P95)
    parser.add_argument("--min-trips", type=int, default=DEFAULT_MIN_TRIPS)
    args = parser.parse_args(argv)
    try:
        trips = read_sample(args.sample)
        report = evaluate(
            trips,
            lambda trip: engine_route(args.engine, trip),
            args.median,
            args.p95,
            args.min_trips,
        )
    except UnusableSample as error:
        print(f"unusable: {error}", file=sys.stderr)
        return 2
    print(render(report))
    return 0 if report.holds else 1


if __name__ == "__main__":
    sys.exit(main())
