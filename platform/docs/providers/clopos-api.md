# CLOPOS — POS integration contract

Working notes on the Clopos Open API v2, precise enough to implement an adapter
against without opening a browser. Written to answer the questions
[ADR 0011](../adr/partial/0011-pos-installations-bindings-and-capability-adapters.md) and
[ADR 0012](../adr/partial/0012-pos-catalog-sync-staging-and-reconciliation.md) are blocked on,
and to record a fiscal finding that [ADR 0038](../adr/partial/0038-legal-entities-fiscal-receipts-and-product-classification.md)
needs.

**Sources actually read** (all fetched 2026-08-23):

| Source | How | Status |
|---|---|---|
| `https://developer.clopos.com/llms.txt` | `curl` | read — this is the documentation index, and it is the fastest way in |
| `https://developer.clopos.com/api-reference/v2/openapi.json` | `curl`, 76,607 bytes, OpenAPI 3.1.0, parsed in full | read — **the authoritative artefact**; every schema below is quoted from it |
| `/index.md`, `/quickstart.md`, `/authentication.md`, `/concepts.md`, `/common-objects.md`, `/rate-limits.md`, `/errors.md`, `/webhooks.md` | `curl` | read, all 200 |
| `/api-reference/v2/{overview, authentication/auth, venues/get-venues, users/get-users, customers/get-all-customers, categories/get-categories, stations/get-stations, products/get-all-products, products/get-product-by-id, products/get-stop-list, price-lists/get-price-lists, price-lists/get-prices, sales/get-sale-types, sales/get-payment-methods, orders/get-orders, orders/get-order-by-id, orders/create-order, orders/update-order, receipts/get-receipts, receipts/get-receipt-by-id, receipts/close-receipt, receipts/patch-update-receipt, receipts/get-receipt-stock-operations, waiter-call/waiter-call}.md` | `curl` | read, all 24 pages returned 200 |
| `https://developer.clopos.com/api-reference/orders/create-order.md` (the "v1" tree) | `curl` | read — see the note below |

**No page failed to load.** Every gap recorded in §12 is a gap in Clopos's
documentation, not a fetch failure.

**Fetch notes, so you can re-derive this.**

* Appending `.md` to any docs URL returns the raw Mintlify source. Plain `curl`
  works; there is no SPA shell problem here (unlike CLICK — see
  [`click-merchant-api.md`](click-merchant-api.md)). Do not use a summarising
  fetcher: it will paraphrase the schemas and you will lose the field types that
  matter.
* `llms.txt` lists what looks like **two** API trees — `/api-reference/v2/...`
  and an unversioned `/api-reference/...`. **There is only one API.** The
  unversioned pages render the same `openapi.json` (the "v1" create-order page's
  body is literally the v2 spec, described as "the streamlined v2 payload"). There
  is no separate v1 surface with extra fields to fall back on.
* There is **no Postman collection** published.

**Provenance of judgements.** Everything in §1–§9 is quoted or directly derived
from the sources above. Where I am inferring rather than quoting, the sentence
says so. §10 (error classification) and §11 (capability matrix) are **mine**, not
Clopos's — they are engineering positions, and they are the two sections most
worth arguing with.

---

## 0. The four answers, up front

1. **Authentication is per-brand, not per-venue.** One credential set covers every
   venue under a Clopos brand; the venue is a per-request header. ADR 0011's
   tenant-owned installation with per-location bindings is the correct shape, and
   the rejected "one installation row per location" alternative would have been
   actively wrong here. See §2, §3.
2. **Clopos is an order authority for acceptance and a recipient for everything
   after it.** It is not one or the other, and the split is not configurable in the
   direction you would hope. See §6 — this is the section to read if you read only
   one.
3. **Clopos does not fiscalize, as far as this API is concerned.** `fiscal_id` is a
   nullable string that *we* write. This does **not** collide with ADR 0038 — it
   fits it. But the API's silence is not proof about what the till does, and
   there are three loud caveats. See §9.
4. **There is no idempotency mechanism of any kind.** No key, no header, no
   semantics, and Clopos's own retry guidance concedes it. A retried export is a
   second kitchen ticket, with no documented way to detect the first. See §7 —
   this is the single largest integration risk on the page.

---

## 1. Shape of the API

**Base URL** (the only one documented — there is no separate sandbox host):

```
https://integrations.clopos.com/open-api/v2
```

Test versus production is a property of the *integrator* and the *brand*, not of
the hostname (§2.4).

**Success envelope** — every endpoint, uniformly:

```json
{
  "success": true,
  "data": [ ... ],
  "total": 196,
  "time": 138,
  "timestamp": "2026-01-19 14:51:33",
  "unix": 1768820869,
  "sorts": ["id", "created_at", "updated_at"]
}
```

`data` is an object for single-resource reads and an array for lists. `total` is
the pre-pagination count. `sorts` (list endpoints) advertises which fields
`sort[0]` accepts — the docs tell you to query a list once to discover them,
which means **the sortable field set is not knowable from the spec** and must be
probed per resource at adapter build time.

**Read `success` before you read the status code.** Clopos states this outright,
because at least one authentication failure is returned as `200 OK` with
`success: false` (§2.3). An adapter that branches on HTTP status alone will treat
a test/production misconfiguration as a successful auth and then fail
incomprehensibly on the next call.

**Three identifiers scope everything** (`/concepts.md`):

| Concept | What it is | Who issues it |
|---|---|---|
| `brand` | Top-level tenant on Clopos. A short slug, e.g. `openapitest`. All venues, products, orders and receipts live inside one. | The Clopos customer |
| `venue` | A physical location under a brand. Numeric ID. A brand has one or hundreds. | The Clopos customer |
| `integrator_id` | Identifies **us**, HorecaOS, as the integrating system. Reused across every brand we connect to. | Clopos |

Clopos's `brand` ≈ HorecaOS's brand-or-tenant; Clopos's `venue` ≈ HorecaOS's location.
The correspondence is close enough to be useful and loose enough to be dangerous:
nothing forces one HorecaOS tenant to be one Clopos brand, and §3 treats that as a
mapping problem rather than an assumption.

---

## 2. Authentication

### 2.1 The credential

`POST /auth`, four fields, all required:

```json
{
  "client_id":     "...",
  "client_secret": "...",
  "brand":         "openapitest",
  "integrator_id": "..."
}
```

They come from **two different places**, and this is the fact ADR 0011's
onboarding flow turns on:

* **`integrator_id`** — issued by Clopos to us, once, for the integration as a
  whole. Requested via a Google Form (`https://forms.gle/Y9P1Wnv4QFAruxny8`).
  Reused across every brand. This is a *platform-level* HorecaOS secret, not a
  tenant-level one.
* **`client_id` / `client_secret` / `brand`** — **not issued by Clopos.**
  Generated by the restaurant themselves, in their own Clopos back office, under
  **Add-ons → Open API**, and then handed to us out of band.

The customer-side procedure, verbatim in effect, because onboarding
documentation will need it:

1. Sign in to the Clopos back office → **Add-ons → Open API** (needs the add-ons
   management permission). If the module is not listed, the brand has not
   subscribed to it — that is a commercial step, not a technical one.
2. **Create credentials.** Clopos generates the ID and secret and displays both.
3. **Choose the Staff user.** Every API request made with these credentials acts
   **on behalf of that user**, and that user's role and permissions determine what
   the integration may do.
4. Toggle status on and save. While the toggle is off, auth fails with
   `Client is disabled`.
5. Send the ID, the secret, and the brand slug to the integrator.

Two consequences that matter more than they look:

* **Step 3 means Clopos capability is per-installation, not per-provider.** Two
  restaurants on the same Clopos version can expose different surfaces to us
  purely because one picked a cashier and the other picked an owner. ADR 0011's
  `capability_snapshot` — discovered empirically per installation rather than
  hardcoded per provider — is exactly right, and Clopos is the proof case.
* **Step 1 means a Clopos brand can be technically ready and commercially
  unable.** The connection check must distinguish "wrong credentials" from
  "module not subscribed", and only the second is fixable by the restaurant
  without a purchase.

### 2.2 The token, its lifetime, and refresh

Success:

```json
{
  "success": true,
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "expires_at": 1767852332,
  "message": "Authentication successful"
}
```

* **Transport header is `x-token`, and the value is the bare JWT.** Despite
  `token_type: "Bearer"`, it is **not** `Authorization: Bearer <token>`. The
  OpenAPI security scheme confirms it: `{"type": "apiKey", "in": "header",
  "name": "x-token"}`. Getting this wrong yields `401 "Headers are missing"`,
  which reads like a different bug than it is.
* **Lifetime: 3600 seconds**, described as "typically". Trust `expires_at`, not
  the constant.
* **There is no refresh token and no refresh endpoint.** "Refresh" means calling
  `/auth` again with the same four credentials. The adapter must therefore hold
  the client secret for the life of the installation, not just at setup — it
  cannot exchange it once for a long-lived artefact.
* `expires_at` from `/auth` is a **Unix timestamp in seconds**. `expires_at`
  inside the `Token expired` *error* body is an **ISO 8601 string**. Same field
  name, two types, in the same API. Parse per-context.
* The JWT encodes `brand`, `venue_id`, `integrator_id`, `stage`, `expires_at`.
  Do not parse it for business logic; it is documented as opaque and its claims
  are not part of any contract.

**Recommended adapter behaviour:** re-authenticate at `expires_at - 300`, cache
one token per `(installation, brand)` in the ADR 0033 shared runtime state rather
than per JVM — the Tier 1 rate limit (§8) is per client IP, and a fleet of pods
each minting its own token will exhaust it.

### 2.3 Failure shapes

From `/auth`:

| Status | `error` | Meaning |
|---|---|---|
| `400` | `Missing client_id, client_secret, brand, or integrator_id` | Our bug |
| `400` | `Invalid integrator_id` | **Our** integrator is unknown or has been deactivated — platform-wide outage, not tenant-specific |
| **`200`** | `Integrator is in test mode. But brand is not in test mode` | Test integrator against a production brand. **Returned as `200 OK` with `success: false`** |
| `401` | (`message` only) | Upstream rejected the `client_id`/`client_secret`/`brand` triple |
| — | `Client is disabled` | The customer toggled the Open API module off |

From authenticated endpoints:

| Status | `error` | Meaning |
|---|---|---|
| `401` | `Headers are missing` | No `x-token` sent |
| `401` | `Invalid token` | Malformed or bad signature. **Do not retry** — re-authenticate |
| `401` | `Token expired` | Re-authenticate and retry once |
| `401` | `Invalid integrator_id` | Our integrator was deactivated mid-flight |
| `401` | `Integrator is in test mode...` | Same mismatch, enforced on every call |

Note that `Invalid integrator_id` and the test-mode mismatch are **platform-level
failures that present as per-tenant 401s**. An adapter that treats every 401 as
"this tenant's credentials are bad" will suspend every binding in the estate when
Clopos deactivates our integrator. Distinguish them and alert differently.

### 2.4 Test versus production

Integrators carry an `is_test` flag. A test integrator may only call
**non-production brands**; the reverse pairing is rejected. There is no separate
sandbox hostname — the same base URL serves both, and the segregation is entirely
in the credential pair. So:

* We need **two** `integrator_id`s: one test, one production.
* ADR 0011's "approved provider environments and egress allowlists" collapses to a
  single allowlisted host for Clopos. The environment dimension moves into
  `non_sensitive_config` as the integrator ID, which is *not* a secret and can
  therefore live in the installation row.

### 2.5 Secret rotation — a real problem for ADR 0028

> "Once generated, the values stay visible on the module page so they can be
> copied again at any time, **but they cannot be regenerated from the back
> office.** Contact dev@clopos.com if a secret needs to be rotated."

Rotation is a support ticket with a human turnaround, initiated by the
restaurant, not by us and not on a schedule. Any ADR 0028 rotation policy that
assumes self-service rotation does not apply to Clopos credentials. Worse, the
secret remains permanently readable in the customer's back office to anyone with
the add-ons permission — so the blast radius of a compromised back-office account
includes our integration, and we cannot cut it off ourselves except by suspending
the binding.

**Position:** treat Clopos client secrets as long-lived and unrotatable, make
binding suspension the actual containment control, and do not promise a rotation
SLA we cannot execute.

---

## 3. What this means for ADR 0011's installation model

The mapping is clean, and it validates two of ADR 0011's rejected alternatives.

```
integration.installations  →  one per Clopos BRAND
    secret_reference         →  { client_id, client_secret }
    non_sensitive_config     →  { brand: "openapitest", integrator_id: "..." }
    capability_snapshot      →  discovered per installation (§11), because the
                                Staff user behind the credential changes it

integration.bindings       →  one per Clopos VENUE
    configuration_override   →  { venue_id: 3 }   → sent as the `x-venue` header
```

* **"One installation row per location" was correctly rejected.** Clopos
  credentials are genuinely brand-scoped. Per-location installations would
  duplicate one secret across every venue and make the (already painful)
  rotation story impossible.
* **A HorecaOS tenant is not necessarily one Clopos brand.** A tenant running two
  Clopos brands needs two installations; a Clopos brand spanning two HorecaOS brands
  needs one installation with bindings that fan out. The ADR 0026 model handles
  both. Do not add a uniqueness constraint that assumes 1:1.

**Connection check and identity read-back** (ADR 0011's discovery steps 3–6):

1. `POST /auth` → proves the credential triple and the integrator.
2. `GET /venues` → returns the venue list for operator confirmation.

But `GET /venues` returns only:

```json
{ "id": 1, "name": "Main", "is_main": 1, "media": [] }
```

**No address, no external code, no timezone, no anything else.** ADR 0011 requires
an operator to confirm the provider restaurant identity before activating a
binding, specifically so orders cannot be exported to the wrong restaurant. With
Clopos, that confirmation rests on a free-text venue name and an `is_main` flag.
For a brand with venues named "Main", "Baku", "Masally" that is workable; for a
brand with three venues named "Filial 1/2/3" it is a coin toss.

**Position:** keep the operator confirmation step — it is still the strongest
control available — but do not describe it as verified identity. Pair it with a
dry-run export to a non-production brand before the first live binding, and
record the confirming operator in the ADR 0027 audit trail, because the venue
name is not evidence.

---

## 4. The catalog surface

### 4.1 The vocabulary, including the trap

Clopos overloads one word. Read this table before writing the normalizer.

| Clopos term | What it actually is | HorecaOS canonical |
|---|---|---|
| **product** (`GOODS`, `DISH`, `TIMER`, `PREPARATION`, `INGREDIENT`) | The sellable or stockable item | Product |
| **`modification`** | A **variant** — size, colour. `type: "MODIFICATION"`, `parent_id` points at the parent `GOODS`. Carries the *full product schema*: its own price, cost, barcode, status, stock | Variant |
| **`modificator`** (inside `modificator_groups`) | A **modifier** — "Extra Cheese". `DISH` only | Modifier option |
| **`modificator_group`** | The modifier group — "Spice Level" | Modifier group |
| **`category`** | Menu category, nested-set tree | Category |
| **`price_list` / `price`** | A named set of per-product prices | Price list |
| **stop list** | Per-product stock limit | Availability |

`modification` and `modificator` differ by three characters and mean entirely
different things. The docs' own field reference says so explicitly — *"'modification'
and 'variant' mean the same thing in this API"* — which is the tell that Clopos
knows it is confusing. Name the DTOs `CloposVariant` and `CloposModifierOption`
in the adapter and never carry the provider words into staging.

### 4.2 Product types and their rules

`GOODS`, `DISH`, `TIMER`, `PREPARATION`, `INGREDIENT`, `MODIFICATION` (the schema
enum). The prose field reference adds a seventh, `MODIFIER`, which the OpenAPI
enum does not contain — an unresolved discrepancy (§12 Q11).

Behavioural rules that change how staging must model this:

* **`GOODS` with variants: the parent is not sellable.** "If a product has
  variants, only those variants can be sold. The main product acts as a parent."
  So a parent `GOODS` maps to a HorecaOS product shell, and each `MODIFICATION` maps
  to the priceable node. The parent's `price` is documented as possibly `0`, and
  **the parent's values are not inherited at sale time**.
* **Modifiers attach only to `DISH`.** A `GOODS` product cannot carry modifier
  groups. If HorecaOS's catalog has modifiers on a non-dish, that combination is not
  representable and must surface as an ADR 0012 conflict, not a silent drop.
* **`INGREDIENT` and `PREPARATION` are not menu items.** They are inventory. The
  sample brand returns them from `/products` alongside real products — the two
  examples in the docs' own response are `Test_Tomato` and `Test_Onion`. Filter
  by `type` on ingest or the difference engine will propose creating tomatoes as
  draft customer-facing products.
* **`TIMER`** is time-based billing (the docs' example is a PS5 rental), priced by
  rules in an opaque `setting` object. Out of scope for food ordering; exclude it
  explicitly rather than by accident.

### 4.3 The stable identifier

**`id`, an integer, per resource, scoped to the brand.** That is the answer to
ADR 0012's "what is the stable identifier on their side".

Everything else is mutable:

* `name`, `full_name`, `parent_name` — free text, editable in the back office
* `price`, `cost_price` — editable
* `status` (`1` active / `0` inactive), `hidden` — editable
* `barcode` — **deprecated**, "not guaranteed to be populated". Use
  `with[]=codes` and read the `codes` array instead
* `category_id`, `station_id` — reassignable

ADR 0012 already says "never guess mapping from mutable product names alone."
Clopos gives no reason to soften that: there is **no** SKU, no external code, no
slug, and no stable secondary key on a product. `id` is all there is.

Categories additionally expose `_lft` / `_rgt` (a nested-set encoding). **These
renumber on every tree edit** and are not identifiers. Read `parent_id` and
`depth`; ignore the nested-set columns entirely.

### 4.4 Reading the catalog

```
GET /products
  page       (default 1)
  limit      (default 50, max 100 per the products page)
  with[N]    category | station | modifications | modifications.codes | taxes
             | codes | modificator_groups | recipe | packages | tags
  selects    comma-separated field list; id, name, type always returned
  filters[N][0] = field, filters[N][1] = value (or filters[N][1][M] for arrays)
```

Filterable fields: `type`, `category_id`, `station_id`, `tags`, `giftable`,
`discountable`, `inventory_behavior`, `haveIngredients`, `sold_by_portion`,
`has_variants`, `has_modifiers`, `has_barcode`, `has_service_charge`.

**Every one of them is structural. Not one is temporal.** Hold that thought for
§5.

Query syntax is **PHP/Laravel bracket notation, not JSON** — `filters[0][0]=type&filters[0][1][0]=GOODS`.
Practical note for the Camel route: the brackets must reach Clopos unencoded or
Laravel will not bind them (the docs' own curl examples pass `--globoff`). Verify
the HTTP component's URI encoding in a contract test rather than assuming it.

Other catalog endpoints: `GET /categories` (with `parent_id`, `type`,
`include_children`, `include_inactive`), `GET /stations`, `GET /price-lists`,
`GET /price-lists/prices`, `GET /products/stop-list`.

### 4.5 Price

Two layers, and the join between them is not documented.

1. **`product.price`** — a single base price on the product/variant.
2. **Price lists** — `GET /price-lists` returns `{id, name, description, status,
   created_at, updated_at}`; `GET /price-lists/prices` returns
   `{id, list_id, product_id, price}`.

`llms.txt` describes a price list as *"a named set of product prices that can be
applied to specific venues or sales channels (for example, a separate menu price
list for delivery)"* — but **no field in either schema expresses that
application.** `PriceList` has no `venue_id`, no `sale_type_id`, no channel. There
is no endpoint that resolves "the price of product X at venue Y for sale type Z".

This is the single worst-documented thing in the API, and it sits directly on
ADR 0012's authority table (customer-facing price → HorecaOS) and ADR 0018's
deterministic pricing. Question Q3 in §12.

**Mitigating position:** ADR 0012 makes HorecaOS authoritative for customer-facing
price anyway. Import Clopos prices as *evidence for review*, not as a live feed,
and the ambiguity becomes a reporting nuisance rather than a pricing incident. Do
not build price-list-to-venue resolution on a guess.

### 4.6 The `venues` array — an undocumented field on the hot path

Every product carries:

```json
"venues": []
```

described in full as: *"Venue-specific availability and pricing overrides."*
**The element shape is never given** — not in the OpenAPI schema (which omits the
field from `Product` entirely), not in the field reference, not in any example
(every sample is `[]`).

This is per-location availability and per-location price. ADR 0016's location
offerings and ADR 0012's availability staging both need it. We cannot model what
we cannot see. Question Q4.

### 4.7 Availability — the stop list

```
GET /products/stop-list?filters[0][0]=id&filters[0][1][0]=1&filters[0][1][1]=332
```

```json
{ "success": true, "data": [ { "id": 54, "limit": 0, "timestamp": 1761202010781 } ] }
```

* `id` is the **product id**.
* `limit` is the stock limit; `0` means out of stock.
* `timestamp` is **Unix milliseconds** — note, milliseconds, while the rest of the
  API uses seconds or `YYYY-MM-DD HH:mm:ss` strings.
* **Absence means no limitation.** A product not in the response is unconstrained,
  not unavailable. Inverting this makes the entire menu disappear.

The `filters` parameter is optional; omitting it returns every constrained
product, which is the form worth polling.

This is the only endpoint in the API that carries a per-row change timestamp, and
it happens to cover the fastest-moving data. §5 leans on that.

### 4.8 Fields with no resolver

* **`unit_id`** — an integer on every product. **There is no units endpoint.** The
  path list contains nothing that resolves a unit ID to a name or a measure. So a
  product is "3 of unit 1" and we cannot say what unit 1 is. ADR 0012 says
  "variant units retain enough raw evidence to diagnose lossy provider models" —
  here the evidence is an unresolvable integer. Question Q5.
* **`gov_code`** — "Government/tax code for the product", nullable, no format,
  no example, no validation. The only tax-classification field in the entire API.
  See §9.3.
* **`unit_weight`** — kilograms, "independent of `sold_by_weight`". Documented
  well enough, unusually.
* **`inventory_behavior`** — `0` MINUS_INGREDIENTS, `1` MINUS_SELF, `3` PASSIVE.
  Note `2` is absent from the enumeration with no explanation.

---

## 5. Change detection — ADR 0012's core question

**Can a change be detected without a full re-read? For the catalog: no. For
availability: yes.**

### 5.1 The catalog: full re-read only

`Product`, `Category` and `PriceList` all carry `created_at` and `updated_at`.
That is the good news and it is where the good news stops.

* `GET /products` accepts **no** `date[0]`/`date[1]` range (unlike `/orders` and
  `/receipts`, which do).
* **No** `updated_at` filter, and no temporal field in the documented filter list.
* The OpenAPI parameter list for `/products` is `page`, `limit`, `filters`,
  `selects` — **no `sort` at all**, and no `with[]` either, though the prose
  documents `with[]`. The generic pagination contract in `/common-objects.md`
  claims list endpoints accept `sort[0]`/`sort[1]` and return a `sorts` array, but
  the products response example returns only `success`/`data`/`total` — **no
  `sorts` key**. Whether `/products` is sortable is genuinely unresolved.
  Question Q6.
* No `ETag`, no `If-Modified-Since`, no cursor, no change feed, no webhook (§8).

So: **page through the whole catalog, then diff client-side on `updated_at`.**

This is survivable, and it is survivable specifically because ADR 0012 already
decided to work this way. The run stages a full normalized snapshot and a
deterministic difference engine compares it. Clopos merely removes the option of
optimising that into an incremental fetch. Cost, from the docs' own sample brand
(284 products, `limit` 100): three requests per catalog read, against a 300 rpm
budget. A daily sync is free. A five-minute sync is also affordable. The
constraint is not the request budget.

**The real cost is the pagination race**, and it bites ADR 0012's resumability
directly. Offset pagination (`page`/`limit`) over a table someone is editing can
**skip or duplicate rows**: if the back office inserts a product while we are on
page 2, page 3 shifts and one product is never read. A missed product presents to
the difference engine as a `REMOVAL_SIGNAL` — a menu item that looks deleted
because we failed to read it.

**Position, and this one should go into the ADR:** ADR 0012 must not treat a
single page-through as an atomic snapshot. Either

* sort by `id` ascending and page by `id > last_seen` if `/products` turns out to
  be sortable (Q6), which makes the walk stable under inserts; or
* if it is not sortable, **require two consecutive agreeing reads before any
  `REMOVAL_SIGNAL` is actioned**, and treat a single run's absence as
  inconclusive rather than as a removal.

The second is the safe default and costs one extra catalog read per run. Given
that ADR 0012 already refuses to physically delete on a removal signal, the
blast radius is a spurious review-queue item rather than a lost product — but a
review queue full of phantom removals is how operators learn to rubber-stamp the
queue, which is worse.

### 5.2 Availability: genuinely incremental

`GET /products/stop-list` returns a per-entry `timestamp` (ms). Poll it on its own
cadence — it is small, it is the fastest-changing data, and it is the one thing
where a stale read has an immediate customer consequence (selling a dish that ran
out). Compare the returned `(id, limit, timestamp)` set against the last one; any
difference is a real change.

**Recommended split:** catalog structure daily (ADR 0012's reviewed run);
stop list every 30–60 seconds (a direct availability feed, not a sync run). These
are different pipelines with different latencies and different authority, and
collapsing them into one daily run means the stop list is useless.

---

## 6. The order surface — authority or recipient?

**Both, and the split is the most important fact in this document.**

Clopos models an order as **two objects with two separate state machines**, and
our write access to each is different.

### 6.1 Object one: `Order`

```
POST /orders          create
GET  /orders          list (page, limit, status, with[], date[0..1], sort[0..1], filters[N])
GET  /orders/{id}     read
PUT  /orders/{id}     update  — { "status": "IGNORE" }  and nothing else
```

`Order.status` ∈ `PENDING` | `RECEIVED` | `IGNORE` | `DELIVERED`.

The documented lifecycle, from `/orders/get-orders.md`:

> "When an order is created through this endpoint, **the POS receives a push
> notification and notifies the clerk of the new order.** `RECEIVED` orders
> automatically transition into open receipts."
>
> "Use `status=PENDING` to monitor orders awaiting POS confirmation."

And from `/orders/update-order.md`:

> "Currently, only the `IGNORE` status transition is supported through this
> endpoint."

Read those together:

| Transition | Who drives it | Can we? |
|---|---|---|
| → `PENDING` | Us, via `POST /orders` | Yes |
| `PENDING` → `RECEIVED` | **The clerk at the POS** | **No** |
| `PENDING` → `IGNORE` | Either — us via `PUT`, or presumably the clerk | Yes |
| `RECEIVED` → `DELIVERED` | Clopos-side, undocumented | **No** |

**`PENDING` → `RECEIVED` is a human decision taken on the POS terminal, and we
cannot make it.** That is Clopos being an *authority*: it accepts or refuses the
order, and HorecaOS learns the outcome by asking.

### 6.2 The escape hatch, and what it costs

`CreateOrderRequest` carries:

* `auto_order_accept` (boolean, default `false`)
* `auto_accept_terminal` (integer — "terminal ID that auto-accepts the order")
* `auto_order_sent_to_station` (boolean, default `false`) — "auto-send to stations
  after acceptance"

Setting `auto_order_accept: true` **converts Clopos from an authority into a
recipient** for that order. The clerk's decision is bypassed and the order goes
straight to `RECEIVED`, and with `auto_order_sent_to_station: true` straight to
the kitchen printers.

This is a per-request flag, which means **the authority model is a configuration
choice we make at export time, not a fixed property of the provider.** That maps
onto ADR 0011 with unusual precision:

* Auto-confirmed HorecaOS orders → `auto_order_accept: true`. Clopos is a recipient.
  `OrderApprovalCapability` is not in play.
* Restaurant-approval mode → `auto_order_accept: false`. Clopos is an authority
  and genuinely implements approval.

ADR 0011 already says "Restaurant-approval mode may request decisions from both
Operations and a POS implementing reliable approval. First valid approval/
rejection wins atomically in ordering." Clopos qualifies — **except for the word
"reliable"**, which §6.4 disputes.

`auto_accept_terminal` requires a terminal ID that **no endpoint in the API
returns.** There is a `/stations` endpoint (preparation stations) but nothing that
lists terminals. Question Q7.

### 6.3 Object two: `Receipt`

Once an order reaches `RECEIVED` it becomes an open receipt, and the receipt
carries a *second, different* status field:

```
Receipt.order_status ∈ NEW | SCHEDULED | IN_PROGRESS | READY | PICKED_UP
                     | COMPLETED | CANCELLED
```

Compare it against `Order.status` (`PENDING`/`RECEIVED`/`IGNORE`/`DELIVERED`).
**Different vocabulary, different granularity, no documented mapping between
them.** An adapter must model both and must not attempt to unify them.

And here is the inversion:

```
PATCH /receipts/{id}
  { "order_status": "...", "order_number": "...", "fiscal_id": "...", "lock": ... }
```

`Receipt.order_status` — the field that *looks* like kitchen progress — **is a
field we write.** It is in the four-field PATCH-writable list. Everything else on
a receipt is explicitly read-only: *"Other receipt fields are read-only and cannot
be modified via this API."*

So the field that would answer "is the food ready?" is an inbound field we set,
not an outbound field the kitchen sets. `IN_PROGRESS` and `READY` are labels
HorecaOS writes onto the Clopos receipt for the restaurant's benefit — they are **not
a preparation-status feed**, and reading our own writes back and calling it POS
telemetry would be a straightforward self-deception.

Nothing else in the API reports kitchen progress. `GET /receipts` exposes
`status` (integer, undocumented enumeration), `lock`, `closed_at`, and the stock
operations a receipt generated — none of which is "the chef started cooking".

### 6.4 The verdict, stated plainly

> **Clopos is the authority for whether an order is accepted. It is a recipient
> for the order's content, its fulfilment progress, and its fiscal identity. It is
> silent on preparation.**

For ADR 0011's capability model:

* `OrderApprovalCapability` — **supported**, and it is a genuine authority.
* `OrderExportCapability` — **supported**. Clopos is a recipient here. §7 is the
  problem.
* `OrderCancellationCapability` — **partial.** `PUT status=IGNORE` works while the
  order is `PENDING`. Once it is `RECEIVED` and a receipt exists, there is no
  documented order-level cancel; the nearest thing is `PATCH order_status=CANCELLED`
  on the receipt, which sets a label and is **not** documented to void the receipt,
  reverse the stock deduction, or stop the kitchen. **Do not represent this as
  cancellation.** Question Q8.
* `PreparationStatusCapability` — **not supported.** Do not implement it against
  Clopos, and make sure the control plane cannot be configured to depend on it,
  which is exactly the failure ADR 0011's discovery step exists to prevent.

**The consequence ADR 0011 should absorb:** with no webhooks (§8), the POS's
approval decision reaches us **one poll interval late**. If Operations and the POS
race to approve and the poll interval is 30 seconds, Operations wins essentially
always, and "first valid approval wins" degrades into "Operations decides, and
the POS's answer arrives afterwards to be reconciled." That is a correctness
question — what happens when the clerk pressed *reject* thirty seconds before we
read it, and Operations already accepted? ADR 0011 says a POS transport failure
never reverses a confirmed commercial order, which resolves it, but the ADR should
say so about *latency* and not only about *failure*.

### 6.5 Order payload details

`CreateOrderRequest` — required: `sale_type_id`, `venue_id`, `customer`,
`products`.

* `customer` requires `id`, `phone`, `address`, `name`. **`id` is required** — a
  Clopos customer ID. So exporting an order requires a Clopos customer to exist
  first (`POST /customers`), which is a second write with its own idempotency
  problem and its own PII consequences under ADR 0029. There is no documented
  guest/anonymous order path. Question Q9.
* Each product line requires `product_id`, `product_name`, `count`, `price`,
  `status`, `product_hash`.
  * `product_name` and `price` are **required on the line**, meaning the caller
    restates the catalog. That is convenient for us (HorecaOS is authoritative for
    price, and Clopos accepts our number) and dangerous in the other direction:
    nothing validates our price against theirs.
  * `count` is typed **`integer`** in the schema, while `portion_size` is a
    number. A half-kilogram of a `sold_by_weight` product has no obvious
    expression. Question Q10.
  * **`product_hash` is required and entirely undocumented** — no derivation, no
    example beyond `"abc123"`, no statement of what it hashes. This is a required
    field on the single most important call in the integration. Question Q1.
  * `status` on a line is a required string, example `"new"`, enumeration not
    given.
* Money is `number` — **floating point, no currency field anywhere in the API.**
  There is no `currency` on an order, a product, a price list, or a receipt. See
  §9.4.

---

## 7. Idempotency and retries — the largest risk

### 7.1 There is none

Searched the OpenAPI spec and all 32 documentation pages: **the word
"idempotency" does not appear.** No `Idempotency-Key` header, no `X-Request-Id`,
no client-supplied unique key on `CreateOrderRequest`, no documented behaviour on
repeat, no dedupe window, no conflict response.

**A repeated `POST /orders` creates a second order.** Nothing in the docs
contradicts this and nothing prevents it.

### 7.2 What an uncertain outcome looks like

Clopos names it, in `/errors.md`:

| Status | Meaning |
|---|---|
| `504` / timeout | "The upstream Clopos API did not respond within **8 seconds**. Safe to retry idempotent requests." |

and in the retry table:

> "Timeout / `504`: **Yes for idempotent requests (`GET`). For non-idempotent
> requests, check the server state first to avoid duplicates.**"

That is Clopos stating, in its own documentation, that `POST /orders` is not
idempotent and that a timeout is an uncertain outcome we must resolve by reading.

**The 8-second upstream budget is short for a POS export.** Our Camel route
timeout must exceed it comfortably, so that we observe Clopos's `504` rather than
our own client timeout — a client-side timeout tells us nothing about whether the
order landed, whereas a `504` at least tells us Clopos's gateway gave up on its
upstream, which is still uncertain but better characterised.

### 7.3 The correlation fields that exist but cannot be set

Every order **response** — from `POST /orders`, `GET /orders`, `GET /orders/{id}`,
and `PUT /orders/{id}` — contains:

```json
{
  "integration_uuid":   null,   // "UUID from the integration source, if provided"
  "integration_id":     null,   // "External ID from the integration source"
  "customer_ref_id":    null,   // "External customer reference ID"
  "integration":        "call_center_new",
  "integration_status": "CREATED",
  "integration_response": null
}
```

These are **exactly** the fields ADR 0011 needs — "provider commands retain stable
IDs/idempotency keys", "its external ID completes export reconciliation and
prevents a duplicate export". The read model has a slot for our UUID. The phrase
*"if provided"* strongly implies a caller can provide it.

**But `CreateOrderRequest` contains none of them.** Not in the OpenAPI schema, not
in the prose payload reference. There is no documented way to set the field whose
description says we may set it.

This is the highest-value question on the list. Question Q1.

### 7.4 `order_number` — the only candidate, and the spec disagrees with itself

The prose page `/orders/create-order.md` documents a top-level field:

> `order_number` (string, optional, **max 20 chars**): Custom order number to
> assign to the order.

and its curl example sends `"order_number": "A-1024"`.

**The OpenAPI `CreateOrderRequest` schema does not contain `order_number`.** The
prose and the machine-readable spec disagree about the request body of the
integration's most important call.

If `order_number` is real, it is the only client-supplied correlation value we
have. But: nothing states it is unique, nothing states it is indexed, no filter
is documented for it on `GET /orders`, and it does not appear on the `Order`
response object at all (it appears on the *`Receipt`* object, where it is also
PATCH-writable). Question Q2.

### 7.5 The recovery read, and why it is unpleasant

After an uncertain `POST /orders`, we must answer "did it land?" The only
available query is:

```
GET /orders?date[0]=2026-08-23&date[1]=2026-08-23&status=PENDING&sort[0]=created_at&sort[1]=-1
```

and then match by inspecting `payload`, which echoes back `customer.phone`,
`customer.address`, the product lines, and the meta. So the match is a heuristic
over `(venue_id, customer.phone, created_at within window, line composition)`.

**A heuristic match is not reconciliation.** Two identical orders from the same
phone ninety seconds apart — a customer who genuinely ordered twice — are
indistinguishable from one order we exported twice.

### 7.6 Position for the adapter

Until Q1/Q2 are answered:

1. **Never blind-retry `POST /orders`.** Any uncertain outcome (`504`, client
   timeout, connection reset) transitions the export to an `UNCERTAIN` state, not
   a retry queue.
2. **Resolve `UNCERTAIN` by reading**, on a bounded schedule, using the heuristic
   above, and **stop rather than guess** when the match is ambiguous — ADR 0012's
   "conflicts stop rather than resolve automatically" principle applied to
   ordering.
3. **Send `order_number` anyway** (carrying the HorecaOS order reference, ≤20 chars),
   accepting that it may be ignored. If it is honoured, it makes the recovery read
   deterministic; if it is dropped, we have lost nothing. Verify empirically
   against the pilot brand on day one — this is a five-minute test that changes
   the risk profile of the whole integration.
4. **Prefer `auto_order_accept: false` during the pilot.** An order sitting in
   `PENDING` awaiting a clerk is recoverable and visible; an order auto-accepted
   and auto-sent to the station is already food. The safe failure and the
   convenient configuration point in opposite directions here, and the pilot
   should take the safe one.
5. **Ask Clopos before go-live, not after.** ADR 0011's exit criteria are not
   genuinely met while a retried export can produce a second kitchen ticket.

---

## 8. Webhooks and polling

### 8.1 There are no webhooks

`/webhooks.md`, in full substance:

> "Webhooks are **not yet available** on Clopos Open API v2. Support is on the
> roadmap and will be released in the near future."

There is no registration endpoint, no signing scheme, no secret, no replay
protection, no event catalogue — nothing exists to describe.

**So the brief's question "how does a webhook authenticate to us?" has no answer
available today, and I am not going to invent one from memory.** When webhooks
ship, that question must be re-asked before any endpoint is exposed; it is
recorded as Q13.

Early access is available by emailing `dev@clopos.com` with our `integrator_id`
and the events we need. **We should do this now** — it is a free option, and the
events we want (order accepted, receipt closed, stop list changed) are precisely
the three the docs name as examples.

### 8.2 Polling is the whole mechanism

Clopos's own recommendation: poll `/orders`, `/receipts`, and
`/products/stop-list`, "seconds, not milliseconds", using `date[0]`/`date[1]` and
sort filters.

| Feed | Endpoint | Suggested cadence | Why |
|---|---|---|---|
| POS approval decision | `GET /orders?status=PENDING` + status transitions | 10–15 s | Directly the approval latency (§6.4) |
| Availability | `GET /products/stop-list` | 30–60 s | Fastest-changing; only incremental surface |
| Receipt closure / settlement | `GET /receipts?date[0]&date[1]&sort[0]=created_at` | 60 s | Feeds ADR 0043 reporting, not a customer path |
| Catalog structure | `GET /products`, `/categories`, `/price-lists` | daily | ADR 0012's reviewed run |

**Budget check.** The Tier 3 limit is 300 requests/minute per
`(integrator_id, brand)` — **per brand, not per venue.** A brand with ten venues
shares one 300 rpm budget, and per-venue polling multiplies request count while
the quota stays fixed. At 10 venues: approval polling at 15 s = 40 rpm, stop list
at 60 s = 10 rpm, receipts at 60 s = 10 rpm ⇒ ~60 rpm, comfortable. At 50 venues
the same cadences reach ~300 rpm and we are at the ceiling.

**Position:** the polling cadence must be a per-installation ADR 0030 policy
value scaled by venue count, not a constant, and the adapter must consume the
`RateLimit-Remaining` header to back off before it hits `429`. Quota increases
are available by writing to `dev@clopos.com` — that request should be made during
onboarding for any brand above roughly 20 venues, not after the first throttle.

Note also: **splitting traffic across multiple integrator IDs to multiply quota is
explicitly a policy violation** that Clopos monitors for. It is not an option, and
nobody should discover that by trying it.

---

## 9. Fiscal — and why this is *not* the ADR 0038 collision

The brief asked to say loudly if Clopos fiscalizes, because two systems issuing
one receipt is a legal problem. **On the evidence of this API, it does not**, and
the shape it does have is unusually convenient. But the caveats are real and the
third one is serious.

### 9.1 What the API actually contains

`Receipt.fiscal_id`, typed `string | null`, described as *"Fiscal receipt
identifier used for tax reporting."* It is `null` in **every** example on every
receipt page.

And critically, it is in the four-field **PATCH-writable** list:

```
PATCH /receipts/{id}
  { "order_status": ..., "order_number": ..., "fiscal_id": "Twrewr89fnscvj22", "lock": ... }
```

*"This method can update receipts even after they are closed."*

Beyond that field there is **nothing fiscal in the entire API**: no endpoint that
mints a fiscal receipt, no OFD / ОФД / soliq reference, no fiscal sign, no
fiscal serial, no QR payload, no ЗНМ/ФМ, no tax-authority interaction, no receipt
document, no cancellation receipt. Grepping the 76 KB spec for
`fiscal|mxik|ikpu|spic|nds|vat|excise|marking` returns exactly three hits, all of
them `fiscal_id`, plus a generic `Tax {id, name, rate}` object.

### 9.2 The reading, and why it fits ADR 0038

**Clopos is a place to *record* a fiscal identifier that some other system
produced.** It does not issue one.

That is not a collision with ADR 0038 — it is the shape ADR 0038 would have asked
for. ADR 0038 holds that the restaurant's legal entity is the seller, that
fiscalization is an obligation of the order owned by the `fiscal` module, and that
Click and Payme discharge it on the `PARTNER` path. Clopos's writable `fiscal_id`
lets us **close the loop**: the `fiscal` module issues via the payment provider,
then PATCHes the resulting identifier onto the Clopos receipt so the restaurant's
own POS reporting reconciles against the receipt that was actually issued.

That is a genuinely good outcome and it should be written into ADR 0038 as an
integration point: `fiscal_id` write-back is how the POS learns what the payment
provider issued. It also means the PATCH is on the critical path for fiscal
evidence completeness, and should be retried to success like any other obligation
step (it is safe to retry — it is a PATCH of a specific value, idempotent by
construction, unlike §7's POST).

### 9.3 Three caveats, and the third is the one that matters

**Caveat 1 — the API's silence is not evidence about the till.**
This documents the *Open API*. It says nothing about what a Clopos POS terminal
does when a cashier rings a sale on the till itself. A POS deployed in Uzbekistan
may well drive a fiscal registrar locally, entirely outside this API surface, and
that behaviour would be invisible here. ADR 0038's `TERMINAL` discharge kind
exists for exactly this case. **This must be confirmed with Clopos in writing
before the pilot takes a cash order** — because if the till fiscalizes a dine-in
or cash sale *and* our `fiscal` module fiscalizes the same order via a partner,
that is the two-receipts-one-sale problem, and it will be discovered by a tax
inspector rather than by us. Question Q14.

**Caveat 2 — `gov_code` is the only classification field, and it is
uncharacterised.**
ADR 0038 requires ИКПУ/MXIK and a package code on every priceable node.
`product.gov_code` — "Government/tax code for the product", nullable, no format,
no example, no validation — is the only candidate source in the API. If it holds
MXIK, it is a genuine input to ADR 0038's classification (a reviewed import under
ADR 0012's "provider operational metadata" authority, never an auto-apply). If it
holds an Azerbaijani code, it is worse than useless because it will *look* right.
There is also `Package {id, name, equal}` on products, which may or may not relate
to ADR 0038's package code — the name is suggestive and the schema is silent.
Questions Q15, Q16.

**Caveat 3 — this product is Azerbaijan-first, and that undermines every fiscal
assumption.**
The evidence is consistent and it is all from Clopos's own examples:

* Sample venues: `"Main"`, **`"Baku"`**, **`"Masally"`** (Masally is a district in
  Azerbaijan).
* Sale types returned in Azerbaijani: `"Yerinde"` (dine-in), `"Catdirilma"`
  (delivery).
* Sample customer phone: `+994705401040` — **+994 is Azerbaijan.**
* Sample customer name: an Azerbaijani name.
* Uzbek fiscal vocabulary — MXIK, ИКПУ, ОФД, ЗНМ, ФМ — appears **nowhere** in any
  page or schema.

So the fiscal behaviour documented here is, at best, the behaviour of an
Azerbaijani product. **Nothing in these docs is evidence about what Clopos does
under Uzbek fiscal law**, which is the only law the pilot operates under.

**Position: treat every fiscal conclusion in this section as provisional and
jurisdiction-unverified.** §9.2's happy reading is the most likely outcome and it
is not established. Getting a written answer to Q14 is the single highest-value
thing anyone can do with this contract, because it is the only open item where
the downside is legal rather than operational.

### 9.4 Money has no currency, and it is a float

There is **no currency field anywhere in the API** — not on an order, a product, a
price list, a receipt, or a payment method. Currency is presumably a brand-level
back-office setting we cannot read.

All amounts are JSON `number` — floating point, not minor units. Receipt examples
show `"total": 30000` alongside product prices like `8.5`, so the scale is
inconsistent between examples and undocumented.

For UZS this is mostly survivable (whole-soum amounts are exactly representable
in a double up to 2^53), but "mostly survivable" is not a property to build a
financial boundary on, and `service_charge` / `discount_value` percentages produce
fractional intermediates that will not round-trip. Question Q17.

**Position:** the adapter parses these as `BigDecimal` from the raw JSON text
(never via a `double`), asserts the brand currency from installation
configuration rather than from the API, and reconciles every exported total
against the receipt total on read-back.

---

## 10. Rate limits, pagination, and the error taxonomy

### 10.1 Rate limits

Three tiers, each a 1-minute sliding window, with IETF `RateLimit-*` headers:

| Tier | Limit | Keyed on | Applies to | Runs |
|---|---|---|---|---|
| 1 | **60 / min** | client IP | `POST /auth` only | — |
| 2 | **600 failed / min** | client IP | all authenticated routes; counts only responses **≥ 400** | **before** JWT validation |
| 3 | **300 / min** | `integrator_id` **:** `brand` | all authenticated routes | **after** JWT validation |

* Tier 3 is the one to plan against. **Minting new tokens does not reset it** —
  all tokens for a pair share the budget. **Distributing across workers does not
  multiply it** — enforcement is central.
* Tier 2 counts only failures, so a healthy integration never sees it. If we do,
  it means something is spamming invalid requests and the fix is upstream.
* Tier 1 is why token caching must be shared across pods (§2.2).

Response headers, present on every authenticated response, for the
currently-most-constrained tier: `RateLimit-Limit`, `RateLimit-Remaining`,
`RateLimit-Reset` (seconds until the window resets). On `429`, back off at least
`RateLimit-Reset` seconds.

**Adapter requirement:** consume `RateLimit-Remaining` and throttle proactively.
Reacting to `429` is strictly worse and Clopos says so.

### 10.2 Pagination

| Parameter | Default | Notes |
|---|---|---|
| `page` | `1` | 1-based |
| `limit` | `50` | **`/common-objects.md` says "typically 200"; `/products` says max 100.** Conflict — assume 100 for products, probe others |
| `sort[0]` | `created_at` | must be a value from the response's `sorts` array |
| `sort[1]` | `-1` | `1` ascending, `-1` descending |
| `date[0]` / `date[1]` | — | inclusive, `YYYY-MM-DD`, on `created_at` |
| `filters[N][0]` / `[1]` | — | field / value, PHP bracket notation |

`total` gives the pre-pagination count. **There is no cursor pagination, no
`ETag`, and no `If-Modified-Since` anywhere.** See §5.1 for why offset pagination
over a mutating catalog is a correctness problem and not just a performance one.

### 10.3 Error envelope, and its three inconsistencies

Documented shape:

```json
{ "success": false, "message": "Human-readable summary", "error": "machine-readable identifier" }
```

But:

1. **Two `error` vocabularies coexist.** `/errors.md` shows human sentences
   (`"Headers are missing"`, `"Invalid token"`, `"Token expired"`). Endpoint pages
   show snake_case slugs (`"not_found"`, `"validation_failed"`, `"unauthorized"`,
   `"invalid_parameter"`). Match defensively on both; do not build a switch on
   either alone.
2. **The OpenAPI `Error` schema is a third shape** — `{error, message, details}`,
   with `error` and `message` required and **no `success` field at all** —
   contradicting the documented envelope that says to branch on `success`.
3. **One authentication failure returns `200 OK`.** Already covered; it is the
   reason rule 1 of the adapter is *parse the body first*.

### 10.4 Classification — retryable / terminal / uncertain

**This table is mine, not Clopos's.** It is the contract the Camel route and the
ADR 0006 retry policy should implement.

| Condition | Class | Action |
|---|---|---|
| `200` + `success: true` | — | Success |
| `200` + `success: false`, test/prod mismatch | **TERMINAL** | Configuration error. Suspend the binding, alert the control plane. Never retry |
| `400` `Missing ...` / `validation_failed` / `invalid_parameter` | **TERMINAL** | Our bug or bad mapping. Dead-letter with the payload as ADR 0011 integration evidence |
| `400` `Invalid integrator_id` | **TERMINAL, platform-wide** | *Our* integrator is dead. Page someone — this affects every tenant, not one |
| `401` `Headers are missing` | **TERMINAL** | Adapter bug. Never retry blind |
| `401` `Invalid token` | **TERMINAL for this token** | Re-authenticate, retry **once**. A second occurrence is terminal |
| `401` `Token expired` | **RETRYABLE** | Re-authenticate, retry once. Should be rare if refresh is proactive |
| `401` `Invalid integrator_id` | **TERMINAL, platform-wide** | As above |
| `401` `Integrator is in test mode...` | **TERMINAL** | Configuration. Suspend binding |
| `401` upstream rejection (`message` only) | **TERMINAL** | Credentials wrong or module disabled. Mark installation `last_connection_status` failed; needs the restaurant |
| `404` | **TERMINAL** | Resource absent in this brand+venue. Often means a stale mapping or the wrong `x-venue` — surface as an ADR 0012 mapping conflict, not a transient error |
| `429` | **RETRYABLE** | Wait ≥ `RateLimit-Reset`, then exponential backoff |
| `500` | **RETRYABLE** | Exponential backoff. Persistent ⇒ escalate to `dev@clopos.com` with timestamp + `integrator_id` |
| `504` / upstream timeout, on `GET` | **RETRYABLE** | Safe — reads are idempotent |
| `504` / upstream timeout, on `POST` `/orders` | **UNCERTAIN** | **Never retry.** §7.6 |
| `504` on `POST /customers` | **UNCERTAIN** | May have created a duplicate customer. Resolve by read |
| `504` on `PATCH /receipts/{id}` | **RETRYABLE** | Setting a specific value; idempotent by construction |
| `504` on `PUT /orders/{id}` (`IGNORE`) | **RETRYABLE** | Setting a terminal state; idempotent by construction |
| `504` on `POST /receipts/{id}/close` | **UNCERTAIN** | Closing is not obviously repeatable; read `closed_at` before any retry |
| Connection reset / client timeout on any write | **UNCERTAIN** | Weaker than a `504` — we do not even know Clopos received it |
| TLS / DNS / connect failure | **RETRYABLE** | Never reached Clopos |

The useful generalisation: **`GET`, `PATCH`, and `PUT` are safe to retry because
they are idempotent by construction. `POST` is not, in any of its three
appearances, and there is no key to make it so.**

---

## 11. Capability matrix for ADR 0011

Against ADR 0011's six ports. **This is my assessment**, to be verified
empirically per installation (§2.1 step 3 means the Staff user's permissions can
reduce any of these).

| Capability | Status | Endpoint | Notes |
|---|---|---|---|
| `CatalogReadCapability` | **Supported** | `GET /products`, `/categories`, `/price-lists`, `/price-lists/prices`, `/stations` | Full-read only; no incremental fetch (§5.1). Price-list→venue resolution undocumented (§4.5) |
| `AvailabilityReadCapability` | **Supported, partial** | `GET /products/stop-list` | Stock limits only. Per-venue availability lives in the undocumented `venues` array (§4.6) |
| `OrderApprovalCapability` | **Supported, poll-only** | `POST /orders` (`auto_order_accept: false`) then poll `GET /orders` | Genuine authority. Latency = poll interval; no push (§6.4) |
| `OrderExportCapability` | **Supported, no idempotency** | `POST /orders` | The §7 risk. Requires a Clopos customer to exist first (§6.5) |
| `OrderCancellationCapability` | **Partial** | `PUT /orders/{id}` `{status: IGNORE}` | Pre-receipt only. Post-`RECEIVED` cancellation undocumented (§6.3, Q8) |
| `PreparationStatusCapability` | **Not supported** | — | The only preparation-shaped field is one we write (§6.3). Must not be configurable as a business path |

**Beyond ADR 0011's six**, Clopos also offers, and we may want ports for later:

* **Receipt/settlement read** — `GET /receipts`, `GET /receipts/{id}`,
  `GET /receipts/{id}/stock-operations`. Feeds ADR 0043 reporting and gives real
  cost-of-goods movement per receipt.
* **Fiscal identifier write-back** — `PATCH /receipts/{id}` `fiscal_id`. §9.2;
  this should become an explicit capability, because ADR 0038 depends on it.
* **Fulfilment status write** — `PATCH /receipts/{id}` `order_status`. Outbound
  telemetry *to* the restaurant, the mirror image of `PreparationStatusCapability`.
* **Customer upsert** — `POST /customers`, `GET /customers`. Touches ADR 0015 and
  ADR 0029; exporting customer phone and address to a POS is a PII flow that needs
  a consent basis, not just an endpoint.
* **Waiter call** — `POST` waiter-call, for ADR 0047 dine-in.

---

## 12. Open questions — the list to put to Clopos

Ordered by how much they change the design. `dev@clopos.com`, quoting our
`integrator_id`.

**Blocking — the integration is unsafe without these**

* **Q1. How does a caller set `integration_uuid` / `integration_id` on
  `POST /orders`?** They appear on every order *response* and the description says
  "if provided", but neither is in `CreateOrderRequest`. If they are settable,
  are they unique-constrained, and can `GET /orders` filter on them? *This is the
  idempotency question; everything in §7 depends on it.*
* **Q2. Is `order_number` a real request field?** The prose documents it (max 20
  chars) and the OpenAPI schema omits it. If real: is it unique per brand or per
  venue, is it enforced, is it filterable, and why does it not appear on the
  `Order` response?
* **Q14. Does a Clopos POS terminal fiscalize a sale on its own, in Uzbekistan,
  outside this API?** Specifically for a cash sale, a dine-in bill settled at the
  till, and a courier card terminal. *If yes, we have two systems issuing one
  receipt and it is a legal problem — see §9.3.*
* **Q18. What is the documented behaviour of a repeated `POST /orders` with an
  identical body?** Two orders, or a dedupe? Is there any dedupe window at all?

**Structural — these change what we can build**

* **Q3. How does a price list bind to a venue or sale type?** `llms.txt` says a
  price list "can be applied to specific venues or sales channels" but no field
  expresses it. How does a client resolve the effective price of product X at
  venue Y for sale type Z? (§4.5)
* **Q4. What is the element shape of `product.venues`?** Documented only as
  "venue-specific availability and pricing overrides"; every example is `[]`.
  (§4.6)
* **Q6. Is `GET /products` sortable, and does it return a `sorts` array?** Can it
  filter or sort on `updated_at`? If not, is there any change feed planned? *This
  determines whether ADR 0012's sync can ever be incremental, and whether the
  pagination race in §5.1 can be closed.* (§5.1)
* **Q8. How is an order cancelled after it reaches `RECEIVED`?** Does
  `PATCH /receipts/{id}` `order_status=CANCELLED` void the receipt, reverse the
  stock deduction, and notify the kitchen — or is it only a label? Is there a
  receipt void/delete? (§6.3)
* **Q13. When webhooks ship: what is the authentication scheme?** Signature
  algorithm, which secret signs, what is in the signed payload, replay window,
  retry policy, and the event catalogue. *Ask now, so the answer arrives before
  we need it.* (§8.1)

**Field-level — needed to finish the normalizer**

* **Q5.** How is `unit_id` resolved? There is no units endpoint. (§4.8)
* **Q7.** How is `auto_accept_terminal` discovered? No endpoint lists terminals.
  (§6.2)
* **Q9.** Is there a guest/anonymous order path, or must a Clopos customer exist
  before every export? `OrderCustomer.id` is required. (§6.5)
* **Q10.** `OrderProduct.count` is typed `integer`. How is a fractional quantity
  expressed for a `sold_by_weight` product? (§6.5)
* **Q11.** Is `MODIFIER` a real product type? The prose lists it; the OpenAPI enum
  does not. (§4.2)
* **Q12.** How is `product_hash` derived, and what does it hash? It is required on
  every order line and undocumented. (§6.5)
* **Q15.** What does `gov_code` hold, in what format? For an Uzbek brand, is it
  ИКПУ/MXIK? Is it settable via the API? (§9.3)
* **Q16.** What is the `Package` object (`{id, name, equal}`) for, and does it
  relate to a fiscal package code? (§9.3)
* **Q17.** What currency are amounts in, and how is it discovered? There is no
  currency field anywhere. What is the money scale and rounding rule? (§9.4)

**Operational**

* **Q19.** Can the client secret be rotated on a schedule, or is it always a
  support ticket? Is there any plan for self-service rotation? (§2.5)
* **Q20.** Is there a sandbox brand available to integrators, or must each
  customer provide a test brand? (§2.4)
* **Q21.** Does `Receipt.status` (integer) have a documented enumeration? The
  filter example uses `status=2` for "closed"; nothing else is stated.
* **Q22.** What is the actual `limit` maximum per resource? `/common-objects.md`
  says "typically 200", `/products` says 100. (§10.2)

---

## 13. Summary for the ADRs

**ADR 0011** — the installation model is confirmed correct. Installation = Clopos
brand; binding = Clopos venue via the `x-venue` header. Four things to absorb:
capability discovery must be per-installation because the credential is bound to a
Staff user whose permissions vary (§2.1); `PreparationStatusCapability` is not
supported and must be unconfigurable for Clopos (§6.3); POS approval is real but
arrives one poll interval late, so the ADR should address approval *latency*
alongside approval *failure* (§6.4); and secret rotation is a support ticket, not
a schedule, which the ADR 0028 dependency needs to reflect (§2.5).

**ADR 0012** — Clopos's stable identifier is an integer `id` per resource and
nothing else; names and prices are mutable and there is no SKU, which vindicates
"never guess mapping from mutable product names". Change detection requires a full
re-read, which the staged-snapshot design already assumes. Two things to add: the
offset-pagination race means a single page-through is not an atomic snapshot and a
`REMOVAL_SIGNAL` should require two agreeing reads (§5.1); and the stop list is a
separate high-frequency feed, not part of the daily reviewed run (§5.2).

**ADR 0038** — Clopos does not appear to fiscalize, and its writable
`Receipt.fiscal_id` is a useful place to write back the identifier Click or Payme
issued, closing the loop with the restaurant's own reporting. That is a fit, not a
collision. But it is unverified for Uzbekistan — this documentation is
Azerbaijan-first on the evidence of its own examples — and Q14 must be answered in
writing before the pilot takes a cash order.
