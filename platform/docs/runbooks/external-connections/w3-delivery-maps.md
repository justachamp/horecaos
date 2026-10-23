# External connections: delivery maps (wave `w3-delivery-maps`, batch 21)

Last executed: never. Everything below is a fact that exists outside this repository (a key,
an account, a contract, a survey, a legal opinion). The platform was built against a fake or a
null provider for each, so each screen works in its dark state today and each connection is a
configuration change, not a code change. This file says, for each fact: **what exists**, **where
the value goes**, **how to prove the connection**, and **how to take it back**.

Rows this file serves: `X.4` (map seam), `3.1` (dispatch board map pane), `3.2` (live map) for
sections 1 to 7; `3.3` (a courier's first sign-in by text message) for section 8; `3.1b` (the
deliveries-console module and the extensions it declines) for sections 9 and 10; `3.4c` and the
moved `3.9` gate item for section 11, which names what is not an external fact but is still open.
The governing record for the maps is [ADR 0145](../../adr/partial/0145-map-and-geocoding-provider.md); the route
descriptor is [`routes/geo-lookup.md`](../../routes/geo-lookup.md); the provider transcription is
[`providers/yandex-maps.md`](../../providers/yandex-maps.md); the processors it adds to are in
[`processor-register.md`](../../processor-register.md).

**What a re-auditor should expect to see before any of this is done.** `GET .../map-config`
answers `configured: false`; every address lookup answers `200` with `status: UNAVAILABLE` and
`reason: NOT_CONFIGURED`; the console's map areas say "no map provider is set up in this
environment" and keep the coordinate table and the typed numbers; the storefront's address
screen lets the customer type an address and saves it `NOT_GEOCODED`. That is the designed state
(ADR 0145 decision 6), not a fault.

## 1. The server key (geocoder and suggest)

| | |
|---|---|
| What exists | `YandexGeocoderAdapter` on the ADR 0007 route `geo.lookup.v1` (circuit breaker, deadlines, outcome model); the two approved endpoint rows `yandex_geocoder_production` and `yandex_suggest_production` (V0490); the response cache and the `geocode.requests` meter; `FakeGeocoderAdapter` (local profile and tests only); fixtures transcribed from the documented response shape, **not captured from the live service** |
| What is missing | A Yandex account, a licence, and a **server** key |
| Where the value goes | Into OpenBao, under the ADR 0028 reference the platform is told about. The reference is configuration; the value is never in a file or an environment variable |
| Who | The platform owner (Ayubkhon Abbosov): the account is the owner's |

Steps:

1. Obtain the key in the owner's Yandex developer account. Restrict it to the geocoder and suggest
   APIs and to the platform's egress address if the console allows it.
2. Write it where only the platform can read it (the category `provider_geocoding` is
   platform-owned and no tenant can write it):

   ```sh
   bao kv put horecaos/production/provider_geocoding/platform/yandex-server-key value=@-   # paste, then Ctrl-D
   ```

3. Tell the platform the reference, and name the provider, in the environment file the stack is
   started with (`/etc/horecaos/production.env` on the pilot VM). `deploy/compose.production.yml`
   forwards these three variables to `platform-app` (and `ProductionComposeRunbookVariablesTests`
   fails if one is dropped), so the file is the only place they are set:

   ```sh
   HORECAOS_GEO_PROVIDER=yandex
   HORECAOS_GEO_YANDEX_SECRET_REFERENCE=horecaos:production:provider_geocoding:platform:yandex-server-key
   ```

4. Restart the platform container (deploy.md). Nothing else changes.

**How to prove it.** (a) `GET /api/v1/operations/tenants/{t}/brands/{b}/map-config` answers
`"configured": true` and `"provider": "YANDEX"`. (b) In the console, New order's address field
shows suggestions. (c) The counter `horecaos.geo.requests{outcome="answered"}` rises and
`horecaos.geo.lookups{outcome="not_configured"}` stops rising. (d) **Capture one real answer per
operation and compare it field by field with `src/test/resources/geo/yandex/*.json`**
(`providers/yandex-maps.md` explains why): a difference is an unreadable answer, which the
screens show as "unavailable" for a working provider.

**How to take it back.** Unset `HORECAOS_GEO_PROVIDER` (or set `none`) and restart. Suggestions
degrade to text, the console falls back to the coordinates, checkout continues on `NOT_GEOCODED`.
Nothing is queued and nothing is lost.

If lookups say `PROVIDER_REFUSED` after a day of working, the key was rotated or revoked in the
provider's console: write the new value under the same reference (the reference does not change).

## 2. The browser key (tiles)

| | |
|---|---|
| What exists | `GET .../map-config` (operations and storefront surfaces) delivers the key to the browser, so it can be rotated without a build; `MapProvider` seam, `YandexMapProvider` (lazy chunk, the vendor script is fetched from the vendor's origin only when a map opens); `NullMapProvider` (the recording fake every map screen is tested against) |
| What is missing | A **public, referrer-restricted** browser key from the same account |
| Where the value goes | `HORECAOS_GEO_YANDEX_BROWSER_KEY` in the platform's environment file (a browser key is public by construction; it is not an ADR 0028 secret, and restricting it is a referrer setting in the Yandex console that nothing in this repository can enforce) |

Referrers to allow: every origin that serves the operations console and a storefront, one per
environment (`HORECAOS_OPERATIONS_ORIGIN`, the storefront hostnames).

**How to prove it.** `map-config` answers `features` containing `TILES` and a non-null
`browserKey`; the console's Live map and the dispatch board's «Map» pane draw tiles; the browser's
network panel shows `api-maps.yandex.ru` loaded from a page of an allowed origin and refused from
any other. **How to take it back.** Unset the variable: `features` loses `TILES`, every map area
says "this environment can search for addresses but has no map to draw", and the typed fields
remain.

## 3. `HORECAOS_GEO_PROVIDER=yandex` per environment

The switch is per environment and is the same two lines as section 1 (the provider name is not a
secret). Local development keeps `HORECAOS_GEO_PROVIDER=fake` under the `local` profile; a
production environment that is set to `fake` reports `NOT_CONFIGURED` on purpose, loudly, rather
than answering from fixtures. Staging gets its own key and its own reference (ADR 0034 isolation
rules): never production's.

## 4. How a Yandex licence is paid for from Uzbekistan, and what it lets the platform store

A business fact, not a configuration. ADR 0073 records international contracting and payment as
"a real obstacle rather than a preference"; ADR 0145 records the published licence terms read on
2026-10-01 (thirty-day cache on the standard tier, storage for the term on an extended one,
minimum fee, results shown only through the licensee's software) and that **nothing in the build
depends on the answer**: the platform persists no geocoder output (a confirmed pin is stored as
`CUSTOMER_PIN` or `OPERATOR_PIN`) and the response cache is held at the narrowest limit.

What is blocked until the answer exists: only the optional `GEOCODER` backfill (an automatic fill
for `NOT_GEOCODED` rows with nobody looking), which no row needs and which this wave does not
build. Owner: finance and legal, through the platform owner.

## 5. The 200-address golden set and the live bake-off

| | |
|---|---|
| What exists | `tools/geo/bake_off.py`: a provider-neutral runner (200 addresses, distance to the surveyed point, in-box flag, the provider's confidence, wrong-city check, the record's thresholds), tests over a synthetic set and recorded answers (`make geo-bakeoff-test`, also a CI step), a `--platform` source that asks the platform's own resolutions endpoint so the provider under test is whichever the environment is configured with |
| What is missing | The golden set (operator-surveyed doors, branches and public landmarks), and a configured provider to run it against |
| Where the value goes | The golden set is a CSV kept **outside git** (`id,query,surveyed_lat,surveyed_lon[,kind][,city]`). It must not be built from customers' saved addresses (ADR 0029). The recorded result is a JSON of ids, distances and counts and is attached to ADR 0145's follow-up note |

Run, once the server key is in place:

```sh
cd /opt/horecaos/platform/tools/geo
BAKEOFF_TOKEN="$(cat /root/.bakeoff-token)" python3 bake_off.py /secure/golden-set.csv \
    --bbox 41.15,69.04,41.47,69.46 \
    --platform https://api.example.uz --tenant <tenant-uuid> --brand <brand-uuid> \
    --json /secure/bakeoff-yandex-2026-10.json
```

(The token is a staff access token of a person holding `geo.lookup`; it is read from the
environment and is never an argument. The run is paced under the platform's thirty lookups a
minute per person, so 200 addresses take about seven minutes.) Exit status: `0` the gate holds,
`1` it does not, `2` the set or the provider is unusable (an unconfigured provider is *unusable*,
not *failing*: nothing was measured). **If Yandex fails, run the identical command against an
environment configured for 2GIS** (an adapter that does not exist yet; ADR 0145 decision 3).

The thresholds are the record's proposed defaults (90% within 100 m, every out-of-box result
`LOW_CONFIDENCE`, none in the wrong city, at least 200 addresses). A different pair is a decision
to record, not a flag to pass quietly: the report prints the thresholds it applied.

## 6. Restricting or rotating the compromised shared browser key

The key `99847472-…` was a committed default in both storefront images, in `public/config.json`
and in the Angular `environment` files. Batch 21 removed it from all eight places and added a
repository guard (`tools/checks/repo_hygiene.py`, `check_no_committed_map_key`) so it cannot
come back. **It is still in git history and still a live key in the owner's Yandex account until
the owner acts on it:**

1. In the Yandex developer console, find the key (or its account) and either **delete** it or
   restrict it by referrer to the origins in section 2. ADR 0145's default on acceptance is to
   treat it as compromised.
2. If the key belonged to a different account, tell its owner.

There is nothing in the repository to prove this step. Owner: the platform owner. It is safe to
do now: no image or build still reads that key.

## 7. Counsel's view on a foreign processor

[`processor-register.md`](../../processor-register.md) records the geocoder against the ADR 0029
data classes it may see (the typed address and a customer-confirmed pin; never a courier's track,
a payment reference or a secret). Whether sending that to a foreign processor is acceptable, and
under what contract, is a legal opinion this repository cannot supply (ADR 0034 records that no
in-country storage obligation currently binds the platform; counsel confirms or changes that).
It does not gate the build and it does gate turning the provider on for a customer. Owner:
legal, through the platform owner.

## 8. The SMS gateway that carries a courier's first sign-in link (row 3.3)

| | |
|---|---|
| What exists | `CourierAccessService` and `CourierAccessRelay` (V0657 `fulfillment.courier_access_invitations`): a manager's «Send invite» or «Reset access» queues a text; the relay makes the one-time token, reads the courier's number from the identity provider for the length of the send, hands `CourierAccessMessenger` the words and the link, and records only the token's SHA-256. The accept endpoints (`/api/v1/operations/courier-access/{inspect,accept}`) and the `/courier-access` page set the password at Keycloak. `FakeCourierAccessMessenger` (the `local` profile only) accepts the message and logs the link; the tests use a recording double. **No real SMS has ever left for a courier.** |
| What is missing | (a) An SMS gateway account that is **cleared for the `COURIER` purpose** (ADR 0146 refuses courier traffic by default: the VAS account may carry codes and order messages only until the owner answers in writing) and an approved sender name; (b) the adapter that implements `CourierAccessMessenger` over that account (the ADR 0146 `COURIER` route; it is code, and it is not written because it cannot be proved without the account) |
| Where the value goes | The account is a provider installation and binding (ADR 0026); its credential is an ADR 0028 reference; the purposes it may carry are the installation's `permittedPurposes` configuration key (`SmsPurposes`), which the owner sets to include `COURIER` once the provider agrees |
| Who | The platform owner (the account and the sender), with the provider; integration writes the adapter |

**Until it exists** a deployment with no `CourierAccessMessenger` bean leaves every invitation `QUEUED`
with `last_error_code = SMS_NOT_CONNECTED` and the courier's record says so ("no SMS gateway carries
courier messages yet"); nothing is dropped and nothing pretends to be sent. A deployment that is not the
`local` profile has no fake.

**How to prove it.** (a) In the console open a courier registered with your own phone, press «Send
invite»: the card says "Waiting to be texted", then "The link was texted"; (b) the handset receives one SMS
in the courier's language with a link of the form `<origin>/courier-access#token=...`; (c) opening it shows
the password form, choosing a password ends on "Your password is set", and the card says "The courier has set
their password"; (d) signing in to the courier app with it works (the courier client's own runbook:
`w4-courier-client.md`); (e) `SELECT status, last_error_code FROM fulfillment.courier_access_invitations`
says `ACCEPTED` and `NULL`, and no row, audit fact or log line contains the number or the token.

**The link's origin.** `horecaos.courier.access.link-origin` (default: the operations console's
`horecaos.frontends.operations-origin`). When the courier client carries the accept page, set it to that
origin and nothing else changes.

**How to take it back.** Remove the adapter bean (or the installation's `COURIER` purpose): invitations wait
as `SMS_NOT_CONNECTED` again. A link already texted keeps working until it expires (72 hours) or the manager
asks again, which voids it.

## 9. The `deliveries-console` module row (row 3.1b)

| | |
|---|---|
| What exists | `ConsoleProfileController` and the profile (slice one), the deliveries list (slice two: `GET /api/v1/operations/tenants/{t}/deliveries`, the `/deliveries` screen, a row opening the order's own detail pane), `ConsoleProfileHttpTests` and `OperationsDeliveryListEndpointTests` proving both against a tenant given the module by calling the services directly |
| What is missing | The `deliveries-console` row in `commercial.modules` **in any real environment**: drafted at price 0 by one person and activated by a second (ADR 0087 four-eyes), then granted to the first courier-service tenant by platform support. No migration may insert it: that would bypass four-eyes. Nothing resolves `DELIVERIES` anywhere real until it is done |
| Who | Two named humans with control-plane access (finance sets the real price later); platform support grants it |

**How to prove it.** Sign in as the tenant's owner: the console opens on `/deliveries`, the rail puts Kitchen,
Marketing and Inbox away, Orders reads «Доставки», and the list shows the tenant's deliveries with the cost at
its basis and the cash to collect.

**How to take it back.** End the module for the tenant (ADR 0087); the owner lands on `/today` at the next
session. `/deliveries` remains reachable by URL for anyone holding `delivery.plan.read`.

## 10. Extensions ADR 0164 declines until a signed tenant asks (row 3.1b)

A client-facing API for a shop's own system to create deliveries, a client portal, per-client invoicing and
statements, pickups from an arbitrary address per delivery, and parcel-to-parcel deliveries are each declined
by ADR 0164 Decision 8, and each is a record of its own, written when a signed courier-service tenant asks.
They are not built and cannot be built against a fake: there is no second aggregate to put them in (Decision
7). The ADR's exit criteria mark row 3.1b built when slice two ships; these are listed here so the gap is
stated and not forgotten. Owner: the platform owner, when a tenant asks.

## 11. Not external facts, and still open (rows 3.3, 3.4c, 3.9)

Listed beside the facts so a re-auditor does not look for a connection that does not exist:

- **`ORDER_UNDELIVERED` / `ORDER_DAMAGED`** read the delivery-exception reason codes (ADR 0178 decision 6).
  The codes are not in the exception list and no screen records one at close; the API refuses a rule on either
  basis until they do. Owner: the platform owner, with counsel (and see the next item).
- **Employed couriers.** Only employed couriers are scored on those two bases, and every engagement is
  `SELF_EMPLOYED` by constraint (V0040). Widening it is a labour-law decision. Owner: counsel.
- **A photo check at the handover.** The courier client's contract names a `PHOTO` kind; no policy asks for one.
  Owner: operations, when a tenant wants it.
- **The courier rating** is declined for v1 (ADR 0178 decision 5). Reopening it needs the owner, counsel and a
  source of the data (`platform/intent/0004`).

