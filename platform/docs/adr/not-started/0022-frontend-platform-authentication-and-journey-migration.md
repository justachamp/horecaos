# ADR 0022: Frontend platform, authentication, and journey migration

- Decision status: Superseded
- Implementation status: Not started — superseded by ADR 0035 before any of it
  shipped, and nothing in the tree implements this record. `frontend/` holds
  three Angular applications and one Flutter application, all ignored by the
  platform repository per `frontend/README.md`; the only React left is
  `frontend/prototypes/{operations,control-plane}` (React 19 + Vite), which that
  README declares throwaway design prototypes rather than a platform. The
  checklist below is not re-scoped here: ADR 0035 owns the surviving work.
- Date proposed: 2026-08-19
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture), frontend
- Depends on: ADR 0003, ADR 0025, ADR 0031
- Supersedes / Superseded by: Superseded by ADR 0035
- Open inputs: Supported locales and accessibility/performance budget numbers (product)

> **Superseded by [ADR 0035](../partial/0035-angular-frontend-platform-and-design-system-adoption.md)
> on 2026-08-21.** This ADR chose React 19 everywhere before the existing Qoida
> applications were examined. They turned out to include a current Angular 21
> storefront, and rewriting it in React for framework consistency alone was not
> worth weeks of work and a visual-regression risk on the revenue surface.
> ADR 0035 standardises on Angular for every web surface and keeps native SwiftUI
> for iOS. What survives from this ADR — the PKCE and tenant-selection rules, and
> the principle that shared code must not straddle frameworks — is carried forward
> there rather than repeated here.
>
> The integration spike recorded below did pass, and its finding still holds: one
> shared package ran unchanged in two stacks. The workspace it produced has since
> been removed from the repository.

## Context

The legacy frontends must be replaced while the backend is migrated capability
by capability. Qoida has distinct experiences for platform/control-plane users,
restaurant Operations, customers/storefront, and potentially couriers. Tenants
have multiple brands, each needing branding and locale, while authentication and
authorization move to Keycloak. Rewriting every screen in one release would
couple frontend cutover to all backend work and remove a safe rollback path.

## Decision

Build a TypeScript frontend workspace with separate deployable applications and
shared, versioned packages.

**React 19 with TypeScript is the single component framework across every
application.** No second component framework enters the target workspace.

Two build stacks are used, chosen per surface by rendering need:

| Application | Stack | Why |
|---|---|---|
| `apps/storefront` | Next.js (App Router) | Public, SEO-critical, Core Web Vitals matter, needs mature SSR, image, and incremental rendering |
| `apps/control-plane` | Vite + TanStack Router + TanStack Query | Authenticated SPA, no SEO requirement, benefits from type-safe routing and fast builds |
| `apps/operations` | Vite + TanStack Router + TanStack Query | Same, plus long-lived sessions and dense real-time views |
| `apps/courier` | Vite + TanStack Router + TanStack Query | Same; built only after courier scope is approved in ADR 0014 |

Supporting choices: pnpm workspaces for package management, Turborepo for task
orchestration and caching, Vitest plus Testing Library for unit and component
tests, and Playwright for end-to-end tests.

**The rule that makes this safe:** shared packages import React only. They must
not import Next.js or TanStack APIs, must not use server components, and must
not assume a router. Anything framework-specific lives in the application. This
keeps the storefront's stack a replaceable decision rather than a structural
one, and it is the invariant to defend in review.

Pin exact stable versions at implementation time and verify them against
official release notes; the versions above name the framework line, not a patch
level.

Initial applications:

```text
apps/control-plane   tenant/brand/location/configuration and SaaS administration
apps/operations      restaurant orders, approvals, catalog/POS, recovery, delivery
apps/storefront      customer discovery, cart, checkout, order tracking
apps/courier         optional only after courier workflow scope is approved
```

Shared packages provide design tokens/components, Keycloak/OIDC integration,
generated API clients, authorization primitives, localization, telemetry,
validation, money/time types, and test utilities. Shared packages cannot import
an application feature.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Angular for all four applications | A serious contender: two legacy frontends are already Angular, so continuity and existing team knowledge favour it, and Angular suits large internal applications well. It loses because the newer legacy dashboard is already React, the storefront needs best-in-class SSR and Core Web Vitals tooling, and the local React hiring pool is larger | Team Angular depth turns out to be the dominant delivery constraint. This is the closest of the rejected options |
| Next.js for all four applications | Uniform tooling, but three of the four surfaces are authenticated single-page applications that gain nothing from server components while paying for a server runtime, cache semantics, and RSC boundaries in every feature | The internal applications develop a real server-rendering or SEO requirement |
| Vite plus TanStack Start for all four applications | Reached 1.x stable in early 2026 with excellent type safety, Vite speed, and deploy portability, and it is the chosen stack for the three internal applications. It does not take the storefront because Next.js still has deeper SSR, image, and incremental-rendering tooling for a public, SEO-critical surface | The next storefront replatform, or when TanStack Start's SSR and image story matches for public pages. The shared package rule below is what keeps that migration cheap |
| Micro-frontends | Independent deployment complexity with no independent teams to justify it | Measured organizational or deployment constraints appear, under a separate ADR |
| Server-rendered Thymeleaf or HTMX inside the monolith | The simplest possible operations, and genuinely viable for the control plane alone. Rejected because operations and storefront are highly interactive, and a shared design system across four surfaces is a product requirement | Never for storefront and operations |
| Keep legacy frontends and only replace the backend | Leaves the tenant, brand, and location model unrepresentable in the UI, so the SaaS product cannot actually be sold | Never |

## Application boundaries

Each app owns feature modules organized by user journey, not backend tables:

```text
feature UI/state -> application use-case client -> generated API client
                 -> shared design/auth/telemetry foundations
```

No browser calls a database, Kafka, S3 private object, Keycloak admin API, or
provider directly. Backend APIs remain the policy enforcement point. A frontend
permission check improves UX but never replaces server authorization.

Micro-frontends are not the default. They add deployment/runtime complexity
without an established need for independent teams. Reconsider only with
measured organizational/deployment constraints and a separate ADR.

## Validation spike

The framework is chosen, so this is a confirmation exercise, not a bake-off. It
exists to catch an integration problem before four applications are scaffolded,
and it is timeboxed.

Build one thin vertical journey in both stacks — Next.js for the storefront
shape and Vite plus TanStack for the internal shape: Keycloak login, tenant
switch, a generated OpenAPI call, a localized and themed page, form validation,
error handling, trace propagation, and a production build.

Confirm before scaffolding:

- accessibility and keyboard behavior meet the agreed target;
- a shared package containing a themed component and an authorization primitive
  builds and runs unchanged in both stacks, which is the load-bearing assumption
  of the React-only shared package rule;
- storefront server rendering propagates trace context and does not leak tokens
  into rendered HTML;
- route-level code splitting and the performance budget hold in both stacks;
- the generated client and its error mapping work identically in both.

If the shared package cannot run unchanged in both stacks, stop and reduce the
workspace to one stack rather than duplicating shared code. Record the pinned
versions of React, Next.js, TanStack Router and Query, Vite, pnpm, and Turborepo
in this ADR once the spike passes.

## Spike result and pinned versions

The integration spike this ADR requires before scaffolding four applications has
been run and passes. The workspace is in `frontend/`.

**The load-bearing question is answered: a shared package runs unchanged in both
stacks.** `@qoida/ui` ships `MenuItemCard`, design tokens, and the ADR 0025
capability primitives. The Next.js storefront renders that component under server
rendering and the Vite plus TanStack control plane renders the same component,
from the same `@qoida/menu-client`, with no per-stack variant. Both build, and
`apps/control-plane/src/__tests__/shared-component.test.tsx` asserts the
equivalence rather than leaving it to review.

**The React-only rule is enforced, not reviewed.**
`packages/ui/src/__tests__/portability.test.ts` fails the build if any shared
source imports Next.js, TanStack, a router, or a server-component directive — and
also asserts it is actually reading the shared sources, because a file-walking
guard that silently finds nothing passes forever.

**The shared package's shipped surface has no Node dependency.** Its typecheck
runs with `types: []` and excludes tests; the tests get their own tsconfig. That
is what lets the same code run in a browser and inside server rendering.

Versions pinned at implementation time and installed successfully together:

| Package | Version |
|---|---|
| Node | 24.19.0 (engine floor 24) |
| pnpm | 11.22.0 (via Corepack) |
| Turborepo | 2.10.11 |
| TypeScript | 5.9.3 |
| React / React DOM | 19.2.8 |
| Next.js | 16.3.2 |
| Vite | 8.2.2 |
| @vitejs/plugin-react | 6.1.0 |
| TanStack Router | 1.170.31 |
| TanStack Query | 5.101.4 |
| Vitest | 4.1.11 |
| Testing Library (React / DOM) | 16.3.2 / 10.4.1 |
| Playwright | 1.62.1 (not yet wired) |

TypeScript 5.9.3 rather than the available 7.0.2: 7 is the native compiler
rewrite, and pinning a major-version rewrite underneath Next 16 and Vite 8 on the
same day is a second integration risk this spike was not scoped to absorb.

**Next.js telemetry is disabled** via `NEXT_TELEMETRY_DISABLED=1` in the
project's scripts, rather than `next telemetry disable`, which writes to a
machine-global config outside the repository.

Still to confirm from this ADR's own checklist, and not yet claimed: Keycloak
PKCE login, tenant switch, the generated OpenAPI client, localization, trace
propagation through server rendering, route-level code splitting against the
performance budget, and the accessibility target. `apps/operations` and
`apps/courier` are not scaffolded.

## Keycloak authentication

Browser applications use Authorization Code with PKCE against the Qoida realm.

- Store tokens in memory where practical; do not persist refresh/access tokens
  in local storage.
- Use secure, same-site, HTTP-only cookies only if the selected architecture
  adds a backend-for-frontend with documented CSRF controls.
- Validate issuer/audience/nonce/state/PKCE and use exact allowlisted redirect
  URIs and post-logout URIs per environment.
- Refresh proactively through the OIDC library, coordinate across tabs, and
  return to the intended route after authentication.
- Tenant selection is explicit. The active tenant is included in API context
  only after membership verification; brand/location selection is constrained
  by the returned capability view.
- Logout clears application cache and sensitive in-memory state.

The browser never holds a Keycloak service-account credential or infers tenant
access from role-name strings alone.

## Frontend authorization model

After login, call the backend `/api/v1/session/context` endpoint defined in
[ADR 0025](../built/0025-fine-grained-authorization-and-capability-model.md), returning the
actor, memberships, active tenant, allowed brand/location scopes, capabilities,
entitlements, and a context version. Route guards and controls use semantic
capabilities such as `order.approve` or `catalog.publish`. All mutations still
receive server-side policy enforcement and stable `403` details.

Switching tenant clears tenant-scoped query caches, carts, optimistic state,
open event streams, breadcrumbs, and persisted filters before loading the new
context. Tests specifically attempt stale-cache display after a switch.

## API clients and compatibility

- Generate typed clients from the backend OpenAPI document in CI.
- Fail generation/checks on undocumented breaking changes.
- Wrap generated transport only for cross-cutting correlation, auth, errors,
  retries, and telemetry; do not hand-copy response types.
- Retry only safe/idempotent requests. Mutations use explicit idempotency keys.
- Map RFC-compatible problem responses to stable user-facing error codes.
- Use cursor pagination and server filtering for large Operations lists.
- Real-time updates use an approved backend transport and always reconcile with
  a fresh authoritative query after reconnect.

## Brand theming and localization

The platform design system defines accessible semantic tokens. A validated
runtime brand manifest supplies logo/media asset references, colors, typography
choices from an allowlist, locale defaults, and safe content—not arbitrary CSS
or JavaScript. Cache manifests by version and fall back to an accessible Qoida
theme when invalid.

All user text uses locale keys; dates use IANA timezone context; money uses
currency-aware formatting but backend integer amounts remain authoritative.
Right-to-left readiness, long translations, and Uzbek/Russian/English needs are
included in visual and accessibility testing once the supported list is agreed.

## State and offline behavior

Server state lives in a query/cache layer scoped by tenant and app; local UI
state remains local. Avoid a single mutable global store containing unrelated
domain data. Storefront cart persistence uses a non-sensitive opaque cart ID and
server reconciliation. Operations may show connectivity loss, but order
approval/payment/refund actions are not queued offline unless a later ADR
defines conflict and security semantics.

## Security baseline

- Enforce CSP without unsafe dynamic script where supported, trusted asset
  origins, clickjacking protection, secure headers, and dependency scanning.
- Render user/template content safely and prohibit raw HTML by default.
- Never log tokens, customer contacts, addresses, payment details, or order notes.
- Redact telemetry payloads and session replay; production source-map access is
  restricted.
- Use short-lived signed URLs from backend authorization for private media.
- Add automated dependency update policy and emergency revocation/runbook.

## Testing and quality gates

```text
unit           pure formatting, permissions, reducers/use cases
component      design system, forms, accessibility, themes/locales
contract       generated clients against OpenAPI examples/mock server
integration    Keycloak login/context switch/error and cache clearing
end-to-end     critical journeys against disposable real backend dependencies
visual         supported viewport/theme/locale snapshots
performance    route budgets and storefront Core Web Vitals targets
```

Critical E2E paths: onboarding owner login, tenant/brand/location setup,
catalog publication, POS review, both restaurant approval channels, customer
checkout/tracking/cancellation, refund/recovery, and delivery exception.
Accessibility targets and performance budgets must be numeric before acceptance.

## Journey-based migration

Do not migrate by page or table. For each vertical journey:

1. Define legacy and target URL, API owner, data owner, analytics, and SLO.
2. Implement target APIs and generated client contracts.
3. Run target UI in a non-production/shadow environment with synthetic data.
4. Route internal users, then a pilot tenant/brand/location using a feature flag.
5. Compare business outcomes, errors, latency, and support contacts.
6. Expand gradually while maintaining a route-level return to legacy.
7. Freeze and remove the legacy journey only after the ADR 0024 exit gate.

For storefront SEO/URLs, preserve redirects, canonical metadata, and analytics
continuity. Never allow both frontends to write the same capability without a
single backend ownership gate.

## Rollout and rollback

Build immutable signed assets with environment configuration injected safely at
deployment. Promote the same artifact across environments. Version API
compatibility independently of frontend release. Rollback changes route traffic
to the prior frontend artifact or legacy journey; it cannot roll back database
facts created by already-completed target operations.

## Consequences

### Positive

- One design system, one authorization model, and one generated client across
  four surfaces.
- Journey-based migration keeps a route-level rollback path to legacy at every
  step.
- Choosing React for every application means shared packages, hiring, and review
  standards are common even though two build stacks exist.

### Negative

- Two meta-frameworks are operated: Next.js for the storefront and Vite with
  TanStack for the internal applications. That is two build pipelines, two
  deployment shapes, and two upgrade cadences.
- Generated clients make backend contract changes immediately visible as
  frontend build failures, which is the point and is also friction.
- Brand theming through a validated manifest limits what tenants can customize
  compared with arbitrary CSS.

### Accepted trade-offs

- The single-component-framework rule is kept at the React level rather than the
  meta-framework level, and shared packages must therefore import React only,
  never framework-specific APIs. If that rule erodes, the storefront can no
  longer move stacks cheaply.
- Offline queuing of approvals and payments is deliberately excluded, so
  operations staff on poor connectivity are blocked rather than optimistic.

## Implementation checklist

- [ ] Inventory legacy apps, journeys, URLs, browser support, analytics, and owners.
- [ ] Run the validation spike and record pinned versions in this ADR. The note above records that the spike passed; the workspace it produced is not in the tree, so nothing about it can be verified from code today.
- [ ] Enforce the React-only shared package rule with a lint rule or dependency check in CI. Moot: ADR 0035 replaced the rule with an Angular-only one, and no shared package exists in this repository.
- [ ] Define design tokens, accessibility target, locales, themes, and performance budgets.
- [ ] Scaffold workspace boundaries, CI, signed builds, and shared packages.
- [ ] Implement Keycloak PKCE, session context, tenant switching, and logout.
- [ ] Generate API clients and enforce compatibility/error/idempotency conventions.
- [ ] Implement secure telemetry, CSP/headers, dependency controls, and privacy tests.
- [ ] Build critical journey/component/contract/E2E/visual test harnesses.
- [ ] Migrate journeys behind tenant/brand/location route flags with metrics.
- [ ] Document deployment, rollback, browser incident, and legacy retirement runbooks.

## Exit criteria

All approved journeys run on one coherent, accessible frontend platform; Keycloak
and tenant switching cannot leak state; APIs are generated and compatibility-
checked; brand themes cannot execute unsafe code; and every journey can be
progressively routed and rolled back without creating two business writers.
