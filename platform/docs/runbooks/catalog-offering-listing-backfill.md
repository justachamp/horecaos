# Listing a tenant's pre-existing offerings into inventory

**Last executed:** never — this is a draft, written alongside gap map row
4.1's pilot-critical fix. Run it once against pre-prod after that fix ships,
then update this line with the date and what it found.

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

**Why a runbook and not an automatic startup reconciler:** every location
this backfills is a real, already-serving tenant's live menu. A scheduler
that silently re-lists every unlisted variant across every tenant on every
boot has a much larger blast radius than this fix needs — a bug in the read
(the anti-join, the AVAILABLE filter) would touch every tenant unattended
instead of the one location an operator is watching. This runbook drives the
same idempotent endpoint a scheduler would, with a human reading each
response before moving to the next location, once, during rollout.

## 1. Find every location carrying a backlog

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -c \
  "SELECT lo.tenant_id, lo.brand_id, lo.location_id, COUNT(*) AS unlisted_count
     FROM catalog.location_offerings lo
     LEFT JOIN inventory.stock_items si
       ON si.variant_id = lo.variant_id AND si.tenant_id = lo.tenant_id
          AND si.location_id = lo.location_id
    WHERE lo.status = 'AVAILABLE' AND si.id IS NULL
    GROUP BY lo.tenant_id, lo.brand_id, lo.location_id
    ORDER BY unlisted_count DESC"
```

**Check:** a row per location that still has a backlog, with its count. Zero
rows means there is nothing to do here — either every tenant onboarded after
this wave shipped, or every offering was already listed. Stop.

## 2. List each location's backlog through the real endpoint

Through the endpoint, never a raw `INSERT` into `inventory.stock_items`:
`POST .../inventory/listing-backfill` calls the same
`StockListingPort#ensureListed` the ADR 0099 sample-menu installer and the
new event listener both call — idempotent, `BINARY`, and it never overwrites
a deliberate sold-out.

```bash
TOKEN=<a bearer token carrying INVENTORY_ADJUST at LOCATION scope for every location below>
HOST=<the environment's own API host>

qc exec -T platform-db psql -U horecaos_migrator -d horecaos -At -F',' -c \
  "SELECT DISTINCT lo.tenant_id, lo.brand_id, lo.location_id
     FROM catalog.location_offerings lo
     LEFT JOIN inventory.stock_items si
       ON si.variant_id = lo.variant_id AND si.tenant_id = lo.tenant_id
          AND si.location_id = lo.location_id
    WHERE lo.status = 'AVAILABLE' AND si.id IS NULL" \
  | while IFS=',' read -r tenant brand location; do
      echo "Backfilling tenant=$tenant brand=$brand location=$location"
      curl -sS -X POST \
        "https://$HOST/api/v1/tenants/$tenant/brands/$brand/locations/$location/inventory/listing-backfill" \
        -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json"
      echo
    done
```

**Check** each response:

- `candidateCount == listedCount` — every unlisted variant this call found
  was actually listed. If they differ, a per-item failure was logged and
  skipped (`OfferingListingBackfillService`'s own class doc) — read that
  location's own application logs for `Backfill could not list variant` and
  decide whether to retry or investigate the one variant by hand.
- `mayHaveMore: false` — the location's whole backlog fit in one call.
  `mayHaveMore: true` means it had more than
  `OfferingListingBackfillService.MAX_LOCATION_BACKFILL` (500) unlisted
  variants; re-run the exact same `curl` command against that one
  `tenant/brand/location` — each call lists another page, and because a
  variant this call just listed drops out of the next call's own unlisted
  read, repeating the same command converges without needing to track a
  cursor by hand.

## 3. Confirm the backlog is gone

Re-run the query from step 1. Zero rows.

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
