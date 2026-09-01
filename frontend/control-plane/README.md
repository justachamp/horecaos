# HorecaOS control plane

The console the people who run HorecaOS as a business use: tenants, onboarding and
offboarding, subscriptions, payments, statistics, platform configuration, and
who among HorecaOS's own staff may do what.

Its subject is the customer — who they are, how they were brought on, what they
pay, what they use, and when they leave. Engineering surfaces are deliberately
absent. Provider installations, message queues, dead letters and migration
tooling are real and necessary and belong to whoever operates the platform
rather than to whoever sells it; an account manager chasing an unpaid invoice
should not have to scroll past a dead-letter queue to find it.

Angular, CONSOLE surface, per [ADR 0035]. One of four application repositories;
the others are `operations`, `storefront` and `mobile`.

## Running it

```
npm install
npm start          # http://localhost:4200
npm run build      # production bundle into dist/
npm test           # vitest, single run
```

`npm start` expects the platform API at `http://localhost:8080`. This
application no longer talks to Keycloak at all (ADR 0062): a signed-out visitor
lands on this console's own `/login`, and if the platform itself is
unreachable the submit fails inline with a Problem Details error rather than
the application showing nothing.

### Configuration

Deployment configuration is read at runtime from `public/config.js`, not baked
in at build time, so one artifact is promoted from staging to production
unchanged:

```js
window.horecaosControlPlaneConfig = {
  apiBaseUrl: 'https://api.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};
```

Nothing secret goes in it — there is nothing secret to put in it. The Keycloak
issuer and client id that used to live here are gone along with the redirect
flow they configured: the platform backend is the only thing that resolves a
Keycloak issuer now, over a confidential client (`horecaos-staff-login`) this
bundle never sees (ADR 0028).

## What is built

**The shell.** A 256px near-black rail, a 48px top bar, a hairline-bordered
content area, and a rail whose sections are filtered by the capabilities the
signed-in principal actually holds. Structure and density come from the
prototype in the platform repository at `frontend/prototypes/control-plane`,
which is throwaway React, is read and never imported.

**The design tokens.** `src/design-system/tokens.css`, vendored from the design
system's token sheet, with a header saying so. IBM Plex Sans and IBM Plex Mono
are self-hosted through `@fontsource` rather than fetched from a font CDN.

**Authentication.** A first-party `/login` page ([ADR 0062], amending [ADR 0003]
and [ADR 0035]'s redirect mechanics). The operator's password never leaves this
origin: submitting POSTs it to the platform's own
`POST /api/v1/control-plane/auth/sessions`, which runs the OAuth2 direct grant
against Keycloak on the backend. Tokens live in memory only, in
`src/app/core/auth/staff-token-store.ts` — there is no PKCE verifier or nonce to
keep in `sessionStorage` any more, because the browser never navigates to
Keycloak at all. `AuthService` proactively refreshes the access token a minute
before it expires.

**Authorization, as an affordance.** `GET /api/v1/session/context` returns the
principal's capabilities; the rail hides what they cannot reach and a route
guard keeps them off it. This is a courtesy, not a control — the API authorizes
every call again, and the platform has a test asserting that this view and
server enforcement agree.

**An API client honouring [ADR 0031].** `src/app/core/api/`:

| Convention | Where |
|---|---|
| `Idempotency-Key` on every mutation, caller-supplied for a retry | `api-client.ts` |
| `If-Match: W/"n"` for optimistic concurrency, and the version read back off the `ETag` | `api-client.ts` |
| Cursor pagination — `?cursor=&limit=`, `{ items, nextCursor }`, no totals | `page.ts` |
| RFC 9457 Problem Details into a typed `ApiError` with the server's stable `code` | `problem.ts`, `interceptors.ts` |
| `X-Correlation-Id` on every request | `interceptors.ts` |
| Money as `{ amountMinor, currency }` | `money.ts` |

The bearer token is attached by an `HttpContext` flag set by this client, not by
matching a URL prefix — a prefix match is one misconfigured base URL away from
sending an operator's token to somebody else's host, silently.

**Money.** UZS is whole som. `amountMinor: 84000` is eighty-four thousand som.
A formatter that asks `Intl` for the currency's decimal places finds ISO 4217's
exponent of 2, divides by a hundred, and quotes 840 — that bug shipped in this
codebase. `money.ts` takes the scale from its own table and throws on a currency
it has not been told about, rather than guessing.

**Localisation** for ru, uz-Latn and en, switchable at runtime. The Russian and
Uzbek catalogues are typed as the English one's key set, so a key added and not
translated fails `npm run build` with the key named. There is no runtime
fallback to English, because a fallback is how half a console ends up in the
wrong language without anyone noticing. Angular's `$localize` was not used: it
produces one bundle per locale and cannot switch without a reload, and this is a
console two people share.

**Tests.** 78 of them, over the shell, the guards, the sign-in page, the auth
service, the API client, the money formatter, the catalogues and the route
table. Not coverage theatre — every one of them fails for a reason somebody
would otherwise debug.

## What is deliberately absent

- **Screens.** Two routes exist, `/` and `/tenants`, and both render a stated
  placeholder. The real screens are specified in `docs/operations-spec/` in the
  platform repository and shown in the prototypes, and half of one built by
  somebody laying foundations is worse than an empty route: it has to be thrown
  away, and until it is it gets mistaken for a specification.
- **The other six sections.** Onboarding, subscriptions, payments, statistics,
  platform configuration, and staff are declared in `src/app/layout/sections.ts`
  with the capability each needs, and have no route. The declaration is there so
  the next person adds a screen rather than re-deriving which capability guards
  it.
- **A generated API client.** [ADR 0031] requires types generated from the
  OpenAPI document, and [ADR 0035] describes how that works across repositories:
  the platform publishes the document as a versioned artifact and each client
  pins a version and generates from it in CI. Until the document is published,
  the handful of response shapes this application needs are declared by hand
  next to the endpoint they came from, and each says which Java record it
  mirrors. `SessionContext` and `Problem` are the only two.
- **Tenant selection.** The console is HorecaOS staff at platform scope; a tenant
  picker belongs with the first screen that needs one.
- **Cross-tab refresh coordination.** [ADR 0035] wants proactive refresh
  coordinated across tabs. What is here is `AuthService`'s own scheduled
  refresh, per tab and independent of any other. Two tabs will refresh
  independently, which works and is wasteful — and, since ADR 0062 also
  retires the Keycloak SSO cookie that used to make sign-in on a second tab
  invisible, two tabs are now also two separate sign-ins.
- **A `sync-tokens` drift check in CI.** There is no CI here yet. `npm run
  check:tokens` exists and compares the vendored sheet against the source of
  record when this repository sits beside the platform one; it is the thing CI
  should run once CI exists.
- **A published design-system package.** That is the right answer and it needs a
  registry decision first — two registries, in fact, npm-compatible for the
  three Angular applications and pub-compatible for Flutter. Until then each
  repository vendors `tokens.css` with a generated-not-authored header, and an
  edit to a vendored copy is a defect rather than a customisation. This
  application does not invent a distribution mechanism of its own.

## Verified live against the dev realm

Unlike the redirect flow this replaced — whose PKCE round trip this repository
could never run end to end, because no realm was reachable from the machine it
was written on — the ADR 0062 exchange was checked directly against a running
Keycloak (2026-09-01): a password grant on the backend's `horecaos-staff-login`
confidential client returns a real token pair; a wrong password and an unknown
username both answer identically; an account with a required action answers
distinguishably; the refresh grant and the RFC 7009 revocation endpoint behave
as `AuthService` assumes; and a Keycloak-locked account (its own brute-force
protection, after repeated failures) is indistinguishable from a wrong
password, confirming the platform's uniform-failure design is not just a
choice but matches what Keycloak itself is willing to reveal. See the platform
repository's ADR 0062 implementation notes for the full transcript.

## What could not be verified here

**The live shell.** The unit tests assert the rail, its capability filtering, the
operator name, the language switch and the timezone. A screenshot of the
signed-in console was not taken, because doing so needs a session.

## Repository status

This is a standalone git repository that is **not yet a submodule** of the
platform repository. The four GitHub remotes do not exist, and a `.gitmodules`
entry pointing at a URL that does not resolve breaks `git clone --recursive` for
everyone in a way that looks like a network failure rather than a mistake. The
promotion steps are in [ADR 0035]; until they are run, `frontend/control-plane`
is an untracked directory from the platform repository's point of view, and that
is intended.

[ADR 0003]: ../../docs/adr/built/0003-keycloak-tenant-authorization.md
[ADR 0028]: ../../docs/adr/partial/0028-secrets-management-and-credential-lifecycle.md
[ADR 0031]: ../../docs/adr/built/0031-http-api-conventions.md
[ADR 0035]: ../../docs/adr/partial/0035-angular-frontend-platform-and-design-system-adoption.md
[ADR 0062]: ../../docs/adr/not-started/0062-staff-sign-in-happens-inside-the-platform.md
