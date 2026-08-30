# HorecaOS frontends

Three Angular applications plus the canonical design tokens. The Flutter customer app
lives in [../mobile](../mobile) and is on hold for launch — the storefront below is the
customer surface ([ADR 0055](../platform/docs/adr/meta/0055-greenfield-launch-scope.md)).
Imported 2026-08-30 from the Qoida workspace, where they were four sibling git
repositories — three of them with no remote. Their pre-import history remains in
`../Qoida/qoida-platform/frontend/*` on the founding machine. Framework choice is
[ADR 0035](../platform/docs/adr/partial/0035-angular-frontend-platform-and-design-system-adoption.md).

Each app's own README is the detailed reference. What follows is oriented toward "what
does this app actually do today", not a percentage — the predecessor's import-time
estimates (~15%/~20%/~70%) are gone because all three have moved well past them and a
number here would only go stale the same way.

## storefront/

The customer-facing app: browse (home, category, product, search), cart, a
session-guarded checkout, order tracking (active/finished/cancelled/detail), and
profile/addresses. Sign-in is phone number plus SMS OTP against the platform's own
session endpoint — not Keycloak/OIDC; see `src/app/core/session/customer-otp.ts`. Tests
live beside the code as `*.spec.ts` (`ng test`, Vitest-backed); there is no separate
end-to-end suite. Angular 21.

## operations/

The application a restaurant runs on during service. `today` and `orders` (with an order
detail pane) are real, built screens; every other rail destination — kitchen, delivery,
couriers, customers, staff, statistics, catalog, places, settings — renders an explicit
"not built yet" placeholder pointing at its spec in
[`../platform/docs/operations-spec/`](../platform/docs/operations-spec/). Auth is
Authorization Code with PKCE against the HorecaOS Keycloak realm via
`angular-auth-oidc-client`; tokens are held in memory only, so a page refresh drops the
session by design, and the realm handshake itself has never been exercised against a
running Keycloak. Tests live beside the code as `*.spec.ts` (`ng test`). Angular 22.

## control-plane/

Platform-staff administration. Two routed screens exist, an overview and a
capability-guarded tenants list; six more sections (onboarding, subscriptions, payments,
statistics, platform configuration, staff) are declared with their required capability in
`src/app/layout/sections.ts` but have no route yet — not even a placeholder route.
Capability-based nav filtering is UX only; the server re-authorizes every call
([ADR 0025](../platform/docs/adr/built/0025-fine-grained-authorization-and-capability-model.md)).
Auth uses `angular-oauth2-oidc` against the same realm, tokens in memory. Tests live
beside the code as `*.spec.ts` (`ng test --watch=false`). Angular 22.

## design-tokens/

`design-tokens/tokens.css` is **the** canonical token file. Today only `control-plane`
and `operations` vendor a copy of it (`storefront` uses Tailwind and vendors no tokens
file at all); re-pointing every app at this one file directly is an open item on ADR
0052's checklist. Of the two that vendor a copy, only `control-plane` checks it against
the source of record: `control-plane/scripts/check-tokens.mjs` diffs
`src/design-system/tokens.css` against `../../design-tokens/tokens.css` (ignoring header
comments) and is wired into its `npm run verify`, but nothing calls that script in CI —
there is no CI configured for any of the three apps yet. `operations`'s vendored copy is
verified by eye, not by a script.

## Known debts

- **Two OIDC libraries against one Keycloak realm.** `control-plane` uses
  `angular-oauth2-oidc`, `operations` uses `angular-auth-oidc-client`. `storefront` isn't
  an OIDC consumer (it's phone/OTP), so this is a two-way split, not three. Converge on
  one.
- **Generated OpenAPI clients exist now and are actively avoided.** The generator
  machinery ADR 0035 asked for now runs — five clients are checked in at
  [`../platform/api/generated/`](../platform/api/generated/) — but no app imports one.
  `storefront` and `operations` each carry a code comment explaining why not: the
  generated types have a known schema-name collision bug that produces the wrong shape
  for at least two response types. Adoption needs that bug fixed first, not just a
  decision to start importing.
- **Angular 21 vs 22 split.** `storefront` pins `^21.1.0`; `control-plane` and
  `operations` pin `^22.1.0`. Align when the storefront next takes dependency work.
- **Workspace toolchain (pnpm workspace vs per-app npm) is deliberately undecided** —
  each app builds independently today with its own lockfile.
