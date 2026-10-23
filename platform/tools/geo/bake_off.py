#!/usr/bin/env python3
"""The map-provider bake-off (ADR 0145, decision 3): one golden set, any provider, one verdict.

ADR 0145 chose the provider by measurement and not by market expectation, and wrote the pass
mark down before anyone ran it. This is the runner. It takes a golden set of addresses with a
surveyed point each, asks a provider for every one, and reports, from the one run:

* the share whose best result lies within 100 m of the surveyed point (the record's proposed
  pass: at least 90%);
* the results that lie outside the region's bounding box and were not marked LOW_CONFIDENCE
  (the proposed pass: none, "100% of results outside the region's bounding box are marked
  LOW_CONFIDENCE");
* the results that put an address in another city (the proposed pass: none, "no result moves a
  customer's address to another city");
* the provider's own stated confidence, the distance distribution, and how many addresses it
  could not place at all (a miss, never dropped from the denominator).

**Provider-neutral by construction.** The runner never names a vendor. It asks a *source* for
candidates, and a source is anything with ``candidates(query) -> list[Candidate]``:

* ``--platform``: the platform's own ``POST .../geocode/resolutions`` (ADR 0145 decision 2), so
  the provider under test is whichever one the environment is configured with
  (``horecaos.geo.provider``). Running "Yandex, then 2GIS if it fails" is running this twice
  against two environments, and the region's box is applied by the platform on the way back
  exactly as it will be for a customer. The bearer token is read from ``BAKEOFF_TOKEN`` and is
  never an argument, a file in the repository or a line of output.
* ``--recorded``: a JSON file of answers recorded earlier, for the runner's own tests and for
  re-scoring a run without spending the provider's quota again.

**No key, no address and no coordinate in the repository.** The golden set is a file the
operator keeps outside git (surveyed doors come from operators; it must not be built from
customers' saved addresses, ADR 0029). The report names an address by its id and prints
distances and counts, never the query text or a point. Standard library only.

Golden set CSV, one address per line, a header row, no personal data:

    id,query,surveyed_lat,surveyed_lon[,kind][,city]

``kind`` is ``BRANCH``, ``LANDMARK`` or ``DOOR`` (the record's three sources); ``city`` is the
locality the address belongs to, used for the wrong-city check when both it and the result's
locality are known.

Recorded answers JSON: ``{"<id>": [{"lat":..,"lon":..,"confidence":"HIGH","locality":"..."}]}``.

Usage:

    bake_off.py golden.csv --bbox 41.15,69.04,41.47,69.46 --recorded answers.json
    BAKEOFF_TOKEN=... bake_off.py golden.csv --bbox 41.15,69.04,41.47,69.46 \\
        --platform https://api.example --tenant <uuid> --brand <uuid> --json result.json

Exit status: 0 the gate holds, 1 it does not, 2 the set, the provider or the source is unusable
(an unconfigured or unreachable provider is *unusable*, not *failing*: nothing was measured).
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable, Mapping, Protocol, Sequence

# ADR 0145's proposed thresholds, closed on 2026-10-07 when the owner accepted the record as
# written. A different set is a decision to record, not a flag to pass quietly, which is why the
# report prints the thresholds it applied.
DEFAULT_WITHIN_METERS = 100.0
DEFAULT_MIN_SHARE_WITHIN = 0.90
DEFAULT_MIN_ADDRESSES = 200

LOW_CONFIDENCE = "LOW_CONFIDENCE"
KINDS = ("BRANCH", "LANDMARK", "DOOR")

EARTH_RADIUS_METERS = 6_371_008.8


@dataclass(frozen=True)
class Address:
    """One golden-set row: an opaque id, what a person would type, and where the door really is."""

    id: str
    query: str
    surveyed: tuple[float, float]
    kind: str = ""
    city: str = ""


@dataclass(frozen=True)
class Candidate:
    """One result a provider returned, in the provider-neutral shape the platform itself uses."""

    lat: float
    lon: float
    confidence: str = ""
    locality: str = ""


@dataclass(frozen=True)
class BBox:
    south_west: tuple[float, float]
    north_east: tuple[float, float]

    def contains(self, lat: float, lon: float) -> bool:
        return (
            self.south_west[0] <= lat <= self.north_east[0]
            and self.south_west[1] <= lon <= self.north_east[1]
        )

    @staticmethod
    def parse(text: str) -> "BBox":
        try:
            sw_lat, sw_lon, ne_lat, ne_lon = (float(part) for part in text.split(","))
        except ValueError as error:
            raise UnusableRun("--bbox is four numbers: south-west lat,lon then north-east lat,lon") from error
        if not (sw_lat < ne_lat and sw_lon < ne_lon):
            raise UnusableRun("--bbox must run south-west to north-east")
        return BBox((sw_lat, sw_lon), (ne_lat, ne_lon))


class UnusableRun(Exception):
    """The set, the source or the provider cannot support a verdict, which is not the same as failing it."""


class Source(Protocol):
    def candidates(self, address: Address) -> list[Candidate]: ...


@dataclass(frozen=True)
class Outcome:
    """What one address measured, with nothing in it a person typed."""

    id: str
    kind: str
    placed: bool
    meters: float | None
    in_box: bool | None
    flagged_low: bool | None
    confidence: str
    wrong_city: bool


@dataclass(frozen=True)
class Report:
    outcomes: tuple[Outcome, ...]
    within_meters: float
    min_share_within: float
    min_addresses: int
    thresholds_note: str = field(default="ADR 0145 decision 3")

    @property
    def addresses(self) -> int:
        return len(self.outcomes)

    @property
    def unplaced(self) -> int:
        return sum(1 for o in self.outcomes if not o.placed)

    @property
    def within(self) -> int:
        return sum(1 for o in self.outcomes if o.meters is not None and o.meters <= self.within_meters)

    @property
    def share_within(self) -> float:
        return self.within / self.addresses if self.addresses else 0.0

    @property
    def outside_box(self) -> int:
        return sum(1 for o in self.outcomes if o.in_box is False)

    @property
    def outside_box_unflagged(self) -> int:
        return sum(1 for o in self.outcomes if o.in_box is False and not o.flagged_low)

    @property
    def wrong_city(self) -> int:
        return sum(1 for o in self.outcomes if o.wrong_city)

    @property
    def enough_addresses(self) -> bool:
        return self.addresses >= self.min_addresses

    @property
    def holds(self) -> bool:
        return (
            self.enough_addresses
            and self.share_within >= self.min_share_within
            and self.outside_box_unflagged == 0
            and self.wrong_city == 0
        )


def haversine_meters(a: tuple[float, float], b: tuple[float, float]) -> float:
    """Great-circle distance, the same arithmetic as the platform's own Haversine."""
    lat1, lon1, lat2, lon2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * EARTH_RADIUS_METERS * math.asin(math.sqrt(h))


def read_golden_set(path: Path) -> list[Address]:
    addresses: list[Address] = []
    seen: set[str] = set()
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        required = {"id", "query", "surveyed_lat", "surveyed_lon"}
        if reader.fieldnames is None or not required.issubset(reader.fieldnames):
            raise UnusableRun(f"the golden set needs the columns {sorted(required)}")
        for number, row in enumerate(reader, start=2):
            identifier = (row["id"] or "").strip()
            query = (row["query"] or "").strip()
            if not identifier or not query:
                raise UnusableRun(f"line {number} has no id or no query")
            if identifier in seen:
                raise UnusableRun(f"line {number} repeats the id {identifier}")
            seen.add(identifier)
            try:
                lat, lon = float(row["surveyed_lat"]), float(row["surveyed_lon"])
            except (TypeError, ValueError) as error:
                raise UnusableRun(f"line {number} has a surveyed point that is not numeric") from error
            if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                raise UnusableRun(f"line {number} has a surveyed point outside the globe")
            kind = (row.get("kind") or "").strip().upper()
            if kind and kind not in KINDS:
                raise UnusableRun(f"line {number} has the kind {kind!r}; the record names {', '.join(KINDS)}")
            addresses.append(Address(identifier, query, (lat, lon), kind, (row.get("city") or "").strip()))
    if not addresses:
        raise UnusableRun("the golden set has no addresses")
    return addresses


def measure(address: Address, candidates: Sequence[Candidate], bbox: BBox) -> Outcome:
    """The best candidate (the first: sources return best first) against the surveyed door."""
    if not candidates:
        return Outcome(address.id, address.kind, False, None, None, None, "", False)
    best = candidates[0]
    in_box = bbox.contains(best.lat, best.lon)
    wrong_city = bool(
        address.city
        and best.locality
        and address.city.casefold() not in best.locality.casefold()
        and best.locality.casefold() not in address.city.casefold()
    )
    return Outcome(
        id=address.id,
        kind=address.kind,
        placed=True,
        meters=haversine_meters(address.surveyed, (best.lat, best.lon)),
        in_box=in_box,
        flagged_low=best.confidence.upper() == LOW_CONFIDENCE,
        confidence=best.confidence.upper(),
        wrong_city=wrong_city,
    )


def run(
    addresses: Iterable[Address],
    source: Source,
    bbox: BBox,
    within_meters: float = DEFAULT_WITHIN_METERS,
    min_share_within: float = DEFAULT_MIN_SHARE_WITHIN,
    min_addresses: int = DEFAULT_MIN_ADDRESSES,
) -> Report:
    outcomes = tuple(measure(address, source.candidates(address), bbox) for address in addresses)
    if not outcomes:
        raise UnusableRun("no address was run")
    return Report(outcomes, within_meters, min_share_within, min_addresses)


# ---------------------------------------------------------------------------------- sources


class RecordedSource:
    """Answers recorded earlier, keyed by the golden set's ids. A missing id is a provider that said nothing."""

    def __init__(self, answers: Mapping[str, Sequence[Mapping[str, object]]]):
        self._answers = answers

    @staticmethod
    def load(path: Path) -> "RecordedSource":
        try:
            with path.open(encoding="utf-8") as handle:
                body = json.load(handle)
        except (OSError, json.JSONDecodeError) as error:
            raise UnusableRun(f"the recorded answers cannot be read: {error}") from error
        if not isinstance(body, dict):
            raise UnusableRun("the recorded answers are an object keyed by the golden set's ids")
        return RecordedSource(body)

    def candidates(self, address: Address) -> list[Candidate]:
        out: list[Candidate] = []
        for raw in self._answers.get(address.id, []):
            try:
                out.append(
                    Candidate(
                        float(raw["lat"]),  # type: ignore[arg-type]
                        float(raw["lon"]),  # type: ignore[arg-type]
                        str(raw.get("confidence", "")),
                        str(raw.get("locality", "")),
                    )
                )
            except (KeyError, TypeError, ValueError) as error:
                raise UnusableRun(f"a recorded answer for {address.id} is malformed") from error
        return out


class PlatformSource:
    """The platform's own resolutions endpoint: whichever provider the environment is configured with.

    Paced under the platform's 30 lookups a minute per person (``GeocodingService``), and a
    ``429`` waits for the ``retryAfterSeconds`` it names and asks again, because a run that
    gave up at the limit would have measured the limiter and not the provider.
    """

    def __init__(
        self,
        base_url: str,
        tenant: str,
        brand: str,
        token: str,
        pause_seconds: float = 2.1,
        sleep: Callable[[float], None] = time.sleep,
        opener: Callable[..., object] = urllib.request.urlopen,
        max_attempts: int = 5,
    ):
        self._url = (
            f"{base_url.rstrip('/')}/api/v1/operations/tenants/{tenant}/brands/{brand}/geocode/resolutions"
        )
        self._token = token
        self._pause = pause_seconds
        self._sleep = sleep
        self._open = opener
        self._max_attempts = max_attempts

    def candidates(self, address: Address) -> list[Candidate]:
        body = json.dumps({"text": address.query}).encode("utf-8")
        for attempt in range(1, self._max_attempts + 1):
            request = urllib.request.Request(  # noqa: S310 - the operator's own platform URL
                self._url,
                data=body,
                method="POST",
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {self._token}",
                },
            )
            try:
                with self._open(request, timeout=15) as response:  # type: ignore[operator]
                    answer = json.load(response)
            except urllib.error.HTTPError as error:
                limited = error.code == 429 and attempt < self._max_attempts
                wait = _retry_after(error) if limited else 0.0
                error.close()
                if limited:
                    self._sleep(wait)
                    continue
                # Never the body: an error from the platform can quote what was sent.
                raise UnusableRun(f"the platform answered {error.code} to a lookup") from error
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
                raise UnusableRun(f"the platform did not answer a lookup: {type(error).__name__}") from error
            self._sleep(self._pause)
            return _candidates_of(answer)
        raise UnusableRun("the platform kept rate limiting the run")


def _retry_after(error: urllib.error.HTTPError) -> float:
    try:
        return max(1.0, float(json.load(error).get("retryAfterSeconds", 30)))
    except (json.JSONDecodeError, TypeError, ValueError, AttributeError):
        return 30.0


def _candidates_of(answer: object) -> list[Candidate]:
    if not isinstance(answer, dict):
        raise UnusableRun("the platform's answer is not an object")
    if answer.get("status") == "UNAVAILABLE":
        # Nothing was measured. "No provider is set up" and "the provider is down" are facts about the
        # environment, and scoring them as a provider that found nothing would fail Yandex for a key
        # nobody wrote to OpenBao.
        raise UnusableRun(f"the provider was unavailable ({answer.get('reason', 'unknown')}): nothing was measured")
    out: list[Candidate] = []
    for result in answer.get("results", []):
        try:
            components = result.get("components") or {}
            out.append(
                Candidate(
                    float(result["latitude"]),
                    float(result["longitude"]),
                    str(result.get("confidence", "")),
                    str(components.get("locality") or ""),
                )
            )
        except (KeyError, TypeError, ValueError, AttributeError) as error:
            raise UnusableRun("a result in the platform's answer is malformed") from error
    return out


# ---------------------------------------------------------------------------------- report


def percentile(values: Sequence[float], fraction: float) -> float:
    """Nearest-rank percentile, so the 95th of 100 addresses is the 95th and not an interpolation."""
    ordered = sorted(values)
    rank = max(1, math.ceil(fraction * len(ordered)))
    return ordered[rank - 1]


def render(report: Report) -> str:
    verdict = "HOLDS" if report.holds else "DOES NOT HOLD"
    distances = [o.meters for o in report.outcomes if o.meters is not None]
    lines = [
        f"addresses            {report.addresses} (the gate wants at least {report.min_addresses})",
        f"placed               {report.addresses - report.unplaced} (unplaced {report.unplaced}, counted as misses)",
        f"within {report.within_meters:.0f} m          {report.within} = {report.share_within:6.1%}   "
        f"limit {report.min_share_within:.0%}",
        f"outside the box      {report.outside_box}, of which not LOW_CONFIDENCE {report.outside_box_unflagged}   limit 0",
        f"wrong city           {report.wrong_city}   limit 0",
    ]
    if distances:
        lines.append(
            f"distance             median {statistics.median(distances):.0f} m   "
            f"95th percentile {percentile(distances, 0.95):.0f} m"
        )
    for kind in KINDS:
        of_kind = [o for o in report.outcomes if o.kind == kind]
        if of_kind:
            share = sum(1 for o in of_kind if o.meters is not None and o.meters <= report.within_meters) / len(of_kind)
            lines.append(f"  {kind:<8} {len(of_kind):>4} addresses, {share:6.1%} within {report.within_meters:.0f} m")
    confidences: dict[str, int] = {}
    for o in report.outcomes:
        confidences[o.confidence or "NONE"] = confidences.get(o.confidence or "NONE", 0) + 1
    lines.append("confidence           " + ", ".join(f"{k} {v}" for k, v in sorted(confidences.items())))
    lines.append(f"gate                 {verdict}")
    if not report.enough_addresses:
        lines.append(f"                     too few addresses: a verdict on {report.addresses} is not the gate")
    return "\n".join(lines)


def to_json(report: Report) -> dict[str, object]:
    """The recorded result: ids, distances and counts, nothing a person typed and no coordinate."""
    return {
        "thresholds": {
            "withinMeters": report.within_meters,
            "minShareWithin": report.min_share_within,
            "minAddresses": report.min_addresses,
            "source": report.thresholds_note,
        },
        "addresses": report.addresses,
        "unplaced": report.unplaced,
        "within": report.within,
        "shareWithin": round(report.share_within, 4),
        "outsideBox": report.outside_box,
        "outsideBoxNotLowConfidence": report.outside_box_unflagged,
        "wrongCity": report.wrong_city,
        "holds": report.holds,
        "outcomes": [
            {
                "id": o.id,
                "kind": o.kind,
                "placed": o.placed,
                "meters": None if o.meters is None else round(o.meters, 1),
                "inBox": o.in_box,
                "confidence": o.confidence,
                "wrongCity": o.wrong_city,
            }
            for o in report.outcomes
        ],
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("golden", type=Path, help="CSV of the golden set")
    parser.add_argument("--bbox", required=True, help="the region's box: sw_lat,sw_lon,ne_lat,ne_lon")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--recorded", type=Path, help="JSON of recorded answers")
    source.add_argument("--platform", help="the platform's base URL; the token is read from BAKEOFF_TOKEN")
    parser.add_argument("--tenant", help="tenant id (with --platform)")
    parser.add_argument("--brand", help="brand id (with --platform)")
    parser.add_argument("--within", type=float, default=DEFAULT_WITHIN_METERS)
    parser.add_argument("--share", type=float, default=DEFAULT_MIN_SHARE_WITHIN)
    parser.add_argument("--min-addresses", type=int, default=DEFAULT_MIN_ADDRESSES)
    parser.add_argument("--json", type=Path, help="write the recorded result here")
    args = parser.parse_args(argv)
    try:
        bbox = BBox.parse(args.bbox)
        addresses = read_golden_set(args.golden)
        if args.recorded:
            chosen: Source = RecordedSource.load(args.recorded)
        else:
            token = os.environ.get("BAKEOFF_TOKEN", "")
            if not (args.tenant and args.brand and token):
                raise UnusableRun("--platform needs --tenant, --brand and BAKEOFF_TOKEN in the environment")
            chosen = PlatformSource(args.platform, args.tenant, args.brand, token)
        report = run(addresses, chosen, bbox, args.within, args.share, args.min_addresses)
    except UnusableRun as error:
        print(f"unusable: {error}", file=sys.stderr)
        return 2
    print(render(report))
    if args.json:
        args.json.write_text(json.dumps(to_json(report), indent=2) + "\n", encoding="utf-8")
    return 0 if report.holds else 1


if __name__ == "__main__":
    sys.exit(main())
