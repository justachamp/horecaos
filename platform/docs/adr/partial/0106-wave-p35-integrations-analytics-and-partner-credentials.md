# ADR 0106: Wiring the integrations hub, a tenant-scoped failure surface, partner API credentials, and an analytics category

- Decision status: Proposed
- Implementation status: Partial — wave `P35` wires the five previously-uncalled
  operations endpoints on `OperationsProviderInstallationController` (bindings
  list, binding activate/suspend, capability-reconciliation, provider
  settings) into `IntegrationsPage`; renders the built marketplace liveness
  matrix and adds a tenant-scoped, tenant-checked error taxonomy and inbox
  replay over the existing ADR 0006 failure model; adds
  `ProviderCategory.ANALYTICS`, its `ConnectFieldCatalog` declaration, and a
  public per-brand analytics-config read the storefront can call; writes down
  a versioned GA4 ecommerce event contract (v1: `view_item`, `add_to_cart`,
  `begin_checkout`, `purchase`) and wires `purchase` at the storefront's order
  confirmation; and issues, lists, rotates and revokes `partner.api_clients`
  as a real Keycloak confidential client. Not built: `view_item` and
  `add_to_cart` are documented and have a client-side helper but no call site
  yet (open issue, named below); POS and notification provider-activity
  watermarks (10.8c asks for them "not only marketplace") — this wave adds a
  `secret_last_used_at` watermark on the installation itself (narrower: proof
  the secret still resolves, not proof of a live provider call) rather than
  instrumenting every gateway's success path, which is named as an open
  input; the `RETIRED` installation transition (ADR 0065's own open item,
  explicitly out of scope per this wave's brief); a real
  `horecaos-partner-provisioning` Keycloak service-account credential, which
  is an infrastructure step outside this repository (the adapter code and its
  contract are built and tested against a fake).
- Date proposed: 2026-09-12
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of
  2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0006, ADR 0025, ADR 0026, ADR 0027, ADR 0028, ADR 0031,
  ADR 0040, ADR 0064, ADR 0065, ADR 0079
- Supersedes / Superseded by: —
- Open inputs:
  - Whether `frontend/operations` should generalize `InstallRequest`'s
    non-secret configuration into a structured map instead of the positional
    `external_account_reference` join this wave keeps for `ANALYTICS` too —
    owner: whoever next needs a fourth positional non-secret field, since a
    fourth field is where the join stops being legible even internally.
  - Provisioning a real `horecaos-partner-provisioning` Keycloak
    service-account credential (mirroring `horecaos-device-provisioning`,
    holding `manage-clients` only) in each environment — owner: platform
    operations (Ayubkhon Abbosov), before a tenant can issue a live partner
    credential outside a test realm.
  - Whether "last used" for a provider secret should mean "resolved during a
    reconciliation preflight" (what this wave ships) or "used in a live
    provider call that actually left the platform" — owner: whoever next
    reads `secret_last_used_at` and is surprised it moved without an order
    going out; the narrower definition is recorded in the Specification
    section below.
  - Wiring `view_item` and `add_to_cart` into the storefront's product and
    cart screens — owner: whichever wave next touches those pages; the
    contract and the injector are ready, only the two call sites are missing.

## Context

Five rows of the operations gap map name one shape of gap repeatedly: a real,
tested, capability-gated backend endpoint with nothing in `frontend/operations`
that ever calls it, next to a genuinely missing capability the frontend cannot
paper over. `10.8a`'s bindings list, bind-activate, bind-suspend,
capability-reconciliation and provider-settings endpoints are all real; the
console just never wired them, and its own doc comments already say why
(`OperationsProviderInstallationController`'s Javadoc names the wave-53 move,
`register-merchant-binding-panel.ts`'s own comment names the missing bindings
read as "a real backend gap, not a shortcut this wave papered over"). `10.8c`'s
marketplace liveness matrix is the same story at
`MarketplaceLivenessService` — built, unread. `10.8d` has no backend at all:
`partner.api_clients` exists with its rotation-pair and expiry constraints
from ADR 0040 (V0038), `PartnerAuthenticationService` already validates a
token against it, and nothing issues, lists, rotates or revokes a row —
a tenant's only path to a partner credential today is a database insert,
which is exactly the `base64(login:password)` handover ADR 0040's own doc
comment says this model replaces. `10.8e` is newer still: `ProviderCategory`
has no `ANALYTICS` value, so a GTM container id or a GA4 measurement id has
nowhere to be stored against a tenant, and the storefront's only counter is a
commented-out, hard-coded, platform-wide Yandex Metrika id
(`frontend/storefront/src/index.html:58-68`) that was never tenant-scoped in
the first place.

`X.14`'s `SecretInput` is the pattern every one of `10.8a`/`10.8d` renders a
credential through, and the gap map's own review already resolved the
apparent tension in its spec: `frontend-and-parity-plan.md:67` and
`frontend-information-architecture.md:342` say "reveal-once", unqualified,
which read together with ADR 0028's write-only secret door sounds like a
promise to read a stored secret back. `operations-spec/settings.md:900`
already carries the correct qualifier — "masked, reveal-once **at entry** and
never re-rendered" — and this wave copies that sentence into the two other
documents rather than inventing new wording, because the security model
(nothing this platform stores is ever returned) is Accepted architecture
(ADR 0028, ADR 0065) and this ADR does not reopen it.

## Decision

Wire the five uncalled endpoints into `IntegrationsPage` exactly as they are
published today — no new backend surface for `10.8a` beyond what
`OperationsProviderInstallationController` already exposes. Render the
liveness matrix read-only. Add a **tenant-scoped, tenant-checked** failure
taxonomy and inbox replay that reuse the existing `INTEGRATION_FAILURE_READ`
and `INTEGRATION_FAILURE_RETRY` capabilities at their default `TENANT` scope
(both are already held by `TENANT_OWNER` and `TENANT_ADMIN`) rather than
minting new capability constants — the gap is a missing tenant-facing
surface over an existing capability grant, not a missing grant. Build
`q-secret-input` in `shared/ui/` with masked presence, a reveal-once-at-entry
toggle for the value currently being typed, a copy action over the ADR 0028
**reference** string (never the value — the reference is not a secret, and
copying it is how a support conversation confirms which credential a
`configured` badge refers to without anyone ever seeing the value), a rotate
trigger, and last-rotated/last-used slots.

Add `ProviderCategory.ANALYTICS` and a `ConnectFieldCatalog` declaration for
three non-secret fields per install (a GTM container id, a GA4 measurement
id, a Search Console verification token — explicitly never behind the secret
door: these are public identifiers a browser's view-source already reveals,
and treating one as a secret would teach an operator that the mask means
nothing). Persist them into the `integration.installations.non_sensitive_config`
column that already exists for exactly this purpose (Clopos's
`requireClerkApproval` already uses it) rather than adding new columns.
Publish one small, unauthenticated storefront read,
`GET /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/analytics`, that
resolves the brand's `ACTIVE` `ANALYTICS` binding and returns the three
non-secret fields — the shape a browser needs to inject GTM/gtag.js itself,
and nothing a server should keep private. Telephony reuses the existing
`VoiceProviderCapabilityCatalog` (`HOSTED_PBX`, `ASTERISK_AMI`); this wave
adds their `ConnectFieldCatalog` entries so they render as ordinary cards in
the same hub, closing the "just another card" half of `10.8e` the brief
names.

Write and version the GA4 ecommerce event contract (`docs/analytics/ga4-ecommerce-event-contract-v1.md`):
a named `view_item`/`add_to_cart`/`begin_checkout`/`purchase` set, each event
carrying `contractVersion: 1` so a future field rename ships as `v2` rather
than silently reinterpreting a year of a tenant's own data. Replace
`frontend/storefront/src/index.html`'s dead, hard-coded Yandex Metrika
comment with nothing static at all — analytics injection becomes a runtime
decision, per brand, from the new storefront read, never a build-time
constant. `purchase` is wired at order confirmation this wave; `view_item`
and `add_to_cart` are contract-ready with a helper (`pushEcommerceEvent`) but
have no call site yet, named above as an open input rather than silently
dropped.

Issue `partner.api_clients` as a real Keycloak confidential client,
`client_credentials`-only, mirroring ADR 0079's device-principal shape
exactly: a new `PartnerClientProvisioner` port beside
`DeviceClientProvisioner` in `iam.api`/`iam.infrastructure.keycloak` (create /
regenerate-secret / disable), on its own least-privilege credential,
`horecaos-partner-provisioning`, holding `manage-clients` alone — never the
general `horecaos-provisioning` credential, for the same reason
`KeycloakDeviceClientProvisioner`'s own doc comment already gives. The
Keycloak-minted secret is additionally written through the ADR 0028 secret
manager under a new `SecretCategory.PROVIDER_MARKETPLACE` (tenant-writable,
alongside the other four `PROVIDER_*` categories) so a partner credential
gets the same masked/rotate/last-rotated treatment every other provider
secret gets in the console, even though `PartnerAuthenticationService` itself
never resolves it — Keycloak validates the token on its own, and the
reference exists for operational consistency and support visibility, not
because the application calls `SecretResolver.resolve()` on it. A new
capability, `PARTNER_API_CLIENT_MANAGE`, covers issue/list/rotate/revoke as
one grant — a partner credential is a different blast radius from
`INTEGRATION_INSTALLATION_MANAGE` (it authenticates *inbound* traffic
claiming to be an aggregator, not outbound calls the platform makes), so it
is deliberately not folded into that capability, matching the brief's own
instruction.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Give `ANALYTICS` its own table with three real columns instead of `non_sensitive_config` jsonb | A new table for three optional strings is disproportionate, and `non_sensitive_config` already exists precisely for a provider's own non-secret configuration (Clopos uses it today) | A fourth or fifth analytics field arrives, or a field needs its own index/constraint |
| Extend `InstallRequest` with a structured `Map<String,String>` of non-secret fields instead of reusing the positional `external_account_reference` join | Bigger surface change to a shared, security-adjacent controller, for a wave already touching five other things in the same file; the join already exists and nothing server-side parses it for any category today, so nothing regresses | The next provider needs a fourth non-secret field, at which point positional joining stops being legible even to the person who wrote it |
| Mint new `INTEGRATION_FAILURE_TENANT_READ`/`_RETRY` capabilities for the tenant surface | `INTEGRATION_FAILURE_READ`/`_RETRY` are already granted to `TENANT_OWNER`/`TENANT_ADMIN` at their default `TENANT` scope; a new pair would duplicate a grant that already exists and gives two names to one fact | A role needs failure-read without failure-retry, or a non-owner/admin tenant role needs either |
| Fold `PARTNER_API_CLIENT_MANAGE` into `INTEGRATION_INSTALLATION_MANAGE` | The brief is explicit that a partner credential is a different blast radius (inbound authentication, not outbound configuration); folding them means a role built for "configure my POS" can also mint aggregator credentials | Never, without a product decision reversing the blast-radius argument |
| Instrument every gateway (`PosGateway`, `PaymentGateway`, `DeliveryGateway`, `NotificationGateway`, `SmsGateway`) to stamp `secret_last_used_at` on a real provider call | No shared choke point exists across all five today (each resolves its own secret independently), and adding one touches five files this wave does not otherwise need to open; reconciliation is the one call site already being wired this wave | A tenant reports that "last used" looks stale despite live traffic, at which point the narrower definition needs revisiting per the Open inputs above |
| Build the partner Keycloak adapter as a generic, category-agnostic `MachineClientProvisioner` reused by both devices and partners | `DeviceClientProvisioner` already exists, is tested, and is wired into `DeviceEnrolmentService`; generalizing it now touches a working ADR 0079 flow for a benefit this wave does not need | A third machine-principal kind needs the same shape and the duplication has grown past two |

## Consequences

### Positive

- Five real endpoints get a caller; a tenant admin can finally see bindings
  created in an earlier session, activate one, suspend one, reconcile
  capabilities, and toggle Clopos's clerk-acceptance setting — all previously
  invisible in the console.
- A merchant can see their own aggregator's silence and their own failure
  taxonomy without phoning support, and can replay their own stuck inbound
  message — narrowed by an explicit tenant check this ADR adds to
  `FailureOperationsService`, not by trusting the existing platform-wide
  methods' optional tenant filter.
- A tenant can issue, rotate and revoke its own partner credential without a
  database operator, closing the exact `base64(login:password)` gap ADR 0040
  names.
- Analytics gets a real, tenant-scoped home instead of a hard-coded,
  switched-off, wrong-tenant counter; the event contract is versioned before
  the first tenant depends on its shape.

### Negative

- The positional `external_account_reference` join, kept for `ANALYTICS`
  rather than replaced, is harder to read than a keyed map and gets harder
  with every additional non-secret field — an accepted trade-off, not an
  oversight (see Alternatives).
- `secret_last_used_at` answers "did this secret still resolve at the last
  reconciliation", not "did this secret authenticate a real order in the last
  hour" — a narrower and less reassuring signal than the row's name might
  suggest to an operator who has not read this ADR.
- `view_item` and `add_to_cart` ship as contract-and-helper only; a tenant
  who builds a marketing report against the full GA4 funnel this month will
  see `begin_checkout` and `purchase` volume with no upstream funnel to
  compare it against.
- A partner credential's Keycloak-minted secret is also written through the
  ADR 0028 door under `PROVIDER_MARKETPLACE`, doubling where the value could
  theoretically leak (Keycloak's own store, plus the secrets manager) for a
  reference that today only the console's masked-badge UI reads back — a
  deliberate consistency choice (see Decision) whose cost is a second place
  ADR 0028 rotation policy must cover.

### Accepted trade-offs

This wave does not build the `RETIRED` installation transition (ADR 0065's
own named gap, explicitly assigned to owner-and-platform rather than a
frontend task), does not instrument every provider gateway for a
call-level liveness watermark, and does not provision the real Keycloak
service-account credential a live partner-client issuance needs outside a
test realm — each is named above with an owner rather than silently dropped.

## Specification

### `q-secret-input` (`frontend/operations/src/app/shared/ui/secret-input/`)

Two modes:

- `entry` — a masked `<input>` with a reveal toggle over the value currently
  being composed in the DOM, never a value the server returned (none ever is).
  Used by `connect-provider-panel`, `register-merchant-binding-panel`, and
  `rotate-secret-dialog`, replacing their three bare `type="password"` inputs.
- `configured` — a masked-presence badge plus the ADR 0028 **reference**
  string (copyable), last-rotated and last-used timestamps (both already
  translated strings supplied by the caller, following `q-inline-alert`'s
  convention), and a `rotate` output the caller wires to its own rotate flow.

### Analytics

- `ProviderCategory.ANALYTICS` added to `integration.provider_environments`
  and `integration.installations`' `ck_*_category` CHECK constraints
  (V0250, mirroring V0145's VOICE addition exactly — restated in full, every
  prior value kept).
- Three `provider_environments` rows seeded (`GOOGLE_TAG_MANAGER`,
  `GOOGLE_ANALYTICS_4`, `GOOGLE_SEARCH_CONSOLE`, environment code
  `PRODUCTION`), each `base_url` pointing at the real Google endpoint the
  identifier ultimately concerns — necessary only to satisfy the existing
  approved-environment model, since the platform's own backend never calls
  out to any of them; the actual "call" is the customer's browser loading a
  script the storefront serves.
- `ConnectFieldCatalog` gains `GOOGLE_TAG_MANAGER` (`gtmContainerId`),
  `GOOGLE_ANALYTICS_4` (`ga4MeasurementId`), `GOOGLE_SEARCH_CONSOLE`
  (`searchConsoleVerificationToken`) — all `secret: false` — plus
  `HOSTED_PBX` and `ASTERISK_AMI` under `VOICE` (host/port/username plus a
  `secret: true` password for the AMI adapter; a `secret: true` webhook token
  for the hosted adapter).
- `ProviderInstallationController.install()` persists these fields into
  `non_sensitive_config` by splitting `externalAccountReference` on `/` and
  zipping it against `ConnectFieldCatalog`'s declared non-secret field order
  for the installation's `providerType` — the same join
  `connect-provider-panel.ts` already builds for every category, corrected in
  this wave to keep an empty field's *position* rather than dropping it (a
  blank field silently shifting every later field left is the failure mode
  this correction closes; it was latent for CLICK/PAYME, which normally fill
  both non-secret fields, and would have been actively wrong for analytics,
  where filling only one of three fields is the common case).
- `GET /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/analytics` —
  unauthenticated, `StorefrontAnalyticsConfigController`, mirroring
  `StorefrontCatalogController`'s own unauthenticated posture. Resolves the
  brand's `ACTIVE` `integration.bindings` row whose installation is category
  `ANALYTICS`, and returns the three non-secret fields (each nullable). No
  binding, or no installation: all three fields null, which the storefront
  reads as "inject nothing."

### GA4 ecommerce event contract v1 (`docs/analytics/ga4-ecommerce-event-contract-v1.md`)

Four events, each an object `{ contractVersion: 1, event: <name>, ecommerce: {...} }`
pushed to `window.dataLayer` via a new `pushEcommerceEvent` helper
(`frontend/storefront/src/app/core/analytics/`): `view_item`, `add_to_cart`,
`begin_checkout`, `purchase` — field shapes follow GA4's own Measurement
Protocol ecommerce object (`items[]`, `currency`, `value`), which keeps a
tenant's GA4 property able to render standard ecommerce reports without a
custom schema on Google's side. `purchase` fires once, at the storefront's
order-confirmation screen, keyed off the platform order id
(`transaction_id`) so a retried confirmation render never double-counts.

### Tenant-scoped failure surface

`FailureOperationsService` gains `taxonomyForTenant(UUID tenantId)` (the same
category/status counts `FailureTaxonomyController` computes, `WHERE tenant_id
= :tenantId`) and an overload of `retryInboxMessage` taking an
`expectedTenantId` that is checked against the message's own tenant before
any write — the existing 4-argument method (no tenant check, PLATFORM-only
caller) is untouched. `OperationsIntegrationFailureController`, new, under
`/api/v1/operations/tenants/{tenantId}/integrations/failures`, exposes both
plus the existing tenant-filterable `listInboxFailuresAcrossConsumers` —
capabilities `INTEGRATION_FAILURE_READ` (reads) and `INTEGRATION_FAILURE_RETRY`
(the replay), both at their default `TENANT` scope.

### Partner API clients

`PartnerClientProvisioner` (`iam.api.partnerclients`) — `create`,
`regenerateSecret`, `disable`, `tokenEndpoint` — implemented by
`KeycloakPartnerClientProvisioner`, wired in `KeycloakConfiguration` on the
new `horecaos-partner-provisioning` credential. `PartnerApiClientService`
(new, `partner.application`) orchestrates: issue mints the Keycloak client
*outside* any database transaction, then in one transaction inserts
`partner.api_clients` `ACTIVE` with the Keycloak-generated secret written
through `SecretIngressGateway` under the new `SecretCategory.PROVIDER_MARKETPLACE`;
rotate calls `regenerateSecret` outside the transaction, then updates the
reference/rotated-at/expires-at inside one; revoke calls `disable` outside
the transaction, then sets `status = 'RETIRED'` inside one — matching every
other rotate-by-value flow's ordering in this codebase (external call,
uncertain outcome, resolved before the database commits to it).
`partner.api_clients` gains `keycloak_client_ref varchar(255)` (V0252) —
Keycloak's own internal client id, needed for every later Admin API call
without a search-by-`client_id` round trip on every rotation. Capability
`PARTNER_API_CLIENT_MANAGE`, granted to `TENANT_OWNER` and `TENANT_ADMIN`,
covers all four operations.

### Provider secret last-used

`integration.installations` gains `secret_last_used_at timestamptz` (V0251).
`ProviderCapabilityReconciliationService.reconcile()` stamps it in the same
`UPDATE` it already issues, exactly when the preflight secret-resolve
succeeds — see the Negative consequences above for what this does and does
not prove.

## Rollout and rollback

Additive on every axis: three new migrations touching only CHECK constraints,
new columns, and new seed rows in an existing table; new controllers and
service methods; no existing endpoint's request or response shape changes.
Rollback is deleting the new frontend call sites and leaving the backend
additions inert — no data migration to reverse. The storefront analytics
injection degrades to "inject nothing" for any brand with no `ANALYTICS`
binding, which is every brand today.

## Implementation checklist

- [x] `q-secret-input` built and wired into the three existing bare-input sites
- [x] Five uncalled `10.8a` endpoints wired into `IntegrationsPage`
- [x] Marketplace liveness matrix rendered
- [x] Tenant-scoped error taxonomy and replay, tenant-checked
- [x] `ProviderCategory.ANALYTICS`, connect fields, storefront read
- [x] GA4 event contract v1 written down; `purchase` wired
- [ ] `view_item`/`add_to_cart` wired (open input)
- [x] `partner.api_clients` issue/list/rotate/revoke over a real Keycloak client
- [ ] Real `horecaos-partner-provisioning` credential provisioned per environment (infra, open input)
- [x] `frontend-and-parity-plan.md:67` and `frontend-information-architecture.md:342` corrected to "reveal-once at entry"

## Exit criteria

A tenant admin can, from the console alone: see an installation's bindings
from an earlier session and bring one live; see their aggregator's liveness
and replay their own stuck message; issue, rotate and revoke a partner API
credential; and install a GTM/GA4/Search Console/telephony provider whose
non-secret identifiers a browser can read back for injection. A storefront
order confirmation emits a versioned `purchase` event into the tenant's own
`dataLayer`.

## References

- [ADR 0006](../built/0006-message-retry-dead-letter-and-replay-operations.md)
- [ADR 0025](../built/0025-fine-grained-authorization-and-capability-model.md)
- [ADR 0026](../built/0026-provider-installations-bindings-and-secret-references.md)
- [ADR 0027](../built/0027-audit-evidence-and-approval-model.md)
- [ADR 0028](../partial/0028-secrets-management-and-credential-lifecycle.md)
- [ADR 0031](../built/0031-http-api-conventions.md)
- [ADR 0040](../partial/0040-marketplace-channel-and-partner-api.md)
- [ADR 0064](../partial/0064-voice-channels-and-the-operator-presence-model.md)
- [ADR 0065](../partial/0065-tenant-self-service-integrations-and-the-write-only-secret-door.md)
- [ADR 0079](../partial/0079-kitchen-display-device-principal-and-enrolment.md)
- `docs/operations-gap-map.md` rows `10.8a`, `10.8c`, `10.8d`, `10.8e`, `X.14`
