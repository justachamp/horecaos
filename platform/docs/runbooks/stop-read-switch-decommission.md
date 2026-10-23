# Switching stops off: the read-switch decommission

**Not a night alert, and not a rollback.** The runbook the console's *Switching stops off* panel (Kitchen > Stop list) and a `409 MATERIALISATION_REQUIRED` both point at. **Last executed:** never; it is written against ADR 0141 and the code of batch 21 and has not been run on a real tenant.

**The way back, before the first command.** Turning reads off is the one switch that can **sell a stopped dish again**, and it is the only step here that cannot be undone by itself: a dish that came back on sale has been sold. What *can* be undone is the switch. `inventory.stops.read_enabled = true` makes the platform consult stops again at once, and no stop row is ever deleted, so every stop that was in force when you turned it off is in force again. If you only want to *pause* new stops, or to stop talking to aggregators, you do not want this runbook: use the other two switches (section 6).

**Who:** a person holding `inventory.stop.manage` at the brand starts the run and accepts its report (the console, steps 1 to 3); a platform administrator (`platform.admin`) writes the switch (step 4). They may be one person. They are two *permissions*, and a tenant owner alone cannot do step 4: the key is deliberately not tenant-visible.

## 1. Run the materialisation

Console: **Kitchen > Stop list > Switching stops off > Run materialisation**. Or:

```bash
TOKEN=...   # a user holding inventory.stop.manage at the brand
BASE=https://api.horecaos.uz
T=10000000-0000-0000-0000-000000000001   # tenant
B=10000000-0000-0000-0000-000000000002   # brand

curl -sS -i -X POST "$BASE/api/v1/tenants/$T/brands/$B/inventory/stop-materialisation-runs" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: mat-$(date +%s)"
```

The request answers when the run has finished (`201`), with the run in the body and its version in the `ETag` header: keep both, you need the run's `id` and that `ETag` in step 3.

The run takes every stop in force *now* and writes it onto the dish's own position wherever that is exact: a branch stop, a brand stop and a menu stop on a dish that is tracked as available-or-not (a `BINARY` item) become `binary_available = false` at each branch they reach, with the stop's own source and reason, and a stock movement reason `EMBARGO_MATERIALISED`. It writes nothing it cannot write exactly.

**Check:** the answer carries `status: COMPLETED` and the counts `stopsSeen`, `positionsWritten`, `positionsAlreadyUnavailable`, `notCarried`, `failedStops`. A second run while one is running answers `409 RUN_IN_PROGRESS`, so wait and read the first (`GET .../stop-materialisation-runs`, newest first). `failedStops` above zero means a stop's write failed; that stop is **not** in the run's set, the switch stays blocked, and the console says so. Run again (it is safe: a position already unavailable is counted as such, not rewritten).

## 2. Read the report

This is the step the whole runbook exists for. **The report is the one place a stop can vanish.** Console: the table under the run, 50 lines at a time. Or:

```bash
RUN=...   # the id from step 1
curl -sS "$BASE/api/v1/tenants/$T/brands/$B/inventory/stop-materialisation-runs/$RUN/report?limit=50" \
  -H "Authorization: Bearer $TOKEN"
# next page: add &cursor=<the last line's id>
```

Each line is a stop, at one branch, that **did not land on a position**. After the switch is off, that dish is on sale there again. The reason says why:

| `reasonCode` | Meaning | What you do |
|---|---|---|
| `UNTRACKED_ITEM` | the dish has no stock count or availability boolean to set | accept, or stop it at the till or with the supplier instead |
| `QUANTITY_ITEM` | the dish is counted, and a count is not a stop | accept, or set its count to zero where it is out |
| `CHANNEL_SCOPE` | the stop was for one channel only (for example the kiosk); a position is branch-wide, so writing it would stop the dish on channels the stop never covered | accept, or make the stop branch-wide on purpose first, then run again |
| `MENU_NOT_EVERY_CHANNEL` | the stop was for a menu that not every channel of that branch carries; same reason | as above |
| `WRITE_FAILED` | the write itself failed | run again; if it repeats, the log names the run |

The dish and the branch are shown by name, resolved when you read; a dish or branch that no longer resolves shows without one, with its id.

**Check:** you can say, line by line, "this dish will be on sale at this branch again, and that is fine." If one is not fine, do **not** accept: fix it (the right-hand column above), then run again from step 1. A report you do not accept costs nothing.

## 3. Accept the report

Console: **Accept the report**. Or, with the run's `ETag` as `If-Match`:

```bash
curl -sS -X POST "$BASE/api/v1/tenants/$T/brands/$B/inventory/stop-materialisation-runs/$RUN/acknowledgement" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: mat-ack-$(date +%s)" -H "If-Match: $ETAG"
```

`$ETAG` is the header from step 1, or the one a fresh `GET .../stop-materialisation-runs/$RUN` returns. A stale-version answer means the run changed since you read it: read it again, re-read the report, accept again. Only a finished run can be accepted, and only once. Accepting is audited against you by name.

**Check:** the run reads `acknowledged: true` with a time.

## 4. Turn reads off (platform administrator)

```bash
ADMIN=...   # a platform administrator's access token
curl -sS "$BASE/api/v1/control-plane/configuration/keys/inventory.stops.read_enabled/resolution?scopeType=TENANT&tenantId=$T" \
  -H "Authorization: Bearer $ADMIN"      # note currentVersionAtScope

curl -sS -X POST "$BASE/api/v1/control-plane/configuration/keys/inventory.stops.read_enabled/values" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"scopeType\":\"TENANT\",\"tenantId\":\"$T\",\"booleanValue\":false,\"expectedVersion\":null,\"reason\":\"ADR 0141 decommission: run $RUN accepted\"}"
```

Set `expectedVersion` to the `currentVersionAtScope` the first call reported, or `null` if nothing is set at that scope yet. Write it at **tenant** scope, one tenant at a time; a platform-scope write covers every tenant and is refused unless every brand of every tenant that has ever had a stop is ready.

**Check:** `200` with `value: false`. Then, in the console, the Stop list's own overlay stops listing embargoes, and a dish that was stopped only by a stop (not a position) is orderable.

## 5. What `MATERIALISATION_REQUIRED` means

`409 RESOURCE_CONFLICT` with `conflict: MATERIALISATION_REQUIRED` and a `blockedBrandCount`. Step 4 was refused and **nothing was written**. For each brand named (the ids come back at tenant scope), one of these holds:

1. **No accepted run.** Do steps 1 to 3 for that brand.
2. **A stop was created after the accepted run.** Every stop in force now has to be in the set the accepted run carried. Run again and accept the new report.
3. **A run failed on a stop** (`failedStops` above zero). Run again.

A brand that has never had a stop is never blocked.

While reads are off, creating an operator or bot stop answers `STOPS_FROZEN` (an ignored stop would only mislead), and the POS poll goes back to writing the position boolean, as it did before stops existed.

## 6. Getting back, and the two lesser switches

| I want to | Do | Effect |
|---|---|---|
| sell nothing that was stopped, again, after step 4 | `inventory.stops.read_enabled = true` (same call, `booleanValue: true`) | stops are consulted at once; every stop row that was in force is in force; a dish sold in between has been sold |
| pause *new* stops (an incident, a bad import) | `inventory.stops.creation_enabled = false` | new operator and bot stops answer `STOPS_FROZEN`; lifts, expiry, reads, and the POS poll's own stops continue; nothing is sold again |
| stop talking to every aggregator | `marketplace.availability.reconcile_enabled = false` | no call leaves for any partner; channels read *not propagated automatically*; on resume every row is resent once |

Only the first can put a stopped dish back on sale. The other two are the rollback ADR 0141 recommends ("freeze, do not disable").

## When it goes wrong

| Seen | Meaning | Do |
|---|---|---|
| step 1 answers `409` | a run is already running for this brand | read it; wait for `COMPLETED` |
| the report is long | the brand has many `UNTRACKED_ITEM` dishes | page through it; this is the information, not noise |
| step 4 `409 MATERIALISATION_REQUIRED` | section 5 | section 5 |
| a dish sold after step 4 that was stopped | it was on the report (it was accepted), or it was stopped after the run | check the report for that dish; if it is not there, the stop was made after the run and step 4 should have been refused: that is a defect, keep the evidence and turn reads back on |
