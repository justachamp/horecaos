# ADR 0160: User-scoped personalization

- Decision status: Proposed — proposed by Claude (batch 19); the platform owner decides
- Implementation status: Not started — the console remembers a few of a person's choices
  and the platform remembers one of them. The platform's one is the interface language:
  `iam.staff_members.ui_locale` (V0453, `ru`, `uz` or `en`), edited through
  `PUT .../staff/me` in «Мой профиль» and applied by `OwnProfile` once, on a browser where
  nobody has chosen a language (`hasStoredLocale`). Every other choice lives in the
  browser and follows neither the person nor the device they next sit at: the language
  switcher's own choice (`horecaos.operations.locale`), the branch (`CurrentLocation`,
  `horecaos.operations.locationId`), the brand (`BrandChoice`,
  `horecaos.operations.brandId`), the order board's filters per tab
  (`horecaos.operations.orderQueue.filters.<tab>`, `order-queue-filter-state.ts`), named
  saved views (`SavedViewsStore`, `q-data-table.views.<viewId>`), the data table's hidden
  columns (`TableFilterStore`, `<key>.hiddenColumns`) and split-pane widths. The order
  board does not use the data table and has no column picker; it draws none of
  created-by, accepted-by, courier type, source icon or courier ETA. What
  `OrderSummaryResponse` carries for them is uneven: `createdBy*` and `acceptedBy*` are an
  actor type and a subject, not a name (the order detail resolves names server-side,
  row `9.2d`), `discountMinor` and `courierId` are carried (the board already resolves
  the courier against a roster that names its type), and there is no courier ETA and no
  source (`origin` is a column of `ordering.orders` that the response does not select).
  «Моя работа» renders a `q-locked-state` band that says interface personalization is not
  built (`my-work-page`). ADR 0030 has no user level: `ResourceScope.ScopeType` is
  `PLATFORM`, `TENANT`, `BRAND`, `LOCATION`, `tenant.configuration_values` and
  `tenant.policies` carry the same four in `ck_*_scope_type` (V0005), and a
  `ConfigurationKey` resolves a scalar (`Boolean`, `Integer`, `Long`, `String` or
  `BigDecimal`). There is no staff preferences table.
- Date proposed: 2026-10-07
- Date decided: —
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0025, ADR 0027, ADR 0029, ADR 0030, ADR 0031, ADR 0033, ADR 0057,
  ADR 0101, ADR 0139
- Supersedes / Superseded by: — (amends no record and edits none. Row `0.2d` says "ADR
  0030 has no user scope — adding one is an ADR amendment". This record answers that
  question by not adding one: ADR 0030's chain stays `PLATFORM`, `TENANT`, `BRAND`,
  `LOCATION`. It reopens no rejected row of ADR 0030, whose Alternatives table does not
  discuss a user level. It also answers the Delever parity matrix's open question "what
  is actually in Персонализация?", which says the answer is "the only per-user-scoped
  state in this section and HorecaOS has no staff-preferences store", and the column
  picker `orders.md` §2.5 calls "persisted per user, IA 0.2")
- Open inputs: each is closed on its proposed default if the owner accepts the record
  as written; the ones that name a person other than the owner stay with that person
  and the work they block is marked.
  - **What is actually in Delever's Персонализация** (product). The parity matrix says
    the tab is documented only as an embedded video with no prose, and guesses
    "language, theme, default branch, table column layout, notification sound". Proposed
    default: the field set in Decision 3 (language, default branch, board columns, saved
    filters); theme, notification sound and density are not in v1 and are added one
    surface at a time when somebody asks. Blocks: nothing.
  - **Whether an owner may set a default for a tenant's people** (product). A tenant
    default column set is a different thing from a person's choice and would be an
    ordinary ADR 0030 `TENANT` key, not a preference. Proposed default: not built; the
    code-owned default set applies until a person changes it.
  - **Whether the column picker waits for the board to carry courier ETA and source**
    (product, engineering). Row `1.1` lists five columns the board does not draw, and
    the board read can supply three of them today (`createdBy`, `acceptedBy` with one
    names batch, and `courierType` from the roster it already fetches) and neither of the
    other two. Proposed default: the picker ships with the columns the board read can
    fill; `courierEta` and `source` are added one at a time, each under a
    `document_schema` bump, when the board read carries their field (Specification,
    «Board columns»), and row `1.1` keeps those two as its residue. Blocks: nothing in
    the build; blocks closing row `1.1`.
  - **Whether the data already in a person's browser moves to their account**
    (product, engineering). Proposed default: never automatically. A device that holds
    saved views or a column choice offers once, per device, «Сохранить мои настройки в
    учётной записи», and sends them only on a yes; the browser copy stays until the
    person clears it. A shared PC that two people sign in to never uploads one person's
    saved views under the other's account.
  - **How the stored document is classified** (security). ADR 0029's own list puts
    "preferences" in `PERSONAL`. Proposed default: the whole document is `PERSONAL`
    and stored envelope-encrypted, because one field in it, a saved view's label, is text
    a person typed and the platform cannot prove it is not about someone. Everything
    else in it is a closed vocabulary.
  - **How long a preferences row is kept** (legal). Proposed default: it is deleted when
    the person's staff-member row is anonymised by ADR 0139's retention sweeper (ADR
    0029's provisional 24 months after employment ends, report-only until legal
    answers), and never earlier; there is nothing else to retain it for.
  - **The control plane** (platform owner). Proposed default: out of scope. A
    platform-scope account has no tenant and no staff-member row, which is what the key
    of this store needs; the control plane keeps its browser-held state.
  - **Limits on a saved view** (product). Proposed default: at most 20 per surface, a
    label of at most 40 characters, and a document of at most 8 KB.

**To accept as written:** say "accept 0160". Every open input above is then closed on
its proposed default.

## Context

Gap-map row `0.2d` (UI personalization, user-scoped) says what is missing: "An operator
who sets a language on the kitchen terminal has to set it again on every other machine,
and no other preference (default branch, column layout, notification sound) can be
expressed at all." Its "Blocked by" says: "ADR 0030 has no user scope — adding one is an
ADR amendment, and the parity matrix's open question 'what is actually in
Персонализация?' leaves the field set an owner decision." Row `1.1` (the order board)
ends on the same dependency: the board does not draw courier type, source icon,
accepted-by, created-by or courier ETA, `orders.md` §2.5 "puts those behind a column
picker, and the picker is `0.2d`, which waits on a user scope in ADR 0030". The IA's row
`0.2` says it owns "UI personalization (**user-scoped**, unlike tenant-scoped settings)".

**Half of the first sentence is already answered, and the rest is not.** The language
follows the person to a new device today, once: `ui_locale` is on the member record, and
`OwnProfile` applies it on a browser with no stored choice. What does not happen is the
reverse, and it is exactly the kitchen terminal in the row's sentence: the language
switcher in the rail writes the browser and not the account, so a choice made on one
machine is never seen by the next, and a browser that already holds an old choice is
never overridden by a changed account. Every other choice (the branch, the board's
filters, a saved view, a hidden column) has no server home at all.

**Why ADR 0030 is the wrong place for it, though the row's wording points there.** Five
properties of the mechanism, each stated in its own record or code, fail a user level.

- **The scope type is shared with authorization.** `ResourceScope` is, in its own
  words, "the only definition of precedence in the platform": ADR 0030 uses `chain()`
  for resolution and ADR 0025 uses `covers` for capability checks. A `USER` value needs
  a place in that chain, and there is none: a person is not under a location, a brand or
  a tenant in the way a location is under a brand, and the preference for a board's
  columns does not depend on the branch they are looking at. The chain would have to be a
  special case (`USER`, then `TENANT`, then `PLATFORM`, skipping two levels), and every
  `switch` over `ScopeType` in resolution and in authorization would change to say so.
- **Every configuration write is an audited, versioned act.** ADR 0030 records "actor,
  reason, previous value and new value as an ADR 0027 audit fact" for each change, and
  `tenant.configuration_values` carries `set_by`, `reason` and `version`. A column
  toggled on a Tuesday morning would write an audit fact with a reason, which is
  evidence nobody wants and a flood of rows nobody reads.
- **Keys are scalars and settings surface to tenants.** A `ConfigurationKey` cannot hold
  a list of columns or a saved filter, and a tenant-visible key appears in Settings and in
  «Find a setting» (`ConfigurationKeys.all()`), where a per-person key would be meaningless
  and, for a tenant administrator, a place to read what a colleague arranged.
- **Caches key on scope.** `tenant.configuration` is cached per resolved scope and evicted
  on a change; one entry per person per key, each evicted on every click, is the cost.
- **Policies are immutable once referenced.** Nothing about a column choice should be
  pinned to a business fact.

The pattern ADR 0030 does fit is the one a tenant default would follow: a `TENANT` key
saying "which columns the board opens with". That is a different feature from a person's
own choice, and it can be added later without touching this record.

**Where the data should live is the question of ADR 0139's boundary.** Keycloak holds
credentials, sessions and the sign-in identifier; the tenant's own record of a person is
`iam.staff_members`, per tenant, and ADR 0139 refused to mirror profile edits into
Keycloak because one account can serve two tenants and the last tenant to write would
win. A preference is per tenant for the same reason (a person's default branch is a
branch of one company), so it belongs beside the member record, not in the identity
provider. It does not belong *in* the member row either: that row carries a version
that manager edits and the person's own edits contend on, and the preference is rewritten
far more often than anything else on it.

**What it may hold.** ADR 0029 lists "preferences" among `PERSONAL` data and the row's
own constraint is "no PII beyond the subject id". Both are satisfied by a closed
vocabulary: a column key, a filter field, a relative date token and a branch id are not
personal content. The one thing a person types, the label of a saved view, is not
provably impersonal, which is why the whole document is protected (Open inputs) and why
a search term, a customer, an order or a courier is never part of a saved filter.

## Decision

**Do not add a user scope to ADR 0030. Add a per-person preferences store in `iam`,
beside the staff-member record, holding one closed-vocabulary document per surface,
written only by the person it belongs to, and keep the choices that belong to a device
on the device.**

1. **No `USER` in `ScopeType`, `ck_*_scope_type`, `ConfigurationKey` or the resolution
   chain.** ADR 0030 and ADR 0025 are unchanged. A tenant-wide default for people, if
   ever wanted, is an ordinary `TENANT` key that the console reads and a person's own
   document overrides.

2. **One store: `iam.staff_preferences`, one row per `(tenant, subject, surface)`.** The
   key is the tenant and the same subject string `iam.grants` and `iam.staff_members`
   use, referenced from the member row by its unique `(tenant_id, principal_subject)`. The
   row holds a protected document, a schema version for the document and a row version,
   and nothing else: no name, phone, address, device identifier, network address or
   free-text search term. The surfaces are a closed set owned by code. A write is
   validated against that surface's schema before it is protected, and a key, a value or a
   surface the code does not know is refused, not stored.

3. **The field set is four things, and each has one home.**

   | Field | Home | Surface and shape |
   |---|---|---|
   | Interface language, as the person's default | `iam.staff_members.ui_locale`, unchanged (ADR 0139) | not in the new store, so there is one owner of it |
   | Interface language, as this device's override | the browser, unchanged | `horecaos.operations.locale`; never sent to the server, because a kiosk or an office PC is a device decision and a shared PC must not carry one person's language to the next |
   | Default branch | `iam.staff_preferences`, surface `SHELL` | `defaultLocationId`, a branch the person holds an active grant at (checked on write, and ignored by the console if the grant has since gone) |
   | Board columns | surface `ORDER_BOARD` | `columns.show` and `columns.hide`, lists of column keys from the code-owned enumeration below, stored as a change from the default so a column added in a later release is not hidden by an old choice |
   | Saved filters | surface `ORDER_BOARD` | `savedViews`, each `{ id, label, tab, filters }` with filters from the closed list below |
   | Everything else a browser keeps (live filters per tab, split-pane widths, the branch and brand last chosen on this device, hidden columns of other tables) | the browser, unchanged | device-class state: high-churn, or a fact about the machine, or free-text |

   The order board's column vocabulary: always shown and not hideable `number`, `time`,
   `total`, `status`, `actions`; shown by default and hideable `branch` (and hidden
   anyway for a single-branch tenant, as `orders.md` §2.5 says), `channel`, `customer`,
   `items`, `payment`, `courier`; off by default and addable in v1 `createdBy`,
   `acceptedBy`, `courierType`, `discount`. A column is offered only if the board read
   gives its cell something to draw (Specification, «Board columns»). The IA row also
   names courier ETA and a source icon, and the board read carries neither, so
   `courierEta` and `source` are not in v1 and are added, one `document_schema` bump
   each, when the board read carries their field. A saved view's filter fields are a
   closed list: `allBranches`, `channelCode`, `origin` (`HORECAOS`, `MARKETPLACE`),
   `fulfillmentMode`, `paymentMethodCode`, `paymentStatus`, `fiscalStatus`, `lateOnly`,
   `problemOnly`, `callbackRequested`, `mineOnly`, and a relative `dateRange` token
   (`TODAY`, `YESTERDAY`, `LAST_7_DAYS`, `THIS_MONTH`). Not allowed: the search box
   (`reference`), a courier, a marketplace binding, an absolute date, a customer, an
   order.

4. **Precedence is one rule, the same for language and for branch.** A choice made on
   this device wins; a person's stored default applies where this device has no choice;
   the platform default applies last. For language that is the browser's stored locale,
   then `ui_locale`, then `ru`. For the branch it is the branch chosen on this device,
   then `SHELL.defaultLocationId` if the roster still contains it, then the only branch
   the person has, then the first. The rail's language switcher offers, once per change
   and defaulting to «Только на этом устройстве», to save the choice as «Для всех моих
   устройств», which writes `ui_locale` through the existing `PUT .../staff/me` under the
   member's version and clears the device override. «Мой профиль» shows when a device
   override is in force and offers «Использовать мой язык на этом устройстве».

5. **Only the person reads or writes their own preferences.** The routes use ADR 0139's
   `@StaffSelfAuthorized(staff.self.manage)`; there is no new capability, no manager
   read, no tenant-wide write. A device principal and a support session have no member
   row and get no preferences, and the console keeps its browser-held state for them.

6. **Writes are cheap and unaudited, and say so.** A preference write writes no ADR 0027
   fact and no event: it is neither a business act nor a security one, and an audit
   record per click is a cost with no reader. The console debounces writes, the server
   rate limits them per principal (ADR 0033) and bounds each document, and a metric
   counts outcomes with no identity. The one preference-related change that is audited is
   the language default, because it already is a profile edit under ADR 0139.

7. **The server's document is the truth for what it holds; the browser keeps a mirror.**
   The console reads all of a person's documents once per session, applies them before
   first paint from a mirror in `localStorage` (so a slow or failed read does not blank
   the board), and writes through. The mirror is keyed by a hash of the subject and
   cleared at sign-out, so the next person on a shared PC inherits nothing. Two devices
   writing the same surface contend through `If-Match`: on a stale version the client
   re-reads, re-applies its one change and tries once more, then drops the write and
   keeps the screen as it is.

8. **Where it appears.** «Мой профиль» gains a section «Интерфейс» with the language
   rule above, the default branch, a list of saved views (rename, delete) and a reset of
   the board's columns; the board's toolbar gets the column picker, which writes through;
   the `q-locked-state` band on «Моя работа» becomes a link to that section. The data
   table's own chooser is left on the browser until a table needs it server-side.

9. **Deleted with the person.** A row goes when the member row is anonymised, and a
   person can reset any surface at any time. Offboarding a tenant ends its keys and so
   the ability to read the documents.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Add a `USER` scope to ADR 0030 (the row's own suggestion) | The scope type is shared with ADR 0025's `covers`; a user has no place in the location, brand, tenant chain; every write would be an audited, versioned configuration change; keys are scalars and tenant-visible; the cache would hold one entry per person per key. It is a larger change to two accepted records than the feature needs | A tenant-visible, inheritable per-person default is wanted *and* `ResourceScope` has been split into a resolution scope and a coverage scope |
| Keep everything in the browser (status quo) | It is the gap: a choice never follows the person, and a shared PC cannot tell two people apart. It is also what must remain for device-class state | Never for person-level choices; the browser stays the home of device state |
| Store preferences as Keycloak user attributes | One Keycloak user serves every tenant it belongs to, so a default branch of one company would be overwritten by another's; every read is an Admin API call; and ADR 0139 refused to mirror profile data there for the same reason | Never for a person who can belong to more than one tenant |
| Add columns to `iam.staff_members` | The row's version is contended by manager edits and by the person's own, the document needs a list and a nested value, and a column per preference is a migration per feature. Fine for three scalars | The field set stays three scalars for good |
| A generic per-user key and value table | An untyped store accepts free text and personal data and is what ADR 0030 rejected for configuration ("typos become silent defaults"); there would be nothing to stop a filter carrying a phone number | Never; closed surfaces are the point |
| Persist live filters per tab on the server | Rewritten on every toggle, includes a free-text search box, and a stale filter reapplied on a new device is as often wrong as right | A tenant asks for filters that follow the person, and the search term is excluded |
| One document per person for all surfaces | Two tabs editing different screens would contend on one version for no reason | A surface count so small the contention cannot occur |
| Tenant-set defaults per job | A different feature (a tenant policy), and it needs a screen and a rule for what a person's own choice does when the default changes | Product asks; it is a `TENANT` key and needs no change here |
| Encrypt only the saved-view label, leave the rest in clear | Two storage shapes and two failure modes for a document that is read once a session; ADR 0029 lists preferences as `PERSONAL` anyway | A document grows large enough that decrypting it costs more than the saving is worth |
| Include theme, notification sound and density now | No evidence they are wanted and no console feature behind them; a surface with no reader is the kind of table ADR 0043 warns about | Someone builds the feature that reads one |

## Consequences

### Positive

- A person's column layout, saved filters, default branch and language follow them to any
  machine, which closes row `0.2d` and unblocks the board's column picker in row `1.1`
  (which keeps courier ETA and source as its residue until the board read carries them).
- ADR 0030, ADR 0025 and `ResourceScope` are untouched, so nothing that resolves a
  setting or checks a capability can be affected by a feature about a column.
- The store holds no personal data beyond the subject id in its key, and the document that
  can hold typed text is protected.
- A shared PC no longer leaks one person's layout to the next, because the mirror is
  keyed by subject and cleared at sign-out.

### Negative

- A second place for a person's choices to live: the language has a person default, a
  device override and a rule between them, which people will not always predict.
- A new table, a surface registry and a validator are code that has to be maintained, and
  each new surface is a release.
- The column and filter vocabularies exist twice, in Java and in TypeScript. The server's
  list is published as an OpenAPI enumeration and the console's registry is typed against
  the generated client, so a drift fails the build, but someone has to keep both honest.
- Nothing an administrator can set applies to a person who has not chosen: the tenant
  default is not built.

### Accepted trade-offs

- A document is decrypted on every read and a write re-encrypts it. Once a session and
  rarely, which is cheap for the protection it buys.
- The last write wins, after one retry, when two devices edit one surface. A preference
  is not worth a merge.
- Saved views are one person's: they cannot be shared with a colleague, copied to another
  tenant or exported, and a reset deletes them.
- Existing browser-held views and column choices do not move by themselves.

## Specification

### Physical model

Additive migration; the number is reserved by the wave that builds it (check every
sibling worktree, as AGENTS.md says).

```text
iam.staff_preferences
  id uuid pk (Ids.newId(); the record reference for the protection AAD)
  tenant_id uuid not null
  principal_subject varchar(255) not null
  surface varchar(32) not null                    -- SHELL, ORDER_BOARD; a closed set in code
  protected_document text not null                -- ADR 0029 PERSONAL, envelope-encrypted, AAD binds tenant, table, column, row
  document_schema smallint not null               -- the surface schema version the document was written under
  version integer not null default 1
  created_at timestamptz not null default now()
  updated_at timestamptz not null default now()
  fk (tenant_id, principal_subject) -> iam.staff_members (tenant_id, principal_subject)   -- uq_staff_member_subject
  unique (tenant_id, principal_subject, surface)
  check surface ~ '^[A-Z][A-Z0-9_]{1,31}$'
  check version >= 1 and document_schema >= 1
  GRANT SELECT, INSERT, UPDATE, DELETE TO horecaos_application      -- DELETE for reset and for anonymisation
```

There is deliberately no queryable column inside the document: nothing queries it, and a
filter on a decrypted field would force the protection off. The surface check is a shape
check and not an enumeration, so a new surface is a code change and not a migration. The
cap is 8 KB per document and 12 surfaces per person, enforced in the service.

### Surface schemas (validated before protecting)

```text
SHELL        { defaultLocationId: uuid | null }
ORDER_BOARD  { columns: { show: [ColumnKey], hide: [ColumnKey] },
               savedViews: [ { id: uuid, label: string(1..40), tab: OrderTabId,
                               filters: { allBranches?, channelCode?, origin?, fulfillmentMode?,
                                          paymentMethodCode?, paymentStatus?, fiscalStatus?,
                                          lateOnly?, problemOnly?, callbackRequested?, mineOnly?,
                                          dateRange?: TODAY | YESTERDAY | LAST_7_DAYS | THIS_MONTH } } ] (max 20) }
ColumnKey    branch | channel | customer | items | payment | courier |
             createdBy | acceptedBy | courierType | discount
             (courierEta and source join by a later document_schema bump: Board columns)
```

Validation refuses an unknown key, an unknown enumeration value, a duplicate key in
`show` and `hide`, a `hide` of an always-shown column, a `show` of a column already shown
by default, a label with control characters or no visible characters, and a label that is
a run of digits long enough to be a phone number. `channelCode` and
`paymentMethodCode` are checked against the shape of the tenant's own codes, not
against a list, since tenants define them. The schema version is bumped when a surface's
shape changes, and the reader upgrades an older document on read.

### Board columns: what each addable column draws from

The picker offers a column only when the board read gives its cell something to draw, so
it cannot offer one that renders empty. `OrderSummaryResponse` (`OperationsOrderController`)
carries `discountMinor`, `createdByActorType` and `createdByActorId`, `acceptedByActorType`
and `acceptedByActorId`, and `courierId`. It carries no courier type, no courier ETA and no
source, and its actor fields are a subject and not a name.

| Column | In v1 | Draws from | Board-read change |
|---|---|---|---|
| `discount` | yes | `discountMinor`, with the order's currency | none |
| `courierType` | yes | `courierId` resolved against the roster the board already fetches for its Курьер column (`courierTypeName`); «—» for an unassigned or partner-carried order, as the Курьер cell | none |
| `createdBy`, `acceptedBy` | yes | the actor fields, resolved to a name | `OrderQueryService` resolves the page's subjects with one `StaffDirectory#namesOf(tenantId, subjects)` batch, as the order detail (row `9.2d`) and `OperatorTodayLeaderboardController` do, and `OrderSummaryResponse` gains `createdByDisplayName` and `acceptedByDisplayName`, null for a non-staff actor; names go only to callers `order.read` already admits |
| `courierEta` | no | the ETA captured for the order's live delivery plan, `fulfillment.api.CourierEtaPort#etaByOrders`, which exists for the kitchen board (row `2.1a`); it answers only for a partner-carried plan and is a snapshot of the winning quote, not a live feed | one batched `CourierEtaPort` call per page, in the manner of `OrderTablesPort` and `ActiveCourierAssignmentsPort`, and `courierEtaAt` on the response |
| `source` | no | `ordering.orders.origin` (`HORECAOS` or `MARKETPLACE`, V0038) with `channelCode`, drawn as an icon from a closed set the console owns | `origin` on the response; no port, the column is `ordering`'s own |

The two deferred columns need a board-read change of their own and a product answer (what
an ETA means for an order a house courier carries, which icons exist), which is why they
wait. Adding either later is a `document_schema` bump, and because a document stores a
change from the default, an older document is unaffected.

### APIs (ADR 0031) and limits (ADR 0033)

```text
GET    /api/v1/operations/tenants/{tenantId}/staff/me/preferences
         -> { surfaces: { SHELL: { document, version } | absent, ORDER_BOARD: { ... } | absent } }
PUT    /api/v1/operations/tenants/{tenantId}/staff/me/preferences/{surface}
         first write: no If-Match; later writes: If-Match: <version>; a second first write answers STALE_VERSION
DELETE /api/v1/operations/tenants/{tenantId}/staff/me/preferences/{surface}      reset to the default
```

All three are `@StaffSelfAuthorized(Capability.STAFF_SELF_MANAGE)`: the row is found by the
tenant in the path and the subject in the token and never by a supplied id. Request
bodies box every optional field (Jackson 3 refuses an omitted primitive and answers
`MALFORMED_BODY`) and are tested with the console's real JSON. An unknown surface answers
`RESOURCE_NOT_FOUND`; a document the schema refuses answers a Problem Details `400` naming
the field. Writes are limited to 60 a minute per principal. The routes are in the
`operations` OpenAPI surface group, and the column and filter enumerations appear in the
generated client as unions the console's registries are typed against. A read is cached
under an ADR 0033 registered accelerator keyed by tenant and subject with a short TTL,
evicted on write; no correctness decision reads the cache.

### Console contract

`OwnPreferences` loads the documents once per signed-in subject after `OwnProfile`, holds
them in signals, and exposes a typed accessor per surface. The mirror key includes a hash
of the subject and is removed at sign-out. The order board reads `ORDER_BOARD.columns` and
its saved views from it and falls back to the code default when the read fails. A first
paint uses the mirror. The shared UI library (ADR 0101) gets a column-picker component the
board hosts; the data table's chooser is unchanged. Every string ships in ru, uz-Latn and
en, and the new section is lazy-loaded, so the initial-bundle budget is unchanged.

### Privacy (ADR 0029) and audit (ADR 0027)

The document is `PERSONAL` and protected; the row holds the tenant and the subject and
no other identifier. No preference is logged, put in a trace attribute, metric label or
event, and a classification test fails for a response component or a log call that carries
a document. No audit fact is written for a preference write (Decision 6); the existing fact
for a profile edit covers `ui_locale`. A preference is never read by any other module.

### Testing

- **Closed vocabulary**: a table of accepted and refused documents, including every refusal
  rule above, a free-text filter field, a label made of digits, an oversized document and
  twenty-one saved views.
- **No PII beyond the subject**: a canary that saves a view whose label looks like a name
  and a phone and asserts the stored text column contains neither, and that no other column
  of the table can hold text.
- **Isolation**: a member of tenant B cannot read or write tenant A's row; a session
  with no member row (a device, a support session) answers `404`; the subject comes from
  the token and never from the body.
- **Versioning**: two writers on one surface, the second answering `STALE_VERSION`; a
  document written under an older schema is upgraded on read.
- **Anonymisation**: anonymising a member deletes their preference rows in the same
  transaction as the member's own update.
- **Precedence**: the console's language and branch rule for each combination of
  device choice and stored default, with a stale `defaultLocationId` for a branch the
  person no longer holds; and the mirror cleared at sign-out so a second sign-in on the
  same browser sees nothing of the first.
- **Drift**: the Java column enumeration and the generated TypeScript union agree, and the
  board's column registry is a `satisfies` over it.
- **Board**: a hidden column is not drawn, an added optional column (`createdBy`) is drawn
  from the name the board read resolved for the page, and a column added by a later
  release is shown to a person whose stored document predates it.
- **Every offered column renders**: the console's column registry maps each `ColumnKey` to
  a cell reading a field of the generated `OrderSummaryResponse` type (a `satisfies` over
  the response type), so a key whose field the response lacks fails the build; for each
  key, a board fixture with the field present draws a non-empty cell, and one with it
  absent (an unassigned order, a non-staff actor) draws «—» and never a raw subject or
  id. The names batch is one `namesOf` call per page, and a name appears only for a
  caller `order.read` admits.

## Rollout and rollback

Ship the table, the validator and the three routes first, with no console caller. Then the
console's `OwnPreferences` and the board's column picker, which are the first readers and
writers; then the saved-view list and the default branch; then «Интерфейс» in «Мой
профиль» and the language rule. The one-time offer to move a device's saved views runs
last and never automatically. Rollback is removing the console's callers: the table is
additive and inert, the browser-held state is untouched and still works, and nothing in
ADR 0030 changed.

## Implementation checklist

- [ ] Owner accepts the record or answers the open inputs.
- [ ] Migration for `iam.staff_preferences` and its `GRANT`s.
- [ ] Surface registry, the two schemas, the validator and the vector tests.
- [ ] Self routes with `@StaffSelfAuthorized`; rate limit; cache registration; OpenAPI
      baselines for all five documents and the generated client, with the enumerations.
- [ ] Anonymisation hook in the retention sweeper's per-member step (`staff_members`
      anonymise); the report-only mode is unchanged.
- [ ] `OwnPreferences`, the mirror and its sign-out clear; the precedence rule in
      `I18n` and `CurrentLocation`.
- [ ] Column picker in the shared library; the board's columns. Of the five the row `1.1`
      note lists, `createdBy` and `acceptedBy` need the names batch (next item),
      `courierType` draws from the roster the board already fetches, and `courierEta`
      and `source` need a board-read extension and are not in v1; `discount` is carried
      and ships.
- [ ] Board read: one `StaffDirectory#namesOf` batch per page, `createdByDisplayName` and
      `acceptedByDisplayName` on `OrderSummaryResponse`, the OpenAPI baselines and the
      renderer registry typed against the regenerated client.
- [ ] A later release, one `document_schema` bump each, not blocking this record:
      `courierEta` (a batched `CourierEtaPort` read and `courierEtaAt`) and `source`
      (`origin` on the response), then add them to `ColumnKey`.
- [ ] Saved views from the server; the one-time per-device offer.
- [ ] «Интерфейс» section in «Мой профиль»; the `q-locked-state` band on «Моя работа»
      replaced by a link; strings in three languages.
- [ ] `0.2d` and `1.1` rows of the gap map and the parity matrix's open question updated
      when this lands (`1.1` stays PARTIAL for courier ETA and source).

## Exit criteria

A person hides «Курьер» and adds «Принял» on the order board, saves a view of today's
late delivery orders, picks a default branch and a language, signs in on a second machine
and finds the same columns, the view, the branch and the language, and a device override
still wins where they set one. A colleague signing in on the same first machine afterwards
sees none of it. The stored row for that person contains a tenant, a subject, a protected
document and versions, and no other identifier; a manager and a tenant administrator have
no route that reads it; anonymising the person removes it. ADR 0030, `ResourceScope` and
the capability model are byte-for-byte what they were.

## References

- ADR 0025 (`covers`, scope), ADR 0027, ADR 0029 (`PERSONAL` includes preferences,
  provisional retention), ADR 0030 (chain, null semantics, why a preference is not a
  setting), ADR 0031, ADR 0033, ADR 0057, ADR 0101 (shared component library), ADR 0139
  (staff record, self strategy, `ui_locale`, no mirroring to Keycloak)
- `platform/docs/operations-gap-map.md` rows `0.2d`, `0.2`, `1.1` and `X.5`
- `platform/docs/frontend-information-architecture.md` row `0.2`
- `platform/docs/operations-spec/orders.md` §2.5 (columns, the picker "persisted per
  user"), §2.4 (filters)
- `platform/docs/delever-parity-matrix.md` «Личный кабинет (Account) и BETA версия - V2»,
  including its open questions
- `ResourceScope`, `ScopeResolution`, `ConfigurationKey`, `V0005`, `V0453`,
  `StaffSelfController`, `StaffSelfAuthorized`, `OrderSummaryResponse`
  (`OperationsOrderController`), `OrderQueryService`, `StaffDirectory`, `CourierEtaPort`,
  `ActiveCourierAssignmentsPort`, `OrderTablesPort`; `V0038` (`ordering.orders.origin`)
- `frontend/operations/src/app/core/auth/own-profile.ts`, `core/i18n/i18n.ts`
  (`hasStoredLocale`), `core/auth/current-location.ts`, `core/auth/brand-choice.ts`,
  `features/orders/order-queue-filter-state.ts`, `shared/ui/table/saved-views-store.ts`,
  `shared/ui/data-table/data-table.ts`, `features/today/my-work-page`,
  `features/staff/my-profile-page`
