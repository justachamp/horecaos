# Legacy schema profile

Source-derived physical profile of the legacy Rayhon/Milliy FastAPI database.
It exists to answer one question per table: **can this table be migrated, and
what has to be measured in production first?**

This document profiles the schema *as declared in source*. It is not a
production profile. Where the code cannot establish a fact, the fact is marked
`PROFILE-nn` and belongs on the Phase 0 production profiling list
(ADR 0024, "Bulk backfill" gate). An unprofiled JSON column is an unmigratable
column; an unprofiled tenant path is a quarantine rule, not a default.

## Sources read

| Source | What it establishes |
|---|---|
| `milliy/backend/app/models/` (15 files, 64 models) | Declared columns, types, nullability, relationships, ORM validators |
| `milliy/backend/app/alembic/versions/` (15 revisions) | The physical DDL actually applied, plus added/renamed/abandoned columns |
| `milliy/backend/app/shared/enums/` (12 modules) | Enumerable label sets for every PG enum type |
| `milliy/backend/app/apps/{customer,vendor,courier,dashboard,integrations}/` | How columns are written and read — the only source for nullable-column meaning |
| `milliy/backend/app/pubsub/` | Notification and push write paths |
| `milliy/backend/content/migrate.sql`, `content/prod.sql` | Real seeded rows: locale keys, JSON instance shapes, lookup coverage |
| `milliy/backend/app/fixtures/product.json` | Real `products.meta` instances |

**Table count covered: 64 / 64.**

## Global conventions

Established from `app/shared/database.py` and confirmed in every `CREATE TABLE`.

- Every table inherits `BaseModel` and therefore carries two columns not
  repeated in the per-table tables below:
  - `created TIMESTAMP NOT NULL` — application-set `datetime.now()`, indexed as
    `ix_<table>_created` on all 64 tables.
  - `updated TIMESTAMP NOT NULL` — application-set `datetime.now()`, `onupdate`.
- **Both are naive `DateTime`, not `timestamptz`.** They are written from the
  application host's local clock. `Order.get_cooking_left_time()` compares these
  naive values against `datetime.now(timezone.utc)`, so the legacy system itself
  is inconsistent about their zone. `PROFILE-01`: determine the server timezone
  that produced historical rows before any timestamp is normalized to UTC.
- There is **no soft-delete column anywhere** — no `deleted_at`, no `is_deleted`.
  Deletion is expressed in three different ways, described in
  [Deletion and status semantics](#deletion-and-status-semantics).
- All JSON columns are `JSONB` wrapped in `MutableDict`/`MutableList`. There are
  no `CHECK` constraints and no JSON schema validation at the database level.
- PG enum types are created by `sa.Enum(...)` and store the Python enum
  **member name**, not its value. This matters exactly once and it matters a
  lot: see `cities` / `PROFILE-02`.
- Foreign keys are declared without `ON DELETE`/`ON UPDATE` actions, so every FK
  is `NO ACTION`. No cascade behaviour can be inferred from the schema.
- Locale dictionaries are validated by
  `app/shared/validators/languages_in_dictionary.py`, which asserts the presence
  of **all three** of `uz`, `en`, `ru`. The validator is an ORM event, so it
  binds only on writes through the ORM. Rows written by `content/*.sql` or by
  hand bypass it.

## The tenancy picture, before the tables

The legacy model has **two unrelated notions of "company"**, and this is the
single largest migration risk in the schema.

```text
companies.id  (uuid, PK)  ──< vendors.company_id (uuid, FK, NOT NULL)
companies.slug (varchar, NOT unique, no index)
        ▲
        │   join by string equality, no FK, no constraint
        │   app/apps/customer/services/address/set_address.py:30-46
        │
customers.company (varchar NOT NULL, server_default 'rayhon', indexed)
        │   values constrained only by enums.Company in Python:
        │   rayhon | marmar | jizbiz | kids_plate | pharmacy
```

Consequences that decide migratability:

1. **Vendor-owned rows are safe.** Anything that reaches `vendors.company_id`
   through non-nullable FKs has a provable single owner.
2. **Customer-owned rows are not.** `customers.company` is a free-text column
   whose only tie to `companies` is a runtime subquery on `companies.slug`.
   `companies.slug` has no unique constraint (`bbd98e60888e_initial.py`), so the
   join is not provably single-valued. `PROFILE-03`.
3. **Orders sit on the boundary.** `orders.vendor_id` is **nullable**, so an
   order's tenant is derivable from the vendor only when it is set; otherwise it
   falls back to `orders.customer_id → customers.company → companies.slug`,
   which is the unreliable path. `PROFILE-04`.
4. `customers.company` was added late (`b74d621cda4e`, 2025-09-01) with
   `server_default='rayhon'`. **Every customer row that predates that migration
   was backfilled to `rayhon` by the default, whether or not that is true.**
   `PROFILE-05`. ADR 0024's rule — rows without provable tenant ownership are
   quarantined, never assigned to a convenient default tenant — applies to the
   entire pre-2025-09 customer population by construction.
5. Only one `companies` row exists in both seed files
   (`092b7b3f-…-a3d6b1271f13`, slug `rayhon`). The multi-brand enum values
   (`marmar`, `jizbiz`, `kids_plate`, `pharmacy`) have no corresponding seeded
   company. `PROFILE-06`: enumerate distinct `customers.company` values in
   production and check each against `companies.slug`. Unmatched values cannot
   be tenanted at all.

Tenant-key reliability is graded per table below as:

- **A — direct**: a non-nullable FK chain to `vendors.company_id`.
- **B — derived**: a non-nullable FK chain that passes through `orders` or
  `carts`, whose own vendor link is nullable.
- **C — customer-slug**: ownership resolves only through
  `customers.company → companies.slug`.
- **D — none**: no tenant column and no path. Global rows.

---

# 1. Tenancy

## `companies`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK, application-generated `uuid4` |
| `slug` | `varchar` | no | **Not unique, not indexed.** The de-facto customer tenant key |
| `name` | `jsonb` | no | Locale dict |
| `description` | `jsonb` | no | Locale dict |
| `image` | `varchar` | yes | Relative path under `companies/images/` |
| `background_image` | `varchar` | yes | Relative path |

Constraints: PK `(id)`. Indexes: `ix_companies_created`. No unique constraints.

- **Tenant key**: D/root. This *is* the brand root. Preserve `id` as
  `tenant.brands.legacy_company_id`.
- **JSON**: `name`, `description` — **established**.
  `{"uz": str, "en": str, "ru": str}`, all three keys enforced by the ORM
  validator and present in both seed files. No nesting, no other keys observed.
- **Mutable aggregate**: none.
- **Delete/status**: `companies` has **no status column at all**. There is no
  way to express a disabled or deleted company. Deletion would be a hard
  `DELETE`, and there is no delete route for companies in the dashboard
  service (`app/apps/dashboard/services/company/company.py` exposes create,
  get, list, update only). Assume every row is live.

## `vendors`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK |
| `slug` | `varchar` | no | Not unique. Generated as `name["en"].lower().replace(" ","-")` when `make_slug()` is called |
| `name` | `jsonb` | no | Locale dict |
| `description` | `jsonb` | no | Locale dict |
| `company_id` | `uuid` | no | FK → `companies.id`, indexed |
| `phone` | `varchar` | yes | |
| `managers` | `jsonb` | yes | Declared `list[dict[str,str]]` but wrapped in `MutableDict` |
| `image` | `varchar` | yes | |
| `background_image` | `varchar` | yes | |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |
| `pre_order` | `boolean` | no | Model default `True`; dashboard input schema default `False` |
| `tin` | `varchar` | yes | Tax identification number |
| `latitude` | `double` | no | |
| `longitude` | `double` | no | |
| `address` | `varchar` | no | Unstructured free text |
| `city_id` | enum `city` | no | FK → `cities.id`, indexed |
| `work_time` | `jsonb` | no | |
| `delivery` | `jsonb` | no | |
| `rating` | `double` | yes | Model default `5.0` |
| `visibility_distance` | `bigint` | yes | Metres. Default `None` in model, `100000` in the dashboard schema |
| `tg_chat_id` | `varchar` | yes | Added `b74d621cda4e` (2025-09-01) |
| `tg_delivery_chat_id` | `varchar` | yes | Added `b74d621cda4e` |

Constraints: PK `(id)`, FKs to `companies`, `statuses`, `cities`. Indexes:
`ix_vendors_city_id`, `ix_vendors_company_id`, `ix_vendors_created`,
`ix_vendors_status_id`. **No unique constraint on `slug`.**

- **Tenant key**: A. `vendors.company_id` is `NOT NULL` with an FK. This is the
  one fully reliable tenant edge in the schema.
- **JSON**:
  - `name`, `description` — **established** locale dicts.
  - `work_time` — **shape established, value format not**:
    ```json
    {
      "working_days": {
        "monday": {"start": "09:00", "end": "23:00"},
        "tuesday": {...}, "wednesday": {...}, "thursday": {...},
        "friday": {...}, "saturday": {...}, "sunday": {...}
      },
      "non_working_days": [
        {"date": "2024-01-01", "start": "09:00", "end": "23:00"}
      ]
    }
    ```
    Weekday keys come from `settings.iso_weekday` (`monday`…`sunday`).
    Two writers disagree on the time format: `content/*.sql` writes `"HH:MM"`,
    while `dashboard/services/company/vendor.py` writes
    `WorkTimeSchema.model_dump(mode="json")` of a `datetime.time`, which
    produces `"HH:MM:SS"`. Two readers disagree on which they accept:
    `Vendor.active`/`start`/`finish` use `time.fromisoformat` (accepts both);
    `get_pre_order_time_slots` uses `strptime(..., "%H:%M")` and **raises on
    `"HH:MM:SS"`**. `PROFILE-07`: measure the distribution of both formats.
    The overnight interval (`start > end`) is explicitly handled by
    `Vendor.active` and explicitly **skipped** by `get_pre_order_time_slots`.
    No overnight instance appears in either seed file, so its existence in
    production is unconfirmed — `PROFILE-08`.
    `get_pre_order_time_slots` tolerates a missing weekday key; `Vendor.active`
    does not (`.get(...).get(...)` on `None`). `PROFILE-09`: check for rows with
    fewer than seven weekday keys.
  - `delivery` — **shape established, optionality not**:
    ```json
    {
      "distance": 1000,
      "distance_price": 10000,
      "max_distance": 100000,
      "min_order_price": 100000,
      "prices_per_km": [{"distance": 5000, "price": 2500}],
      "peak_hours": [
        {"start": "12:00", "end": "14:00", "distance": 5000,
         "distance_price": 10000,
         "prices_per_km": [{"distance": 3000, "price": 1500}]}
      ],
      "discount": {
        "value": 0, "type": "amount" | "distance",
        "min_order_price": null,
        "times": [{"start": "HH:MM", "end": "HH:MM"}]
      }
    }
    ```
    Keys `distance`, `distance_price`, `prices_per_km` are read with `[]`
    subscripts by `calculate_delivery_price` and are therefore required in
    practice. `peak_hours` and `discount` are read with `.get` and are **absent
    from both seed files**. `PROFILE-10`: how many vendors carry a `discount`
    block, and does `discount.times` always exist when `discount` does?
    `calculate_delivery_price` initialises `apply_discount = False` and only
    sets it inside the `"times" in discount` branch, so a discount without
    `times` never applies — a discount block whose absence of `times` was
    intended as "always" is silently dead. `max_distance` is read as
    `.get("max_distance", -1)` in `create_order`, so a missing key rejects
    every order.
  - `managers` — **not established**. `PROFILE-11`. The only writer sets it to
    `None`; both seed files write the JSON literal `null`; no reader does
    anything but pass it through `serialize_vendor`. The declared Python type
    (`list[dict[str,str]]`) contradicts the mutability wrapper (`MutableDict`),
    so the declaration is not evidence. ADR 0024 already forbids treating this
    as authoritative identity; it cannot be transformed until profiled.
- **Mutable aggregate**: `rating` — **flag**. Declared `Optional[float]` with a
  validator that asserts truthiness and the range 0–5, default `5.0`, and
  **no writer anywhere in the codebase**. `ratings` rows are never aggregated
  into it. Every seeded vendor carries `5`. It has no history, cannot be
  reconciled against the target's review projection, and per
  `docs/domains/legacy-mapping.md` must not become a location source-of-truth
  field. `PROFILE-12`: confirm whether production values still all equal 5 (in
  which case the column carries no information at all) or were edited out of
  band.
- **Delete/status**: `status_id ∈ {on, off, archived}`. Vendors are never
  hard-deleted by any route. `archived` is the closest thing to a delete, but
  no code sets it for vendors and the `statuses` lookup table is seeded with
  only `on` and `off` — see `PROFILE-16`. Deactivated (`off`) vendors are still
  referenced by `orders.vendor_id`, `products.vendor_id`, `carts.vendor_id`,
  `ui_elements.vendor_id`, `fin_agents.vendor_id`. Referenced-while-disabled is
  the normal state, not an anomaly.

## `vendor_delivery_methods`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `vendor_id` | `uuid` | no | FK → `vendors.id`, indexed |
| `delivery_method_id` | enum `deliverymethod` | no | FK → `delivery_methods.id`, indexed |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |

Constraints: PK `(id)`; `UNIQUE (vendor_id, delivery_method_id)` named
`unique_vendor_delivery_method`.

- **Tenant key**: A, via `vendor_id`.
- **JSON**: none.
- **Mutable aggregate**: none.
- **Delete/status**: `status_id`; the customer read path filters
  `status_id == on`. No delete route. `off` rows persist.

---

# 2. Identity and access

## `vendor_users`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `vendor_id` | `uuid` | no | FK → `vendors.id`, indexed |
| `username` | `varchar` | no | **Not unique.** Used as the login identifier |
| `password` | `varchar` | no | bcrypt hash |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |
| `role` | enum `vendorrole` | no | `admin` \| `packer` \| `finance` |
| `last_login` | `timestamp` | yes | Overwritten on **every authenticated request**, not on login |
| `last_login_ip` | `varchar` | yes | Never written by any code path found |
| `access_token` | `varchar` | yes | Current JWT, stored in plaintext; authentication matches on it |
| `fcm_token` | `varchar` | yes | Push target |
| `device_build` | `varchar` | yes | |

Constraints: PK `(id)`, FKs to `vendors`, `statuses`. Indexes:
`ix_vendor_users_created`, `ix_vendor_users_status_id`,
`ix_vendor_users_vendor_id`. **No unique constraint on `username`** — unlike
`dashboard_users`, which has one.

- **Tenant key**: A, via `vendor_id`.
- **JSON**: none.
- **Mutable aggregate**: `last_login` is overwritten by
  `dependencies/vendor.py:__get_vendor_user` on every request, so it is a
  "last seen" value with no history, not a login event log. Do not migrate it
  as a login timestamp.
- **Delete/status**: `status_id`; authentication rejects anything other than
  `on`. No delete route. Duplicate usernames across vendors are possible and
  must be checked — `PROFILE-13`.

## `dashboard_users`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `first_name` | `varchar` | no | |
| `last_name` | `varchar` | no | |
| `phone` | `varchar` | no | |
| `username` | `varchar` | no | **UNIQUE** |
| `password` | `varchar` | no | bcrypt hash |
| `role` | enum `dashboarduserrole` | no | `admin` \| `manager` \| `dispatcher` |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |
| `last_login` | `timestamp` | yes | Overwritten per request |
| `last_login_ip` | `varchar` | yes | |
| `access_token` | `varchar` | yes | Plaintext JWT |

Constraints: PK `(id)`, `UNIQUE (username)`, FK to `statuses`.

- **Tenant key**: **D — none.** Dashboard users are global operators with no
  company or vendor column. They are referenced by `orders.operator_id` and
  `orders.shipment_bonus_by_id` across every tenant. In a multi-tenant target
  they cannot be assigned to a tenant from the data; the grouping is an
  approved business input, not a derivation. `PROFILE-14`.
- **JSON**: none.
- **Mutable aggregate**: `last_login`, as above.
- **Delete/status**: this is the only user table with a real delete —
  `dashboard_users.py:98` issues `sa.delete(...)`. Deleted operators leave
  dangling-by-value references: `orders.operator_id` and
  `orders.shipment_bonus_by_id` are FK-constrained with `NO ACTION`, so the
  delete fails while orders reference them, meaning historically referenced
  operators are still present. Newly created, never-referenced operators can
  vanish without trace.

## `customer_sessions`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK; equals the JWT `sub` claim |
| `customer_id` | `uuid` | no | FK → `customers.id`, indexed |
| `device_id` | `uuid` | no | FK → `customer_devices.id`, indexed |
| `ip` | `varchar` | no | |
| `access_type` | `varchar` | no | Always the literal `"bearer"` |
| `access_token` | `varchar` | no | Plaintext JWT |
| `last_active` | `varchar` | no | **ISO-8601 datetime stored as text** |
| `status` | enum `sessionstatus` | no | `active` \| `deactivated` \| `expired`, indexed |

Constraints: PK `(id)`, FKs to `customers`, `customer_devices`.

- **Tenant key**: C, via `customer_id`.
- **JSON**: none.
- **Mutable aggregate**: `last_active` is a text timestamp overwritten in
  place. `PROFILE-15`: confirm it is always a parseable ISO string; it is a
  `varchar` with no validation, and the only writer sets
  `datetime.now().isoformat()` at session creation and never updates it — so
  the column name is misleading and it is really "created" duplicated.
- **Delete/status**: no delete. Superseded sessions are set to `expired` by
  `create_session`; device deletion expires them too. Rows accumulate forever.

## `otps`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement; returned to clients as `otp_job_id` |
| `phone` | `varchar` | no | Validator asserts prefix `998` |
| `customer_id` | `uuid` | yes | FK → `customers.id`, indexed. **Nullable** |
| `code` | `varchar` | no | **Plaintext OTP code** |
| `ip` | `varchar` | no | |
| `type` | enum `otptype` | no | `login` \| `reset_password`, indexed |
| `status` | enum `otpstatus` | no | `new` \| `sent` \| `failed` \| `verified` \| `expired`, indexed |
| `tries` | `integer` | no | Default 0 |
| `expires` | `timestamp` | yes | |

- **Tenant key**: C when `customer_id` is set; **none when it is null**. Every
  code path sets it, so a null indicates a legacy or out-of-band write.
  `PROFILE-17`. Rows with a null `customer_id` carry only a phone number and
  cannot be tenanted — quarantine.
- **JSON**: none.
- **Mutable aggregate**: `tries` and `status` are mutated in place; there is no
  attempt history. OTP audit cannot be reconstructed.
- **Delete/status**: no delete. Terminal state is expressed in `status`.
  `failed` is set in bulk by `create_otp` for all of a customer's prior
  unverified login OTPs.

## `courier_otps`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `phone` | `varchar` | no | Validator asserts prefix `998` |
| `code` | `varchar` | no | Plaintext |
| `ip` | `varchar` | no | |
| `status` | `varchar` | no | **Plain string, not a PG enum**, indexed. Comment says `enums.OTPStatus` |
| `tries` | `integer` | no | |
| `expires` | `timestamp` | yes | |

Added in `c2337cfac332` (2024-03-24).

- **Tenant key**: **D — none.** There is no `courier_id`, only a phone string.
  Couriers themselves are untenanted (see `couriers`). Not migratable to a
  tenant; retain as operational history or drop.
- **JSON**: none.
- **Mutable aggregate**: `tries`, `status`.
- **Delete/status**: none. `PROFILE-18`: because `status` is an unconstrained
  `varchar`, enumerate its actual distinct values rather than assuming the
  five `OTPStatus` labels.

---

# 3. Customers

## `customers`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK |
| `company` | `varchar` | no | `server_default 'rayhon'`, indexed. Added `b74d621cda4e` |
| `username` | `varchar` | no | Indexed. Holds the phone number, or `archive_<phone>` for archive rows |
| `first_name` | `varchar` | yes | Mapped to the private attribute `_first_name` |
| `last_name` | `varchar` | yes | Mapped to `_last_name` |
| `phone` | `varchar` | no | |
| `language` | `varchar` | no | Default `uz`. Plain string, not an enum |
| `image` | `varchar` | yes | Mapped to `_image` |
| `extra` | `jsonb` | no | |
| `archive_id` | `uuid` | yes | **Self-FK** → `customers.id`, indexed |

Constraints: PK `(id)`; `UNIQUE (username, company)` named
`uq_customers_username_company`, added in `154c792c2f27` (2025-09-01) which
**dropped the previous global `UNIQUE (username)`**. Self-FK on `archive_id`.

- **Tenant key**: C — and this is the boundary case that defines the quarantine
  rule. `company` is a `varchar` joined to `companies.slug` by string equality
  in `set_default_address`, with no FK and no unique constraint on the target.
  Combined with the `server_default 'rayhon'` backfill (`PROFILE-05`) and the
  possibility of unmatched slug values (`PROFILE-06`), **no customer row is
  provably tenanted from the data alone** for rows created before 2025-09-01.
- **The archive pattern.** Every real customer has an `archive_id` pointing to a
  second `customers` row whose `username` is `archive_<phone>` and whose own
  `archive_id` is `NULL`. The archive row is the destination for "deleted"
  addresses and devices. Consequences:
  - The table contains roughly **2× the real customer count**.
  - `get_customers` in the dashboard filters `archive_id IS NOT NULL` to
    exclude archive rows — that predicate is the only reliable discriminator.
  - Archive rows have `extra = {}`, no cart, and no orders.
  - `PROFILE-19`: count rows where `archive_id IS NULL` and `username` does not
    start with `archive_`. Those are neither real customers nor archives and
    have no defined meaning.
- **JSON**: `extra` — **partially established**.
  Keys written by code: `has_profile_data` (bool), `has_address` (bool),
  `otp_tries` (int, via `set_otp_tries`). Key **read but never written** by
  this codebase: `otp_limit` → `{"can_receive_after": "<ISO datetime>"}`
  (`login.py:92-101`). `PROFILE-20`: `otp_limit` is produced by something
  outside this repository or by a retired code path; its full shape must be
  read from production. Archive rows carry `{}`.
- **Mutable aggregate**: `extra.otp_tries` is a counter recomputed in place.
- **Delete/status**: **no status column and no delete route.** A customer is
  never deleted or deactivated. "Deletion" happens only for the customer's
  addresses and devices, by reparenting them to the archive customer.

## `customer_addresses`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK |
| `customer_id` | `uuid` | no | FK → `customers.id`, indexed |
| `name` | `varchar` | no | User-supplied label |
| `address` | `varchar` | no | Free text |
| `latitude` | `double` | no | |
| `longitude` | `double` | no | |
| `is_default` | `boolean` | no | Default `false`. **Not enforced unique per customer** |
| `note` | `varchar` | yes | |

- **Tenant key**: C, via `customer_id`. Note the twist: for a "deleted"
  address, `customer_id` points at the **archive** customer, whose `company`
  value is set at archive creation and is therefore still usable — but the row
  no longer identifies a live customer.
- **JSON**: none.
- **Mutable aggregate**: none.
- **Delete/status**: **`delete_address` reassigns `customer_id` to
  `customer.archive_id`** (`address/delete.py:21`). The row is not deleted.
  `orders.address_id` still points at it, so historical orders keep resolving
  their delivery address through a row that now belongs to the archive
  customer. Deleted rows *are* still referenced — this is the intended design.
  Migration must treat "address belongs to an archive customer" as the
  soft-delete marker. The last address cannot be deleted
  (`CANNOT_DELETE_LAST_ADDRESS`), so every live customer with any address has
  at least one live address.

## `customer_devices`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK |
| `customer_id` | `uuid` | no | FK → `customers.id`, indexed |
| `key` | `varchar` | no | **UNIQUE**, indexed. Composite value `"<customer_id>:::<device_id>"` |
| `name` | `varchar` | no | |
| `language` | `varchar` | no | Default `uz` |
| `platform` | `varchar` | no | Default `android`. Plain string, not the `platform` enum |
| `version` | `varchar` | yes | |
| `build` | `varchar` | yes | |
| `fcm_token` | `varchar` | yes | |
| `notifications_muted` | `boolean` | no | Default `false` |

- **Tenant key**: C, via `customer_id`, with the same archive caveat.
- **JSON**: none.
- **Mutable aggregate**: none.
- **Delete/status**: `delete_device` reparents to the archive customer **and**
  rewrites `key`. The rewrite is doubly prefixed: the setter already prepends
  `f"{self.customer_id}:::"` and the caller passes `f"{archive_id}:::{key}"`,
  producing `"<archive_id>:::<archive_id>:::<device_id>"`.
  `PROFILE-21`: `customer_devices.key` therefore has **at least three
  observable formats** — one, two, or (for repeat deletions) more `:::`
  segments. The getter's `split(":::")[-1]` hides this at runtime, but any
  migration that parses the key must handle all of them. Associated sessions
  are set to `expired` at the same time.

## `customer_invitations`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK |
| `customer_id` | `uuid` | no | FK → `customers.id`, indexed |
| `phone` | `varchar` | no | Invitee |
| `status` | `varchar` | no | Plain string, default `new`. Values from `enums.InvitationStatus`: `new` \| `accepted` \| `sent` |
| `code` | `varchar` | no | |

- **Tenant key**: C, via the inviting customer.
- **JSON**: none.
- **Mutable aggregate**: none.
- **Delete/status**: `status` only; no delete. `PROFILE-22`: `status` is an
  unconstrained `varchar`; enumerate real values. No code sets `accepted`, so
  the referral loop may never close in the legacy data.

## `black_lists`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `customer_id` | `uuid` | no | FK → `customers.id`, indexed |
| `order_id` | `bigint` | no | FK → `orders.id`, indexed. **Required** |
| `action` | enum `blacklistaction` | no | `only_online_order` \| `no_order` \| `no_login` \| `note` |

- **Tenant key**: C via customer, B via order. Both available.
- **JSON**: none.
- **Mutable aggregate**: none.
- **Delete/status**: no status, no delete, **no expiry column**. A block is
  permanent once written. Only `no_login` is enforced anywhere
  (`login.py:89`); `only_online_order`, `no_order` and `note` are written
  nowhere and enforced nowhere. `PROFILE-23`: the non-null `order_id` means a
  block cannot exist without an originating order, which constrains how blocks
  can have been created.

## `favourite_products`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `customer_id` | `uuid` | no | FK → `customers.id`, indexed. **Model declares `Mapped[int]`; DDL is `uuid`** |
| `product_id` | `uuid` | no | FK → `products.id`, indexed |

Constraints: `UNIQUE (customer_id, product_id)` named `unique_favourite_product`.

- **Model/DDL drift**: `product.py:361` declares `customer_id: Mapped[int]`
  while `bbd98e60888e_initial.py` creates `sa.UUID()`. The physical column is
  `uuid`. The same drift affects `search_histories.customer_id` and
  `offer_users.customer_id`. Trust the DDL, not the model.
- **Tenant key**: C via customer; A via `product_id → products.vendor_id`.
  A favourite therefore has **two tenant answers** and they can disagree if a
  customer of one brand favourited another brand's product. `PROFILE-24`.
- **JSON**: none.
- **Mutable aggregate**: none.
- **Delete/status**: hard `db.delete` in the favourite service. Unfavouriting
  removes the row. No history.

## `search_histories`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `customer_id` | `uuid` | no | FK → `customers.id`, indexed. Model declares `int` (drift) |
| `query` | `varchar` | no | |
| `count` | `integer` | no | Validator requires `> 0` |
| `extra` | `jsonb` | yes | |

- **Tenant key**: C.
- **JSON**: `extra` — **not established**. `PROFILE-25`. No writer sets it;
  `create_search_history` omits it entirely, so it is `NULL` for every row this
  code created. Any non-null value is legacy.
- **Mutable aggregate**: `count` — **flag**. Incremented in place
  (`search_history.count += 1`) with no per-search event rows. Search volume
  over time is unrecoverable; only the running total survives, and it has no
  timestamp beyond `updated`.
- **Delete/status**: no status, no delete.

---

# 4. Catalog

## `categories`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK |
| `name` | `jsonb` | no | Locale dict |
| `description` | `jsonb` | no | Locale dict |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |
| `priority` | `integer` | no | Validator requires `> 0` |
| `image` | `varchar` | yes | |

- **Tenant key**: **D — none.** `categories` has **no `vendor_id` and no
  `company_id`.** Categories are global across every vendor and every company.
  They are tenanted only transitively and ambiguously, through
  `products.category_id → products.vendor_id`, which is many-to-many in effect:
  one category can carry products of several vendors and therefore several
  companies. **This is a genuine blocker.** ADR 0002 makes catalogs
  brand-owned; the legacy data provides no brand for a category. `PROFILE-26`:
  for each category, count the distinct `companies` reachable through its
  products. Categories touching more than one company must be split or
  reassigned by business decision, not derived.
- **JSON**: `name`, `description` — **established** locale dicts (19 rows in
  `prod.sql`, all three keys present).
- **Mutable aggregate**: none.
- **Delete/status**: `status_id`, and a hard `sa.delete` in
  `dashboard/services/product/category.py:90`. Because `products.category_id`
  is `NOT NULL` with a `NO ACTION` FK, deleting a referenced category fails.
  Deleted categories are therefore always unreferenced.

## `kitchens`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK |
| `name` | `jsonb` | no | Locale dict |
| `description` | `jsonb` | no | Locale dict |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |
| `priority` | `integer` | no | |
| `image` | `varchar` | yes | |

- **Tenant key**: **D — none.** Same problem as `categories`: no vendor or
  company column, reachable only through `products.kitchen_id`.
  `PROFILE-26` applies. Only two kitchen rows exist in the seeds, and both
  seed files reuse the same UUID for a kitchen and a category
  (`e739e8a6-…-625cce25f059`) — the identifier spaces overlap by coincidence,
  which will confuse any ID-based reconciliation. `PROFILE-27`.
- **JSON**: `name`, `description` — **established**.
- **Mutable aggregate**: none.
- **Delete/status**: `status_id` plus a hard `sa.delete`, same FK protection as
  categories.

## `products`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK |
| `name` | `jsonb` | no | Locale dict |
| `description` | `jsonb` | no | Locale dict |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |
| `category_id` | `uuid` | no | FK → `categories.id`, indexed |
| `kitchen_id` | `uuid` | no | FK → `kitchens.id`, indexed |
| `vendor_id` | `uuid` | no | FK → `vendors.id`, indexed |
| `image` | `varchar` | yes | |
| `sku` | `varchar` | yes | **Not unique** |
| `time_enabled` | `boolean` | no | `server_default 'false'` |
| `start` | `time` | yes | Availability window start |
| `finish` | `time` | yes | Availability window end |
| `has_discount` | `boolean` | no | `server_default 'false'` |
| `tag_discount` | `boolean` | no | `server_default 'false'`. Read nowhere |
| `discount` | `integer` | no | `server_default '0'` |
| `discount_type` | enum `discounttype` | yes | `flat` (absolute) \| `unit` (percent) |
| `stock_enabled` | `boolean` | no | `server_default 'false'` |
| `priority` | `integer` | no | Model default 1000 |
| `meta` | `jsonb` | yes | |
| `search` | `tsvector` | yes | |

- **Tenant key**: A. `vendor_id` is `NOT NULL` with an FK. Products are the
  most reliably tenanted catalog rows in the schema.
- **JSON**:
  - `name`, `description` — **established** locale dicts.
  - `meta` — **shape established from fixtures, production variance not**.
    `app/fixtures/product.json` shows a **nested** structure:
    ```json
    {"uz": {"name": "Osh", "description": "Osh"},
     "en": {"name": "Pilaf", "description": "Pilaf"},
     "ru": {"name": "Плов", "description": "Плов"}}
    ```
    The model declares `dict[str, str]` — flat — which contradicts the fixture.
    Neither seed file writes `meta` at all (the `products` INSERT in
    `prod.sql` omits the column), and no dashboard route writes it.
    `PROFILE-28`: `meta` is either NULL, flat, or nested in production; all
    three must be counted. It also feeds the `search` tsvector definition with
    weight `B`, so its shape affects search behaviour.
  - `search` — a `TSVectorType("name","description","meta", weights=...)`
    column. **No trigger and no GIN index were ever created** by any migration,
    and no application code populates it, yet `search_items` queries it with
    `.match()`. `PROFILE-29`: the column is almost certainly NULL for every row,
    making legacy full-text search silently non-functional and the column
    worthless to migrate. Confirm, then drop rather than transform.
- **Mutable aggregate**: `discount`/`has_discount` are edited in place with no
  price history. A completed order's discount is preserved separately in
  `order_line_items`, so orders are safe, but "what was this product's price
  on date X" is unanswerable.
- **Delete/status**: `status_id ∈ {on, off, archived}` **plus** a hard
  `sa.delete` in `dashboard/services/product/product.py:191`. Read paths use
  two different predicates — the customer path filters `status_id == on`, the
  dashboard and vendor paths filter `status_id != archived` — so `archived` is
  used as the soft-delete marker in this table specifically. Archived products
  remain referenced by `order_line_items.variant_id → variants.product_id`
  and by `ui_element_items.product_id`, which has no status filter of its own.

## `variants`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK |
| `name` | `jsonb` | no | Locale dict |
| `description` | `jsonb` | no | Locale dict |
| `sku` | `varchar` | yes | Not unique |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |
| `product_id` | `uuid` | no | FK → `products.id`, indexed |
| `price` | `integer` | no | Minor-unit-free integer UZS |
| `preparation_time` | `integer` | no | Default 0, minutes |
| `vat` | `integer` | no | Default 0, percent |
| `spic_id` | `varchar` | no | Uzbek fiscal product classifier (ADR 0038) |
| `unit_code` | `varchar` | no | Fiscal unit code |
| `stock_count` | `integer` | no | Default 0 |
| `package_id` | `uuid` | yes | **Self-FK** → `variants.id`, indexed. Added `c2337cfac332` |
| `package_volume` | `double` | no | `server_default '1'`. Added `2f5d2e8f7a64` as `Integer`, widened to `Float` in `9246576bfbb8` |
| `is_package` | `boolean` | no | `server_default 'false'`. Added `c2337cfac332` |

Abandoned column: `package_count` `integer` `server_default '1'` was added in
`c2337cfac332` (2024-03-24 10:43) and **dropped the same day** in
`e4c39a3c263c` (21:01), superseded by `package_volume`. It does not exist in
the current schema; expect it to be absent from any production dump taken after
that date.

- **Tenant key**: A, via `product_id → products.vendor_id`. Both hops are
  `NOT NULL`.
- **JSON**: `name`, `description` — **established**.
- **Mutable aggregate**: `stock_count` — **flag, hard**. Recomputed in place by
  the dashboard variant service; there is **no ledger**. The separate `stocks`
  table that would have provided one is dead (see below). Two further problems:
  1. `prod.sql` seeds `stock_count = -1`, below the model's implied floor and
     below the `Stock.quantity >= 0` validator. Negative values are real.
     `PROFILE-30`: is `-1` a sentinel for "untracked"?
  2. `Variant.validate_availability` reads
     `not (stock_count > 0) and stock_count > quantity`, which is `False` for
     every positive stock and therefore never raises. Stock enforcement in the
     legacy system is effectively `stock_count <= 0` in `create_order` only.
  Stock levels cannot be reconciled against ADR 0017's inventory ledger. They
  must be imported as an opening balance with an explicit as-of timestamp, not
  as history.
- **Delete/status**: `status_id` plus a hard `sa.delete` in
  `dashboard/services/product/variant.py:103`. `order_line_items.variant_id` is
  a `NOT NULL` FK, so variants that were ever ordered cannot be deleted —
  historical orders are protected. `cart_line_items.variant_id` likewise. The
  customer read path filters `status_id != archived`; the order path requires
  `status_id == on`.
  **Deleted-but-referenced does not occur here; disabled-but-referenced does.**

## `tags`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `name` | `jsonb` | no | Locale dict |
| `icon` | `varchar` | yes | |

- **Tenant key**: **D — none**, and unlike categories there is not even a
  transitive path unless `product_tags` rows exist.
- **JSON**: `name` — locale dict by declaration and validator; **no instance
  observed**. `PROFILE-31`.
- **Mutable aggregate**: none.
- **Delete/status**: no status column, no delete route.
- **Liveness**: `models.Tag` is referenced **zero** times outside the model
  package. No route creates, reads, updates or deletes tags. Treat as a dead
  table pending a production row count (`PROFILE-32`).

## `product_tags`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `product_id` | `uuid` | no | FK → `products.id`, indexed |
| `tag_id` | `integer` | no | FK → `tags.id`, indexed |
| `priority` | `integer` | no | Validator requires `> 0` |

Constraints: `UNIQUE (product_id, tag_id)` named `unique_product_tag`.

- **Tenant key**: A, via `product_id`, *if* rows exist.
- **JSON**: none.
- **Mutable aggregate**: none.
- **Delete/status**: none.
- **Liveness**: zero references outside the model package. Dead pending
  `PROFILE-32`.

## `recommended_products`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `product_id` | `uuid` | no | FK → `products.id`, indexed |
| `variant_id` | `uuid` | no | FK → `variants.id`, indexed |
| `priority` | `integer` | no | Validator requires `> 0` |

- **Tenant key**: A, via either FK — but note the table stores **both** a
  product and a variant with no constraint that the variant belongs to the
  product. `PROFILE-33`: check for rows where
  `variant.product_id != recommended_products.product_id`; such rows have two
  conflicting tenant answers.
- **JSON**: none.
- **Mutable aggregate**: none.
- **Delete/status**: none.
- **Liveness**: zero references outside the model package. Dead pending
  `PROFILE-32`.

---

# 5. Inventory

## `stocks`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `bigint` | no | PK, autoincrement |
| `product_id` | `uuid` | no | FK → `products.id`, indexed |
| `variant_id` | `uuid` | no | FK → `variants.id`, indexed |
| `quantity` | `integer` | no | Validator requires `>= 0` |

- **Tenant key**: A, via either FK, with the same product/variant consistency
  caveat as `recommended_products`.
- **JSON**: none.
- **Mutable aggregate**: this table *looks* like the movement ledger that would
  make `variants.stock_count` reconcilable. **It is not populated.**
  `models.Stock` is referenced **zero** times outside the model package: no
  route writes it, no query reads it, and it has no direction, reason or
  reference column that a ledger entry would need. It is a single mutable
  quantity per (product, variant) — a second denormalised counter, not history.
- **Delete/status**: none.
- **Migration verdict**: the legacy system has **no inventory history at all**.
  ADR 0017's ledger cannot be seeded from legacy movements because none were
  recorded. Opening balances must come from `variants.stock_count` with an
  explicit as-of cutover timestamp and a signed statement that pre-cutover
  movements are not recoverable. `PROFILE-34`: row count of `stocks`; if
  non-zero, something outside this repository writes it and that writer must be
  found before cutover.

---

# 6. Carts and orders

## `carts`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK. **No default** — always supplied by the caller |
| `customer_id` | `uuid` | no | FK → `customers.id`, **UNIQUE** |
| `vendor_id` | `uuid` | yes | FK → `vendors.id`, indexed |
| `offer_id` | `integer` | yes | FK → `offers.id`, indexed |
| `distance` | `integer` | no | Default 0, metres |
| `delivery_time` | `timestamp` | yes | Pre-order slot |
| `address_id` | `uuid` | yes | FK → `customer_addresses.id`, indexed |

Constraints: PK `(id)`, `UNIQUE (customer_id)` — **one cart per customer,
forever**. There is no cart history; the same row is reused and cleared.

- **Tenant key**: A when `vendor_id` is set, C otherwise. `vendor_id` is
  populated only once the customer sets a default address
  (`set_default_address`), so **a cart created before any address exists has a
  null vendor and is untenanted except through the customer slug**.
  `PROFILE-35`.
- **JSON**: none.
- **Mutable aggregate**: the entire row. A cart is a single mutable working
  set — `clear_cart` deletes its line items after checkout, and changing the
  address deletes all line items when the nearest vendor changes
  (`set_address.py:52`). **There is no abandoned-cart history**; ADR 0019's
  cart events cannot be backfilled.
  `validate_delivery_time` asserts `value > now + 1 hour` **before** the
  `None` check short-circuits — the assertion runs on `None` too and raises
  `TypeError`, so setting `delivery_time = None` through the ORM fails.
  `PROFILE-36`: this means null `delivery_time` rows can only have been created
  by never setting it, not by clearing it.
- **Delete/status**: no status, no delete. Carts are permanent per customer.

## `cart_line_items`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `bigint` | no | PK. **No autoincrement declared in the model**; the DDL creates it without a sequence |
| `cart_id` | `uuid` | no | FK → `carts.id`, indexed |
| `variant_id` | `uuid` | no | FK → `variants.id`, indexed |
| `quantity` | `integer` | no | Validator requires `> 0` |
| `note` | `varchar` | yes | |

- **Tenant key**: inherits the cart's — A or C. Same nullable-vendor problem.
- **JSON**: none.
- **Mutable aggregate**: `quantity` is updated in place; the row is hard-deleted
  when quantity reaches zero (`update_cart_item.py:36`,
  `remove_from_cart.py:30`).
- **Delete/status**: hard delete, in three places: item removal, cart clearing
  after checkout, and vendor change on address update. Nothing survives.
- **Note**: `bbd98e60888e_initial.py` creates `id` as `sa.BigInteger()` with no
  `autoincrement=True`, unlike every other bigint PK in the schema.
  `PROFILE-37`: confirm whether a sequence or identity exists in production;
  if not, IDs are supplied by the application and may collide or be sparse.

## `orders`

The largest and most important table. 47 columns.

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `bigint` | no | PK, autoincrement. Customer-visible order number |
| `customer_id` | `uuid` | no | FK → `customers.id`, indexed |
| `address_id` | `uuid` | yes | FK → `customer_addresses.id`, indexed |
| `device_id` | `uuid` | yes | FK → `customer_devices.id`. **Not indexed** |
| `platform` | enum `platform` | no | `android` \| `ios` \| `web` \| `telegram` \| `support` |
| `status_id` | enum `orderstatus` | no | FK → `order_statuses.id`, indexed |
| `type_id` | enum `ordertype` | no | FK → `order_types.id`, indexed |
| `planned_time` | `timestamp` | yes | Pre-order slot |
| `vendor_id` | `uuid` | yes | FK → `vendors.id`, indexed. **Nullable — the tenancy hole** |
| `order_price` | `bigint` | no | Default 0. Net of item discounts |
| `order_price_without_discount` | `bigint` | no | Default 0 |
| `delivery_price` | `bigint` | no | Default 0. Net of delivery discount |
| `delivery_price_without_discount` | `bigint` | no | Default 0 |
| `delivery_distance` | `bigint` | no | Default 0, metres |
| `packaging_price` | `bigint` | no | `server_default '0'`. Added `2f5d2e8f7a64` |
| `cancelled_time` | `timestamp` | yes | |
| `start_time` | `timestamp` | yes | |
| `completed_time` | `timestamp` | yes | |
| `cancel_reason` | `varchar` | yes | Free text |
| `cancelled_by_id` | `varchar` | yes | **Untyped, unconstrained actor id** |
| `cancelled_by_type` | enum `ordercancelledbytype` | yes | `customer` \| `vendor` \| `dashboard` |
| `operator_id` | `integer` | yes | FK → `dashboard_users.id`. Not indexed |
| `operator_notes` | `jsonb` | yes | Model default `{}` |
| `cooking_started` | `timestamp` | yes | |
| `cooking_finished` | `timestamp` | yes | |
| `cooking_status` | enum `cookingstatus` | no | `new` \| `cooking` \| `ready` \| `cancelled` |
| `cooking_time` | `integer` | yes | Minutes, default 0 |
| `cooking_extra_time` | `integer` | no | Minutes, default 0 |
| `cooking_extra_data` | `jsonb` | yes | Model default `{}` |
| `shipment_status` | enum `shipmentstatus` | no | `new` \| `attached` \| `in_vendor` \| `in_delivery` \| `delivered` \| `cancelled` |
| `shipment_courier_id` | `integer` | yes | FK → `couriers.id`, indexed |
| `shipment_note_for_courier` | `varchar` | yes | |
| `shipment_courier_comment` | `varchar` | yes | |
| `shipment_attach_time` | `timestamp` | yes | |
| `shipment_to_vendor_time` | `timestamp` | yes | |
| `shipment_to_customer_time` | `timestamp` | yes | |
| `shipment_to_vendor_distance` | `bigint` | yes | |
| `shipment_to_customer_distance` | `bigint` | yes | |
| `shipment_delivery_time` | `bigint` | yes | |
| `shipment_delivery_start_time` | `timestamp` | yes | |
| `shipment_delivery_end_time` | `timestamp` | yes | |
| `shipment_courier_inform` | `jsonb` | yes | Model default `{}` |
| `shipment_attached_by_id` | `integer` | yes | **No FK.** Meaning depends on `shipment_attached_by_type` |
| `shipment_attached_by_type` | enum `shipmentattachedbytype` | yes | `courier` \| `dashboard` |
| `shipment_bonus` | `integer` | yes | |
| `shipment_bonus_by_id` | `integer` | yes | FK → `dashboard_users.id` |
| `shipment_attachment_policy_groups` | `jsonb` | yes | Declared `list[int]`, `MutableList` |
| `payment_method` | enum `paymentmethod` | no | Added `13078b532970` (2025-11-25), `server_default 'cash'`. **Model annotates it `Optional` but declares `nullable=False`** |
| `payment_status` | enum `paymentstatus` | no | Added `13078b532970`, `server_default 'new'` |
| `payment_transaction_id` | `varchar` | yes | Added `13078b532970`. No FK to `transactions` |

Indexes: `ix_orders_address_id`, `ix_orders_created`, `ix_orders_customer_id`,
`ix_orders_shipment_courier_id`, `ix_orders_status_id`, `ix_orders_type_id`,
`ix_orders_vendor_id`.

- **Tenant key**: **B, and conditionally unreliable.** `vendor_id` is nullable.
  It is set at creation from `cart.vendor_id`, which itself is nullable, and
  the dashboard order-update path can reassign it to a different vendor
  (`order.py:835`) — **deleting the order's line items in the process**
  (`order.py:821`). So:
  - Orders with `vendor_id IS NOT NULL` → tenant A via the vendor. Reliable.
  - Orders with `vendor_id IS NULL` → fall back to `customer_id → company →
    slug`. Path C. **Quarantine per ADR 0024.** `PROFILE-04`.
  - Orders whose vendor was reassigned have lost their original line items and
    therefore their original financial composition. There is no record of the
    change other than `updated`. `PROFILE-38`: this is a reconciliation hazard —
    `order_price` may not equal the sum of surviving `order_line_items`.
- **JSON**:
  - `operator_notes` — **established**:
    ```json
    {"internal_note": ["..."], "vendor_note": ["..."], "courier_note": ["..."]}
    ```
    All three keys optional; values are **appended lists of strings**
    (`order.py:838-850`). Readers take `[-1]` for the latest note.
    One inconsistency: `serialize_order` renders `internal_note` as
    `order.operator_notes["internal_note"] or ''` — a scalar idiom applied to a
    list. `PROFILE-39`: check whether any row stores `internal_note` as a bare
    string rather than a list; the serializer suggests an older scalar shape.
  - `cooking_extra_data` — **established** (Telegram receipt bookkeeping):
    ```json
    {
      "telegram_receipts": {
        "<target>": {
          "text": "...", "message_id": 123, "changed": true,
          "reply_to_message_id": null,
          "snapshot": { ...receipt snapshot... },
          "last_note_text": "...", "last_note_response": {...},
          "last_note_reply_to_message_id": null
        }
      },
      "sent_to_<target>_tg": true,
      "<target>_tg_response": { ...raw Telegram API response... }
    }
    ```
    `<target>` is one of exactly `"vendor"` or `"courier"`
    (`order.py:1178-1189`). The `snapshot` sub-object has two variants,
    `_build_vendor_receipt_snapshot` (order_number, branch_name, source,
    customer_name, payment_method, order_type, created_at, updated_at, items,
    items_summary, total_amount) and `_build_courier_receipt_snapshot` (adds
    customer_phone, address, map_url, delivery_price; omits items). This column
    is **operational integration state, not business data** — it should not be
    migrated, only archived.
  - `shipment_courier_inform` — **not established**. `PROFILE-40`. No writer and
    no reader anywhere in the codebase; model default `{}`. Non-empty values
    would be legacy or externally written.
  - `shipment_attachment_policy_groups` — **not established**. `PROFILE-41`.
    Declared `list[int]` with `MutableList`, default `[]`, no writer, no reader.
    The name implies courier-group scoping but nothing confirms it.
- **Mutable aggregate**: the price columns. `order_price`, `delivery_price`,
  `packaging_price` and their `_without_discount` twins are recomputed in place
  by the dashboard update path with no amendment record — ADR 0039's amendment
  model has no legacy source. `payment_status` is likewise mutated in place and
  is **duplicated** against `payments.status` and `transactions.status` with no
  constraint keeping the three in agreement. `PROFILE-42`: measure disagreement
  between `orders.payment_status`, the latest `payments.status`, and the latest
  `transactions.status`. Money reconciliation depends on knowing which one is
  authoritative.
- **Delete/status**: no delete, no soft-delete column. Cancellation is
  `status_id = 'cancelled'` plus `cancelled_time`, `cancel_reason`,
  `cancelled_by_id`, `cancelled_by_type`. Three independent status machines run
  in parallel on the same row — `status_id`, `cooking_status`,
  `shipment_status` — with no constraint aligning them. `PROFILE-43`: build the
  observed cross-product of the three; ADR 0019/0041's separate order and
  production lifecycles need to know which combinations really occur.
- **Enum drift**: the PG type `ordertype` was created with four labels
  (`delivery`, `express`, `takeaway`, `on_time`). `enums.OrderType` in the
  current code has **five** — it added `external`. **No migration ever added
  the `external` label to the PG type**, and no seed inserts an `external` row
  into `order_types`. Any attempt to write `external` fails at the database.
  `PROFILE-44`: confirm the label set of the `ordertype` type in production; if
  it was added by hand outside Alembic, the migration history is not a reliable
  description of the schema and every other enum must be re-read from
  `pg_enum`.

## `order_line_items`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `bigint` | no | PK, autoincrement |
| `order_id` | `bigint` | no | FK → `orders.id`, indexed |
| `variant_id` | `uuid` | no | FK → `variants.id`, indexed |
| `name` | `jsonb` | no | Locale dict, **snapshotted from the variant at order time** |
| `quantity` | `integer` | no | |
| `price` | `integer` | no | Snapshotted unit price |
| `discount` | `integer` | no | Snapshotted from `products.discount` |
| `discount_type` | enum `discounttype` | no | Snapshotted |
| `note` | `varchar` | yes | Customer note |
| `internal_note` | `varchar` | yes | Always `None` at creation |
| `is_package` | `boolean` | no | `server_default 'false'`. Added `2f5d2e8f7a64` |
| `reference_id` | `uuid` | yes | Added `f78e3bb07f2c`. **No FK.** For package rows, the id of the `variants` row the package belongs to |

- **Tenant key**: B, via `order_id`. Also A via `variant_id →
  products.vendor_id`. `PROFILE-45`: these can disagree if an order's vendor was
  reassigned; compare them.
- **JSON**: `name` — **established** locale dict, a point-in-time copy. This is
  the one place the legacy system does snapshot properly, and it is what makes
  historical order reconstruction possible at all.
- **Mutable aggregate**: none — but see the deletion note.
- **Delete/status**: **hard deletes, and they are destructive.** Line items are
  deleted wholesale when the dashboard reassigns an order's vendor
  (`order.py:821`) and individually by `delete_order_item` (`order.py:876`).
  There is no status column and no tombstone. A completed order's item list is
  therefore not guaranteed to reflect what was actually sold. This is the
  single strongest argument for reconciling `orders.order_price` against the
  item sum during migration rather than trusting either.
- **Package rows**: an order can contain synthetic package line items created
  from `variants.package_id` with `is_package = true`, `discount = 0`, and
  `reference_id` pointing at the parent variant. Quantity is
  `get_packages_count(quantity, package_volume)`. These rows are priced from
  `variants.package.price` and roll into `packaging_price` on the order.

## `order_statuses`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | enum `orderstatus` | no | PK. `new` \| `accepted` \| `cooking` \| `ready` \| `delivering` \| `completed` \| `cancelled` |
| `name` | `jsonb` | no | Locale dict |
| `description` | `jsonb` | no | Locale dict |

Seeded with all seven rows in both seed files.

- **Tenant key**: D. Global lookup.
- **JSON**: `name`, `description` — **established** locale dicts.
- **Mutable aggregate**: none.
- **Delete/status**: none. Reference data.

## `order_types`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | enum `ordertype` | no | PK |
| `name` | `jsonb` | no | Locale dict |
| `description` | `jsonb` | no | Locale dict |

Seeded with **four** rows (`delivery`, `takeaway`, `on_time`, `express`). The
Python enum has five. See `PROFILE-44`.

- **Tenant key**: D. Global lookup.
- **JSON**: `name`, `description` — **established**. Note the seeded `on_time`
  row is labelled "Pre Order"/"Предзаказ" — the id and the label diverge.
- **Mutable aggregate**: none.
- **Delete/status**: none.

## `incidents`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `order_id` | `bigint` | no | FK → `orders.id`, indexed |
| `content` | `varchar` | no | Free text |
| `cause` | enum `incidentcause` | no | `vendor` \| `courier` \| `customer` \| `operator` \| `dispatcher` |

- **Tenant key**: B, via `order_id`, inheriting the order's nullable-vendor
  problem.
- **JSON**: none.
- **Mutable aggregate**: none.
- **Delete/status**: no status, no delete, **no resolution column**. An incident
  once written is permanent and open.
- **Liveness**: `models.Incident` is referenced **zero** times outside the model
  package. Dead pending `PROFILE-32`.

## `ratings`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `customer_id` | `uuid` | no | FK → `customers.id`, indexed |
| `order_id` | `bigint` | no | FK → `orders.id`. **Unique index** `ix_ratings_order_id` since `1b787605480a` |
| `service_rating` | `integer` | yes | Made nullable by `666bc398f30a` |
| `delivery_rating` | `integer` | yes | Made nullable by `666bc398f30a` |
| `comment` | `varchar` | yes | Made nullable by `666bc398f30a` |
| `status` | enum `ratingstatus` | no | `new` \| `pending` \| `approved` \| `rejected` |

- **Tenant key**: B via `order_id`, C via `customer_id`.
- **JSON**: none.
- **Mutable aggregate**: **the ratings themselves are updatable in place.**
  `update_rating` overwrites `service_rating`, `delivery_rating` and `comment`
  and resets `status` to `new`, with no revision history. A rating's current
  value is not evidence of what the customer originally submitted.
- **Delete/status**: `status`, no delete. Only `new` is ever written by
  application code; `pending`, `approved`, `rejected` have no writer.
  `PROFILE-46`: if production contains approved/rejected rows, a moderation
  tool outside this repository exists and must be inventoried.
- **The rating aggregation gap**: `ratings` rows are never rolled up into
  `vendors.rating` or `couriers.rating`/`rating_count`. The three are
  independent. See `PROFILE-12` and `PROFILE-56`.

## Promotions applied to carts and orders

### `offers`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `vendor_id` | `uuid` | no | FK → `vendors.id`, indexed |
| `name` | `jsonb` | no | Locale dict |
| `description` | `jsonb` | no | Locale dict |
| `type` | enum `offertype` | no | `promo_code` \| `discount` |
| `scope` | enum `offerscope` | no | `order_price` \| `delivery_price` \| `total_price` |
| `audience` | enum `offeraudience` | no | `all` \| `new` \| `existing` \| `selected` |
| `promo_code` | `varchar` | no | **UNIQUE globally**, not per vendor |
| `discount` | `bigint` | no | Validator requires `> 0` |
| `discount_type` | enum `discounttype` | no | `flat` \| `unit` |
| `track_count` | `boolean` | no | Default false |
| `total_count` | `bigint` | no | Default 0 |
| `used_count` | `bigint` | no | Default 0 |
| `track_per_user_count` | `boolean` | no | Default false |
| `per_user` | `integer` | no | Default 0 |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |
| `eternal` | `boolean` | no | Default true |
| `start_time` | `timestamp` | yes | |
| `end_time` | `timestamp` | yes | |
| `from_sum` | `bigint` | no | Default 0 |
| `from_order_count` | `integer` | no | Default 0 |

- **Tenant key**: A, via `vendor_id`. But `promo_code` is globally unique, so
  two vendors of different companies cannot share a code — a constraint the
  multi-tenant target will not want to inherit. `PROFILE-47`.
- **JSON**: `name`, `description` — **established** locale dicts by declaration
  and validator; no instance observed in seeds.
- **Mutable aggregate**: `used_count` — **flag**. A counter with no per-use
  rows in `offers` itself; `offer_users_used` would be the ledger but has no
  writer (below). Redemption history is unrecoverable.
- **Delete/status**: `status_id`, no delete.
- **`discount_type` is semantically ambiguous and must be profiled.**
  `PROFILE-48`. The enum comments and the catalog pricing query
  (`app/apps/customer/utils/stmts.py:26-37`) agree that `flat` is an absolute
  amount and `unit` is a percentage. `get_offer_discounts`
  (`cart/get_offer_discounts.py:23-39`) inverts this for the `order_price`
  scope — it applies `flat` as `subtotal * discount / 100` and `unit` as a flat
  subtraction — and then **inverts it back** for the `delivery_price` and
  `total_price` scopes. Whatever the intent, historical `offers` rows were
  authored against one of these two readings and the schema does not say which.
  Migrating the values without resolving this changes real discounts.
- **Liveness**: `models.Offer` has **zero** direct references outside the model
  package; it is reached only through the `carts.offer` relationship. No route
  creates an offer. `PROFILE-32` applies.

### `offer_orders`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `order_id` | `bigint` | no | FK → `orders.id`, indexed |
| `offer_id` | `integer` | no | FK → `offers.id`, indexed |
| `discount_sum` | `integer` | no | Validator requires `> 1000` |
| `discount_formula` | `varchar` | no | Free text |

- **Tenant key**: B via order, A via offer → vendor. Both available.
- **JSON**: none. `discount_formula` is an unstructured string that is the only
  record of how a discount was computed. `PROFILE-49`: sample its values; it may
  be the only reconstructable audit of applied promotions.
- **Mutable aggregate**: none.
- **Delete/status**: none.
- **Liveness**: zero references outside the model package. This is the table
  that *should* record every applied offer, and nothing writes it — so
  `orders.order_price` reflects discounts that leave no trace. `PROFILE-32`.

### `offer_users`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `customer_id` | `uuid` | no | FK → `customers.id`, indexed. Model declares `int` (drift) |
| `offer_id` | `integer` | no | FK → `offers.id`, indexed |

Audience targeting for `offers.audience = 'selected'`. **No unique constraint on
`(customer_id, offer_id)`** — duplicates are possible.

- **Tenant key**: C via customer, A via offer. Can disagree. `PROFILE-24`
  pattern.
- **JSON**: none. **Mutable aggregate**: none. **Delete/status**: none.
- **Liveness**: zero references outside the model package. `PROFILE-32`.

### `offer_users_used`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `customer_id` | `uuid` | no | FK → `customers.id`, indexed |
| `offer_id` | `integer` | no | FK → `offers.id`, indexed |
| `used_time` | `timestamp` | no | |

- **Tenant key**: C via customer, A via offer.
- **JSON**: none. **Mutable aggregate**: none. **Delete/status**: none.
- **Liveness**: zero references outside the model package. Together with
  `offer_orders`, this means the per-user redemption limits (`per_user`,
  `track_per_user_count`) were never enforceable. `PROFILE-32`.

---

# 7. Payments

## `payment_methods`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | enum `paymentmethod` | no | PK. `cash` \| `click` \| `payme` \| `online` \| `terminal` \| `free` \| `bank_transfer` |
| `name` | `jsonb` | no | Locale dict |
| `image` | `varchar` | yes | |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |

Seeded with three rows only: `cash` (on), `click` (off), `payme` (on). The PG
enum type has seven labels. `PROFILE-50`: `online`, `terminal`, `free` and
`bank_transfer` have no lookup row, so `fin_agents.payment_method_id` and
`orders.payment_method` can hold values with no corresponding
`payment_methods` row for `fin_agents` (FK-protected) but **`orders.payment_method`
has no FK at all** and can hold any of the seven.

- **Tenant key**: D. Global lookup.
- **JSON**: `name` — **established**.
- **Mutable aggregate**: none.
- **Delete/status**: `status_id`; the customer payment-method list filters
  `status_id == on`.

## `fin_agents`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `name` | `jsonb` | no | Locale dict |
| `extra` | `jsonb` | no | |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |
| `payment_method_id` | enum `paymentmethod` | no | FK → `payment_methods.id`, indexed |
| `vendor_id` | `uuid` | no | FK → `vendors.id`, indexed |

Constraints: `UNIQUE (payment_method_id, vendor_id)` named `unique_pm_vendor`.

- **Tenant key**: A, via `vendor_id`. Clean.
- **JSON**: `extra` — **not established**. `PROFILE-51`. Declared
  `Dict[str, str]`, `NOT NULL`, seeded as `{}` for all six cash agents, and
  **never read by any code**. The name suggests it is where a provider's
  merchant credentials or terminal identifiers would live. Per ADR 0026/0028 it
  must be assumed to contain secrets until proven otherwise, and profiled with
  a redacting reader.
- **Mutable aggregate**: none.
- **Delete/status**: `status_id`, no delete.

## `payments`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `bigint` | no | PK, autoincrement |
| `order_id` | `bigint` | no | FK → `orders.id`, indexed |
| `fin_agent_id` | `integer` | no | FK → `fin_agents.id`, indexed |
| `fin_agent_extra` | `jsonb` | no | |
| `amount` | `bigint` | no | Total including delivery and packaging |
| `status` | enum `paymentstatus` | no | Indexed. `new` \| `in_progress` \| `completed` \| `failed` \| `cancelled` |

- **Tenant key**: A via `fin_agent_id → vendor_id` — **this is the most reliable
  tenant path for any money row in the schema**, because `fin_agents.vendor_id`
  is non-nullable while `orders.vendor_id` is not. Use it in preference to the
  order path.
- **JSON**: `fin_agent_extra` — **established as always `{}`**. Set to `{}` at
  creation (`create_order.py:214`) and never updated by any code path. Any
  non-empty value in production is legacy. `PROFILE-52`.
- **Mutable aggregate**: `status` is mutated in place with no state-transition
  log; only `updated` records that something changed. See `PROFILE-42` on the
  three-way status duplication.
- **Delete/status**: `status`, no delete. Exactly one payment row is created per
  order at checkout; there is no partial-payment or split-tender model
  (ADR 0046 has no legacy source).

## `transactions`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `bigint` | no | PK, autoincrement. **Sent to Click as `merchant_trans_id`** |
| `payment_id` | `bigint` | no | FK → `payments.id`, indexed |
| `customer_id` | `uuid` | no | FK → `customers.id`, indexed |
| `amount` | `bigint` | no | |
| `status` | enum `transactionstatus` | no | Indexed |
| `fin_agent_id` | `integer` | no | FK → `fin_agents.id`, indexed |
| `fin_agent_transaction_id` | `varchar` | yes | Provider's id, e.g. `click_trans_id` |
| `fin_agent_extra` | `jsonb` | no | |

- **Tenant key**: A via `fin_agent_id → vendor_id`. Reliable.
- **JSON**: `fin_agent_extra` — **established for Click, unknown otherwise**:
  the full `PrepareData`/`CompleteData` payload merged with
  `{"error": int, "error_note": str}`, i.e.
  `click_trans_id, service_id, click_paydoc_id, merchant_trans_id, amount,
  action, error, error_note, sign_time, sign_string` plus `merchant_prepare_id`
  on completion. **`sign_string` is a signature secret derived from
  `click_secret_key` and is persisted in this column** — treat the whole column
  as sensitive (ADR 0029). For cash it stays `{}`. The Payme branch of
  `create_invoice` is an empty `pass`, so Payme transactions exist with
  `{}` and no provider reference. `PROFILE-53`: enumerate distinct key sets by
  `fin_agents.payment_method_id`.
- **Mutable aggregate**: `status` and `fin_agent_extra` are overwritten on each
  webhook. A transaction that was prepared then completed retains only the
  completion payload — the prepare payload is lost. Provider reconciliation
  must use the provider's own records, not this column.
- **Delete/status**: `status`, no delete. Note `complete.py` sets
  `fin_agent_transaction_id` **before** checking whether the transaction is
  valid, so failed and rejected webhooks still stamp a provider id.

## `tax_receipts`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `bigint` | no | PK, autoincrement |
| `transaction_id` | `bigint` | no | FK → `transactions.id`, indexed |
| `payload` | `jsonb` | no | |
| `status` | enum `taxreceiptstatus` | no | Indexed. `new` \| `waiting` \| `success` \| `error` |
| `request` | `jsonb` | no | |
| `response` | `jsonb` | no | |
| `error` | `jsonb` | no | |

- **Tenant key**: A via `transaction_id → fin_agent_id → vendor_id`.
- **JSON**: all four columns — **not established**. `PROFILE-54`. `models.TaxReceipt`
  has **zero** references outside the model package: nothing creates, reads or
  updates a tax receipt. Yet `variants.spic_id` and `variants.unit_code` exist
  specifically to feed fiscalisation, and ADR 0038 depends on fiscal receipt
  history. Either the table is empty and Uzbek fiscal receipting was never
  implemented here, or a process outside this repository writes it. This is a
  compliance-relevant unknown and should be resolved before, not during, the
  payments wave.
- **Mutable aggregate**: `status`, `response`, `error` would all be overwritten
  in place if anything wrote them.
- **Delete/status**: `status`, no delete.

---

# 8. Delivery and courier

## `areas`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `name` | `varchar` | no | **Plain string, not a locale dict** |
| `type` | enum `areatype` | no | `circle` \| `polygon` \| `city` |
| `coordinates` | `jsonb` | no | |

- **Tenant key**: **D — none.** Areas are global. They reach a vendor only
  through `courier_group_areas → courier_groups → courier_group_vendors`, a
  many-to-many path that can resolve to several vendors and therefore several
  companies. ADR 0037's delivery zones are brand- or location-scoped; the
  legacy area has no owner. `PROFILE-55`.
- **JSON**: `coordinates` — **not established**. `PROFILE-56`. `NOT NULL`,
  passed straight through from `AreaInputSchema.coordinates: Optional[dict]`
  with no validation, no key contract, and no reader anywhere. The `type`
  column implies at least three distinct shapes — a circle (centre plus
  radius), a polygon (a ring of points), and a city (probably a `cities`
  reference) — but **none of them appears in the code or the seeds**. Every
  variant must be read from production before any geometry can be built. Note
  the schema declares `Optional[dict]` in the input while the column is
  `NOT NULL`, so `null` cannot be stored but `{}` can.
- **Mutable aggregate**: `coordinates` is replaced wholesale on update
  (`area.py:71`) with no version history.
- **Delete/status**: **hard delete** (`area.py:85-89`), preceded by deleting the
  `courier_group_areas` rows that reference it. No status column. Deleted areas
  leave no trace, and any order routed by a since-deleted area cannot be
  explained.

## `couriers`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `first_name` | `varchar` | no | Lowercased by the input schema |
| `last_name` | `varchar` | no | Lowercased by the input schema |
| `image` | `varchar` | yes | |
| `phone` | `varchar` | no | **UNIQUE** |
| `online` | `boolean` | no | Indexed, default false |
| `last_location_sent` | `timestamp` | no | Indexed, `server_default 'now()'`. Added `6951a13710b8` |
| `last_order` | `boolean` | no | Indexed, default false |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |
| `courier_type` | enum `couriertype` | no | Indexed. `foot` \| `bike` \| `car` \| `van` \| `truck` \| `scooter` \| `motorcycle` |
| `rating` | `double` | no | Default 5 |
| `rating_count` | `integer` | no | Default 0 |
| `id_card` | `varchar` | no | **UNIQUE**. National ID |
| `jshir` | `varchar` | no | **UNIQUE**. Uzbek personal identification number — **PII** |
| `driving_license` | `varchar` | yes | PII |
| `vehicle_registration_id` | `varchar` | yes | |
| `vehicle_plate_number` | `varchar` | yes | |
| `vehicle_fuel_type` | `varchar` | yes | |
| `emergency_contact` | `jsonb` | yes | Declared `list[dict[str,str]]`, wrapped in `MutableDict` |
| `address` | `varchar` | yes | Free text |
| `notes` | `jsonb` | yes | Declared `list[dict[str,str]]`, wrapped in `MutableList` |
| `referral` | `varchar` | yes | Never written |
| `work_time` | `jsonb` | yes | Declared `list[dict[str,str]]`, wrapped in `MutableDict` |
| `access_token` | `varchar` | yes | Plaintext |
| `refresh_token` | `varchar` | yes | Plaintext |
| `expires` | `timestamp` | yes | |
| `last_login` | `timestamp` | yes | |
| `last_login_ip` | `varchar` | yes | |
| `device_info` | `jsonb` | no | `server_default '{}'`. Made `NOT NULL` by `e4c39a3c263c` after a backfill |
| `extra` | `jsonb` | no | `server_default '{}'`. Added `c2337cfac332` as nullable, made `NOT NULL` by `e4c39a3c263c` |

- **Tenant key**: **D — none.** `couriers` has no company or vendor column. A
  courier reaches vendors only through `courier_group_couriers →
  courier_groups → courier_group_vendors`, which is many-to-many and may span
  companies. **Couriers are a shared global fleet in the legacy model.**
  ADR 0042 needs a courier to belong somewhere; the data does not say where.
  `PROFILE-57` — the grouping is a business input.
  This table also carries the schema's densest PII (`jshir`, `id_card`,
  `driving_license`, home address) and must be handled under ADR 0029 during
  profiling; profile key sets, never values.
- **JSON**:
  - `device_info` — **partially established**. One key is proven:
    `fcm_token` (the `Courier.fcm_token` property). Everything else is unknown.
    `PROFILE-58`.
  - `extra` — **partially established**. One key is proven: `otp_tries`
    (`courier/services/authentication.py:62`). `PROFILE-58`.
  - `notes` — **type established, contract not**. Written straight from
    `CourierInputSchema.notes: Optional[list[dict[str,str]]]` with no key
    validation; never read. `PROFILE-59`.
  - `emergency_contact` — **not established**. `PROFILE-60`. No writer, no
    reader; declared type contradicts the `MutableDict` wrapper.
  - `work_time` — **not established**. `PROFILE-61`. No writer, no reader.
    Declared `list[dict[str,str]]` here, in contrast to `vendors.work_time`
    which is an object — so the two columns of the same name are **not the same
    shape** and must not be profiled with one script.
- **Mutable aggregate**: `rating` and `rating_count` — **flag**. Both have
  default values (5, 0) and **no writer anywhere in the codebase**. Like
  `vendors.rating`, they are never derived from the `ratings` table. `online`,
  `last_order` and `last_location_sent` are also live mutable state with no
  history; `courier_locations` is the closest thing to a track and it has its
  own problems. `PROFILE-62`.
- **Delete/status**: `status_id`, no delete. Authentication requires
  `status_id == on`. Disabled couriers remain referenced by
  `orders.shipment_courier_id`, `courier_locations`, `courier_group_couriers`,
  `courier_blocks`, `courier_client_notes` — all normal.

## `courier_groups`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `name` | `varchar` | no | Plain string |
| `description` | `varchar` | no | Plain string, `NOT NULL` |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |

- **Tenant key**: **D — none directly.** A group's vendors come from
  `courier_group_vendors`, which is many-to-many. `PROFILE-63`: for each group,
  count distinct companies among its vendors. Groups spanning companies cannot
  be assigned to one tenant and are the concrete blocker for migrating courier
  routing policy.
- **JSON**: none.
- **Mutable aggregate**: none.
- **Delete/status**: `status_id` **and** a hard `sa.delete`
  (`courier/group.py:139`) preceded by deleting its `courier_group_areas` and
  `courier_group_vendors` rows. `courier_group_couriers` rows are **not**
  deleted first, and the FK is `NO ACTION`, so the delete fails while any
  courier is a member. `PROFILE-64`: check for `courier_group_couriers` rows
  whose `courier_group_id` no longer exists — they should be impossible, and
  if any exist the FK was dropped or bypassed in production.

## `courier_group_areas`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `courier_group_id` | `integer` | no | FK → `courier_groups.id`, indexed |
| `area_id` | `integer` | no | FK → `areas.id`, indexed |

**No unique constraint on the pair** — duplicates possible.

- **Tenant key**: D, inherited from the group's ambiguity.
- **JSON**: none. **Mutable aggregate**: none.
- **Delete/status**: hard delete, both on group update and on area delete.

## `courier_group_couriers`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `courier_group_id` | `integer` | no | FK → `courier_groups.id`, indexed |
| `courier_id` | `integer` | no | FK → `couriers.id`, indexed |

**No unique constraint on the pair.** `update_courier` deletes and re-inserts
memberships, so duplicates are unlikely but not prevented.

- **Tenant key**: D.
- **JSON**: none. **Mutable aggregate**: none.
- **Delete/status**: hard delete on courier update.

## `courier_group_vendors`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `courier_group_id` | `integer` | no | FK → `courier_groups.id`, indexed |
| `vendor_id` | `uuid` | no | FK → `vendors.id`, indexed |

**No unique constraint on the pair.**

- **Tenant key**: A per row, via `vendor_id` — this is the only table in the
  courier cluster with a direct tenant edge, and it is precisely the table that
  proves groups can span companies. It is the input to `PROFILE-63`.
- **JSON**: none. **Mutable aggregate**: none.
- **Delete/status**: hard delete on group update and group delete.

## `courier_blocks`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `courier_id` | `integer` | no | FK → `couriers.id`, indexed |
| `reason` | `varchar` | no | Free text |
| `object` | `varchar` | no | **Untyped identifier of the blocked thing** |
| `object_type` | enum `courierblockobjecttype` | no | `client` \| `vendor` \| `dashboard` |

- **Tenant key**: **D, and worse — polymorphic.** `object` is a bare `varchar`
  whose referent depends on `object_type`: a customer UUID, a vendor UUID, or a
  dashboard user integer. There is no FK. `PROFILE-65`: sample `object` by
  `object_type` and confirm the id format for each. Rows with
  `object_type = 'vendor'` are tenantable; the others are not.
- **JSON**: none.
- **Mutable aggregate**: none.
- **Delete/status**: no status, no delete, **no expiry**. A block is permanent.
  Note the `validate_object_type` validator has no `return`, so it returns
  `None` and **nulls the column it validates** — `object_type` is `NOT NULL`,
  so any ORM write of this field would fail. Combined with zero references
  outside the model package, this table is almost certainly empty or
  externally written. `PROFILE-32`.

## `courier_client_notes`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `courier_id` | `integer` | no | FK → `couriers.id`, indexed |
| `customer_id` | `uuid` | no | FK → `customers.id`, indexed |
| `note` | `varchar` | no | Free text about a customer, written by a courier |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |

- **Tenant key**: C via `customer_id`; the courier side gives nothing.
- **JSON**: none. **Mutable aggregate**: none.
- **Delete/status**: `status_id`, no delete.
- **Liveness**: zero references outside the model package. `PROFILE-32`.
  Note this table holds free-text staff commentary about identifiable
  customers — a GDPR/PII consideration for ADR 0029 whether or not it is
  migrated.

## `courier_instructions`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `courier_id` | `integer` | no | FK → `couriers.id`, indexed |
| `title` | `varchar` | no | |
| `note` | `varchar` | no | |
| `status_id` | enum `courierinstructionstatus` | no | `new` \| `sent` \| `failed` \| `read` \| `reverted`. **No FK** — a bare enum, unlike every other `status_id` in the schema |

- **Tenant key**: D — none.
- **JSON**: none.
- **Mutable aggregate**: `status_id` progresses in place with no delivery log.
- **Delete/status**: `status_id`, no delete. The `validate_status_id` validator
  has the same missing-`return` bug as `courier_blocks.object_type`, so ORM
  writes to this column null it out and violate `NOT NULL`.
- **Liveness**: zero references outside the model package. `PROFILE-32`.

## `courier_locations`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement. **`integer`, not `bigint`** |
| `courier_id` | `integer` | no | FK → `couriers.id`, indexed |
| `latitude` | `double` | no | |
| `longitude` | `double` | no | |
| `idempotency_key` | `varchar` | no | **Not unique.** Values observed: `f"order__{order_id}"` or the literal `"-1"` |

- **Tenant key**: D — none, except transitively through the order named in
  `idempotency_key`, which is a **string-encoded foreign key with no
  constraint**. `PROFILE-66`: parse `idempotency_key` and confirm the
  `order__<id>` format is the only one besides `-1`.
- **JSON**: none.
- **Mutable aggregate**: none — this is genuinely append-only, and it is the
  only append-only table in the courier cluster. Read paths take
  `ORDER BY created DESC LIMIT 1`.
- **Delete/status**: no status, no delete, **no retention policy**. This is a
  high-volume telemetry table on an `integer` PK. `PROFILE-67`: row count and
  growth rate. If it is near 2.1 billion the PK will overflow; more likely it
  needs a retention decision rather than a migration (ADR 0045 treats field
  telemetry separately from business data).
- **Idempotency is not enforced**: the column is named `idempotency_key` but
  has no unique index, and every non-order ping writes the same literal `"-1"`.
  It provides no deduplication.

---

# 9. Notifications

## `notifications`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK |
| `type` | enum `notificationtype` | no | `sms` \| `fcm` \| `email` \| `telegram` |
| `title` | `varchar` | no | **Rendered text, already localised at write time** |
| `body` | `varchar` | no | Rendered text |
| `image` | `varchar` | no | `NOT NULL`; written as the empty string |
| `customer_id` | `uuid` | no | FK → `customers.id`, indexed |
| `status` | enum `notificationstatus` | no | `pending` \| `sent` \| `failed` |
| `kind` | enum `notificationkind` | no | 12 labels: `order_created`, `order_updated`, `order_cancelled`, `order_completed`, `order_ready`, `promotion`, `special_offer`, `payment_success`, `payment_failed`, `new_release`, `new_service`, `new_product` |
| `object_id` | `varchar` | no | **Was `uuid`, changed to `varchar` by `c43ad4d3654d`** |
| `object_type` | enum `notificationobjecttype` | no | `order` \| `product` \| `service` \| `release` |
| `response` | `jsonb` | yes | |
| `reference_id` | `varchar` | yes | Never written |

- **Tenant key**: C, via `customer_id`. For `object_type = 'order'` the order
  gives a B path too.
- **`object_id` has two incompatible formats.** `c43ad4d3654d` (2024-02-25)
  widened the column from `uuid` to `varchar` precisely so that bigint order ids
  could be stored; the current writer does `str(order.id)`. Rows written before
  that migration hold UUID strings; rows after hold decimal integers.
  `PROFILE-68`: split by format and resolve each against the right table.
- **JSON**: `response` — **established as always `{}`** by the only writer
  (`pubsub/subscribers/order.py:93`). The FCM send result is logged but never
  written back, so delivery evidence does not exist in the database. Any
  non-empty value is legacy. `PROFILE-69`.
- **Mutable aggregate**: `status` would be mutated in place, but nothing ever
  moves it off `pending` — the send path does not update the row.
  `PROFILE-70`: if production shows `sent`/`failed` rows, another writer exists.
- **Delete/status**: `status`, no delete, no retention. Localised text is
  **baked into the row**, so re-rendering under ADR 0020's template model is
  impossible; these rows migrate as historical text or not at all.

## `notification_preferences`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `customer_id` | `uuid` | no | FK → `customers.id`, indexed, **UNIQUE** |
| `general` | `boolean` | no | Default true |
| `special_offers` | `boolean` | no | Default true |
| `promotions` | `boolean` | no | Default true |
| `new_release` | `boolean` | no | Default true |
| `new_service` | `boolean` | no | Default true |

- **Tenant key**: C, via `customer_id`. One row per customer.
- **JSON**: none.
- **Mutable aggregate**: none, but note there is **no consent timestamp and no
  consent history** — only current booleans. ADR 0015/0020 require provable
  consent; a boolean with no `updated`-independent provenance is weak evidence.
  `PROFILE-71`: how many customers have a row at all? Absence of a row is
  indistinguishable from "all defaults", and the default is opt-**in**.
  Whether that default can be carried into the target is a legal decision, not
  a data one.
- **Delete/status**: no status, no delete. The five channel booleans do not map
  onto the twelve `notificationkind` labels; four kinds
  (`order_*`, `payment_*`, `new_product`) have no preference switch at all.

---

# 10. Content and storefront UI

## `ui_elements`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `name` | `jsonb` | no | Locale dict |
| `description` | `jsonb` | no | Locale dict |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |
| `type` | enum `uielementtype` | no | `category` \| `banner` \| `offer` \| `popular` |
| `priority` | `integer` | no | Validator requires `> 0` |
| `vendor_id` | `uuid` | no | FK → `vendors.id`, indexed |
| `visibility_distance` | `integer` | no | Default 0. **Read by nothing** |
| `image` | `varchar` | yes | |
| `extra` | `jsonb` | yes | |

- **Tenant key**: A, via `vendor_id`. Clean.
- **JSON**:
  - `name`, `description` — **established** locale dicts (seeded).
  - `extra` — **not established**. `PROFILE-72`. No writer, no reader; the seed
    INSERT omits the column entirely so seeded rows are `NULL`. There is no
    dashboard route for UI elements at all — they exist only in
    `content/migrate.sql`. Any non-null `extra` in production came from a hand
    edit or a retired admin tool.
- **Mutable aggregate**: none.
- **Delete/status**: `status_id`, no delete. Note the customer read path
  (`ui/elements.py`) joins `models.UiOffer` in the `WHERE` clause without
  joining it in the `FROM`, producing an implicit cross join — the queries are
  correct only by accident of there being few `ui_offers` rows. Not a data
  issue, but it means the observed storefront behaviour is not a reliable guide
  to what the data means.

## `ui_offers`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK |
| `name` | `jsonb` | no | Locale dict |
| `description` | `jsonb` | no | Locale dict |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |
| `image` | `varchar` | yes | |
| `order_button_text` | `jsonb` | no | Locale dict |
| `order_button_action` | enum `offerbuttonaction` | no | `open_items` \| `open_category` \| `open_cart` \| `open_url` |
| `extra` | `jsonb` | yes | |
| `vendor_id` | `uuid` | no | FK → `vendors.id`, indexed |
| `type` | enum `uioffertype` | no | `product` \| `category` \| `ui_offer` |
| `product_id` | `uuid` | yes | FK → `products.id`, indexed |
| `category_id` | `uuid` | yes | FK → `categories.id`, indexed |

- **Tenant key**: A, via `vendor_id`.
- **JSON**:
  - `name`, `description`, `order_button_text` — **established** locale dicts
    (all three seeded, e.g. `{"en":"Order","ru":"Заказать","uz":"Buyurtma"}`).
  - `extra` — **not established**. `PROFILE-72`. Same situation as
    `ui_elements.extra`: no writer, no reader, omitted from the seed INSERT.
    Given `order_button_action = 'open_url'` exists as a label but no column
    holds a URL, `extra` is the likely home of that URL — **but nothing in the
    code confirms it**. This is exactly the kind of guess the profile must not
    make; measure it.
- **Mutable aggregate**: none.
- **Delete/status**: `status_id`, no delete.
- **Consistency**: `type` and the two nullable target FKs are not constrained
  against each other. Seeds show `type='product'` with `product_id` set and
  `category_id` null, and `type='category'` with the reverse — but nothing
  enforces it, and `type='ui_offer'` has no target column at all.
  `PROFILE-73`: count rows where `type` disagrees with which FK is populated,
  and rows where both or neither are set.

## `ui_element_items`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `ui_element_id` | `integer` | no | FK → `ui_elements.id`, indexed |
| `type` | enum `uielementitemtype` | no | `product` \| `category` \| `ui_offer` |
| `product_id` | `uuid` | yes | FK → `products.id`, indexed |
| `category_id` | `uuid` | yes | FK → `categories.id`, indexed |
| `ui_offer_id` | `uuid` | yes | FK → `ui_offers.id`, indexed |
| `priority` | `integer` | no | Validator requires `> 0` |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |

- **Tenant key**: A, via `ui_element_id → ui_elements.vendor_id`. But the item
  can point at a `product_id` belonging to a **different** vendor, since
  nothing constrains it. `PROFILE-74`: compare the element's vendor with the
  target product's/offer's vendor. Cross-vendor storefront items would be a
  tenancy leak in the target.
- **JSON**: none.
- **Mutable aggregate**: none.
- **Delete/status**: `status_id`, no delete. Same three-way nullable-target
  inconsistency as `ui_offers`; `PROFILE-73` applies here too.

## `faq_categories`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `name` | `jsonb` | no | Locale dict |
| `description` | `jsonb` | no | Locale dict |

- **Tenant key**: **D — none.** FAQ content is global across all companies.
  In the target it must be assigned to a tenant (or to the platform) by
  decision. `PROFILE-75`.
- **JSON**: `name`, `description` — **established** (seeded).
- **Mutable aggregate**: none.
- **Delete/status**: **no status column and no delete route.** Every row is
  live forever.

## `faqs`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `integer` | no | PK, autoincrement |
| `question` | `jsonb` | no | Locale dict |
| `answer` | `jsonb` | no | Locale dict |
| `category_id` | `integer` | no | FK → `faq_categories.id`, indexed |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |
| `priority` | `integer` | no | |

- **Tenant key**: D — none. `PROFILE-75`.
- **JSON**: `question`, `answer` — **established** locale dicts (seeded).
  Note the customer read path (`support/faq.py`) does **not** filter on
  `status_id`, so disabled FAQs are still served. That is a behavioural fact
  worth carrying into parity testing.
- **Mutable aggregate**: none.
- **Delete/status**: `status_id`, no delete, not enforced on read.

## `social_medias`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | enum `socialmedia` | no | PK. `facebook` \| `instagram` \| `telegram` \| `twitter` \| `youtube` |
| `name` | `varchar` | no | **Plain string, not a locale dict** |
| `image` | `varchar` | yes | Seeded as `media/social_media/<x>.svg` |
| `url` | `varchar` | no | |
| `priority` | `integer` | no | |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |

Seeded with all five rows.

- **Tenant key**: **D — none.** Global platform links. In a multi-brand target
  these are per-brand; the legacy data cannot say which brand. `PROFILE-75`.
- **JSON**: none.
- **Mutable aggregate**: none.
- **Delete/status**: `status_id`, no delete. The PK is an enum, so there can
  never be more than five rows and a second Instagram account per brand is
  unrepresentable.

---

# 11. Configuration and lookup

## `configs`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | enum `config` | no | PK. `otp_verification_phones` \| `default_vendor_id` \| `sms_center` \| `fcm` \| `telegram` |
| `name` | `varchar` | no | |
| `value` | `jsonb` | no | |

- **Tenant key**: **D — none.** Platform-global configuration.
- **JSON**: `value` — **not established. This is the single most important
  unprofiled JSON column in the schema.** `PROFILE-76`.
  `models.Config` is referenced **zero** times outside the model package: no
  code reads or writes this table. Everything the enum names is instead read
  from environment settings (`settings.default_otp_verification_phones`,
  `settings.click_*`, `settings.send_to_tg`). So either the table is empty and
  the enum is aspirational, or it holds live configuration consumed by
  something outside this repository.
  Two of the five ids are unambiguously sensitive:
  - `sms_center` and `fcm` would hold provider credentials → ADR 0028/0029
    apply; profile key names only, never values, and never copy the column into
    a migration staging table in clear.
  - `default_vendor_id` would hold a vendor UUID as configuration — a tenant
    reference living inside a JSON blob with no FK. If it is populated, it is a
    tenancy dependency that no schema analysis would find.
  Neither seed file inserts a single `configs` row.
- **Mutable aggregate**: `value` would be replaced wholesale.
- **Delete/status**: no status, no delete.

## `statuses`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | enum `status` | no | PK. `on` \| `off` \| `archived` |
| `name` | `jsonb` | no | Locale dict |
| `description` | `jsonb` | no | Locale dict |

- **Tenant key**: D. Global lookup, referenced by 17 tables.
- **JSON**: `name`, `description` — **established** (seeded).
- **Mutable aggregate**: none.
- **Delete/status**: none.
- **`PROFILE-16` — the archived row.** Both seed files insert only `on` and
  `off`. The PG enum type has three labels, and `products`/`variants` read
  paths filter on `status_id != archived`, implying archived rows are expected.
  But every `status_id` column is an FK to this table, so an `archived` value
  is impossible unless the lookup row was added later by hand. Determine
  whether the `archived` row exists in production. If it does not, **no row in
  the entire database is archived**, and the product soft-delete described
  above has never actually been used.

## `cities`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | enum `city` | no | PK |
| `name` | `jsonb` | no | Locale dict |

- **Tenant key**: D. Global lookup.
- **JSON**: `name` — **established** (seeded). Note the seeded `andijon` row
  carries Tashkent's name in all three locales — a copy-paste error preserved
  in `prod.sql`. `PROFILE-77`: do not trust `cities.name`; re-source locality
  names.
- **`PROFILE-02` — the enum stores names, not ISO codes.** `enums.City` is
  documented as ISO 3166-2:UZ and its *values* are `uz-an`, `uz-tk`, and so on.
  But `sa.Enum` on this class persists the member **name**, and the migration
  created the type with labels `andijon`, `buxoro`, …, `toshkent_shahri`,
  `xorazm`. The seeds confirm it: `INSERT INTO cities VALUES ('toshkent_shahri', …)`.
  **The ISO codes exist only in Python and are never stored.** Any mapping
  built from the enum's values will match nothing.
- **Coverage**: the PG enum has 14 labels; both seed files insert **3 rows**
  (`toshkent`, `toshkent_shahri`, `andijon`). `vendors.city_id` is an FK to
  this table, so vendors can only exist in those three localities unless more
  rows were added in production. `PROFILE-78`.
- **Mutable aggregate**: none. **Delete/status**: none, and no status column —
  a locality cannot be disabled.

## `delivery_methods`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | enum `deliverymethod` | no | PK. `delivery` \| `takeaway` |
| `name` | `jsonb` | no | Locale dict |
| `description` | `jsonb` | no | Locale dict |
| `image` | `varchar` | yes | Seeded `null` |
| `status_id` | enum `status` | no | FK → `statuses.id`, indexed |

Seeded with both rows.

- **Tenant key**: D. Global lookup; per-vendor enablement lives in
  `vendor_delivery_methods`.
- **JSON**: `name`, `description` — **established**.
- **Mutable aggregate**: none.
- **Delete/status**: `status_id`, no delete.
- **Note**: `delivery_methods.id` and `order_types.id` are different enums with
  overlapping labels (`delivery`, `takeaway`). `create_order` maps between them
  by string comparison. Do not merge them during migration without checking
  `PROFILE-44`'s `express`/`on_time`/`external` cases, which exist only on the
  order-type side.

---

# 12. Logs

## `logs`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK |
| `request_id` | `uuid` | no | Indexed |
| `initiator` | `varchar` | no | Free-text actor |
| `content` | `varchar` | no | Free text |
| `level` | enum `loglevel` | no | `info` \| `warning` \| `error` \| `critical` |
| `extra` | `jsonb` | yes | |

- **Tenant key**: **D — none.** No tenant column; `initiator` is untyped text.
- **JSON**: `extra` — **not established**. `PROFILE-79`.
- **Mutable aggregate**: none. Append-only by intent.
- **Delete/status**: none, no retention policy.
- **Liveness**: `models.Log` is referenced **zero** times outside the model
  package. Application logging goes to `structlog`/stdout
  (`main.py:26`), not to this table. Almost certainly empty. `PROFILE-32`.

## `action_logs`

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK |
| `request_id` | `uuid` | no | Indexed |
| `initiator_id` | `uuid` | no | **`uuid` — cannot hold a `dashboard_users.id` or `couriers.id`, which are integers** |
| `initiator_type` | enum `loginitiatortype` | no | `system` \| `dashboard` \| `vendor` \| `customer` \| `courier` |
| `object_id` | `varchar` | no | Polymorphic, untyped |
| `object_type` | enum `logobjecttype` | no | `product` \| `category` \| `variant` \| `vendor` \| `order` \| `courier` |
| `action` | `varchar` | no | Free text |
| `content` | `jsonb` | yes | |
| `extra` | `jsonb` | yes | |

- **Tenant key**: **D — none**, and structurally unable to acquire one: both
  the actor and the object are polymorphic references with no FK.
  `initiator_id` is typed `uuid` while three of the five `initiator_type`
  values (`dashboard`, `courier`, and `system`) refer to integer-keyed or
  non-existent entities. `PROFILE-80`: if the table is non-empty, determine
  what `initiator_id` actually holds for each `initiator_type` — the schema is
  self-contradictory.
- **JSON**: `content`, `extra` — **not established**. `PROFILE-79`.
- **Mutable aggregate**: none.
- **Delete/status**: none, no retention.
- **Liveness**: zero references outside the model package. This is the table
  that would have provided the legacy audit trail ADR 0027 wants to inherit,
  and **nothing writes it**. Assume there is no legacy audit history.
  `PROFILE-32`.

---

# Deletion and status semantics

There is no single delete convention. Four distinct mechanisms coexist, and
each behaves differently with respect to dangling references.

| Mechanism | Tables | Are "deleted" rows still referenced? |
|---|---|---|
| **Hard `DELETE`** | `dashboard_users`, `areas`, `courier_groups`, `courier_group_*`, `categories`, `kitchens`, `products`, `variants`, `cart_line_items`, `order_line_items`, `favourite_products` | Prevented by `NO ACTION` FKs in most cases — a referenced row cannot be deleted. **The exception is `order_line_items`**, which has nothing pointing at it and is deleted destructively on vendor reassignment. |
| **`status_id = 'archived'`** | `products`, `variants` (read paths only) | Yes. Archived products stay referenced by `order_line_items → variants → products` and by `ui_element_items.product_id`, which does not filter status. Conditional on `PROFILE-16`. |
| **`status_id = 'off'`** | 17 tables carrying `status_id` | Yes, always. Disabled is the normal steady state for vendors, fin agents, delivery methods, UI elements. Not a delete. |
| **Reparenting to the archive customer** | `customer_addresses`, `customer_devices` | **Yes, deliberately.** The row survives, `customer_id` moves to `customers.archive_id`, and `orders.address_id` keeps resolving to it. This is the only true soft-delete in the schema and it is invisible to anyone who does not know to look for the archive pattern. |

Tables with **no way to express deletion or deactivation at all**:
`companies`, `customers`, `carts`, `orders`, `black_lists`, `incidents`,
`faq_categories`, `courier_locations`, `logs`, `action_logs`, `tags`,
`configs`, `cities`, `search_histories`, `notification_preferences`,
`customer_invitations` (status only), `otps`/`courier_otps` (status only).

---

# Summary of findings

## Table coverage

**64 of 64 tables profiled.** Grouping used: tenancy (3), identity and access
(5), customers (7), catalog (7), inventory (1), carts and orders (12, including
4 promotion tables), payments (5), delivery and courier (10), notifications (2),
content and storefront UI (6), configuration and lookup (4), logs (2).

## JSON columns

### Shape established from code, seeds, or fixtures — 28 columns

`companies.name`, `companies.description`, `vendors.name`,
`vendors.description`, `vendors.work_time` (structure only — see the format
caveat), `vendors.delivery`, `categories.name`, `categories.description`,
`kitchens.name`, `kitchens.description`, `products.name`,
`products.description`, `variants.name`, `variants.description`,
`order_line_items.name`, `order_statuses.name`, `order_statuses.description`,
`order_types.name`, `order_types.description`, `statuses.name`,
`statuses.description`, `cities.name`, `delivery_methods.name`,
`delivery_methods.description`, `payment_methods.name`, `ui_offers.order_button_text`,
`ui_elements.name`/`description` and `ui_offers.name`/`description` (counted as
the locale-dict family), plus the two behavioural blobs
`orders.operator_notes` and `orders.cooking_extra_data`, and the
provider-payload blob `transactions.fin_agent_extra` (Click only).

The locale-dict family is uniformly `{"uz": str, "en": str, "ru": str}` — all
three keys, enforced by an ORM validator and confirmed in both seed files. Rows
written by `content/*.sql` bypass the validator but happen to comply.

### Shape NOT established — 21 columns, all Phase 0 blockers

| Column | Why it could not be established |
|---|---|
| `configs.value` | No reader, no writer, no seed rows. Includes probable credentials and a vendor UUID. `PROFILE-76` |
| `areas.coordinates` | `NOT NULL`, three implied geometry variants, zero instances anywhere. `PROFILE-56` |
| `vendors.managers` | Only ever written as `null`; declared type contradicts its mutability wrapper. `PROFILE-11` |
| `ui_elements.extra` | No reader, no writer, omitted from seeds. `PROFILE-72` |
| `ui_offers.extra` | Same; probably holds the `open_url` target but nothing confirms it. `PROFILE-72` |
| `couriers.emergency_contact` | No reader, no writer. `PROFILE-60` |
| `couriers.work_time` | No reader, no writer; **different declared shape from `vendors.work_time`**. `PROFILE-61` |
| `couriers.notes` | Type known, key contract unknown, never read. `PROFILE-59` |
| `couriers.device_info` | Only `fcm_token` proven. `PROFILE-58` |
| `couriers.extra` | Only `otp_tries` proven. `PROFILE-58` |
| `customers.extra` | `has_profile_data`, `has_address`, `otp_tries` proven; **`otp_limit` is read but never written by this codebase**. `PROFILE-20` |
| `products.meta` | Fixture shows nested `{lang:{name,description}}`; model declares flat `dict[str,str]`; seeds omit it. `PROFILE-28` |
| `orders.shipment_courier_inform` | No reader, no writer. `PROFILE-40` |
| `orders.shipment_attachment_policy_groups` | No reader, no writer; `list[int]` by declaration only. `PROFILE-41` |
| `fin_agents.extra` | `NOT NULL`, always `{}` in seeds, never read; likely provider secrets. `PROFILE-51` |
| `payments.fin_agent_extra` | Always `{}` from this code; non-empty values would be legacy. `PROFILE-52` |
| `tax_receipts.payload` | Table has no writer at all. `PROFILE-54` |
| `tax_receipts.request` | Same |
| `tax_receipts.response` | Same |
| `tax_receipts.error` | Same |
| `search_histories.extra` | Never written; `NULL` for every row this code created. `PROFILE-25` |
| `notifications.response` | Always `{}` from this code. `PROFILE-69` |
| `logs.extra`, `action_logs.content`, `action_logs.extra` | Tables have no writer. `PROFILE-79` |

Established-with-caveats, which must still be measured before transformation:

- `vendors.work_time` — structure known; **two time formats (`HH:MM` vs
  `HH:MM:SS`) written by different code paths, and one reader crashes on the
  longer one.** Overnight intervals are handled by one reader and skipped by
  another, and no overnight instance is observable. `PROFILE-07`, `-08`, `-09`.
- `vendors.delivery` — required keys known; `peak_hours` and `discount` are
  optional and absent from every seed. `PROFILE-10`.
- `orders.operator_notes` — list-valued by the writer, but the dashboard
  serializer reads `internal_note` as if it were scalar. `PROFILE-39`.
- `transactions.fin_agent_extra` — Click payload known and **contains
  `sign_string`, a signature secret**. Payme and cash paths unknown.
  `PROFILE-53`.

## Tables whose tenant key is not reliably derivable

**Grade C — resolvable only through `customers.company → companies.slug`, a
string join with no FK and no unique constraint on the target.** Every one of
these is subject to the `server_default 'rayhon'` backfill problem
(`PROFILE-05`) for rows predating 2025-09-01:

`customers`, `customer_addresses`, `customer_devices`, `customer_sessions`,
`customer_invitations`, `search_histories`, `notification_preferences`,
`notifications`, `black_lists` (customer side), `ratings` (customer side),
`courier_client_notes`, `otps`.

**Grade D — no tenant column and no derivable path at all.** These need an
approved business grouping, not a query:

| Table | Why |
|---|---|
| `categories` | No vendor/company column; reachable only through products, and one category can span several companies. `PROFILE-26` |
| `kitchens` | Same as categories |
| `tags` | No path even in principle |
| `dashboard_users` | Global operators referenced by orders across every tenant. `PROFILE-14` |
| `couriers` | Global fleet; vendor reach is many-to-many through courier groups. `PROFILE-57` |
| `courier_groups` | Can contain vendors of different companies. `PROFILE-63` |
| `courier_group_areas`, `courier_group_couriers` | Inherit the group's ambiguity |
| `courier_instructions` | Courier-scoped only |
| `courier_locations` | Courier-scoped; order reference is a parsed string. `PROFILE-66` |
| `courier_blocks` | Polymorphic untyped `object` column. `PROFILE-65` |
| `courier_otps` | Phone string only, no courier FK |
| `areas` | No owner; delivery geometry is global. `PROFILE-55` |
| `faqs`, `faq_categories`, `social_medias` | Global content. `PROFILE-75` |
| `configs`, `statuses`, `cities`, `delivery_methods`, `order_statuses`, `order_types`, `payment_methods` | Global lookups (expected and fine) |
| `logs`, `action_logs` | No tenant column, polymorphic actors. `PROFILE-80` |

**Conditionally unreliable — the important ones.** These have a tenant path
that works for most rows and fails for a measurable minority:

| Table | Condition |
|---|---|
| `orders` | `vendor_id` is **nullable**. Null-vendor orders fall back to the grade-C customer path. `PROFILE-04` |
| `carts` | `vendor_id` is nullable and is only set once an address exists. `PROFILE-35` |
| `cart_line_items` | Inherits the cart's |
| `order_line_items`, `incidents`, `offer_orders`, `black_lists`, `ratings` | Inherit the order's |
| `favourite_products`, `offer_users`, `offer_users_used` | Have **two** tenant answers — customer slug and product/offer vendor — which can disagree. `PROFILE-24` |
| `ui_element_items` | Element's vendor and target product's vendor are unconstrained against each other. `PROFILE-74` |
| `recommended_products` | `product_id` and `variant_id` are unconstrained against each other. `PROFILE-33` |
| `otps` | `customer_id` is nullable. `PROFILE-17` |

**Fully reliable — grade A.** A non-nullable FK chain to `vendors.company_id`:
`vendors`, `vendor_delivery_methods`, `vendor_users`, `products`, `variants`,
`stocks`, `product_tags`, `offers`, `fin_agents`, `payments`, `transactions`,
`tax_receipts`, `ui_elements`, `ui_offers`, `courier_group_vendors`.
`companies` is the root.

## Tables with no application writer (dead or externally written)

`configs`, `stocks`, `tags`, `product_tags`, `recommended_products`,
`incidents`, `courier_blocks`, `courier_client_notes`, `courier_instructions`,
`tax_receipts`, `offer_orders`, `offer_users`, `offer_users_used`, `logs`,
`action_logs`. `offers` has no create route either.

`PROFILE-32` covers all of them with one question: **row count in production.**
A non-zero count means a writer exists outside this repository, and that writer
must be found before its table is migrated or its absence assumed. Several of
these are the tables that would have carried the histories the target most
wants — the inventory ledger (`stocks`), the promotion redemption ledger
(`offer_orders`, `offer_users_used`), and the audit trail (`action_logs`).

## Mutable aggregates with no history

Flagged for ADR 0024 reconciliation: these cannot be reconciled against a target
ledger because the legacy system kept no movements.

| Column | Consequence |
|---|---|
| `vendors.rating` | No writer at all; never derived from `ratings`. Likely still the `5.0` default everywhere. `PROFILE-12` |
| `couriers.rating`, `couriers.rating_count` | Same — no writer. `PROFILE-62` |
| `variants.stock_count` | Recomputed in place, negative values observed, no ledger. Import as an opening balance only. `PROFILE-30` |
| `stocks.quantity` | The would-be ledger; unwritten |
| `offers.used_count` | Redemption counter with no per-use rows |
| `search_histories.count` | Running total, no events |
| `orders.order_price` and siblings | Recomputed on amendment; line items deleted on vendor reassignment. `PROFILE-38` |
| `orders.payment_status` vs `payments.status` vs `transactions.status` | Three unconstrained copies of one fact. `PROFILE-42` |
| `ratings.service_rating`/`delivery_rating`/`comment` | Overwritten in place on update; original submission lost |
| `customer_sessions.last_active`, `*.last_login` | "Last seen" values overwritten per request, not event logs |
| `transactions.fin_agent_extra` | Prepare payload overwritten by completion payload |

## Schema-history findings

- **Abandoned column**: `variants.package_count` added and dropped the same day
  (`c2337cfac332` → `e4c39a3c263c`), superseded by `package_volume`, which was
  itself widened from `Integer` to `Float` in `9246576bfbb8`.
- **Type change with a data consequence**: `notifications.object_id` went
  `uuid → varchar` (`c43ad4d3654d`) so it could hold bigint order ids. The
  column now holds two formats. `PROFILE-68`.
- **Constraint replacement**: `customers.username` lost its global unique index
  and gained `UNIQUE (username, company)` (`154c792c2f27`) on the same day the
  `company` column was introduced. Pre-migration usernames were globally
  unique; post-migration they are unique per company.
- **Backfill by default**: `customers.company` (`b74d621cda4e`) and
  `couriers.last_location_sent` (`6951a13710b8`, `server_default 'now()'`) both
  fabricated values for existing rows. Neither default is evidence.
- **Backfill with an explicit `UPDATE`**: `e4c39a3c263c` ran
  `update couriers set device_info = '{}' where device_info is null` before
  making the column `NOT NULL`. Empty-object courier JSON may mean "never had
  one", not "explicitly empty".
- **Model/DDL drift**: three `customer_id` columns are declared
  `Mapped[int]` in Python but created as `uuid` in DDL —
  `search_histories`, `favourite_products`, `offer_users`. The DDL is
  authoritative.
- **Enum drift**: `enums.OrderType.external` exists in Python with no
  corresponding label in the PG `ordertype` type and no `order_types` row.
  `PROFILE-44` — and if the label *was* added by hand in production, the
  Alembic history is not a trustworthy description of the schema and every
  enum type must be re-read from `pg_enum`.
- **Enum storage**: PG enums store Python member **names**. This silently
  discards the ISO 3166-2 codes in `enums.City`. `PROFILE-02`.
- **Lookup coverage gaps**: `statuses` seeded without `archived`
  (`PROFILE-16`), `cities` seeded with 3 of 14 (`PROFILE-78`),
  `payment_methods` seeded with 3 of 7 (`PROFILE-50`), `order_types` seeded
  with 4 of 5 (`PROFILE-44`). Every one of these is an FK target, so the gaps
  bound what values can exist.
