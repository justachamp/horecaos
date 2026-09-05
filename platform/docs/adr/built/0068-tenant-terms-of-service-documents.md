# ADR 0068: Tenant terms of service — versioned, tenant-authored, with a platform default

- Decision status: Accepted
- Implementation status: Built — a tenant owner authors terms per locale from Settings
  10.12 (`OperationsTermsController`, `frontend/operations`'s `TermsPage`) and publishes
  a version; `legal.terms_versions`/`legal.terms_version_contents` (V0160) hold it,
  append-only, insert/select-only at the database grant; the storefront serves it
  through `StorefrontTermsController` (`TermsAcceptanceService.effective`), falling back
  locale-by-locale to the bundled, brand-name-interpolated `PlatformDefaultTerms` for any
  language the tenant has not written; and `POST .../terms/accept` records the customer's
  acceptance through a new `customers.api.ConsentRecorder` port onto the existing ADR 0015
  `customer.consent_decisions` store, labelled by version and locale together
  (`"v3:ru"`/`"default-v1:en"`) rather than by version alone, so a translation is never
  read as interchangeable with the words it stands for. `AuthCodeComponent` checks
  acceptance status after every sign-in and, when it disagrees with what is now in force,
  routes the customer through `/terms` with an explicit "I agree" action before
  `/locations`. `TermsAcceptanceServiceTests.publishingANewVersionDoesNotRetroactivelyChangeWhatWasAlreadyAccepted`
  is the record's own required proof, advancing a controlled clock across a republish.
  The legacy `TERMS_{EN,RU,UZ}_CONTENT` files and the JizBiz-naming `terms.section*`
  i18n keys they backed are deleted from the storefront. Not built, and out of scope by
  the owner's own decision: no endpoint anywhere refuses an order for a stale acceptance
  (see Alternatives, "Enforcing re-acceptance server-side").
- Date proposed: 2026-09-05
- Date decided: 2026-09-05
- Deciders: Ayubkhon Abbosov (platform owner)
- Depends on: ADR 0015 (consent), ADR 0025 (capabilities), ADR 0027 (audit), ADR 0031 (HTTP conventions), ADR 0057 (OpenAPI surface groups)
- Supersedes / Superseded by: — / —
- Open inputs: none — the owner's 2026-09-05 decision below settles the model; a tenant's actual legal text is theirs to author and is not an input this record waits on

## Context

The storefront's terms-of-service copy (`frontend/storefront/src/app/pages/terms/content/terms-{en,ru,uz}.content.ts`)
is hardcoded, identical for every tenant, and names the legacy brand this
codebase was imported from ("JizBiz") throughout — the seller's identity, its
mobile app names, its support email, its delivery pricing. A prior wave made
brand *identity* configurable (`AppConfig.brand` from `config.json`,
`applyBrand.ts`) and deliberately left legal text alone, because interpolating
a brand's display name into somebody else's legal terms is not the same
problem as swapping a logo — a public-offer agreement makes specific factual
and contractual claims (a seller's identity, a returns policy, a delivery
price) that a find-and-replace cannot make true for a different business.

Every tenant onboarded onto the platform today therefore ships another
company's legal text, under another company's name in places the interpolation
would have missed, to their own customers. No table, service, or endpoint in
the platform lets a tenant write their own. No table records that a customer
ever agreed to anything at all before ordering — the storefront's sign-in
screen links to `/terms`, but nothing behind that link is evidence of
acceptance, and the platform cannot show what a specific customer read on a
specific date if the words later change.

`customers` already owns a general consent primitive (V0017,
`customer.consent_decisions`): an append-only, insert-only record of a
purpose, a policy version string, a decision, and when it was decided,
read through `ConsentDirectory` and written through `ConsentService`. It was
built for exactly this class of fact and was sitting unused for the one
purpose every tenant on the platform actually needs it for.

## Decision

**A tenant authors and publishes its own terms of service, per locale,
versioned by insertion never by edit; a customer's acceptance is recorded
against the exact version and language they were shown; and a tenant who
writes nothing serves a lawful, brand-neutral platform default with their own
name interpolated in, never the legacy brand's text.**

- **The model is `legal.terms_versions` + `legal.terms_version_contents`**, one
  new module (`legal`), scoped to a brand (`tenant_id`, `brand_id`): a tenant
  may run several brands and a version is a release of one brand's document,
  not the tenant's as a whole. Locale is `ru` / `uz-Latn` / `en` — the
  platform's existing three-locale vocabulary (`notifications.domain.MessageLocale`,
  `ordering`'s `REQUIRED_LOCALES`, `marketing`'s `SUPPORTED_LOCALES` — each
  module already declares this trio locally rather than sharing one type, and
  `legal` follows the same convention).
- **Publishing inserts the next version; nothing ever updates or deletes a
  prior one.** The database grant on both tables is `SELECT, INSERT` only,
  the same append-only posture V0017 gives `consent_decisions` and V0007 gives
  `audit_events`. A customer accepted specific words at a specific time, and
  rewriting them under an acceptance already on record would make that
  acceptance evidence of nothing — the same argument ADR 0027's
  `ApprovalPolicyService.author` already makes for a threshold.
- **A version may cover fewer than all three languages.** Authoring is not
  gated on translation completeness the way `NotificationTemplateService`
  gates activation on all three; a tenant that writes Uzbek and Russian first
  and English later publishes twice, not never.
- **Acceptance reuses ADR 0015's consent store instead of a new table.**
  `customer.consent_decisions` already is an append-only purpose/policy-version/
  decision/timestamp record with the same non-rewritable guarantee an
  acceptance needs. `legal` records a `TERMS_OF_SERVICE` purpose through a new,
  narrow `customers.api.ConsentRecorder` port (`recordGrant`) added alongside
  the existing read-only `ConsentDirectory`, rather than building a
  module-local acceptance table that duplicates a guarantee already proven.
- **The accepted label carries locale, not only version.** `"v3:ru"`, not
  `"v3"` — because a translation can say something different from the words
  it stands for, and a customer who read the Uzbek text agreed to the Uzbek
  text, not to a Russian version that happens to share a number. Switching
  app language after accepting asks again. This is the conservative reading
  of "which specific words did this customer see", and it is a judgement call
  this record makes explicitly rather than leaving implicit.
- **A locale the tenant has not authored falls back to the platform default
  for that same language — never to a different language the tenant did
  write.** Showing an English-reading customer the tenant's Uzbek text because
  that is all that exists is unreadable to them; the platform's own
  brand-neutral English text, naming their brand, is not.
- **The platform default is generic, brand-neutral, and says so.** It is
  bundled in `legal.domain.PlatformDefaultTerms`, one text per locale, with
  `{{brandName}}` interpolated from the storefront's own `AppConfig.brand.displayName`
  — no lookup added to `tenancy.api` for a single display string the caller
  already has. It closes with its own disclosure: *"These Terms are a general
  default provided by the platform. {{brandName}} may replace them with its
  own terms at any time."* **It is not legal advice and claims no specific
  jurisdiction's law** — it is generic marketplace wording a tenant's own
  lawyer is expected to review, the same posture the owner's decision below
  states in plain language. The default itself is versioned
  (`PlatformDefaultTerms.VERSION`) so that a future wording change does not
  silently reinterpret what an already-recorded default acceptance meant.
- **A new version does not require re-acceptance to be enforced by the
  platform, but the storefront asks anyway.** `TermsAcceptanceService.status`
  compares a customer's last accepted label against what is currently in
  force and answers false the moment they differ — the day after a
  republish, a customer who accepted the old version reads as not having
  accepted the new one. Nothing server-side blocks an order on this; the
  storefront checks status after each sign-in (not on every cold start — see
  the wave's own report for why) and, when it disagrees, shows the terms
  screen with an explicit "I agree" action before continuing, the same
  screen a first-time customer sees.
- **Authoring is `tenant-owner`-only.** `Capability.TERMS_MANAGE` and
  `Capability.TERMS_READ` are both held by `tenant-owner` alone among the
  tenant bundles, the exact argument ADR 0038's `LEGAL_ENTITY_MANAGE` already
  makes: the words a customer is asked to accept before ordering are a legal
  decision for the tenant's principal, not an operational one an
  administrator or branch manager makes on the owner's behalf.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A new module-local acceptance table (`legal.terms_acceptances`) | Duplicates the append-only, non-rewritable guarantee `customer.consent_decisions` already proves and is already tested for; two evidence stores for "did this customer agree to something" is two places to search and two places to get the guarantee wrong | Never — if consent's shape ever cannot express an acceptance fact, that is itself an ADR 0015 gap to fix, not a reason to fork |
| Interpolating the brand's name into the existing hardcoded content, keeping it otherwise as-is | The hardcoded text makes factual claims specific to one legacy business — its delivery price, its returns window, its support email, its named mobile apps and Telegram bot — that a name swap cannot make true for a different tenant; this was already tried by a prior wave for brand identity and deliberately not extended to legal text for exactly this reason | Never; a tenant's own words are the correct answer, which is this record's whole decision |
| Requiring every version to cover all three locales before it may publish, matching `NotificationTemplateService` | A terms document is authored by one person, often not simultaneously in three languages; gating publication on translation completeness means a tenant with only Uzbek and Russian ready cannot ship either until English exists too, which is worse for their customers than publishing what is ready | If a jurisdiction is later found to legally require all three before any may take effect |
| Falling back to another locale the tenant *did* author, rather than to the platform default, when the requested locale is missing | Showing an English-reading customer the tenant's Uzbek words is not more informative than showing them the platform's own English words — it is unreadable to them either way, and the platform default at least reads correctly and discloses what it is | Never as the default; a tenant could reasonably prefer this and nothing here prevents them from simply authoring the third locale instead |
| Enforcing re-acceptance server-side — refusing to place an order until the customer accepts the current version | No endpoint in the platform currently blocks ordering on an ADR 0015 consent decision (marketing and notifications only *read* consent to decide whether to send), and inventing the first such enforcement point as a side effect of this record would be a larger, separate decision about where in the order path that check belongs | If a tenant or jurisdiction is found to require it; the acceptance-status endpoint already exists for that check to be added against |
| Scoping authoring at the tenant level instead of per brand | A tenant may run several brands with different legal identities and storefront deployments (`AppConfig.brandId` is one brand per deployment); a shared terms document across brands is something a tenant can still choose by publishing the same text to each, but a tenant forced to share one has no way out — the same argument V0094 already made for the FAQ | Never |

## Consequences

### Positive

- Every tenant can serve their own words instead of a legacy brand's, closing
  a real legal and trust problem for every business onboarded so far.
- Reuses a proven, tested, append-only evidence mechanism (ADR 0015) instead
  of adding a second one, keeping "did a customer agree to something" a
  one-place question across the platform.
- A tenant who does nothing still serves lawful, readable, honestly-labelled
  text rather than a broken build or somebody else's brand.

### Negative

- A tenant with no lawyer and no intention of writing their own terms is
  permanently served the platform default — this record deliberately does not
  chase them to write one, and a stale, unreviewed default is a real
  possibility for a tenant that never opens the authoring screen.
- Locale-scoped acceptance labels mean a customer who reads the app in two
  languages across two sessions accepts twice for what a tenant may consider
  "the same terms" — more re-prompts than a version-only scheme, accepted
  here as the more conservative reading of what a translation actually
  proves.
- No endpoint in the platform enforces re-acceptance before an order; a
  determined customer can keep ordering having only ever agreed to a stale
  version, because this record does not add the first consent-gated
  checkout in the platform.

### Accepted trade-offs

- The platform default's wording is authored once, by this wave, and is not
  reviewed by counsel in any jurisdiction — it is a scaffold a tenant's own
  lawyer is expected to replace, said so in the text itself, and this record
  does not claim otherwise.
- `brandName` for the platform default arrives as a caller-supplied field on
  the storefront's read and accept requests rather than being resolved
  authoritatively from `tenancy`, to avoid a new cross-module dependency for
  a single display string the storefront already holds
  (`AppConfig.brand.displayName`). A caller passing an unexpected value only
  ever mis-renders its own read; nothing sensitive or persisted depends on it.

## Specification

### Physical model

```text
legal.terms_versions
  id, tenant_id, brand_id, version (int, unique per tenant+brand, >0)
  published_by, published_at, created_at
  FK (tenant_id, brand_id) -> tenant.brands (tenant_id, id)
  UNIQUE (tenant_id, brand_id, version)
  UNIQUE (id, tenant_id)                 -- lets contents FK carry tenant_id too

legal.terms_version_contents
  id, tenant_id, terms_version_id, locale ('ru'|'uz-Latn'|'en'), body (text)
  FK (terms_version_id, tenant_id) -> legal.terms_versions (id, tenant_id)
  UNIQUE (terms_version_id, locale)
  CHECK body is not blank
```

`horecaos_application` holds `SELECT, INSERT` on both tables and nothing else
(V0160).

### APIs

Operations (ADR 0057 `operations` surface), `tenant-owner` only:

```
GET  /api/v1/operations/tenants/{tenantId}/brands/{brandId}/terms-documents                (TERMS_READ, BRAND scope)
GET  /api/v1/operations/tenants/{tenantId}/brands/{brandId}/terms-documents/current         (TERMS_READ, BRAND scope)
GET  /api/v1/operations/tenants/{tenantId}/brands/{brandId}/terms-documents/{version}        (TERMS_READ, BRAND scope)
POST /api/v1/operations/tenants/{tenantId}/brands/{brandId}/terms-documents                  (TERMS_MANAGE, BRAND scope, mutating)
```

`tenant-owner` is a `TENANT`-scoped bundle (ADR 0025 scopes cover downwards,
so this still passes the `BRAND`-scoped check); the operations frontend
therefore resolves this screen's tenant through `CurrentTenant` and lets the
operator pick a brand from the existing `OperationsBrandController.list`
reader, rather than through `CurrentBrand`, which derives its answer from a
`BRAND`-or-`LOCATION`-scoped grant an owner-only principal does not hold —
exactly the failure `CurrentTenant`'s own doc names Finance and Staff as
needing it for.

Storefront (ADR 0057 `storefront` surface):

```
GET  /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/terms?locale=&brandName=              unauthenticated
POST /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/terms/accept                          @CustomerOwned, @Idempotent
GET  /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/terms/acceptance-status?locale=&brandName=  @CustomerOwned
```

The read is on `SecurityConfiguration`'s explicit `permitAll` GET list beside
the FAQ and the menu, for the reason they are: somebody deciding whether to
sign up reads this before they have an account.

### Testing

- `TermsPublishingServiceTests` — versions are append-only, per-brand
  sequencing, validation (empty/blank/unknown-locale/oversized bodies
  refused).
- `TermsAcceptanceServiceTests`, including
  `publishingANewVersionDoesNotRetroactivelyChangeWhatWasAlreadyAccepted`:
  advances a controlled clock, publishes v1, accepts it, advances the clock
  again, publishes v2 with materially different text, and asserts the
  original acceptance's label and timestamp are untouched while the current
  version and accepted-status both correctly move.
- `OperationsTermsControllerEndpointTests` /
  `StorefrontTermsControllerEndpointTests` — HTTP-level capability
  enforcement (owner may publish and read, administrator may do neither) and
  the unauthenticated read's real filter-chain wiring.

## Rollout and rollback

Additive: one new schema, one new module, two new controllers, no change to
an existing table or endpoint. Rollback is deleting the module and migration
before either ships to a real tenant; once a tenant has published a version
or a customer has accepted one, the append-only tables are exactly the
records that must not be rolled back over.

## Implementation checklist

- [x] `legal` module: domain, `JdbcTermsStore`, `TermsPublishingService`,
      `TermsAcceptanceService`
- [x] V0160: `legal.terms_versions`, `legal.terms_version_contents`, grants
- [x] `Capability.TERMS_READ` / `TERMS_MANAGE`, granted to `tenant-owner`
- [x] `customers.api.ConsentRecorder`, implemented by `ConsentService`
- [x] `OperationsTermsController`, `StorefrontTermsController`
- [x] `SecurityConfiguration` permit-list entry for the storefront read
- [x] Platform default text, `ru`/`uz-Latn`/`en`, brand-name interpolated
- [x] Operations authoring screen (Settings 10.12): brand picker, three-locale
      editor, publish, publish history with a read-only per-version preview
- [x] Storefront: reads the served document; records acceptance after
      sign-in; re-prompts on a version mismatch
- [x] Legacy-brand hardcoded terms content removed from the storefront
- [x] OpenAPI baselines regenerated (all five documents)

## Exit criteria

- A tenant with no published terms sees, on the storefront, the platform
  default naming their own brand — never the legacy brand.
- A tenant can write and publish terms per locale from Settings and see the
  storefront serve exactly that text.
- A customer's acceptance record names the version and locale they read, and
  a later publish does not change what that record says, proven by
  `TermsAcceptanceServiceTests`.
- `grep -ri jizbiz frontend/storefront/src` finds nothing.

## References

- `frontend/storefront/src/app/pages/terms/` (the surface this replaces)
- `frontend/storefront/src/app/core/config/app-config.ts`, `apply-brand.ts` (the brand-identity precedent this follows)
- ADR 0015 (consent), ADR 0025 (capabilities), ADR 0027 (audit), ADR 0038 (legal entities — the `tenant-owner`-only precedent), ADR 0057 (OpenAPI surface groups)
