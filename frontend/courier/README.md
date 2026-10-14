# HorecaOS Courier

The app a courier carries on shift. An installable web app (PWA) on the platform's design system,
decided in [ADR 0179](../../platform/docs/adr/not-started/0179-the-courier-client.md): Angular on the
same toolchain as the storefronts, Keycloak Authorization Code with PKCE as a **public** client,
foreground location with a screen wake lock. No Flutter, no Telegram mini app.

## What exists today

| Screen | Route | What it does |
|---|---|---|
| Sign-in | `/auth/callback`, `/auth/error`, `/auth/signed-out` | The PKCE round trip, and the three ways it can end |
| Today | `/` | Who the platform says this courier is, their branches, the open shift and duty state, the registration status (`GET /api/v1/courier/tenants/{tenantId}/me`) |
| Profile | `/profile` | The account, the language, the app version, sign out |

Not built yet, in this order: shift open/close/break, the duty indicator and the telemetry sender
(with the courier transparency notice), then offers and deliveries. Every one of them calls the
platform's existing courier endpoints (`CourierShiftController`, `CourierDeliveryController`, the
`courier` OpenAPI group) -- this directory is the first client that does.

## Sign-in

`src/app/core/auth/`. Authorization Code + PKCE (S256) against the realm in `issuerUrl`, no client
secret anywhere. The discovery document's `issuer` must equal the configured one and its endpoints
must be on the issuer's origin; `state`, `nonce` and the PKCE verifier bind the callback to the
request that started it.

**Tokens are held in memory only** (AGENTS.md). Reloading the page loses them, and that is cheap:
the route guard sends the browser back to the authorization endpoint and Keycloak answers with a
new code at once, without a password prompt, while the SSO session lives. The client's session
lifetimes are set for a shift by `platform/infra/keycloak/create-courier-login-client.sh`. The only
thing kept across the redirect is the verifier/state/nonce of the login in flight, in
`sessionStorage`, single-use.

## Configuration

Read at start from `/config.json`, so one image runs everywhere. In the container it is written
from environment variables by `docker-entrypoint.sh`:

| Variable | Meaning | Default |
|---|---|---|
| `APP_API_BASE_URL` | API prefix, same-origin | `/api/v1` |
| `APP_ISSUER_URL` | The Keycloak realm, e.g. `https://auth.horecaos.uz/realms/horecaos` | `http://localhost:8081/realms/horecaos` |
| `APP_CLIENT_ID` | The public client | `horecaos-courier` |
| `APP_TENANT_ID` | The tenant this deployment's couriers work for | the local fixture tenant |
| `APP_DISPLAY_TIME_ZONE` | Zone times are shown in | `Asia/Tashkent` |

A missing or malformed value stops the app at start with a plain message in three languages; it
never falls back to a placeholder tenant.

## Run it

```bash
npm ci
npm start            # http://localhost:5200, /api proxied to localhost:8080
npm test             # Vitest, once
npm run build        # production, with the service worker
npm run lint && npm run lint:rules && npm run format:check && npm run check:tokens
```

Against a local stack (`make up` in `platform/`), create the Keycloak client once with
`platform/infra/keycloak/create-courier-login-client.sh`, then sign in as a courier account
provisioned from the operations console.

## Design system and languages

`src/design-system/tokens.css` is a verbatim copy of `frontend/design-tokens/tokens.css`;
`npm run check:tokens` (a CI step) fails if they differ, and ESLint and prettier ignore it because
it is where the closed type scale is defined. Use the `--q-*` tokens and `.q-*` type classes; a raw
`font-size: Npx` is a lint error, and so is a physical `margin-left` (ADR 0149).

Messages are `ru`, `uz-Latn` and `en`, split by feature area (`messages/<area>.<locale>.ts`).
`core` is always loaded; a screen's area is loaded by `messagesGuard('<area>')` on its route. A new
area needs its three modules, a line in `message-areas.ts` and one in `catalogue-loaders.ts`; the
other two locales are typed against English, so an untranslated key fails the build.

## What a PWA cannot do

It cannot sample location with the screen off, and it cannot receive a push with the app closed
until ADR 0020's push channel exists. The app therefore keeps a screen wake lock while a duty
session is open and polls for offers; both limits are stated in ADR 0179 and are the measured
reason a native client may one day replace this one.

## Bundle

`initial` warns at 350 kB, errors at 500 kB. Every screen is a lazy chunk. The service worker
caches the shell, never `/config.json` and never `/api/**`.
