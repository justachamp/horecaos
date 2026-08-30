# HorecaOS storefront

The customer-facing Angular app: browse a brand's published menu, build a
basket, check out, and track an order. One build serves any tenant/brand —
see [Runtime configuration](#runtime-configuration) — and it talks to the
HorecaOS platform API only; there is no backend of its own.

## Running against the platform locally

The storefront has nothing to show without the platform API running behind
it. From the `platform/` directory (see `platform/AGENTS.md` and
`platform/CLAUDE.md` for the full picture):

```bash
cd platform
make up    # Postgres, Kafka, Keycloak
make run   # starts the API on :8080, with the `local` Spring profile
```

`make run` activates fixture data via the `local` profile — see
[`platform/docs/local-fixtures.md`](../../platform/docs/local-fixtures.md) for
the authoritative reference. The parts this storefront cares about:

| Resource | Value |
| --- | --- |
| Tenant | `10000000-0000-0000-0000-000000000001` |
| Brand | `10000000-0000-0000-0000-000000000002` |
| Location | `10000000-0000-0000-0000-000000000003` |
| Channel | `STOREFRONT` |
| Currency | `UZS` |

These are exactly the values already committed in `public/config.json`, so a
fresh checkout needs no edits to run against a local `make run`.

### Starting the storefront

```bash
npm ci
npm start        # ng serve, on http://localhost:5000
```

`proxy.conf.json` forwards `/api/*` to `http://localhost:8080`, which is
where `make run` listens — so `apiBaseUrl` in `public/config.json` can stay
`/api/v1` (same-origin from the dev server's point of view) without CORS
configuration on either side.

### Signing in

There is no password. Sign-in is a phone number and an SMS code, and `make
run`'s `local` profile configures one fixed number that needs no real SMS
gateway:

| Setting | Value |
| --- | --- |
| Phone | `+998 00 000 00 00` |
| Code | `000000` |

Enter that phone number on the storefront's own login screen and `000000`
when asked for the code. Any other number goes down the real SMS route and
fails locally with `NO_PROVIDER_BINDING` — there is no gateway bound in a
local environment. This preset only exists because the `local` Spring profile
activates it; see `local-fixtures.md` for why it cannot reach a real
deployment.

### The local payment caveat: CASH only

Checking out with `CLICK` or `PAYME` opens an online payment session
(`PaymentSessionService.open`, `POST .../orders/{orderId}/payment-sessions`)
and hands the browser to a Click or Payme checkout page. The request carries
a `returnUrl` — where the provider sends the browser back — and the
platform's own validation requires it to start with `https://`
(`PaymentSessionRequest.returnUrl`, `^https://.*`). `PaymentSessionService`
derives that URL from `window.location.origin`, and `ng serve`'s origin is
plain `http://localhost:5000`.

**That means CLICK and PAYME cannot complete their round trip in local
development.** The payment-session request itself will be refused by the
platform with `VALIDATION_FAILED` the moment it is attempted from a non-https
origin. This is not a bug to work around locally: it is what the platform is
supposed to do with a return address that cannot possibly be reached back
securely.

**Use `CASH` for every local checkout.** It has no online session, no
`returnUrl`, and completes the same order flow — `checkout()` → order
`CONFIRMED` — without ever needing this storefront to be served over https.
A deployment that wants to exercise CLICK/PAYME end-to-end needs to be served
over https (a tunnel such as ngrok, or a real deployment) so that
`window.location.origin` is itself `https://…`.

## Runtime configuration

`ApiClient` never reads `environment.ts` for its API base URL — see
`src/app/core/config/app-config.ts` and `load-config.ts`. Everything
tenant-specific is read from `/config.json` **at runtime**, before the
Angular application bootstraps (`src/main.ts`), and a missing or malformed
file fails loudly rather than silently defaulting to a placeholder tenant.
That is what makes one build deployable against any tenant: rebuild for a
new brand and you no longer need a rebuild, only a different `config.json`.

For local development that file is the committed `public/config.json`, read
directly by the dev server. For the Docker image (`Dockerfile`), the same
file is generated at **container start** by `docker-entrypoint.sh`, which
runs `envsubst` over `config.template.json` using these environment
variables:

| Variable | Default | Maps to |
| --- | --- | --- |
| `APP_API_BASE_URL` | `/api/v1` | `apiBaseUrl` |
| `APP_TENANT_ID` | the local fixture tenant | `tenantId` |
| `APP_BRAND_ID` | the local fixture brand | `brandId` |
| `APP_DEFAULT_LOCATION_ID` | the local fixture location | `defaultLocationId` |
| `APP_CHANNEL` | `STOREFRONT` | `channel` |
| `APP_YANDEX_MAPS_API_KEY` | the shared browser key already in `public/config.json` | `yandexMapsApiKey` |

The defaults reproduce the same fixture tenant `public/config.json` already
holds, so `docker run` with no environment variables set behaves like local
dev. Point the same image at a different tenant by overriding the `APP_*`
variables at `docker run` / in your orchestrator, with no rebuild:

```bash
docker build -t horecaos-storefront .
docker run -p 8080:80 \
  -e APP_API_BASE_URL=https://api.example.com/api/v1 \
  -e APP_TENANT_ID=<tenant-uuid> \
  -e APP_BRAND_ID=<brand-uuid> \
  -e APP_DEFAULT_LOCATION_ID=<location-uuid> \
  -e APP_CHANNEL=STOREFRONT \
  -e APP_YANDEX_MAPS_API_KEY=<key> \
  horecaos-storefront
```

## Development server

```bash
npm start
```

Opens on `http://localhost:5000` (see `angular.json`), and reloads on
source changes. `proxy.conf.json` sends `/api/*` to `http://localhost:8080` —
start the platform first (see above), or every request fails at the first
hop.

## Building

```bash
npm run build
```

Compiles to `dist/qoida-storefront-jizbiz/browser`. The production
configuration (the default) enables output hashing and enforces the bundle
budgets in `angular.json`.

## Tests

```bash
npm test
```

Runs the Vitest-based unit-test builder (`@angular/build:unit-test`). Every
spec file under `src/app/**/*.spec.ts` runs; there is no separate e2e suite.

## Docker image

```bash
docker build -t horecaos-storefront .
docker run -p 8080:80 horecaos-storefront
```

Two stages: `npm ci && npm run build` in a `node:22-alpine` builder, then the
built `browser/` output served by `nginx:alpine`. `nginx.conf` rewrites every
path to `index.html` for client-side routing and disables caching on
`index.html` itself, so a release is picked up immediately rather than held
by a stale cached shell — see the comment in `nginx.conf` for why that
matters for the Telegram Mini App specifically. See
[Runtime configuration](#runtime-configuration) above for how the image is
pointed at a tenant.

## Code scaffolding

```bash
ng generate component component-name
```

See `ng generate --help` for the full list of schematics Angular CLI ships.
