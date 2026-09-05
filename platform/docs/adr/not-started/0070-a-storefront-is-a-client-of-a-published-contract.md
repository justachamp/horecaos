# ADR 0070: A storefront is a client of a published contract, not a build of the platform

- Decision status: Proposed
- Implementation status: Not started
- Date proposed: 2026-09-05
- Date decided: —
- Deciders: platform owner (direction, the open inputs below), Claude (architecture)
- Depends on: 0016, 0018, 0019, 0021, 0025, 0026, 0027, 0028, 0029, 0031, 0033, 0036, 0043, 0051, 0057, 0063, 0068
- Supersedes / Superseded by: —
- Open inputs: whether third-party storefronts may be browser-only public
  clients at launch or must be server-backed confidential clients (owner);
  which of the current storefront paths are in the guaranteed public surface
  and which stay internal (owner, with this record's recommendation below);
  the deprecation window a published path is guaranteed for (owner); whether
  app registration is free or a commercial act, which the shop's own decision
  will settle (owner)

## Context

The owner intends tenants to choose between storefronts: more than one
first-party option, storefronts a tenant builds itself against open APIs, and
eventually storefronts bought from a shop. The requirement stated with it is
that **the core does not change per storefront** — catalog, cart, checkout,
sign-in, profile, settings, preferences and ordering must be standard for all.

Most of that already exists and was not built for this purpose. ADR 0057
publishes a per-surface OpenAPI group for `storefront`: 44 paths covering the
published menu and media, cart lines, destination, pricing and payment methods,
checkout and payment sessions, identity registration and verification and
sessions, `me` with addresses and favourites, orders with cancellation, loyalty,
referrals, dine-in, terms and acceptance, serviceability and delivery fee. It has
a checked-in baseline, a generated TypeScript client, and `OpenApiContractTests`
refusing to drop a published path or accept a baseline refresh that would. The
first-party Angular storefront is already only a client of it: this platform's
own storefront was recently re-themed end to end — name, colour tokens, icons,
terms of service, order-status vocabulary — without one backend file changing.

**What does not exist is an app tier.** A storefront identifies itself today by
`tenantId` and `brandId` in a `config.json`, and by nothing else. Customer-scoped
calls carry the customer's own opaque session (ADR 0051); the pre-account browse
surface is deliberately `permitAll`, listed path by path with the reasoning
recorded in `SecurityConfiguration`. The consequence is that the platform cannot
tell one storefront from another, or from a script. A tenant cannot authorise a
vendor's storefront and revoke it later. Nothing can be metered or rate-limited
per app. An order cannot say which storefront produced it. None of that matters
while the only storefront is ours; all of it is load-bearing the moment a third
party ships one, and a shop cannot exist without it.

The constraint that makes the shape non-obvious is that a storefront is usually a
**browser application**, and a browser keeps no secret. An app credential model
borrowed from server-to-server integrations would either be unusable by the very
clients this is for, or would ship a secret into a bundle and call it security.

## Decision

**The `storefront` OpenAPI group is the contract, and it becomes a product
surface rather than our application's private API.** No second API, no
per-storefront backend-for-frontend, no GraphQL layer: the same document our own
storefront consumes is the one a tenant or a vendor builds against. This is the
decision that keeps "the core does not change per storefront" true, because the
core has exactly one storefront-facing shape and every storefront is downstream
of it.

**Storefront apps get an identity, on the machinery providers already use.** An
app is registered once on the platform and installed per tenant under ADR 0026,
with any secret held as an ADR 0028 reference. Every request carries the app's
identity **in addition to** the customer's session, never instead of it: the app
says which storefront is asking, the session says which customer.

**Public and confidential clients are distinguished, honestly.** A browser-only
storefront registers as a **public client**: no secret, an app id, and an
origin allowlist the platform enforces — it is attributable and revocable, and
it is not authenticated in a sense a browser cannot deliver. A server-backed
storefront registers as a **confidential client** with a secret reference and
gets what that earns: higher quotas and access to any endpoint judged unsafe for
a public client. The distinction is stated in the contract so a vendor knows
which they are building, and the platform never pretends a bundled secret is one.

**A tenant authorises apps and can revoke them.** Authorising a storefront for a
brand is a tenant act, capability-gated (ADR 0025) and audited (ADR 0027);
revoking takes effect immediately, the way suspending a provider installation
already does. A tenant's storefront choice is data, not a deployment.

**The pre-account browse surface stays anonymous, but stops being
unattributed.** A storefront must render a menu before anyone signs in, so those
paths remain `permitAll` for the customer — but they require an app identity, so
the platform can meter, rate-limit and revoke. Anonymous is about the customer;
it was never meant to mean the platform cannot tell who is asking.

**Brand identity is served by the platform, not carried in each app's config.**
Today the first-party storefront reads its brand name, logo and theme from its
own `config.json` — reasonable for one app we deploy, wrong for an app a vendor
ships to many tenants. A registered app resolves the tenant's brand identity
from the platform, so a marketplace storefront renders the tenant's brand without
the vendor hard-coding or re-deploying anything.

**An order records the storefront that produced it**, in the provenance shape ADR
0064 uses for a call and ADR 0069 proposes for the assistant. "Which storefront
did this come from" is a question a tenant comparing two of them will ask on day
one.

**A conformance suite defines what may call itself a storefront.** A published
test suite an app must pass: the identity flow, cart to checkout with quote
binding and idempotency respected, refusals rendered as words rather than raw
codes to a customer, terms acceptance recorded against the version in force, and
no personal data written anywhere the contract does not sanction. Passing is a
listing requirement, and it is what stops the shop selling something that
embarrasses the tenant whose brand it wears.

**Versioning is a promise with a stated window.** `v1` paths are never removed —
already enforced by the contract gate — change is additive, and a breaking change
is a `v2` served alongside `v1` for a published deprecation period.

**The shop itself is not this decision.** Listing, pricing, revenue share and
install flow are a separate record. This one builds the contract and the app
tier that a shop would sell into, and is worth having even if no shop is ever
built.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Leave storefronts identified by `tenantId`/`brandId` in config, as today | No revocation, no metering, no attribution, no way to tell a vendor's app from a scraper. A marketplace cannot be built on it | Adequate while every storefront is first-party and deployed by us; revisit nothing — this is the gap being closed |
| A backend-for-frontend per storefront | Precisely the "core changes per storefront" outcome the owner ruled out, multiplied by every app in the shop | Never for the standard surface; a vendor may build their own BFF *above* the contract, which is their business |
| GraphQL for storefronts | A second API shape to specify, secure, version and keep in step with the REST contract the platform already gates. The benefit is client-side query flexibility, which a per-vendor BFF can provide without splitting the platform's own surface | Vendors demonstrate the fixed shapes genuinely block them |
| Server-rendered themes: the tenant picks a look, we render it | A theming system, not open APIs. It caps what a vendor can build at whatever our templates express, and the owner's stated goal is that vendors build storefronts, not skins | The shop turns out to be dominated by look-and-feel variation rather than capability |
| API keys managed in an external gateway | Splits authorization truth away from ADR 0025 capabilities and ADR 0026 installations, which already model per-tenant provider authorization exactly | The platform outgrows its own authorization model, which is not the case |
| Give vendors only the customer's session token, no app identity | This is today. It cannot answer "which app", cannot be revoked per app, and cannot be metered | Never |

## Consequences

### Positive

- A tenant chooses a storefront the way they choose a payment provider: install,
  authorise, revoke — data, not a deployment.
- The core stays one shape for every storefront, and the compatibility gate that
  already protects our own app protects every vendor's app for free.
- Metering, quotas and per-app attribution become possible, so a tenant can
  compare two storefronts on their own orders.
- A vendor can build against a contract that is versioned, documented, tested and
  generated into a client, rather than against screenshots of our app.

### Negative

- **Our own storefront must register too**, and its deployment gains a step it
  deliberately did not have: today it holds no credential at all.
- **Requiring an app identity on the anonymous browse surface is a breaking
  change** for any existing caller, including our own app during rollout.
- A public client is attributable and revocable but not authenticated, and no
  wording changes that. Some endpoints will be confidential-client-only, which
  means some vendors cannot build browser-only storefronts.
- A published contract is a constraint: reshaping a storefront endpoint stops
  being an internal decision.
- The conformance suite is a standing maintenance cost and a gate on vendor
  velocity — and a weak suite is worse than none, because it certifies nothing
  while implying it does.

### Accepted trade-offs

- **Attribution over frictionlessness**: every storefront request now names an
  app, at the cost of a registration step for the simplest possible client.
- **One REST contract over per-client flexibility**: vendors who want a different
  shape build it above the contract, not inside the platform.
- **A narrower public surface than the current 44 paths.** Channel-specific
  endpoints — the Telegram link and mini-app routes, dine-in QR exchange — are
  bound to particular surfaces and this record recommends they stay internal
  rather than being frozen as public contract on day one.

## Specification

**App registry.** `storefront_apps` (platform-owned: id, name, vendor, client
type PUBLIC or CONFIDENTIAL, origin allowlist, secret reference for confidential
clients, conformance status and version) and per-tenant authorisation rows
(tenant, brand, app, status, granted_by, granted_at, revoked_at), tenant-scoped
with non-null `tenant_id` and keys including it.

**Request identity.** A stable app header carrying the app id, validated on every
storefront path — including the `permitAll` browse paths, where it authenticates
the *app* while leaving the customer anonymous. For public clients the request
`Origin` must match the allowlist; for confidential clients the secret is
presented as ADR 0028 defines. An unregistered, unauthorised, or revoked app is
refused by name, not by silence.

**Quotas and metering.** Per-app and per-tenant limits on ADR 0033; usage into
ADR 0043 facts; the number of authorised storefront apps gated by the tenant's
ADR 0021 entitlement.

**Brand identity endpoint.** The brand's display name, logo and theme served on
the storefront surface, so any app renders the tenant's identity without
redeploying. Supersedes the first-party app's `config.json` brand block as the
source of truth; the config keeps only what is genuinely per-deployment.

**Order provenance.** The authorised app id recorded on the order at checkout,
readable in operations beside the channel.

**Conformance suite.** Executable, published, versioned with the contract, and
run against a candidate app's staging deployment. Cases assert the flows named in
the Decision. A conformance result is recorded against the app and expires when
the contract's major version moves.

**Documentation.** The generated `storefront` OpenAPI document published as
reference, with the deprecation policy and the public/confidential distinction
stated in it rather than in prose elsewhere.

## Rollout and rollback

Three stages, each independently reversible. **One**: registry, authorisation and
app identity accepted but *optional* — requests without it continue to work, and
the platform records how many arrive unattributed. **Two**: first-party
storefronts registered and sending it, brand identity served from the platform,
provenance recorded. **Three**: identity required, after the unattributed count is
zero for a stated period. Rollback at any stage is making identity optional
again; nothing else depends on it having been mandatory.

## Implementation checklist

- [ ] App registry, client types, origin allowlist, secret references
- [ ] Per-tenant authorisation with capability, audit, and immediate revocation
- [ ] App identity validation across the storefront surface, browse paths included
- [ ] Refusals by name for unregistered, unauthorised, revoked, and origin-mismatched apps
- [ ] Brand identity endpoint; first-party storefront migrated off its config block
- [ ] Per-app quotas (0033), usage facts (0043), entitlement gate (0021)
- [ ] Order provenance carrying the app, surfaced in operations
- [ ] Public surface decided path by path, and the internal set documented as internal
- [ ] Conformance suite, published and versioned with the contract
- [ ] Deprecation policy published alongside the generated document
- [ ] Control-plane screens: registry and conformance status; operations screen: authorise and revoke

## Exit criteria

A tenant authorises a second storefront from the operations console and both are
live against the same brand; each order in operations names the storefront that
produced it; revoking one stops it serving that tenant within a request, while
the other is unaffected; a storefront built by someone with no access to this
repository passes the conformance suite against the published document alone;
and no platform code changed to make any of it work.

## References

- ADR 0057 (per-surface OpenAPI groups — the contract this promotes)
- ADR 0026 (installations), ADR 0028 (secrets), ADR 0025 (capabilities),
  ADR 0027 (audit), ADR 0033 (quotas), ADR 0021 (entitlements)
- ADR 0051 (opaque customer sessions), ADR 0063 (Telegram-native identity)
- ADR 0068 (tenant terms — served through this surface), ADR 0069 (assistant
  provenance, same shape as app provenance)
