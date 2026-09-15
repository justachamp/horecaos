# ADR 0134: Persisted ABC/XYZ product classification as its own run, not a projected fact

- Decision status: Proposed
- Implementation status: Built — the persisted run (schema, service,
  endpoints, Java tests), the product report's own defect fix (7.7:
  `Категория`, `СТОП`, filter-bar reactivity, the DINE_IN disclosure), and the
  ABC/XYZ console surface (7.7a/7.7b: both tabs, the cumulative column, the
  AX/CZ matrix as a click-through filter, the run/recompute action) are built
  and tested this wave (`wave140-t14`) — an operator holding
  `reporting.classification.run` can request a run over a valid window and
  read it back from the console today.
- Date proposed: 2026-09-15
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction
  of 2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0043
- Supersedes / Superseded by: —
- Open inputs: whether the XYZ coefficient-of-variation thresholds (10% / 25%,
  the conventional textbook split — statistics.md names only ABC's 80%/95%)
  should stay a platform-wide default or become a value a tenant can tune, the
  way `ADR 0043 §S2.3`'s SLA bucket boundaries are deliberately fixed today
  (platform owner); whether a classification run needs a retention/pruning
  policy once real tenants accumulate months of on-demand runs (platform
  owner, no volume evidence yet to size it against)

## Context

statistics.md S2.7 states the whole reason 7.7a exists: "Delever's ABC page
states the 80/15/5 split in prose and shows a class letter; a manager who
disputes a product being class C has nothing to look at." The substrate for
the arithmetic was already close — `readVariantSales` (7.7's own read) already
returns per-product revenue ordered descending, and the page already computes
a per-row share from it. What was genuinely absent was a **record**: nothing
in `reporting` persists the window, the thresholds, or the metric a
classification was computed under, so a class-C ruling could not be checked
against anything after the page was closed.

`reporting`'s existing pattern for a derived number is a `fact_*` table:
written once at `DayCloseService`'s own close, rebuildable from the same
source every time, and read many times. A classification run does not fit
that shape. It is not a projection of an operational event that already
happened — it is the answer to a question a manager asked, at the moment they
asked it, over a window and thresholds they (or the console, on their behalf)
chose. Re-running it later over the same window can honestly disagree with an
earlier run (more orders may have closed since), and a dispute is defended by
pointing at the run that was live when the dispute was raised, not by a
number a nightly job might silently recompute out from under it.

## Decision

1. **`reporting.classification_run` and `classification_result` are their own
   tables, not `fact_*` tables**, and are never updated or deleted — see each
   migration's own comment. A run is triggered by a new capability,
   `reporting.classification.run` (`Capability.REPORTING_CLASSIFICATION_RUN`),
   deliberately separate from `reporting.read`: starting a run writes rows,
   and a read-only viewer of the product report must not be able to mint a
   new disputable record just by opening a tab. Reading the most recently
   computed run over an exact window (`GET .../classification-runs/latest`)
   stays behind `reporting.read` — seeing what was last computed is a read.

2. **ABC ranks by `revenue.gross.v1`**, statistics.md's own printed line
   ("`metric revenue.gross.v1`"), not net — a promotion given away as a
   discount still counts as the sale that earned the product its shelf
   space. Cumulative share is classified against `ClassificationThresholds
   .DEFAULT`'s 80%/95% split (statistics.md's own "пороги 80/95%"), stored on
   every run as basis points so the boundary is an exact integer rather than
   a rounded percentage nobody can reproduce.

3. **XYZ's coefficient of variation is computed over 7-day buckets spanning
   the requested window**, and the ≥28-day floor
   (`ProductClassificationService.MINIMUM_WINDOW_DAYS`) is chosen so the
   shortest allowed window always has exactly four buckets to vary across —
   28 = 4 × 7 is not a coincidence, it is why 28 was picked as the floor
   rather than, say, 21 or 30. A missing bucket is zero, never omitted: a
   product with one huge week and three silent ones is exactly what "erratic"
   means, and dropping the silent buckets would average them away.
   `CoefficientOfVariation` uses population statistics (`/n`, never the `n-1`
   sample correction) because every bucket the window has is used, not a
   sample drawn from a larger one.

4. **A run always recomputes and writes a new row**, even over a window just
   computed. `GET .../latest` exists so a repeat page view does not pay a
   write (or need the write capability) to see what was already there, but
   nothing coalesces two `POST` calls into one row — see Consequences for the
   accepted cost.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Project the classification into a `reporting.fact_*` table, recomputed at `DayCloseService`'s own close, the same shape as every other reporting number | A dispute over a stored class-C ruling needs the exact record that was true when the dispute was raised; a nightly recompute would silently answer differently for a re-read of an old dispute, defeating the whole reason 7.7a exists | Never for the disputed-run record itself; a *separate*, always-current "current classification" read model projected from the same source could still be added later for a dashboard tile, without replacing the run record |
| Compute XYZ's coefficient of variation over daily buckets instead of 7-day buckets | Day-of-week ordering effects (a weekend restaurant's Friday/Saturday spike) would read as "erratic" for nearly every product at pilot scale, which is not the demand-variability signal XYZ exists to surface; daily buckets also do not align cleanly with any natural window floor | If a tenant's own operating rhythm (e.g. a 24/7 kiosk format) makes day-of-week seasonality genuinely absent, so a finer grain would not manufacture noise |
| Reuse `reporting.read` for starting a run, on the reasoning that 7.7 is already a read-only screen | A run is a write — it persists rows a later dispute is checked against — and ADR 0025's capability model ties a capability to the actual effect, not to the screen it is reached from | Never, without changing what a "read" means platform-wide |
| Let the caller pass custom ABC/XYZ thresholds per run instead of a fixed platform default | A materially larger build for this wave's 2-day allocation (validation, a console control, and a second set of numbers to explain in the printed caption line) with no named row asking for it | If a tenant with an unusual margin structure finds the 80/95 or 10%/25% defaults genuinely wrong for their catalogue |
| `location_ids` as a native `uuid[]` or `jsonb` column | Nothing ever queries inside it — every read matches the whole set against the whole set (`readVariantBuckets`'s `location_id IN (...)`, `findLatestRun`'s exact-window match) — so the array-binding machinery a `uuid[]` insert needs buys no query capability over a plain comma-joined string, only more code | If a future read ever needs to find every run touching one location without also knowing the run's other locations |

## Consequences

### Positive

- A disputed class-C ruling now has an exact, recorded answer: the window,
  the thresholds, the metric, and who asked for it — statistics.md S2.7's own
  stated improvement over Delever.
- The ≥28-day floor is enforced by the schema itself
  (`ck_classification_run_window_floor`), not only by the service — a
  migration bug or a future direct-SQL caller cannot silently insert a run
  that violates the one invariant the whole feature exists to protect.
- `CoefficientOfVariation` and `ClassificationThresholds` are pure,
  dependency-free functions, directly unit-testable against a known series
  with no database — see `CoefficientOfVariationTests`.

### Negative

- Every `POST` writes a new run and its full result set, with no
  deduplication against an identical recent request. A manager (or a script)
  calling it repeatedly over the same window accumulates rows with nothing
  pruning them — acceptable at pilot scale (one tenant, a console with no
  polling loop against this endpoint) but a real cost at multi-tenant volume.
- A window that is not an exact multiple of 7 days has a shorter final
  bucket, whose lower raw quantity (fewer days to sell in) is used unadjusted
  in the coefficient-of-variation series — a small, systematic bias toward
  reading the last partial bucket as a dip. Not corrected in this wave.
- XYZ's 10%/25% coefficient-of-variation thresholds are a platform default
  chosen from the conventional textbook split, not sourced from
  statistics.md (which names only ABC's 80/95) and not yet reviewed by the
  platform owner — see Open inputs.
- `classification_result` has no read that breaks one run down per-location;
  a multi-location tenant gets one run per explicit `locationIds` selection,
  never an automatic per-location split within a single tenant-wide run.

### Accepted trade-offs

A classification run's correctness depends on `reporting.fact_order_line`
being fully closed for the requested window at the moment the run is
computed — the same dependency every other `reporting` read already has on
`DayCloseService`, not a new one. This ADR does not attempt to make a run
retroactively correct if it is computed against a day whose close later gets
recut; `MixedBoundaryRegimeException`'s existing refusal (reused unchanged
from `ReportQueryService`) is the only guard against that case today.

## Specification

See `db/migration/V0364__reporting_classification_run.sql`,
`V0365__reporting_classification_result.sql`,
`reporting/domain/{ClassificationThresholds,ClassificationRun,CoefficientOfVariation}.java`,
`reporting/application/ProductClassificationService.java`,
`reporting/infrastructure/persistence/JdbcClassificationStore.java`, and
`reporting/web/ProductClassificationController.java`.

## Rollout and rollback

Additive only: two new tables, one new capability, one new controller, and an
additive `fulfilmentType` parameter on the existing `/variant-sales` read (7.7's
own defect fix). No existing endpoint, event contract, or migrated column
changes shape. Rollback is removing the capability from every role bundle
(refusing every future run while leaving already-written rows in place as a
historical record) or dropping the two tables outright if no run has been
relied on yet.

## Implementation checklist

- [x] `reporting.classification_run` + `classification_result` (V0364, V0365)
- [x] `Capability.REPORTING_CLASSIFICATION_RUN`, granted to
      `TENANT_OWNER`/`TENANT_FINANCE`/`BRAND_MANAGER`/`LOCATION_MANAGER`
- [x] `ProductClassificationService.run`/`latest`, the ≥28-day guard, ABC/XYZ
      classification
- [x] `ProductClassificationController` (`POST`/`GET .../latest`)
- [x] 7.7's own defect fix: `variant-sales` reacts to `fulfilmentType`, the
      filter-bar reactivity defect, the `Категория` column and the `СТОП`
      marker (frontend; see this wave's own report for exactly what shipped)
- [ ] A tenant-tunable ABC/XYZ threshold override (see Alternatives) — future
      wave, no row asks for it yet

## Exit criteria

A manager holding `REPORTING_CLASSIFICATION_RUN` can request a classification
over a ≥28-day window from the console and see the AX/CZ matrix and both
tabs' columns render from a run that is still readable (`GET .../latest`)
after navigating away and back, printing the exact window, thresholds and
computed-at instant statistics.md's own caption line describes. A window
under 28 days is refused with a stated reason rather than silently answered.

## References

- ADR 0043 (reporting)
- `docs/operations-spec/statistics.md` S2.7
- `docs/operations-gap-map.md` rows 7.7, 7.7a, 7.7b
