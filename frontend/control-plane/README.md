# Qoida control plane

The console the people who run Qoida as a business use: tenants, onboarding and
offboarding, subscriptions, payments, statistics, platform configuration, and
who among Qoida's own staff may do what.

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

`npm start` expects the platform API at `http://localhost:8080` and Keycloak at
`http://localhost:8081/realms/qoida`. Without Keycloak the application starts
and shows its sign-in-unavailable state rather than a blank page — which is
also what it does in a real outage, and is worth seeing once.

### Configuration

Deployment configuration is read at runtime from `public/config.js`, not baked
in at build time, so one artifact is promoted from staging to production
unchanged:

```js
window.qoidaControlPlaneConfig = {
  apiBaseUrl: 'https://api.qoida.uz',
  issuerUrl: 'https://auth.qoida.uz/realms/qoida',
  clientId: 'qoida-control-plane',
  displayTimeZone: 'Asia/Tashkent',
};
```

Nothing secret goes in it. The OAuth client is public and holds no credential.

## What is built

**The shell.** A 256px near-black rail, a 48px top bar, a hairline-bordered
content area, and a rail whose sections are filtered by the capabilities the
signed-in principal actually holds. Structure and density come from the
prototype in the platform repository at `frontend/prototypes/control-plane`,
which is throwaway React, is read and never imported.

**The design tokens.** `src/design-system/tokens.css`, vendored from the design
system's token sheet, with a header saying so. IBM Plex Sans and IBM Plex Mono
are self-hosted through `@fontsource` rather than fetched from a font CDN.

**Authentication.** Authorization Code with PKCE against the Qoida realm
([ADR 0003], [ADR 0035]). Tokens live in memory; only the PKCE verifier and the
nonce go to `sessionStorage`, because they are written before the browser
navigates to Keycloak and read after it comes back, and a value kept in memory
across that boundary is a value that is gone. See
`src/app/core/auth/in-memory-oauth-storage.ts`.

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

**Tests.** 62 of them, over the shell, the guards, the API client, the money
formatter, the catalogues and the route table. Not coverage theatre — every one
of them fails for a reason somebody would otherwise debug.

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
- **Tenant selection.** The console is Qoida staff at platform scope; a tenant
  picker belongs with the first screen that needs one.
- **Cross-tab refresh coordination.** [ADR 0035] wants proactive refresh
  coordinated across tabs. What is here is the library's automatic silent
  refresh, per tab. Two tabs will refresh independently, which works and is
  wasteful.
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

## What could not be verified here

**Keycloak.** No realm is reachable from the machine this was written on, so the
PKCE round trip has never run end to end. What is verified is everything up to
the redirect and everything after the token exists: the configuration, the
storage partition, the guards, and the token's path onto a request. What is not:
that the realm has a `qoida-control-plane` public client, that
`http://localhost:4200/` and the production origin are exact allowlisted
redirect URIs, that the `organization` claim is in the access token, and that
the refresh grant works with `offline_access`. Each of those is a realm
configuration question, and the first person with a running Keycloak should walk
through them before trusting the sign-in path.

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
[ADR 0031]: ../../docs/adr/built/0031-http-api-conventions.md
[ADR 0035]: ../../docs/adr/partial/0035-angular-frontend-platform-and-design-system-adoption.md
