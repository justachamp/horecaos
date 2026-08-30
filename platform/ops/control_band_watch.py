#!/usr/bin/env python3
"""Control-band watcher — stage 6 of the AI-native SDLC.

Detection is arithmetic. A model decides what a breach *means*; it never decides
whether one happened, and it cannot lower a threshold to make a problem go away.

    python3 ops/control_band_watch.py --sample          # collect and evaluate
    python3 ops/control_band_watch.py --sample --dry-run
    python3 ops/control_band_watch.py --status          # show current bands
    python3 ops/control_band_watch.py --sample --only outbox-backlog

Each metric's history lives in ops/state/<id>.jsonl. Breaches escalate by tier:

    1 sigma   log        record it
    2 sigma   diagnose   Claude, read-only, writes intent/NNNN-*/intent.md
    3 sigma   act        Claude may also open a PR or run a pre-approved runbook

Tier 3 still cannot reach production: .claude/hooks/deploy-gate.sh requires a
release manager's approval regardless of what invoked the command.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BANDS = ROOT / "ops/bands.yaml"
STATE = ROOT / "ops/state"
INTENT = ROOT / "intent"

GREEN, RED, YELLOW, DIM, OFF = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"


def load_config() -> dict:
    try:
        import yaml
    except ImportError:
        sys.exit(
            "This watcher reads ops/bands.yaml, which needs PyYAML:\n"
            "    pip install pyyaml\n"
            "Operators edit the bands by hand, so the config stays YAML with its "
            "comments rather than becoming JSON."
        )
    return yaml.safe_load(BANDS.read_text())


def collect(metric: dict) -> float | None:
    """Run the metric's source command and parse one number from stdout."""
    command = metric["source"]["command"]
    try:
        proc = subprocess.run(
            ["bash", "-o", "pipefail", "-c", command],
            cwd=ROOT, capture_output=True, text=True, timeout=60,
        )
    except subprocess.TimeoutExpired:
        print(f"{YELLOW}warn{OFF}  {metric['id']}: source timed out")
        return None
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout).strip().splitlines()
        print(f"{YELLOW}warn{OFF}  {metric['id']}: source failed — "
              f"{detail[0] if detail else 'no output'}")
        return None
    match = re.search(r"-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?", proc.stdout)
    if not match:
        print(f"{YELLOW}warn{OFF}  {metric['id']}: source printed no number")
        return None
    return float(match.group(0))


def history(metric_id: str) -> list[dict]:
    path = STATE / f"{metric_id}.jsonl"
    if not path.exists():
        return []
    out = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if line:
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return out


def append(metric_id: str, record: dict) -> None:
    STATE.mkdir(parents=True, exist_ok=True)
    with (STATE / f"{metric_id}.jsonl").open("a") as fh:
        fh.write(json.dumps(record) + "\n")


def evaluate(metric: dict, value: float, past: list[float], defaults: dict) -> dict:
    """Classify a sample. Pure arithmetic — no model involved."""
    window = metric.get("window", defaults["window"])
    min_samples = metric.get("min_samples", defaults["min_samples"])
    drift_len = metric.get("consecutive_drift", defaults["consecutive_drift"])
    baseline = past[-window:]

    if len(baseline) < min_samples:
        return {"tier": 0, "reason": f"baseline has {len(baseline)}/{min_samples} samples",
                "sigma": None, "mean": None, "stdev": None}

    mean = statistics.fmean(baseline)
    stdev = statistics.pstdev(baseline)

    direction = metric.get("direction", "both")
    if stdev == 0:
        # A flat baseline: any movement in the watched direction is notable, but
        # sigma is undefined, so treat it as tier 1 rather than inventing a number.
        moved = (direction == "high" and value > mean) or \
                (direction == "low" and value < mean) or \
                (direction == "both" and value != mean)
        return {"tier": 1 if moved else 0, "sigma": None, "mean": mean, "stdev": 0.0,
                "reason": "flat baseline; value moved off it" if moved else "unchanged"}

    sigma = (value - mean) / stdev
    if direction == "high":
        signed = sigma
    elif direction == "low":
        signed = -sigma
    else:
        signed = abs(sigma)

    tier = 0
    reason = f"{signed:+.1f} sigma"
    if signed >= 3:
        tier = 3
    elif signed >= 2:
        tier = 2
    elif signed >= 1:
        tier = 1

    # Western Electric: a run on one side of the mean is drift even when no single
    # sample is extreme. This is the rule that catches slow degradation.
    if tier < 2 and len(baseline) >= drift_len:
        run = baseline[-drift_len:] + [value]
        side = "high" if direction != "low" else "low"
        drifted = all(v > mean for v in run) if side == "high" else all(v < mean for v in run)
        if drifted:
            tier = max(tier, 2)
            reason = f"{signed:+.1f} sigma; {drift_len} consecutive samples {side} of mean"

    return {"tier": tier, "sigma": signed, "mean": mean, "stdev": stdev, "reason": reason}


def next_intent_number() -> int:
    numbers = [
        int(m.group(1))
        for p in INTENT.glob("[0-9]*/")
        if (m := re.match(r"(\d+)-", p.name))
    ]
    return max(numbers) + 1 if numbers else 1


def respond(metric: dict, value: float, verdict: dict, action: str, dry_run: bool) -> None:
    """Hand a breach to Claude. Read-only to diagnose; write-capable to act."""
    number = next_intent_number()
    slug = f"{number:04d}-{metric['id']}-breach"
    runbook = metric.get("runbook")

    prompt = f"""A production control band was breached on Qoida Platform.

    Metric:      {metric['id']}
    Description: {metric.get('description', '').strip()}
    Value:       {value} {metric.get('unit', '')}
    Baseline:    mean {verdict['mean']:.4g}, stdev {verdict['stdev']:.4g}
    Breach:      {verdict['reason']} (tier {verdict['tier']}, action: {action})
    {'Runbook:     ' + runbook if runbook else ''}

Diagnose this. Read the relevant code, recent commits, migrations, and the runbook
if one is named. Correlate with what shipped recently.

Then write intent/{slug}/intent.md using intent/TEMPLATE.intent.md, with:
  - the anomaly and the evidence you actually found, quoted, with file:line or commit
  - your best diagnosis, and explicitly how confident you are
  - the proposed outcome, stated as a problem to solve rather than a patch to apply
  - affected systems and modules
  - open questions, each with a named owner where you can identify one

Set Originator to "control-band watcher ({metric['id']})".

Two things matter more than a fast answer:
  - Distinguish what you verified from what you inferred. An on-call engineer will
    triage this at an inconvenient hour and needs to know which is which.
  - If the evidence does not support a diagnosis, say so and write the intent
    anyway, with the open questions that would resolve it. A confident wrong
    diagnosis costs more than an honest "unknown"."""

    if action == "act":
        prompt += """

Tier 3: you may also open a pull request with a fix, or run a pre-approved runbook
step. Anything touching production still requires a release manager's approval —
the deploy gate will block it, and that is correct. Do not try to route around it."""

    tools = "Read,Grep,Glob,Bash(git log:*),Bash(git diff:*),Bash(git show:*)"
    if action == "act":
        tools += ",Write,Edit,Bash(make:*),Bash(gh pr create:*)"

    if dry_run or not shutil.which("claude"):
        why = "dry run" if dry_run else "claude CLI not on PATH"
        print(f"{DIM}       would invoke Claude to {action} → intent/{slug}/ ({why}){OFF}")
        return

    print(f"       invoking Claude to {action} → intent/{slug}/")
    proc = subprocess.run(
        ["claude", "-p", prompt, "--allowedTools", tools],
        cwd=ROOT, capture_output=True, text=True, timeout=900,
    )
    if proc.returncode != 0:
        print(f"{RED}       Claude failed (exit {proc.returncode}){OFF}")
        print(f"{DIM}       {(proc.stderr or proc.stdout).strip()[:300]}{OFF}")
    else:
        print(f"{GREEN}       diagnosis written{OFF}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sample", action="store_true", help="collect and evaluate now")
    ap.add_argument("--status", action="store_true", help="show the current bands")
    ap.add_argument("--only", help="a single metric id")
    ap.add_argument("--dry-run", action="store_true",
                    help="detect and record, but never invoke Claude")
    args = ap.parse_args()

    if not args.sample and not args.status:
        ap.print_help()
        return 0

    config = load_config()
    defaults = config["defaults"]
    metrics = [m for m in config["metrics"]
               if not args.only or m["id"] == args.only]
    if not metrics:
        print(f"No metric matched {args.only!r}.")
        return 1

    if args.status:
        for metric in metrics:
            past = [r["value"] for r in history(metric["id"])]
            if not past:
                print(f"  {metric['id']:<28} no samples yet")
                continue
            window = metric.get("window", defaults["window"])
            baseline = past[-window:]
            mean = statistics.fmean(baseline)
            stdev = statistics.pstdev(baseline)
            print(f"  {metric['id']:<28} last={past[-1]:<12.4g} "
                  f"mean={mean:<12.4g} stdev={stdev:<12.4g} n={len(baseline)}")
        return 0

    now = datetime.now(timezone.utc).isoformat()
    breached = 0

    for metric in metrics:
        value = collect(metric)
        if value is None:
            continue

        past = [r["value"] for r in history(metric["id"])]
        verdict = evaluate(metric, value, past, defaults)
        append(metric["id"], {"at": now, "value": value, "tier": verdict["tier"],
                              "reason": verdict["reason"]})

        tier = verdict["tier"]
        tiers = metric.get("tiers", defaults["tiers"])
        action = tiers.get(tier) if tier else None

        if tier == 0:
            print(f"{GREEN}ok{OFF}    {metric['id']:<28} {value:<12.4g} {DIM}{verdict['reason']}{OFF}")
            continue

        breached += 1
        colour = RED if tier >= 2 else YELLOW
        print(f"{colour}tier {tier}{OFF} {metric['id']:<28} {value:<12.4g} "
              f"{verdict['reason']} → {action}")

        if action in ("diagnose", "act"):
            respond(metric, value, verdict, action, args.dry_run)

    print(f"\n{breached} band(s) breached." if breached else "\nAll metrics in band.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
