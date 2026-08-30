# ADR 0015: Customer accounts, cross-brand identity, and consent

- Decision status: Accepted
- Implementation status: Partial — V0017 carries `customer.customer_accounts`,
  `principal_links`, `brand_profiles`, `contact_points`, `addresses` and
  `consent_decisions` with composite tenant/brand constraints; the `customers`
  module resolves a Keycloak issuer/subject to an account under a tenant identity
  policy (`ConfiguredCustomerPolicyLookup`, default `TENANT_SHARED`), follows
  merge redirects, stores contact points and addresses under ADR 0029 envelope
  encryption with a normalized-hash lookup (`FieldProtection` in
  `CustomerProfileService`), and records consent.
  **A customer now has a surface of their own.** `StorefrontCustomerController`
  serves this record's `me` routes at
  `/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/me`: the account and
  its display name, language and timezone, and list/add/replace/remove of the
  caller's own addresses. Every route is `@CustomerOwned` with the account
  resolved from the caller's token and never from a path — which also closes the
  gap that no endpoint would tell a customer their own account id, so the
  account-keyed surfaces elsewhere were unreachable from the storefront. The
  reveal records `CUSTOMER_SELF_SERVICE` as its ADR 0027 purpose rather than
  borrowing a dispatch one; removal archives rather than deletes, because V0017
  grants the application role no `DELETE` on `customer.addresses` and a cart or
  order holds its own copy (V0056); and the response reports both the tenant's
  current identity mode and the scope this account is actually partitioned at,
  which disagree after a governed mode change. `preferred_locale` had no writer
  at all before this, so every ADR 0020 message went out in the default
  language. `StorefrontCustomerSurfaceTests` covers it end to end and
  `CustomerIdentityTests` covers the statements underneath.
  **Only since V0060 is that
  identity policy the one the operator actually chose.** Until then
  `ConfiguredCustomerPolicyLookup` read the denormalised
  `tenant.tenants.customer_identity_policy` column that V0017 added and that no
  code in the tree ever wrote, so it answered `TENANT_SHARED` for every tenant:
  a tenant that configured `BRAND_ISOLATED` through the control plane was
  silently given shared partitioning, with one person's profile, addresses and
  order history visible across brands meant to be separate businesses. The
  versioned `tenant.customer_identity_policies` table is now the single source of
  truth and the only thing read; V0060 backfills the denormalised column, has a
  trigger mirror it, and refuses the deployment if honouring a tenant's real mode
  would re-partition customer accounts that already exist. **V0072 drops that
  mirror**, its trigger and `tenant.mirror_customer_identity_mode()`. The trigger
  gated on `superseded_at IS NULL` alone — a third copy of the predicate V0063
  reduced to one, inside the trigger V0063 cited as its precedent — so the column
  held the newest policy row's mode rather than the governing one, and was wrong
  for the whole of any scheduled cutover window. No predicate repairs it: a
  trigger fires on a write and the governing row changes when the clock passes
  `effective_from`, with nothing writing. `tenant.current_customer_identity_policy`
  (V0063) is the only remaining answer, and it takes the instant as a parameter.
  Unfinished: `customer.customer_accounts (tenant_id, identity_policy_version)`
  still has no foreign key to `tenant.customer_identity_policies (tenant_id,
  version)`, whose `uq_customer_identity_policy_version` is a unique constraint on
  exactly those columns — which is why the ordinal bug could stamp a version a
  tenant never had and the database accepted it. Adding it is blocked only by test
  fixtures in `reporting`, `payments`, `fiscal`, `loyalty` and `ordering` that
  stamp `identity_policy_version = 1` on tenants for which they insert no policy
  row at all. **A customer principal now
  exists.** V0055 adds `customer.verification_challenges`, and
  `StorefrontCustomerIdentityController` issues a one-time code, spends an
  attempt against it and redeems a single-use grant into an account at
  `/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/identity`
  (`CustomerVerificationService`, `VerificationChallengeIssuer`,
  `JdbcVerificationChallengeStore`, `VerificationChallengeSweeper`); the code is
  held only as a keyed hash and the destination only under envelope encryption.
  `PrincipalCustomer` resolves that account from the caller's own token, and
  `@CustomerOwned` is what authorises the storefront cart, checkout, order and
  payment-session routes in `ordering` and `payments` — those paths no longer
  declare a staff capability. `CustomerVerificationTests`,
  `VerificationChallengeSqlTests` and `CustomerIdentityTests` cover it.
  **No code can actually be delivered yet**: nothing in the committed tree
  implements `customers.spi.VerificationCodeTransport`, deliberately rather
  than by oversight, so `CustomerVerificationService` answers that verification
  is unavailable and `VerificationTransportGuard` refuses to start any
  non-local profile until an SMS adapter exists. Also not built:
  `CustomerController`
  remains entirely staff-scoped (`customer.manage`, `customer.pii.reveal`,
  `customer.read`) and is unchanged by the customer surface above; there is no
  customer self-service consent API — a decision needs a policy version and its
  evidence, and a storefront toggle that manufactured one would not be a legal
  basis; no `PATCH` of a contact point and no value returned for one, so a
  customer can see that a verified phone exists and not what it is; no device
  table; no guest claim or merge operation (only the
  read-side redirect); no PII-free outbox event from this module; and no legacy
  shadow resolution or rollout tooling.
- Date proposed: 2026-08-19
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture), legal
- Depends on: ADR 0002, ADR 0003, ADR 0005, ADR 0029
- Supersedes / Superseded by: — / [ADR 0051](../built/0051-customer-session-authentication.md)
  decides how a customer authenticates, which this record left to Keycloak. Its
  `(issuer, subject)` resolution, its identity-policy partitioning and its consent model
  are unchanged and are what ADR 0051 resolves through.
- Open inputs: Geocoder/map provider selection (product); disposition of invitations, favorites, search history, ratings, incidents, and blacklists (product, legal)

## Context

A platform user may belong to several tenants. Within a tenant, a control-plane
setting decides whether customer identity is shared across brands or isolated by
brand. The current model does not yet define how authenticated principals,
guests, contact points, addresses, devices, consent, and brand-specific customer
profiles relate. Getting this wrong would leak PII across tenants or brands and
would make later identity-policy changes unsafe.

## Decision

The customer module owns a tenant-scoped `CustomerAccount` as the durable
commercial identity. Authentication remains in Keycloak; Qoida links a verified
Keycloak subject to an account and never uses a JWT alone as a customer record.
A brand profile holds brand-specific preferences and history projections.

Identity resolution obeys the tenant's versioned `CustomerIdentityPolicy`:

- `TENANT_SHARED`: one account can have profiles for several brands.
- `BRAND_ISOLATED`: resolution requires a brand and cannot return an account
  first created for another brand.

Changing this policy is a governed migration, not a control-plane toggle that
rewrites identities immediately.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Use the Keycloak subject as the customer record | Guests have no subject, commercial history cannot live in an identity provider, and per-tenant partitioning becomes impossible. It would also push customer PII into Keycloak | Never |
| One global customer identity across all tenants | Cross-tenant PII exposure and a direct contradiction of the isolation baseline. Two tenants are two unrelated businesses | Never |
| Automatically merge accounts by matching phone number or email | The single most dangerous option available. Recycled numbers, shared family phones, and stale POS data would hand one person another person's order history, addresses, and benefits. Merging requires proof of the authenticated principal | Never automatically. Operator-reviewed merges with evidence remain supported |
| Always isolate customers per brand | Many tenants run several brands and want one account across them. Hard isolation would block a real product requirement | Never; it stays a per-tenant policy |
| Always share customers across brands | Some tenants operate brands as separate businesses with separate legal bases for consent | Never |
| Store contact details and addresses in plaintext with database grants | Grants do not protect backups, replicas, exports, or logs. Envelope encryption under ADR 0029 does | Never |
| Allow the identity policy to be flipped in the control plane | Flipping it after customer data exists silently merges or splits real people. It is a governed migration with dry runs and approval | Never |

## Aggregate and ownership model

```text
Tenant
  CustomerAccount
    PrincipalLink
    ContactPoint
    Address
    ConsentDecision
    CustomerDevice
    BrandProfile (one per participating brand)
```

`CustomerAccount` is the technical anchor in both modes. In isolated mode its
`identity_partition_brand_id` is mandatory; in shared mode it is null. This
keeps every row tenant-owned while giving the database a field that can enforce
the selected partition.

## Physical model

### `customer.customer_accounts`

```text
id, tenant_id, identity_partition_brand_id null
status, display_name, preferred_locale, preferred_timezone
identity_policy_version, merged_into_account_id null
version, created_at, updated_at, anonymized_at null
unique(tenant_id, id)
```

### `customer.principal_links`

```text
id, tenant_id, identity_partition_brand_id null
customer_account_id, issuer, subject
status, linked_at, unlinked_at null
```

Use two active partial unique indexes: `(issuer, subject, tenant_id)` where the
partition brand is null, and `(issuer, subject, tenant_id,
identity_partition_brand_id)` where it is not null. One Keycloak subject may
therefore link independently in each tenant and, in `BRAND_ISOLATED` mode, each
brand, but cannot resolve to two active accounts inside the same partition.

### `customer.brand_profiles`

```text
id, tenant_id, brand_id, customer_account_id
status, loyalty_reference null, marketing_profile_version
first_order_at null, last_order_at null, version, created_at, updated_at
unique(tenant_id, brand_id, customer_account_id)
```

### Contact, address, consent, and device tables

```text
customer.contact_points
  id, tenant_id, customer_account_id, type, normalized_hash
  encrypted_value, verification_status, verified_at, is_primary, timestamps

customer.addresses
  id, tenant_id, customer_account_id, label, encrypted_fields
  latitude/longitude null, delivery_instructions_encrypted null
  status, version, timestamps

customer.consent_decisions
  id, tenant_id, customer_account_id, brand_id null
  purpose, channel null, decision, policy_version, source
  evidence_reference null, decided_at, recorded_at

customer.devices
  id, tenant_id, customer_account_id, device_fingerprint_hash
  push_token_encrypted, platform, status, last_seen_at, timestamps
```

Consent is append-only. Current consent is a query/projection over the latest
valid decision for purpose, scope, and channel.

## What is built so far

The account, principal link, brand profile, contact point, address, and consent
tables; identity resolution under both policies; PII protection through ADR 0029;
and append-only consent with a current-decision query.

**Resolution is on `(issuer, subject)` and nothing else.** `CustomerIdentityTests`
asserts that two accounts sharing a phone number stay two accounts: households
share numbers and recycled numbers change owner, so matching on contact would
merge two real people and hand one of them the other's order history. The lookup
returns every match and lets a human decide.

**Two partial unique indexes, not one.** A null identity partition does not
compare equal to itself in a unique index, so a single index would give
`TENANT_SHARED` tenants no uniqueness at all and let one subject silently acquire
several accounts. The split is asserted directly.

**A concurrent first sign-in resolves rather than fails.** Two simultaneous
sign-ins race to insert the link; the index rejects the loser, which then re-reads
and uses the winner's account. Without that the user would simply see an error on
their first ever sign-in.

**Merge redirects are followed with a bound.** A cyclic or overlong redirect
raises rather than looping, because bad merge data must surface instead of hanging
a customer's sign-in. The source row is never deleted: immutable order snapshots
point at it.

**Personal values are encrypted per row and looked up by a separate keyed hash.**
A ciphertext copied to another row fails to decrypt rather than revealing the
wrong person's number — asserted by actually copying one. Deterministic encryption
was rejected because over a domain as small as Uzbek mobile numbers it would let
anyone with read access confirm whether a given number belongs to a customer.
Coordinates stay in clear: a courier cannot be routed to a ciphertext, and a
coordinate identifies a building rather than a person.

**Consent is append-only at the grant level**, not by convention. The application
role holds `SELECT` and `INSERT` on `customer.consent_decisions` and nothing else,
asserted against `information_schema`. Absence of a decision is not consent, so
`hasConsent` returns false rather than defaulting to true — "we never asked" and
"they said yes" must not collapse into a marketing message nobody agreed to.

**Revealing decrypted data is its own capability** (`customer.pii.reveal`) and
requires a stated purpose. Seeing that a customer exists and reading their phone
number are different levels of access.

**The address document has a stated shape.** `AddressFields` carries подъезд
(entrance), этаж (floor), квартира and ориентир (landmark) as structured fields,
all inside `encrypted_fields`. Those are what actually locate a door in this
market — a courier standing in a Soviet-era block cannot find a flat from a
street line, and for a large share of addresses the landmark is the only thing
that locates the building — and they stay encrypted because they say where one
identified person lives. This ADR previously left the shape unspecified.

**A missing coordinate now says why.** V0021 adds
`addresses.coordinate_source`, in clear because it is a workflow state rather
than personal data. `NOT_GEOCODED` is retryable; `LANDMARK_ONLY` is a complete
address that dispatch reaches by calling and must never be re-queued;
`GEOCODER`, `CUSTOMER_PIN` and `OPERATOR_PIN` each require a point;
`LEGACY_UNSOURCED` is written only by the migration and refused by the service,
so no new row can claim an unknown origin. A check constraint keeps source and
coordinates in agreement in both directions. Without the column, a geocoding
backfill selecting on a null coordinate either re-queries every landmark address
forever or stops retrying the ones a provider outage left empty. The original
range check — which passed on a latitude with no longitude, because the AND
evaluated to NULL — is replaced by separate pair-completeness and range rules.

Not yet built: the guest and account merge workflow, devices and push tokens,
contact verification, the identity-policy change migration, and geocoding itself
(the provider remains an open input; only the schema that records its result is
in place). The merge workflow needs an order aggregate to merge histories
between.

## Address and geocoding boundary

An address keeps the customer's protected submitted text separately from an
optional normalized/geocoded result. A provider-neutral `GeocodeAddress` /
`ReverseGeocodePoint` port returns point, normalized components, provider
reference, confidence, precision, and calculation/version time. Map SDK/DTO and
raw response stay in the integration adapter; a provider cannot overwrite the
customer's address silently.

Delivery eligibility is evaluated by fulfillment against approved PostGIS
zones using the selected location and address point. Low-confidence, conflicting,
or out-of-zone results require correction/manual confirmation. Product must
approve the map/geocoder provider, locality/address format, coordinate source
precedence, consent/retention, and what happens when the provider is unavailable.
Legacy coordinates and text are migrated only after range, pair completeness,
coordinate order/SRID, and source semantics are proven.

## Identity resolution

Resolution inputs are `issuer`, `subject`, tenant, brand, and identity-policy
version. The application service:

1. Authorizes storefront access to the requested tenant and brand.
2. Loads the tenant identity policy from PostgreSQL.
3. Locates a principal link in the correct tenant/brand partition.
4. Creates an account and brand profile transactionally when permitted.
5. Writes an outbox event without PII.

Email and phone are contact methods, not globally unique identity keys. Qoida
must not auto-merge accounts because two people can share a phone, a recycled
number can change owner, and POS data may be stale.

## Guest and account merge workflow

A guest cart/order uses a random guest reference and contact snapshot. Claiming
a guest history requires proof of the authenticated principal and a short-lived,
single-use claim challenge. An explicit merge workflow is:

```text
REQUESTED -> EVIDENCE_REQUIRED -> READY -> MERGING -> COMPLETED
          -> REJECTED | EXPIRED | MANUAL_REVIEW
```

Merging never rewrites immutable order snapshots. It attaches an authorized
account reference and records source/target mappings. Conflicting loyalty,
consent, or active recovery benefits require deterministic rules or manual
review. A completed merge leaves the source account tombstoned and redirectable
inside the same tenant only.

## Identity-policy change

`BRAND_ISOLATED -> TENANT_SHARED` needs duplicate discovery, proposed
merge groups, consent review, dry-run counts, approval, and restartable batches.
The reverse direction needs an explicit split policy for history, addresses,
benefits, and principal links. Until that split policy is approved, the reverse
transition is forbidden.

## Legacy customer-feature disposition

The legacy source also contains invitations/referrals, favorite products,
search history, ratings, incidents, and blacklists. They are not identity
attributes and must not be copied into `customer_accounts.extra`.

- A retained favorite is a brand-aware saved-item relation that tolerates an
  archived/unpublished product and follows the active identity partition.
- A retained referral/invitation needs explicit inviter/invitee proof, reward,
  fraud, expiry, and benefit-grant rules before backfill.
- Search history is collected/migrated only for an approved purpose, consent,
  retention, export, and erasure policy; aggregate analytics should not retain
  unnecessary customer identity.
- Ratings become order-linked feedback with moderation/visibility rules or are
  archived/retired; they are not a mutable customer profile field.
- Incidents may seed ADR 0013 recovery cases only when source evidence supports
  a deterministic mapping.
- Blacklists require a separate auditable risk/restriction decision with owner,
  reason, expiry, access, appeal, and legal basis, or they remain protected
  archive evidence.

Product selects `MIGRATE`, `TRANSFORM`, `ARCHIVE`, or `RETIRE` for each in the
migration coverage register before customer/storefront cutover. If retained,
the domain owner and physical model are added to the canonical domain documents
before implementation.

## APIs

```text
GET    /api/v1/customer/me
PATCH  /api/v1/customer/me
GET    /api/v1/customer/me/addresses
POST   /api/v1/customer/me/addresses
DELETE /api/v1/customer/me/addresses/{addressId}
PUT    /api/v1/customer/me/consents/{purpose}
POST   /api/v1/customer/guest-claims

GET    /api/v1/operations/customers/{customerId}
POST   /api/v1/operations/customer-merges
GET    /api/v1/control-plane/tenants/{tenantId}/identity-migrations/{id}
```

Customer APIs infer the subject from the token and do not accept a caller-
supplied customer ID. Operations APIs require scoped roles and an audit reason.

## Events

```text
CustomerAccountCreated
CustomerPrincipalLinked
CustomerBrandProfileCreated
CustomerContactVerified
CustomerConsentChanged
CustomerAccountMergeCompleted
CustomerAccountAnonymized
```

Events contain stable IDs, scopes, purpose codes, and versions; they do not
contain raw email, phone, address, device token, or free-form notes.

## Security, privacy, and retention

- Encrypt contact values, addresses, push tokens, and sensitive notes using a
  rotatable envelope-encryption scheme; hash normalized lookup values.
- Composite tenant foreign keys prevent cross-tenant relationships; brand
  ancestry checks protect brand profiles and isolated identities.
- Separate customer self-service, location Operations, and control-plane data
  views. Mask contact details unless a role and workflow need them.
- Export, correction, erasure/anonymization, legal hold, and retention jobs are
  explicit operations with audit evidence.
- Logs, traces, Kafka headers, metrics, and error messages must not include PII.

## Concurrency and idempotency

Account creation and guest claiming require idempotency keys. Unique principal
and partition constraints settle concurrent registration races. Updates use a
version column. Merge workers lock the merge case, checkpoint child collections,
and can resume without duplicating links or consent decisions.

## Testing

- A subject can belong to multiple tenants without resolving across them.
- Shared mode resolves one account across brands but keeps brand profiles
  distinct; isolated mode cannot resolve across brands.
- Concurrent first login creates exactly one account/profile.
- Guest claim and merge retries are duplicate-safe and retain order snapshots.
- Policy migration dry-runs, checkpoints, conflict handling, and rollback gates
  are exercised with production-shaped data.
- SQL integration tests attempt cross-tenant and cross-brand foreign keys and
  assert rejection.
- Privacy tests verify ciphertext at rest and absence of PII in events/logs.

## Rollout and rollback

Add tables and resolution services first, then shadow-resolve legacy customers
without changing storefront behavior. Migrate links and profiles tenant by
tenant, reconcile counts and sampled identities, then move one brand journey to
the new module. Rollback returns reads/writes to the legacy owner while retaining
new mappings and append-only consent evidence; it never attempts destructive
unmerge.

## Consequences

### Positive

- One person can shop across tenants and brands without any tenant seeing
  another's data.
- Identity mode is a tenant policy with a governed migration path rather than a
  structural assumption baked into the schema.
- PII is encrypted, referenced by hash for lookup, and absent from events and
  logs by construction.

### Negative

- Encrypted contact values cannot be queried directly, so every lookup path must
  be designed around normalized hashes, and support tooling becomes harder to
  build.
- Guest claiming and account merging are genuinely complex workflows that must
  be correct on the first attempt because a wrong merge exposes personal data.
- Key rotation and erasure obligations become permanent operational work.

### Accepted trade-offs

- Refusing to auto-merge on phone or email means duplicate accounts will exist
  and support will occasionally merge them manually. This is the correct side of
  the trade.
- The reverse identity-policy transition stays forbidden until a split policy is
  approved, so a tenant can make a choice it cannot immediately undo.

## Implementation checklist

- [ ] Approve shared/isolated resolution and identity-policy transition rules.
- [ ] Approve guest claim, merge, anonymization, retention, and legal-hold rules.
- [ ] Approve address/geocoder provider, normalization, coordinate precedence, zone, and outage rules.
- [ ] Decide invitations, favorites, search history, ratings, incidents, and blacklist disposition.
- [x] Add customer tables with composite tenant/brand constraints via Flyway. V0017 (schema from V0002), tightened by V0046's tenant-boundary constraints.
- [ ] Implement account, profile, consent, address, device, and merge domains. Account, brand profile, consent and address are built (`CustomerIdentityService`, `CustomerProfileService`, `ConsentService`); there is no device table or domain, and merge exists only as a read-side redirect through `merged_into_account_id` — nothing performs a merge.
- [x] Implement Keycloak principal resolution and scoped authorization. `customer.principal_links` keyed on issuer and subject, resolved by `CustomerIdentityService.resolve`, and the link is now created by `POST /identity/registrations` redeeming a verified grant against the caller's own token — never against a phone number, so a recycled number cannot inherit an order history. Every `CustomerController` route still carries `@RequiresCapability` and is staff-only; the storefront routes are scoped instead by `@CustomerOwned` over `PrincipalCustomer`, which is delegated-authority-free by design.
- [x] Add encryption/key-rotation and normalized hash lookup services. `CustomerProfileService` writes through ADR 0029's `FieldProtection`, and `contact_points.normalized_hash` / `JdbcCustomerStore.findByNormalizedHash` is the lookup path that never decrypts.
- [ ] Add self-service, Operations, and migration APIs with audit records. The staff-scoped Operations surface exists (`/api/v1/tenants/{tenantId}/customers`), and self-service now covers identity only — `POST /identity/verification-challenges`, its `/attempts`, and `POST /identity/registrations` on `StorefrontCustomerIdentityController`. A customer still cannot read or edit their own profile, addresses or consent through any route, and there is no customer migration API.
- [ ] Publish PII-free events through the outbox. The `customers` module references no outbox and publishes nothing.
- [ ] Build legacy shadow resolution, reconciliation, and tenant rollout tools. Nothing in V0017 or the module carries a legacy customer identifier.
- [ ] Add concurrency, privacy, migration, and negative isolation tests. Three classes now: `CustomerIdentityTests`, `CustomerVerificationTests` and `VerificationChallengeSqlTests`. Privacy and negative isolation are covered on the verification path — the code is not recoverable from the row, its hash is keyed and per-challenge salted, nothing prints the number or the code, audit evidence is identifiers only, a challenge is tenant-scoped, identity is never resolved by phone, and an unwired deployment issues nothing. Concurrency and migration are still not covered, and neither is any of it on the profile, address or consent paths.

## Exit criteria

The same authenticated person can safely shop in multiple tenants; a tenant's
approved policy consistently shares or isolates identity across brands; guest
claims and merges are auditable and restartable; and no query, event, or foreign
key can expose customer PII outside its authorized tenant/brand scope.
