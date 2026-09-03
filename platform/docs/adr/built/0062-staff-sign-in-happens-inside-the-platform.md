# ADR 0062: Staff sign in inside the platform — the backend validates credentials with Keycloak

- Decision status: Accepted
- Implementation status: Built — both staff apps sign in on their own page
  (wave 18); the backend exchanges credentials with Keycloak's direct grant on
  the `horecaos-staff-login` confidential client, refresh and sign-out proxy
  through the backend (since wave 35 the refresh token persists in
  sessionStorage — reload, deep links, and sleep-expired sessions restore or
  redirect cleanly; the access token stays in-memory only), failures are uniform (wrong password, unknown user, and
  a brute-force-locked account answer identically — verified against live
  Keycloak, which itself cannot distinguish them), a required action answers
  `ACCOUNT_ACTION_REQUIRED`, and sign-in is ADR 0033 rate-limited. The public
  redirect clients are gone from the realm file and their callback code is
  deleted; the production runbook's hardening step deletes them from an
  older-imported live realm and rotates the staff-login secret. Proven by the
  full curl matrix and a real-browser pass against the dev realm, plus
  `StaffAuthServiceTests`/`StaffDirectGrantClientTests`/`StaffSessionControllerTests`
  and both apps' suites.
- Date proposed: 2026-09-02
- Date decided: 2026-09-02
- Deciders: platform owner (directed the in-platform login and the backend-validates
  model), Claude (architecture and trade-off record)
- Depends on: 0003, 0025, 0031, 0033, 0035
- Supersedes / Superseded by: — (amends the staff sign-in mechanics ADR 0035
  carried forward from ADR 0022: the authorization-code redirect is replaced for
  the two staff apps; ADR 0035 remains the frontend-platform record)
- Open inputs: none.

## Context

Control-plane and operations sign-in redirects the browser to Keycloak, which
paints its own login page and redirects back. The owner directed a change on
2026-09-02: staff log in on a first-party page inside each app, and **the backend
takes the credentials to Keycloak** — the browser never sees Keycloak. Two forces:
the redirect handoff is a jarring seam in a product whose every other screen is
first-party, and the operations callback is broken in practice — a flow with this
little surface being wrong this often is telling us something.

Customers are untouched: the storefront's phone+OTP identity (ADR 0015/0051) never
involved Keycloak redirects, and ADR 0063 extends it Telegram-natively.

## Decision

- Each staff app renders its own login page. The SPA POSTs credentials to a
  platform endpoint (`/api/v1/auth/staff/sessions`, per-app client context), which
  exchanges them with Keycloak over the **OAuth2 direct grant** (resource-owner
  password flow) on a confidential client held by the backend — the SPA never
  talks to Keycloak and never holds a client secret. The response carries the
  access and refresh tokens the apps already use as bearers; a companion refresh
  endpoint proxies the refresh grant; logout revokes the refresh token.
- Keycloak stays the identity store, untouched in its role: accounts, roles,
  brute-force lockout, password policy, and required actions keep working.
  A `required action` outcome (expired password, unconfigured account) is
  surfaced as an actionable Problem Details response, not a silent failure.
- The endpoint is rate-limited per ADR 0033 and never logs credentials; failures
  are uniform ("invalid credentials") to avoid user enumeration.
- The public redirect clients for the two staff apps are retired from the realm
  once the new flow ships; the broken callback dies with them.

**Recorded trade-offs, accepted by the owner's direction**: the direct grant is
disfavored in OAuth 2.1 because the app touches the password — acceptable here
because both apps and the backend are the same first party, and Keycloak remains
the only verifier; browser SSO across the two apps via the Keycloak cookie is
lost (each app logs in on its own); WebAuthn/social login at the Keycloak page is
foreclosed for staff while this stands — a future need there means a superseding
record, not a quiet re-enable.

## Implementation checklist

- [ ] Confidential direct-grant client in the realm (file + live), secret via ADR 0028 reference
- [ ] `POST /api/v1/auth/staff/sessions` + refresh + logout, Problem Details errors, ADR 0033 rate limits, uniform failure wording
- [ ] Control-plane and operations login pages, session bootstrap replacing the redirect handling, token refresh wiring
- [ ] Retire the public redirect clients from the realm; remove callback code paths
- [ ] Tests: happy path against the realm, wrong password (uniform), locked account, required-action surfacing, rate-limit refusal; frontend suites for both apps

## Exit criteria

A staff user signs in on each app's own page against the live dev realm, works a
full session including a token refresh, and signs out; no browser request ever
reaches Keycloak; the retired redirect clients are gone from the realm file.
