# Listing a tenant's pre-existing offerings into inventory

**Last executed:** 2026-09-29 on **pre-prod**: 10 candidate offerings, 0 listed
at both JizBiz branches — every offering was already listed in inventory, so
the backfill found nothing to do. Also 2026-09-29, locally, in two halves —
read the second one before trusting the rest of this file on a real host.
Not yet run against production.

- Step 1's query ran against the local Postgres: an empty backlog (the
  local fixtures are fully listed), then the same query on three
  deliberately unlisted rows inside a rolled-back transaction, which returned
  the one expected location with `unlisted_count = 3`.
- Steps 2 to 4 were **not** run with `curl` against a running API. Signing
  in needs a staff access token, and standing up the app, Keycloak and a
  grant for one runbook was out of proportion. Instead these tests in
  `platform/src/test/java/uz/horecaos/platform/inventory/web/` guard it:
  - `InventoryUnlistedOfferingsReportTests#runbookCommandsAreRealAndItsChecksHold`
    parses this very file and dispatches its own `curl` commands (method,
    path, headers) at the real controllers over MockMvc, then asserts the
    response fields the checks below read.
  - `InventoryUnlistedOfferingsReportTests#runbookMintsAFreshIdempotencyKeyPerCall`
    runs step 3's command twice and fails if it stops minting a new
    `Idempotency-Key` per call.
  - `InventoryUnlistedOfferingsReportTests#runbookNamesOnlyTestsThatExist`
    fails if this note cites a test that is not there.

  A renamed path or field fails one of those rather than an operator.
- Pre-prod (2026-09-29): run after the auto-listing fix shipped. Ten
  candidate offerings were checked and **zero** were listed at either of the
  two JizBiz branches — everything was already listed, so there was nothing
  to backfill.
- Production: not yet run. When it is, update the "Last executed" line at the
  top of this file with the date, the host, and what it found.

One-time catch-up, not a recurring job. From this wave onward,
`CatalogAuthoringService` publishes `OfferingBecameAvailable` whenever an
offering is created or set `AVAILABLE`, and
`inventory.application.CatalogOfferingListingTrigger` lists it in inventory
automatically — every offering an operator sets `AVAILABLE` from now on needs
no action here. This runbook is only for offerings that were already
`AVAILABLE` **before** that fix shipped: each one still has no
`inventory.stock_items` row and reads as unsellable
(`NOT_STOCKED_AT_LOCATION`) regardless of `catalog.use_stock_logic`, exactly
as `InventoryService#evaluateAvailability` has always treated an unlisted
variant — the gap this fix closes going forward, and this runbook closes for
what already existed.

**One branch, or one dish, by hand?** The operations console does it with the
same endpoints and no shell: Catalog → Stock shows an "Offered here but not
listed in inventory" report for the selected branch with a **List all**
button (`POST .../inventory/listing-backfill`), and the product editor's
Availability tab lists one variant at every branch that offers it. Use this
runbook when many tenants or branches need catching up in one sitting.

**Why a runbook and not an automatic startup reconciler:** every location
this backfills is a real, already-serving tenant's live menu. A scheduler
that silently re-lists every unlisted variant across every tenant on every
boot has a much larger blast radius than this fix needs — a bug in the read
(the anti-join, the AVAILABLE filter) would touch every tenant unattended
instead of the one location an operator is watching. This runbook drives the
same idempotent endpoint a scheduler would, with a human reading each
response before moving to the next location, once, during rollout.

## Before you start

On the host, as root, in the compose directory (the same two shortcuts
[deploy.md](deploy.md) uses):

```bash
cd /opt/horecaos/horecaos-platform
alias qc='docker compose -f compose.production.yaml --env-file /etc/horecaos/production.env'
HOST=api.horecaos.uz   # production; a pre-prod host is its own API host
```

Steps 2 and 3 also need `curl`, `jq` and `uuidgen` on the host they run from,
and nothing here guarantees them: [deploy.md](deploy.md) runs its own `curl` and
`jq` scripts in the `ops` container precisely because the host needs neither.
Check before step 2, and install whatever is missing:

```bash
for tool in curl jq uuidgen; do
  command -v "$tool" >/dev/null || echo "MISSING on this host: ${tool}"
done
```

**Check:** no `MISSING` line.

**The token is per tenant.** Steps 2 and 3 call tenant endpoints, and a
token only acts inside the tenant it was issued for. Use an access token of
that tenant's owner or admin (`tenant-owner`, `tenant-admin` or
`location-manager` at a scope covering the branch — all three hold
`inventory.adjust`, and `inventory.read` for the dry run). Do one tenant at a
time; there is no single token for "every location below".

## 1. Find every location carrying a backlog

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -At -F',' <<'SQL' > backlog.csv
SELECT lo.tenant_id, lo.brand_id, lo.location_id, COUNT(*) AS unlisted_count
  FROM catalog.location_offerings lo
  LEFT JOIN inventory.stock_items si
    ON si.variant_id = lo.variant_id AND si.tenant_id = lo.tenant_id
       AND si.location_id = lo.location_id
 WHERE lo.status = 'AVAILABLE' AND si.id IS NULL
 GROUP BY lo.tenant_id, lo.brand_id, lo.location_id
 ORDER BY lo.tenant_id, unlisted_count DESC;
SQL
column -s, -t backlog.csv
```

**Check:** a row per location that still has a backlog, with its count
(`tenant_id  brand_id  location_id  unlisted_count`). An empty file means there
is nothing to do here — either every tenant onboarded after this wave
shipped, or every offering was already listed. Stop.

The file has no tenant names and no personal data; delete it when you are
done.

## 2. Dry run: what would be listed

Read-only. `GET .../inventory/unlisted-offerings` describes exactly the set
step 3 will list — same anti-join, same `AVAILABLE` filter — without
touching anything. Pick the tenant, get its token, and read each location:

```bash
TENANT=<one tenant_id from backlog.csv>
read -rsp 'access token for this tenant: ' TOKEN; echo
API="https://${HOST}/api/v1"

while IFS=',' read -r tenant brand location count; do
  base="${API}/tenants/${tenant}/brands/${brand}/locations/${location}/inventory"
  echo "== location ${location}: step 1 counted ${count}"
  curl -fsS "${base}/unlisted-offerings?limit=5" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Accept: application/json" | jq '{totalCount, hasMore, sample: [.items[].productName]}'
done < <(grep "^${TENANT}," backlog.csv)
```

**Check:** `totalCount` equals that location's `unlisted_count` from step 1.
The `sample` names five of the dishes, so an operator who knows the menu can
see the list is the right one. A location that shows `totalCount: 0` was
listed by hand since step 1 — nothing to do for it.

## 3. List each location's backlog through the real endpoint

Through the endpoint, never a raw `INSERT` into `inventory.stock_items`:
`POST .../inventory/listing-backfill` calls the same
`StockListingPort#ensureListed` the ADR 0099 sample-menu installer and the
new event listener both call — idempotent, `BINARY`, and it never overwrites
a deliberate sold-out.

```bash
while IFS=',' read -r tenant brand location count; do
  base="${API}/tenants/${tenant}/brands/${brand}/locations/${location}/inventory"
  while :; do
    result="$(curl -fsS -X POST "${base}/listing-backfill" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Idempotency-Key: $(uuidgen)" \
      -H "Content-Type: application/json")" || { echo "FAILED at location ${location}"; break; }
    echo "location ${location}: ${result}"
    more="$(jq -r '.mayHaveMore and .listedCount > 0' <<<"${result}")" \
      || { echo "jq could not read the response at location ${location}"; break; }
    [ "${more}" = "true" ] || break
  done
done < <(grep "^${TENANT}," backlog.csv)
```

The loop runs in bash. It stops re-calling a location when a call lists nothing
(`listedCount: 0`), so a location whose remaining variants keep failing cannot
spin forever — that is the case to investigate from the logs, not to loop on.
It also stops, and says so (`jq could not read the response`), when `jq` cannot
parse a response, so a missing `jq` never ends a location quietly after its
first page of a bigger backlog.

The call is a `POST` that takes **no body** and needs the `Idempotency-Key`
header (ADR 0031) — without it the API answers `400 IDEMPOTENCY_KEY_REQUIRED`.
Each call in the loop mints its own key on purpose: the key is one *intent*,
and every page of a big backlog is a new one. **Never paste a fixed key into
this command.** The server remembers the answer to a key, so re-sending the
same key replays the first response (`Idempotency-Replayed: true`) and lists
nothing new — the safe behaviour for a lost response, and exactly wrong for
"do the next page". If a call fails partway (`curl` exits non-zero, the loop
prints `FAILED at location`), fix the cause and re-run the loop: a location
already caught up answers `candidateCount: 0`.

**Check** each printed response (`{"candidateCount":…,"listedCount":…,"mayHaveMore":…}`):

- `candidateCount == listedCount` — every unlisted variant this call found
  was actually listed. If they differ, a per-item failure was logged and
  skipped (`OfferingListingBackfillService`'s own class doc) — read that
  location's own application logs for `Backfill could not list variant` and
  decide whether to retry or investigate the one variant by hand.
- `mayHaveMore: false` — the location's whole backlog fit in one call. The
  loop above already re-calls a location while `mayHaveMore` is `true` (it had
  more than `OfferingListingBackfillService.MAX_LOCATION_BACKFILL`, 500,
  unlisted variants); each call lists another page because a variant this call
  just listed drops out of the next call's own unlisted read, so repeating
  converges without a cursor.
- A `403` (curl prints nothing useful under `-f`; re-run one call without it)
  means the token lacks `inventory.adjust` for that branch, or belongs to a
  different tenant than `TENANT`.

## 4. Confirm the backlog is gone

Re-run the dry run from step 2 for the same tenant: every location reports
`totalCount: 0`. Then re-run the query from step 1 for the whole host:

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -At -F',' <<'SQL'
SELECT lo.tenant_id, lo.location_id, COUNT(*)
  FROM catalog.location_offerings lo
  LEFT JOIN inventory.stock_items si
    ON si.variant_id = lo.variant_id AND si.tenant_id = lo.tenant_id
       AND si.location_id = lo.location_id
 WHERE lo.status = 'AVAILABLE' AND si.id IS NULL
 GROUP BY lo.tenant_id, lo.location_id;
SQL
```

**Check:** no rows for the tenant you just did. Repeat steps 2 to 4 for the
next tenant in `backlog.csv`, then:

```bash
unset TOKEN
rm -f backlog.csv
```

## What this does not do

- **Never lists a variant a kitchen has deliberately marked sold out.**
  `ensureListed`'s own idempotency leaves an existing stock item — available
  or not — exactly as it is. A location that looks unchanged after this ran
  is not a bug; it means every offering there was either already listed or
  never actually `AVAILABLE`.
- **Never touches a `HIDDEN` or `UNAVAILABLE` catalog offering.** Only
  `AVAILABLE` offerings are backfill candidates — the same condition
  `OfferingBecameAvailable` fires on going forward, so this runbook and the
  event agree about what "should be listed" means.
- **Does not backfill QUANTITY items with a real on-hand count.** It lists
  `BINARY`, starting available, matching the sample-menu path exactly. A
  tenant that tracks real quantities sets its own on-hand counts afterward
  through the console's `/catalog/stock` page (gap map row 4.4c) —
  backfilling a fabricated on-hand number here would be a worse guess than
  leaving it for an operator who actually knows the count.
