#!/usr/bin/env python3
"""Tests for control-band detection.

This arithmetic decides when an agent gets woken up. Too sensitive and every
sample pages someone; too dull and drift goes unnoticed for a week. Both failure
modes are silent, so they get tests.

Run with `make bands-test`, or directly.
"""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "control_band_watch", Path(__file__).resolve().parent / "control_band_watch.py")
cbw = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cbw)

DEFAULTS = {"window": 30, "min_samples": 10, "consecutive_drift": 8}
HIGH = {"id": "t", "direction": "high"}
LOW = {"id": "t", "direction": "low"}
BOTH = {"id": "t", "direction": "both"}

failures: list[str] = []


def check(name: str, expected_tier: int, metric: dict, value: float,
          past: list[float]) -> None:
    verdict = cbw.evaluate(metric, value, past, DEFAULTS)
    if verdict["tier"] == expected_tier:
        print(f"\033[32mok\033[0m   tier {expected_tier}  {name}")
    else:
        print(f"\033[31mFAIL\033[0m tier {expected_tier}  {name} "
              f"(got tier {verdict['tier']}: {verdict['reason']})")
        failures.append(name)


# A steady baseline with a little noise: mean 100.05, stdev 1.564.
# Sigma boundaries, so the expectations below are exact rather than eyeballed:
#   1 sigma = 101.61,  2 sigma = 103.18,  3 sigma = 104.74
STEADY = [98, 102, 99, 101, 100, 103, 97, 100, 101, 99,
          100, 102, 98, 101, 99, 100, 102, 98, 100, 101]

# --- not enough history -----------------------------------------------------
check("no history at all", 0, HIGH, 500, [])
check("baseline below min_samples stays quiet", 0, HIGH, 500, [100, 101, 99])

# --- ordinary variation -----------------------------------------------------
check("a sample at the mean", 0, HIGH, 100, STEADY)
check("ordinary noise, inside 1 sigma", 0, HIGH, 101.5, STEADY)

# --- breaches ---------------------------------------------------------------
check("mild rise, past 1 sigma", 1, HIGH, 102.5, STEADY)
check("clear rise, past 2 sigma", 2, HIGH, 104.0, STEADY)
check("severe rise, past 3 sigma", 3, HIGH, 110, STEADY)

# --- direction --------------------------------------------------------------
check("a fall does not breach a high-watching metric", 0, HIGH, 40, STEADY)
check("a value just under 1 sigma stays quiet", 0, HIGH, 101.6, STEADY)
check("a fall breaches a low-watching metric", 3, LOW, 40, STEADY)
check("a rise does not breach a low-watching metric", 0, LOW, 130, STEADY)
check("either side breaches a both-watching metric", 3, BOTH, 130, STEADY)
check("either side breaches a both-watching metric, falling", 3, BOTH, 40, STEADY)

# --- Western Electric drift -------------------------------------------------
# Every sample is individually unremarkable, but the run is one-sided. This is
# the case a plain sigma threshold misses entirely.
DRIFT = STEADY[:10] + [101, 101.5, 102, 101, 102, 101.5, 102, 101]
check("eight consecutive samples above the mean is drift", 2, HIGH, 101.2, DRIFT)

NO_DRIFT = STEADY[:10] + [101, 99, 102, 98, 101, 99, 102, 98]
check("an alternating run is not drift", 0, HIGH, 100, NO_DRIFT)

# --- degenerate baselines ---------------------------------------------------
FLAT = [0] * 20
check("movement off a flat baseline is tier 1, not a divide-by-zero", 1, HIGH, 3, FLAT)
check("a flat baseline that stays flat is quiet", 0, HIGH, 0, FLAT)
check("movement the wrong way off a flat baseline is quiet", 0, HIGH, -3, FLAT)

# --- window ------------------------------------------------------------------
# Only the last `window` samples form the baseline, so an old regime must not
# keep a metric permanently "breached" after it settles at a new level.
OLD_REGIME = [1000] * 40 + [100] * 30
check("a settled new regime is in band once it fills the window", 0, HIGH, 100, OLD_REGIME)

print()
if failures:
    print(f"{len(failures)} band test(s) failed:")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print("all band tests passed")
