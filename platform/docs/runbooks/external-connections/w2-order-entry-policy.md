# External connections: order entry and order policy (wave `w2-order-entry-policy`, ADR 0177)

**Last executed:** never. Nothing below has been connected; each section says what was built against a
stand-in and what the owner or another person completes.

Three things this wave builds are finished on the platform's side and wait on a person outside the
repository. None blocks anything else: with each one unconnected the product works, and says in words what
it is missing. Each section answers the same four questions, in the order a person at a keyboard needs
them: what exists, where the value goes, how to prove the connection, and how to go back.

---

## 1. The map provider behind the New order pin and search (`1.3b`, `X.4`)

**What exists.** The whole map layer: `GET .../brands/{brandId}/map-config`, the three rate-limited lookups
(`POST .../locations/{locationId}/geocode/suggestions`, `/resolutions`, `/reverse-resolutions`), the Yandex
adapter proved against recorded provider bodies (`YandexGeocoderAdapterTests`), and the controlled fake the
`local` profile registers (`FakeGeocoderAdapter`). `HORECAOS_GEO_PROVIDER` defaults to `none`, in which every
lookup answers `NOT_CONFIGURED`, `map-config` says `configured: false`, and the New order address pane shows
the map's coordinate fields and says no provider is configured. **The operator types the point** (an address
with no pin is refused for delivery, so this is the working path today, and it is what a phone order uses
whether or not a map is ever connected).

**What only the owner (and finance) can do.**

- Open a Yandex Maps account and a licence, and ask finance how a licence is paid for from Uzbekistan (ADR
  0145's open input; ADR 0073 records international payment as "a real obstacle"). Until that is answered
  nothing here is a build task.
- Hold the **server key** and the **browser key**. The server key is a secret and goes only into OpenBao
  (ADR 0028); the browser key is public and referrer-restricted.

**Where the values go.** On the production host, in the environment file the deployment reads (`/etc/horecaos/production.env`;
the names are documented at the `horecaos.geo` block of `application.yml`, and `deploy/env.template` does
not list them yet, so add them there in the same change that sets them), never in this repository:

```sh
HORECAOS_GEO_PROVIDER=yandex
HORECAOS_GEO_YANDEX_SECRET_REFERENCE=horecaos:production:provider_geocoding:platform:yandex-server-key
HORECAOS_GEO_YANDEX_BROWSER_KEY=<the public, referrer-restricted browser key>
```

and the server key into OpenBao, in the platform-owned `provider_geocoding` category (no tenant can write it):

```sh
bao kv put horecaos/production/provider_geocoding/platform/yandex-server-key value=<the server key>
```

The provider's own page, `docs/providers/yandex-maps.md`, lists every property and what it does. Restart the
application. The endpoint is not configured here: it is the approved Yandex row of
`integration.provider_environments`, as for every provider (ADR 0026).

**How to prove the connection.** In order, each one a thing you can see:

1. `GET /api/v1/tenants/{t}/brands/{b}/map-config` as an operator answers `configured: true`, the provider
   `yandex`, the browser key and the attribution text.
2. In the console, New order, a delivery order, "+ new address": typing in the search box offers suggestions,
   and choosing one puts a pin on a map that draws real tiles. If the tiles are blank, the browser key's
   referrer restriction does not include the console's origin.
3. The pin you confirm saves as `OPERATOR_PIN`, never as `GEOCODER` (ADR 0145 decision 5):
   `SELECT coordinate_source FROM customer.addresses ORDER BY created_at DESC LIMIT 1;`.
4. The metering counter for lookups moves, and a burst past the rate limit answers `429 RATE_LIMIT_EXCEEDED`.

**How to go back.** Set `HORECAOS_GEO_PROVIDER=none` and restart. Saved pins and addresses are untouched, and
the pane returns to coordinate fields.

---

## 2. Settlement evidence for a manually entered aggregator order (`1.3g`, ADR 0114)

**What exists.** Creating the order: `POST .../orders/aggregator-entries` records an aggregator's phoned-through
order under its `AGGREGATOR` channel with the aggregator's own totals, as `origin = MARKETPLACE`,
`pricing_authority = EXTERNAL`, `entry_mode = MANUAL`, for `PICKUP` and for `DELIVERY` (against a saved address
the operator gave through `customer.address.capture`). It is proved over HTTP
(`OperationsAggregatorEntryHttpTests`). The information architecture's row names creating the order, and
creation works.

**What is not built, and why.** A manual entry writes no `ordering.order_external_pricing` row (and no
`order_handover_challenges` row). Nothing in `src/main` reads that table (only the automated marketplace
intake writes it), so a row written now would be write-only. Whether to write one, and how a manual entry
should reconcile against a statement that has no automated push, is **finance's answer** (ADR 0114's open
input); whether `fulfillment_authority` may ever be `PARTNER` is product's.

**What the person completes.** Finance says yes or no. If yes, the build is small: the typed totals map one to
one onto the V0038 columns (`UNKNOWN` funding, null settlement columns, `arithmetic_verified = true`,
`ck_external_pricing_reconciles` holds because the service already derives tax so the total reconciles), and a
reader is built in the same change so the row is not write-only.

**How to prove the connection.** Record a manual entry; `SELECT * FROM ordering.order_external_pricing WHERE
order_id = '<id>'` returns one row; the settlement report that reads it includes the order.

**How to go back.** Stop writing the row; the order itself is unaffected.

---

## 3. The late-order-threshold stored values (`10.3b`, ADR 0150)

**What exists.** `docs/runbooks/late-order-threshold-stored-values.md` and the SQL it runs, proved by
`LateOrderThresholdRunbookTests`. The runbook's own header says **Last executed: never.**

**What only an operator with database access to the real hosts can do, with the owner's go.** Run that runbook
against pre-prod (`gcloud` IAP ssh to the VM `horecaos`, `/etc/horecaos/production.env`, `sudo -E`) and then
production, find any tenant that saved `ordering.late_order_threshold_minutes` while it did nothing, and tell
that tenant its meaning has changed. Order: pre-prod first, read the rows, then production.

**How to prove it.** The runbook's own verification query returns the rows it was written to find; record the
date it was executed at the top of that runbook.

**How to go back.** The runbook is a read and a notification; it changes no data.

---

## 4. Stored values of the five retired order-policy keys (informational)

Not an external connection, recorded here because it is the one migration of this wave that touches data:
V0653 deletes any stored `tenant.configuration_values` row for `ordering.business_day_start_hour`,
`ordering.average_order_minutes`, `ordering.maximum_order_minutes`, `ordering.vat_rate_percent` and
`ordering.routing_poll_interval_minutes`, because the startup validator refuses to boot over a row whose key
is no longer declared. Before the release that ships it reaches a real host, count what would go:

```sql
SELECT key_code, scope_type, count(*) FROM tenant.configuration_values
WHERE key_code IN ('ordering.business_day_start_hour', 'ordering.average_order_minutes',
                   'ordering.maximum_order_minutes', 'ordering.vat_rate_percent',
                   'ordering.routing_poll_interval_minutes')
GROUP BY 1, 2;
```

An empty result is the expected one: the page said four of the five were "not applied yet" and none ever had a
reader, so no behaviour depended on any value. A non-empty result is worth a sentence to the tenant, not a
reason to hold the release.
