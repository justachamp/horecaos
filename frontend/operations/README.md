# Qoida Operations

The console one restaurant's staff use during service.

It is used standing up, on a 1366×768 laptop in a call centre or a 24" screen on
a manager's desk, by somebody who is also on the phone. That single fact decides
everything about it: speed of the common action beats completeness of the rare
one, the queue is never hidden behind anything, and the number of late orders is
visible from every screen in the application.

Angular 22, `CONSOLE` surface of the Qoida Design System, `ru` / `uz-Latn` / `en`.

---

## What is here

This repository contains **foundations, not screens**. The smallest thing that
proves the shape is right and lets the next person start on a view instead of on
plumbing.

| Area | State |
|---|---|
| Angular 22 workspace, standalone, zoneless, Vitest | Builds and tests |
| Design tokens, vendored and applied | `src/tokens.css`, verified in a browser |
| Shell — rail, top bar, always-visible late count, F2 | Built |
| Routing — two real routes, eleven honest placeholders | Built |
| Authentication — Authorization Code + PKCE against Keycloak | Built, partly unverifiable — see below |
| API client — Problem Details, idempotency, `If-Match`, cursor pages | Built and tested |
| Localisation — runtime switching, build-time completeness | Built and tested |
| Money and time formatting | Built and tested |

### Deliberately absent

- **Every screen.** The order board, the order detail, taking an order, the
  kitchen queue, dispatch, couriers, customers, staff, statistics, the menu,
  branches, settings. They are specified across `docs/operations-spec/` in the
  qoida-platform repository — 8 855 lines covering 109 views — and prototyped in
  `frontend/prototypes/operations`. A half-built screen is worse than an empty
  route, because it teaches operators habits the finished one has to break.
- **A generated API client.** ADR 0031 requires types generated from the OpenAPI
  document and ADR 0035 requires pinning a published version of it in CI. No
  document is published yet, so `src/app/core/api` hand-writes the *conventions*
  — which are stable — and hand-writes no *response types*, which are not.
- **Live updates.** ADR 0045 is not built. `ServiceStatus` is where the polling
  fallback goes, and it says so.
- **Any capability check.** Deliberate, not missing. See "Authorization" below.
- **A shared design-system package.** See "The tokens are a copy".

---

## Running it

```bash
npm install
npm start          # http://localhost:4200, against http://localhost:8080
npm run build      # production bundle into dist/
npm test           # Vitest, watching
npm run test:ci    # Vitest, once
npm run format     # Prettier
```

`npm start` uses `src/environments/environment.development.ts`, which points at
the platform on `localhost:8080` and Keycloak on `localhost:8081` — the defaults
in the platform repository's `application.yml`. Both come up with that
repository's docker compose.

**Without Keycloak running, the application redirects to the issuer and stops
there.** That is correct behaviour, not a failure: there are no routes outside
the guard except the login callback.

---

## Authentication

Authorization Code with PKCE against the Qoida realm (ADR 0003, ADR 0035), via
`angular-auth-oidc-client`.

**Tokens are never persisted.** `SplitSecurityStorage` keeps the access token,
the refresh token, the id token and the user claims in a closure, and writes only
the redirect state — the PKCE verifier, the CSRF `state`, the id-token nonce — to
`sessionStorage`, because the code flow navigates the whole document away and
back and cannot complete without them. None of that is a bearer credential.

The cost is stated rather than hidden: **a page refresh drops the session.** Where
Keycloak's SSO cookie is alive the re-redirect is invisible; where it is not, the
operator sees a login screen mid-shift. That is the price of not leaving a bearer
token in a browser store.

### What could not be verified here

The realm is not reachable from this repository, and these five things are
asserted in `src/app/core/auth/auth.providers.ts` and proven nowhere. The first
person with a running Keycloak should check them by hand:

1. A client `qoida-operations` exists in realm `qoida` and is a **public** client.
2. PKCE `S256` is **required** on it, not merely permitted.
3. `http://localhost:4200/auth/callback` and the production callback are
   allowlisted **exactly**. No wildcards — a wildcard redirect URI is an open
   redirect.
4. An audience mapper puts the platform API into the token's `aud`, which is what
   Spring Security validates.
5. Refresh tokens are enabled for the client, since silent renew uses them rather
   than an iframe.

What *was* verified in a browser, against a stub discovery endpoint: the guard
redirects an unauthenticated operator to the issuer with `response_type=code`,
`code_challenge_method=S256`, a real `code_challenge`, `state` and `nonce`, and
the exact redirect URI; the deep link is preserved across the redirect; and
`sessionStorage` afterwards contains the verifier, state and nonce and **no
token**, while `localStorage` contains only the locale preference.

### Authorization

There is no `can(capability)` helper and there will not be one. The API is the
enforcement point (ADR 0025). A client-side capability check is a usability
affordance — hiding a button an operator cannot use — and treating it as security
is a bug. Capability resolution runs over grants, scopes and entitlements that no
token claim summarises, so any client-side copy would be both weaker and stale.

---

## The API client

`src/app/core/api` is the only place this application talks to the platform. A
feature that injects `HttpClient` directly has silently opted out of all four
ADR 0031 conventions:

- **Problem Details.** Every failure becomes an `ApiError` carrying the stable
  `code` from the server's registry, the field errors, and the correlation id.
  `problem.detail` is English written for a developer and is never shown to an
  operator.
- **Idempotency.** `ApiClient` has no mutation overload that takes a bare body.
  It takes a `Command`, which carries a key minted once per operator intent and
  reused on every retry of that intent. A key minted inside a retry loop is how
  one approval becomes two.
- **Optimistic concurrency.** Reads return the version beside the body; mutations
  send it as a weak `If-Match`. Two operators deciding the same order at the same
  moment settle at one outcome, and the loser gets `STALE_VERSION` with both
  versions.
- **Cursor pagination.** No offsets, no page numbers. `resetOnFilterChange` exists
  because a cursor encodes the filter set and reusing one across a filter change
  fails.

### One thing the server and the ADR disagree about

ADR 0031 declares `/api/v1/operations/**` for this audience. The platform serves
the location endpoints there, but `OperationsOrderController` is still mapped on
the older `/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders`.
`src/app/core/api/operations-paths.ts` is the single file that knows this, so the
day the controller moves, exactly one file changes.

---

## Localisation

`ru`, `uz-Latn`, `en`, switchable at runtime from the top bar. Russian is the
default because that is what the staff read.

**A missing translation fails the build.** `messages.en.ts` defines the key set;
`MessageKey` is derived from it; the other catalogues are typed
`Record<MessageKey, string>`. Adding an English key without translating it is a
`tsc` error naming the key, once per untranslated locale, and `ng build` and
`ng test` both stop. Verified by adding a key and watching the build fail.

Angular's own `$localize` was the obvious choice and is the wrong one here: it
compiles one bundle per locale, so switching languages means loading a different
deployment. A shared terminal changes hands between operators mid-shift.

Content names — dishes, brands, branches, people — are never message keys. They
are tenant data in the language the tenant wrote them.

---

## Money

`{ amountMinor, currency }`, always. `src/app/core/format/money.ts` carries the
one arithmetic mistake this codebase has already shipped:

ISO 4217 gives UZS two minor units and every `Intl` implementation agrees. **The
platform stores whole som.** A formatter that asks `Intl` for the exponent and
divides by 100 renders a 125 000 som bill as `1 250,00`. That shipped last week.

So the exponents are declared as data this platform owns, an unknown currency
throws rather than guessing, and there is a test asserting that `Intl` says 2 and
this module says 0 — so that the disagreement is deliberate and documented rather
than discovered again.

---

## The tokens are a copy

`src/tokens.css` is **generated, not authored**. It is the design system's
CONSOLE sheet, vendored byte-for-byte apart from one line (its height rule names
React's `#root`; Angular's mount node is `q-root`). It is listed in
`.prettierignore` so a formatting pass cannot make a copy stop being a copy.

Four frontend repositories each hold their own copy because there is no shared
package registry yet, and **inventing one is not this application's decision to
make.** A published `@qoida/design-tokens` package is the right answer; it needs a
registry decision first — self-hosted Verdaccio, GitHub Packages, or npm private
— and that belongs in an ADR, not in a `package.json`. Until then ADR 0035's
`sync-tokens` script and a per-repository drift check are what keep the four
copies honest.

Editing this file to change a colour is always wrong. Change it in the design
system and regenerate.

---

## Privacy

No personal data goes into a log, a trace or an analytics event (ADR 0029). Two
places where that is enforced rather than merely intended:

- `ApiError.message` carries the code, the status and the correlation id, and
  never `problem.detail` — which can name something the operator typed. Error
  messages reach logs and error reporting.
- The correlation id is a fresh UUID per request. An id that encoded a user, a
  session or a device would write personal data into every log line the request
  touches.

`Auth.displayName` is personal data. It may be rendered in the account menu; it
may not be logged. `Auth.subject` is the opaque `sub` claim and is the safe value
for correlating a session.

The `@ibm/plex-*` packages ship a postinstall telemetry script. npm's
`allow-scripts` gate blocks it and it has deliberately not been approved.

---

## Layout of the source

```text
src/
  tokens.css                 generated design tokens, do not edit
  styles.css                 everything visual this application may decide
  environments/              build-time configuration, no secrets
  app/
    core/
      api/                   ADR 0031 conventions and the one HTTP seam
      auth/                  PKCE configuration, the guard, token storage
      i18n/                  catalogues, runtime switching, the `t` pipe
      format/                money and time
    shell/                   rail, top bar, the counters shown everywhere
    features/
      today/                 the landing route
      orders/                the docked-detail layout, with no board in it
      auth-callback/         the Keycloak redirect target
      not-built/             the honest empty route
```

## Related documents in the qoida-platform repository

- `docs/adr/0003` — identity and access
- `docs/adr/0025` — capabilities and scopes
- `docs/adr/0029` — personal data
- `docs/adr/0031` — HTTP API conventions
- `docs/adr/0035` — Angular, the design system, and the four repositories
- `docs/operations-spec/` — the 109 views this shell will carry
- `frontend/prototypes/operations/` — throwaway React. Never import it; read it.

## Repository status

Standalone git repository, no remote. It becomes a submodule of qoida-platform
once the remote exists. Nothing here creates a `.gitmodules` entry, because a
submodule pointing at a URL that does not resolve breaks `git clone --recursive`
for everybody.
