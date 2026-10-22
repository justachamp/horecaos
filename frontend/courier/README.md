# HorecaOS Courier

The app a courier carries on shift. An installable web app (PWA) on the platform's design system,
decided in [ADR 0179](../../platform/docs/adr/partial/0179-the-courier-client.md): Angular on the
same toolchain as the storefronts, Keycloak Authorization Code with PKCE as a **public** client,
foreground location with a screen wake lock. No Flutter, no Telegram mini app.

## What exists today

| Screen | Route | What it does |
|---|---|---|
| Sign-in | `/auth/callback`, `/auth/error`, `/auth/signed-out` | The PKCE round trip, and the three ways it can end |
| Today | `/` | Who the platform says this courier is, their branches, the open shift and duty state, the registration status (`GET .../me`) |
| Offers | `/offers` | The offers the courier can take at the shift's branch, polled every 15 s; accept or decline with the courier's position; the customer's address where the tenant allows it before acceptance |
| Deliveries | `/deliveries`, `/deliveries/:id` | What the courier carries and has finished; one delivery with its steps: arrived, picked up, the cash confirmed, handed over, the customer's address behind an audited tap |
| Shift | `/shift` | Open a shift at a branch, start and end a break, close it behind a confirmation, declare the cash handed over; beneath it, the duty card: go on duty, with the location notice, the wake lock and the limit of a PWA said in words |
| Location notice | `/notice` | What is collected, when, who sees it, for how long. **A marked draft until the tenant's wording replaces it**; accepting is recorded on the platform |
| Profile | `/profile` | The account, the language, the app version, sign out |

While a duty session is open an indicator sits under the top bar on every screen and says in
words whether the position is really arriving.

Not built, and not code's to build: acceptance on a real handset, the pre-production Keycloak
client and a courier account (`platform/docs/runbooks/external-connections/w4-courier-client.md`),
the notice's final wording, push for offers (ADR 0020), and the pickup and handover endpoints the
gate contract names, which another wave builds (ADR 0179, "The gates").

## Sign-in

`src/app/core/auth/`. Authorization Code + PKCE (S256) against the realm in `issuerUrl`, no client
secret anywhere. The discovery document's `issuer` must equal the configured one and its endpoints
must be on the issuer's origin; `state`, `nonce` and the PKCE verifier bind the callback to the
request that started it.

**Tokens are held in memory only** (AGENTS.md). Reloading the page loses them, and that is cheap:
the route guard sends the browser back to the authorization endpoint and Keycloak answers with a
new code at once, without a password prompt, while the SSO session lives. That session is the
realm's -- 30 minutes idle and 10 hours at most at the defaults; Keycloak refuses a client
lifetime longer than the realm's -- and an app in the foreground on duty keeps it alive by
refreshing, so a phone locked on a long break signs in again and a working shift does not (ADR
0179 puts the choice of raising it with the owner). The only thing kept across the redirect is the
verifier/state/nonce of the login in flight, in `sessionStorage`, single-use.

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
| `APP_CURRENCY` | The ISO 4217 code couriers are settled in; sent when a shift opens and closes | `UZS` |

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
provisioned from the operations console (the account has no password until Keycloak's own flow
sets one).

## Design system and languages

`src/design-system/tokens.css` is a verbatim copy of `frontend/design-tokens/tokens.css`;
`npm run check:tokens` (a CI step) fails if they differ, and ESLint and prettier ignore it because
it is where the closed type scale is defined. Use the `--q-*` tokens and `.q-*` type classes; a raw
`font-size: Npx` is a lint error, and so is a physical `margin-left` (ADR 0149).

Messages are `ru`, `uz-Latn` and `en`, split by feature area (`messages/<area>.<locale>.ts`).
`core` is always loaded; a screen's area is loaded by `messagesGuard('<area>')` on its route. A new
area needs its three modules, a line in `message-areas.ts` and one in `catalogue-loaders.ts`; the
other two locales are typed against English, so an untranslated key fails the build.

## How the app is put together

`core/api` (`ApiClient`, `IntentKeys`: one idempotency key per intent, kept across a request that
never arrived), `core/courier` (`CourierApi`, every call of the courier surface, and the `/me`
store), `core/device` (location, wake lock and battery behind injection seams), `core/telemetry`
(`TelemetrySender`, `DutyStore`), `core/notice`, `core/shift`, `core/offers`, `core/deliveries`
(`DeliveriesStore`, `classifyStepFailure`, `stepMessage`), `core/work` (`WorkSession`). Screens are
in `features/`. Specs run the real stores over a fake platform and a fake handset
(`src/testing/work-harness.ts`, `fake-device.ts`): nothing in `core/` is replaced but `CourierApi`,
the phone's location and its wake lock.

Every step a courier takes answers in one of five ways and the screens branch on the way, never on
a sentence: it worked (a soft gate's unconfirmed checks are a warning, not a failure), a hard gate
refused it (`409 GATE_FAILED`, the failed checks named), a rule refused it (`422` with a `reason`,
twelve of them, each with a sentence in three languages), it changed under the courier (reloaded),
or it did not arrive.

## What a PWA cannot do

It cannot sample location with the screen off, and it cannot receive a push with the app closed
until ADR 0020's push channel exists. The app therefore keeps a screen wake lock while a duty
session is open and polls for offers; both limits are stated in ADR 0179 and are the measured
reason a native client may one day replace this one.

## Bundle

`initial` warns at 350 kB, errors at 500 kB. Every screen is a lazy chunk. The service worker
caches the shell, never `/config.json` and never `/api/**`.
