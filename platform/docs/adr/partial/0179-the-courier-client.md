# ADR 0179: The courier client

- Decision status: Accepted — proposed by Claude (batch 21); accepted by the platform owner 2026-10-14
- Implementation status: Partial — the client exists and signs a courier in, and the platform
  finally has the courier's own surface a client needs; the screens that make a courier
  useful are not built. `frontend/courier` is an Angular 21 PWA with Authorization Code +
  PKCE as a public client (`OidcClient`, `AuthService`: discovery with the issuer and
  endpoint-origin checks, `state`, `nonce`, RFC 9207 `iss`, single-flight refresh, tokens in
  memory only), the shell, a Today screen fed by `GET /api/v1/courier/tenants/{tenantId}/me`,
  a profile, ru / uz-Latn / en catalogues split by area, an installable service-worker shell
  whose update is offered and never applied behind the courier's back, a Dockerfile and nginx
  with a runtime `config.json`, the `horecaos-courier` entry in the publish matrix and a
  `frontend-build` leg with its token-drift step. The platform has a `courier` OpenAPI group of
  its own (`OpenApiSurface.COURIER`, its baseline and generated client), `CourierSelfController`
  (`GET .../me`: branches, open shift, duty state, registration status),
  `CourierSelfDutyController` (the courier's own duty session and observation batch, over the
  new `telemetry.api.CourierDutyPort`), the capabilities `courier.self.read` and
  `courier.telemetry`, and the tie ADR 0045 stated and nothing built: a shift now tells its duty
  session when a break starts and ends and when the shift ends, whoever ends it
  (`DutySessionLifecyclePort`). `platform/infra/keycloak/create-courier-login-client.sh` creates
  the public client with exact redirects, run twice and a full PKCE round trip proven against
  a real Keycloak 26.7.0. What is not built: shift open/close/break screens, the duty indicator
  and the telemetry sender with its wake lock (client part 2), the courier transparency notice
  and the recorded acknowledgement that gate switching ingest on (part 2; the wording is
  external), offers and deliveries (part 3), real-handset acceptance and a pre-production
  Keycloak client and courier account (external), and a push channel for offers (ADR 0020), so
  offers are polled.
- Date proposed: 2026-10-14
- Date decided: 2026-10-14
- Deciders: proposed by Claude (batch 21); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0003, ADR 0014, ADR 0020, ADR 0025, ADR 0029, ADR 0030, ADR 0031,
  ADR 0035, ADR 0042, ADR 0045, ADR 0049, ADR 0055, ADR 0057, ADR 0066
- Supersedes / Superseded by: — (answers four records without editing them: ADR 0035's
  "a courier application … belongs to whatever ADR 0042 settles", which ADR 0042 did not;
  ADR 0022's `apps/courier` line, already superseded by ADR 0035; and the courier half of the
  open input ADR 0057 and ADR 0066 left. Each carries a dated status addition pointing here.
  ADR 0045's text is unchanged; its rollout says telemetry ingest ships last, behind the
  notice, and this record keeps that order.)
- Open inputs: each is closed on its proposed default if the owner accepts the record as
  written; the ones that name an external fact stay with their owner and the work they block
  is marked.
  - **How long a courier stays signed in** (platform owner). Keycloak refuses a client session
    lifetime longer than the realm's (measured 2026-10-14: "Client session idle timeout cannot
    exceed realm SSO session idle timeout"), so a courier's session is the realm's: 30 minutes
    idle, 10 hours maximum. While the app is in the foreground on duty it never idles out (the
    token refresh and the ten-second batches keep the session alive); a phone left locked for
    half an hour on a break signs in again, and a shift longer than ten hours signs in once.
    Proposed default: change nothing. Raising the realm's values changes every account's
    session, staff included, and is the owner's call; the script's optional shorter overrides
    exist for the opposite wish. Blocks: nothing.
  - **How a courier gets a first password** (platform owner). Courier registration creates the
    Keycloak account and its organization membership and sets no password; no flow to set one
    exists, and a courier with no work email cannot use a reset link
    (`CourierAccountProvisioningService`: "the courier mobile app's own onboarding, which does not
    exist yet"). Proposed default: for the pre-production acceptance the owner sets a temporary
    password in the Keycloak admin console for the one test courier; a real onboarding flow (a
    one-time link by SMS, ADR 0020) is a later record. Blocks: onboarding more than a handful of
    couriers, not the build.
  - **The courier transparency notice** (tenant and counsel, owner: Ayubkhon Abbosov to
    obtain). ADR 0045 makes it the precondition for switching ingest on. Proposed default:
    client part 2 ships a versioned notice screen with placeholder text and a recorded
    acknowledgement, and the courier-self endpoints here are not called by any client until
    it exists. Blocks: switching telemetry on for a real tenant.
  - **Pre-production wiring and a real handset** (platform owner). A phone with GPS, the
    `horecaos-courier` client created in the pre-production realm
    (`HORECAOS_COURIER_ORIGINS` set to the real origins), a courier account, a DNS name and
    certificate for the courier origin. Proposed default: built against the fake
    identity provider and browser geolocation emulation in the specs;
    `docs/runbooks/external-connections/w4-courier-client.md` says what connects it.
    Blocks: acceptance on a handset, not the build.
  - **Whether a native client replaces the PWA** (platform owner). Proposed default: no,
    until the gap the Decision names is measured on real couriers. See the revisit triggers.

**Decision record, 2026-10-14.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "accept all defaults, start batch 21" (batch 21); each decision below is taken at the value the owner chose or, where the owner said nothing else, at the smallest default the batch-21 plan proposed.

## Context

Gap-map row `3.9` has been `PARTIAL` since batch 10 for one reason that is not a missing
screen: **nothing calls the courier's surface.** Batch 18 built the offers, the deliveries,
the acceptance and status-change gates, the customer-location reveal and the cash
confirmation under six capabilities, plus the courier's own shift endpoints, and every one of
them is refused or answered correctly to a caller that does not exist. `mobile/` is the
customer ordering app and is on hold (ADR 0055); ADR 0022 named `apps/courier` as "optional,
built only after courier scope is approved" and was superseded by ADR 0035, which withdrew the
courier surface from its own scope and left it to "whatever ADR 0042 settles"; ADR 0042 settles
pay, shifts and settlement and does not say what a courier carries. No accepted record names
the platform, where it lives, how it signs in, or how it is located.

Two things the code showed that no record had. First, a courier **could not post a position
at all**: `CourierDutyController` (duty sessions and the observation batch) declares
`courier.duty.manage` at a location scope, a staff capability, and a courier holds no grants
(ADR 0049), so the batch endpoint ADR 0045 designed for the courier's handset was reachable
only by a dispatcher. Its own class comment says the courier's path would be "a second
controller with its own declaration, not a widening of this one", and nothing was built.
Second, the courier's shift endpoints had no read: a client could not learn its own branches
or whether a shift was open.

And one that the first live run of the new Keycloak script showed: the realm bounds how long
any client's session can be (Specification, "Signing in").

The location problem is the one that makes the platform choice non-obvious. ADR 0045
collects a courier's position inside a duty session, at a server-set cadence of ten seconds.
A web application is given a position only while its page is visible. The Screen Wake Lock
API keeps the page visible; nothing keeps it running when the courier presses the power
button, answers a call or opens a map in another app. A native application can ask for
background location; a PWA cannot, and no amount of engineering here changes that.

## Decision

**D7. The courier client is an Angular PWA in `frontend/courier`, on the platform design
system, signing in with Keycloak Authorization Code + PKCE as a public client, collecting
location in the foreground under a screen wake lock. There is no Flutter courier app and no
Telegram mini app for couriers.**

1. **Where and what.** `frontend/courier`, Angular 21 with the builder, Vitest runner and
   zoneless signals the storefronts use, its own `package.json` and lockfile, built into
   `horecaos-courier` and served by nginx with a runtime `config.json` (issuer, client id,
   tenant, API prefix), the same shape as the other frontends. The design tokens are the
   canonical `frontend/design-tokens/tokens.css`, vendored and diffed in CI like
   control-plane's; the closed type scale and logical-properties rules apply. Messages are
   ru, uz-Latn and en, split by area and loaded by route. Every screen is a lazy chunk.
2. **Sign-in is the identity provider's.** Authorization Code + PKCE (S256) as the public
   client `horecaos-courier`, created by `create-courier-login-client.sh` with **exact**
   redirect URIs (`<origin>/auth/callback`), exact post-logout URIs and exact web origins for
   each real origin; no wildcard; no secret; no implicit or password flow; no role claims in
   the token, because a courier is authorized by owning a courier row (ADR 0049) and never by
   a role. Tokens are held in memory only (AGENTS.md); a reload costs a redirect that
   Keycloak answers without a password while the SSO session lives. Platform sessions (ADR
   0062) are the staff consoles' way and are not extended to couriers.
3. **The courier surface is its own OpenAPI group**, `courier`, `/api/v1/courier/**`, with its
   own baseline and generated client, the way ADR 0066 moved `platform-admin` once
   `frontend/control-plane` consumed it. The paths leave the `operations` group; nothing in
   `frontend/operations` called them (grepped before the move).
4. **The courier's own backend surface is completed**, all `CourierSelfAuthorized` and none
   delegable to a manager: `GET .../me` under `courier.self.read`; the duty session (read,
   open, close) and the observation batch under `courier.telemetry`, the second controller ADR
   0045 promised, in `courier` rather than `telemetry` because the declaration it carries is
   `courier`'s and an import the other way would close a module cycle. It reaches
   `telemetry` through `CourierDutyPort`, where a reading goes in once and nothing comes back
   out. The collection gate is not the courier's to choose: it resolves at the branch (ADR
   0030).
5. **A duty session opens from a shift and ends with it.** ADR 0045 said so and only the
   first half was built (`CourierShiftPort` refuses an open without a shift). Now the shift
   tells its session in the same transaction: a break suspends collection, its end resumes
   it, and closing the shift closes the session, whether the courier, a manager or the
   sweeper closes it. There are no break endpoints on the duty session, because a second
   place to say "I am on break" is a second way for the two to disagree.
6. **Foreground location with a wake lock; the limitation is stated, not hidden.** While a
   duty session is open the app keeps a screen wake lock, shows the visible on-duty
   indicator ADR 0045 requires, and sends buffered readings at the cadence the platform
   returns on every response. **A PWA cannot sample location with the screen off.** A courier
   who locks the phone or switches app produces a gap in the track and a stale pin; the
   platform already treats a stale or coarse fix as absent rather than approximate (ADR
   0045), and no figure that pays a courier is computed from the track, so a gap costs the
   courier nothing and costs dispatch visibility. The app says so on the screen that asks
   for the wake lock. Native background tracking is deferred until the gap is measured.
7. **Offers are polled.** Push to the courier app is ADR 0020's channel and is not built;
   `offerTtl` defaults to 60 seconds, so the poll interval is a number part 3 chooses and
   tests against that.
8. **Order of work.** Telemetry ingest ships last (ADR 0045's rollout): the sender, and the
   recorded acknowledgement of the transparency notice that must precede it, are client
   part 2; the endpoints exist here so part 2 has something to call, and no client calls
   them before then.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A Flutter courier app in `mobile/` | It would sample location in the background, which is the one thing a PWA cannot do. But `mobile/` is the customer app and on hold (ADR 0055); a courier app is a second Flutter product with its own store listings, review and release cycle, on a team of one; and nothing yet says the gap matters, because no figure that pays a courier reads the track | A measured share of courier shifts where the foreground gap defeats dispatch (stale pins past the ADR 0045 staleness window while a courier carries an order), or the owner takes `mobile/` off hold |
| A Telegram mini app for couriers | A mini app is a web view with the same location limits, plus a second identity (a Telegram account, ADR 0063's customer machinery) that Keycloak does not own, and the platform's audit and revocation would then depend on a messenger. The customer-side Telegram work does not transfer: customers sign in with a phone code, couriers have an organization membership | Never for location or sign-in. Telegram stays a notification channel (ADR 0020) |
| The React/Vite `apps/courier` ADR 0022 named | Superseded by ADR 0035: one framework, one design system, shared tooling | Never |
| Courier screens inside the operations console | AGENTS.md keeps courier workflows a separate surface unless a product decision combines them. The sessions differ (staff platform sessions, ADR 0062, against Keycloak SSO), the audience's device and connectivity differ, and the console's initial bundle sits at its own budget | A product decision to merge surfaces |
| Platform sessions for couriers (the backend exchanges a password with Keycloak, ADR 0062) | Needs a courier sign-in page and session endpoints that issue a second kind of credential, for a user who has none of the staff consoles' reasons (a shared till, a PIN). Authorization Code + PKCE is the standard AGENTS.md prescribes for frontends and gives single sign-on for free | Keycloak's own login page proves unusable on cheap handsets in testing |
| Persist the refresh token (storage or `offline_access`) so a courier signs in once a month | AGENTS.md forbids persisting tokens; a personal phone is lost and shared more often than a till. The cost is the session lifetime above, and it is a configuration the owner can lengthen at the realm | The owner accepts a device-bound credential for this client, ideally with a passkey |
| A courier-self controller inside `telemetry` | It closes a module cycle: `telemetry` would import `courier.api` for the self-authorization declaration while `courier` already imports `telemetry.api`. Moving the declaration to a neutral module would touch the imports of every courier controller other waves are editing | The declaration moves to a shared module for another reason |
| A tenant-agnostic `GET /me` that lists a courier's workplaces | Nothing yet has a courier serving two tenants (a courier-service tenant is ADR 0164, not started), and it would be the only courier endpoint without the tenant in its path (ADR 0031). The tenant is configuration of the deployment | A courier genuinely works for more than one tenant on one handset |
| Let the courier app send its own `courierId` as the staff batch does | The one parameter through which a courier could post as somebody else. The courier is the token's subject and nothing else | Never |

## Consequences

### Positive

- Row `3.9` gets its call site: every courier endpoint built in batch 18 and before now has a
  client that can reach it, and part 3 is the call site "no call site = `PARTIAL`" was waiting
  for.
- A courier can be tracked at all. Before this, the only way a position reached the platform
  was a dispatcher opening a session on a courier's behalf, which ADR 0045 says is exactly what
  must not be how collection starts.
- Collection cannot outlive the shift. A courier who closes the shift, a manager who ends
  service and the sweeper that closes a shift left open overnight all end the session; before
  this, none of them touched it and a pin stayed on the dispatcher's map.
- The courier surface has a contract of its own: its own baseline, its own generated client,
  and a membership test that already proves the groups partition the paths.
- One toolchain, one design system, one CI shape; the PWA installs from a URL, with no store.

### Negative

- **A PWA cannot sample location with the screen off.** Tracks have gaps and pins go stale
  whenever a courier locks the phone or leaves the app; the wake lock drains the battery the
  dispatcher is watching. This is the largest cost of the choice and it is stated here and on
  the screen, not discovered in the field.
- **No push.** A courier with the app closed learns of an offer when they next open it; the
  offer may have lapsed (60 seconds). Until ADR 0020 has a push channel, dispatch relies on
  the app being open on duty.
- **A courier's session is the realm's**, 30 minutes idle and 10 hours maximum: a locked phone
  on a long break signs in again, and nothing short of raising the realm's values for every
  account changes it.
- Tokens in memory mean every reload is a round trip to the identity provider. The identity
  provider is therefore on the courier's critical path in a way it is not for the staff
  consoles.
- The frontends stay split across Angular 21 (both storefronts, courier) and 22 (operations,
  control-plane); the README already named this debt and a new app on the older line widens
  it by one. It moves with the storefronts.
- A courier's coordinate now crosses a module boundary: `courier` hands a reading to
  `telemetry` through `CourierDutyPort`. It goes in and nothing returns one, every request type
  prints as nothing, and the port's class comment says so; but `telemetry.api` used to be a
  package that carried no coordinate and no longer is.
- The courier's endpoints leave the `operations` OpenAPI group, so
  `api/generated/horecaos-api-v1.operations.ts` loses fourteen operations it carried. Nothing
  consumed them; a future consumer of that client would have to know.
- The staff `CourierDutyController` still says its batch is exempt from the mandatory
  `Idempotency-Key` while `@RequiresCapability(mutating = true)` in fact requires it
  (`IdempotencyInterceptor.requiresIdempotency`). Nothing calls it, so nothing broke; it is
  recorded here rather than silently fixed, because changing a staff endpoint's contract is
  not this record's to do. The courier batch, which will be called every ten seconds, really is
  exempt, with a narrow exact-path exemption in `EndpointCapabilityDeclarationTests` and a test
  that it still declares its authorization.

### Accepted trade-offs

- Dispatch visibility of couriers is best-effort, not continuous, until a measured gap
  justifies a native client. ADR 0045's own rule makes this safe: nothing that pays a courier
  reads the track, and a stale or coarse position is absent, not approximate.
- A deployment serves one tenant's couriers (the tenant is in `config.json`). A second tenant
  is a second container, as with the storefront.
- The self endpoints exist before the notice does. Nothing calls them until client part 2
  ships the notice and its acknowledgement, and the ingest rollout stays last; the gap is a
  matter of sequence inside one wave, not of production.

## Specification

### Signing in

`horecaos-courier`: public, standard flow only, `pkce.code.challenge.method = S256`, exact
redirect URIs `<origin>/auth/callback`, exact web origins, exact post-logout redirect URIs
(`<origin>/auth/signed-out`), `fullScopeAllowed = false`, default scopes `basic profile roles
web-origins acr` (no `email`: the app shows a name and a login, never an address), optional
`organization`, and an audience mapper adding `horecaos-api` to the access token. The script
refuses a non-https origin that is not a localhost development server, a wildcard, a path, a
duplicate, and a run that names localhost and real origins together (a localhost redirect on a
realm serving real couriers is one an attacker can satisfy on their own machine); it reads the
client back and fails if it is not public, carries a secret, enables another flow, has a
wildcard, or lost PKCE. Re-running converges: an override dropped from the environment is
removed from the client.

Measured against Keycloak 26.7.0 on 2026-10-14: the callback carries `code`, `state`,
`session_state` and `iss` (RFC 9207); a wrong `code_verifier` is `invalid_grant`; the access
token is `aud = horecaos-api`, `azp = horecaos-courier`, scope `openid profile`, no
`realm_access` or `resource_access`, 300 seconds; the identity token carries the `nonce`, a
name and a login and no email; a refresh works; an unregistered `redirect_uri` and an
unregistered post-logout redirect are refused with 400; a request without a code challenge is
answered with `error=invalid_request`; and CORS allows the registered origin on the token
endpoint. A client session lifetime longer than the realm's is refused by Keycloak, which is
why none is set by default.

### The courier surface (`courier` OpenAPI group)

All under `/api/v1/courier/tenants/{tenantId}`, all `CourierSelfAuthorized`; the courier is the
token subject within the path's tenant and an account that is nobody's courier there (a tenant
administrator, a courier of another tenant) is answered 404.

| Method and path | Capability | Notes |
|---|---|---|
| `GET /me` | `courier.self.read` | label, engagement status and registration validity, bound branches (main first), open shift with duty state; `Cache-Control: no-store`; no legal name, number or telephone |
| `GET /brands/{b}/locations/{l}/duty-session` | `courier.telemetry` | the courier's open session at this branch or a null session; carries the cadence and the batch ceiling |
| `POST …/duty-sessions` | `courier.telemetry` | `Idempotency-Key`; `{deviceId}`; refused 409 `NO_OPEN_SHIFT` or `REGISTRATION_LAPSED`; opening twice returns the open session; the gate resolves at the branch |
| `POST …/duty-sessions/{id}/closures` | `courier.telemetry` | `Idempotency-Key`; only the courier's own open session, otherwise 404 |
| `POST …/telemetry/observations` | `courier.telemetry` | no courier id in the body; exempt from the key (natural key); 422 `NO_OPEN_DUTY_SESSION` with nothing stored, including a batch for another branch's path; 202 with `suspended` during a break; `nextBatchAfterSeconds` on every answer |
| the shift, break, close, cash-declaration, offer and delivery endpoints | as built | unchanged by this record |

Capabilities `courier.self.read` and `courier.telemetry` are in no role bundle
(`PlatformRoleTests.NON_STAFF_RELATIONSHIP_AUTHORIZED`).

### The shift tells its session

`CourierShiftService` calls `DutySessionLifecyclePort` in the shift's own transaction:
`breakStarted` suspends an open session, `breakEnded` resumes a suspended one, `shiftEnded`
closes an open or suspended one with end reason `SHIFT_ENDED`, audited with the capability the
close used (`courier.shift.open` for the courier, `courier.shift.approve` for a manager,
`courier-shift-auto-close` for the sweeper). No session is the ordinary case and a no-op; a
session the courier already signed off keeps its own end reason.

### The app

`src/app/core/auth` (`pkce.ts`, `oidc-client.ts`, `auth.service.ts`, `auth.guard.ts`),
`core/api` (ADR 0031: `Idempotency-Key` on every mutation, `If-Match` as a weak validator,
problem details, one renewal and one retry on a 401, the bearer sent only to the platform),
`core/courier` (the `/me` store), `core/i18n`, `layout`, `features/{auth,home,profile}`.
`frontend/courier/README.md` is the reference.

## Rollout and rollback

Merge, and CI builds and publishes `horecaos-courier`; a host that does not set
`HORECAOS_COURIER_ORIGIN` serves nothing for it. To enable on a real environment: create the
client with the real origins (`HORECAOS_COURIER_ORIGINS=https://courier.… create-courier-login-client.sh`
on the Keycloak host), point DNS and set the origin and tenant in the environment file, and
provision a courier account from the operations console. Telemetry stays off by sequence
until client part 2 and the notice. Rollback: close every duty session (collection stops at the
source, ADR 0045), delete the Keycloak client (every courier session ends), and stop the
`courier-web` service; the platform endpoints are inert without a client.

## Implementation checklist

- [x] Decide where the courier client lives, how it signs in and how it is located.
- [x] `frontend/courier` scaffold: PKCE sign-in, shell, Today, Profile, ru / uz-Latn / en,
      installable shell, Dockerfile and nginx, publish matrix, CI leg.
- [x] The `courier` OpenAPI group, its baseline and generated client.
- [x] `GET .../me`, the courier's own duty session and observation batch, the two capabilities.
- [x] The shift tells its duty session about breaks and its own end.
- [x] `create-courier-login-client.sh`, run against a real Keycloak and a full PKCE round trip.
- [ ] Client part 2: shift open/close/break, the duty indicator, the wake lock, the telemetry
      sender at the server's cadence, the versioned transparency notice and its recorded
      acknowledgement (wording is external; placeholder meanwhile).
- [ ] Client part 3: offers (polled), accept and decline with position, my deliveries,
      advance, the customer-location reveal, payment confirmation, the gate refusals by
      reason.
- [ ] Pre-production: the Keycloak client, a courier account, DNS, and acceptance on a real
      handset with GPS (external).
- [ ] A push channel for offers (ADR 0020).

## Exit criteria

A courier signs in on a phone with their own Keycloak account, opens a shift, goes on duty,
takes an offer and delivers it with their position measured by the gates, and the dispatcher
sees them on the map only while they are on duty and only until the shift ends; the app tells
the courier, in words, what it cannot do with the screen off; and every one of those calls is
a call the `courier` OpenAPI group documents.

## References

- [ADR 0003: Keycloak and tenant authorization](../built/0003-keycloak-tenant-authorization.md)
- [ADR 0014: Scheduled delivery sourcing and partner orchestration](../partial/0014-scheduled-delivery-sourcing-and-partner-orchestration.md)
- [ADR 0020: Notification preferences, templates and delivery](../partial/0020-notification-preferences-templates-and-delivery.md)
- [ADR 0022: Frontend platform, authentication and journey migration](../not-started/0022-frontend-platform-authentication-and-journey-migration.md)
- [ADR 0025: Fine-grained authorization and capability model](../built/0025-fine-grained-authorization-and-capability-model.md)
- [ADR 0030: Configuration and policy resolution](../built/0030-configuration-and-policy-resolution.md)
- [ADR 0031: HTTP API conventions](../built/0031-http-api-conventions.md)
- [ADR 0035: Angular frontend platform and design system adoption](../partial/0035-angular-frontend-platform-and-design-system-adoption.md)
- [ADR 0042: Courier compensation, shifts and settlement](../partial/0042-courier-compensation-shifts-and-settlement.md)
- [ADR 0045: Real-time operational push and field telemetry](../partial/0045-realtime-operational-push-and-field-telemetry.md)
- [ADR 0049: Non-staff principal authorization](../built/0049-non-staff-principal-authorization.md)
- [ADR 0055: Greenfield launch scope](../meta/0055-greenfield-launch-scope.md)
- [ADR 0057: OpenAPI per-surface document groups](../built/0057-openapi-per-surface-document-groups.md)
- [ADR 0062: Staff sign-in happens inside the platform](../built/0062-staff-sign-in-happens-inside-the-platform.md)
- [ADR 0066: `platform-admin` joins the control-plane OpenAPI surface](../built/0066-platform-admin-joins-the-control-plane-openapi-surface.md)
- [ADR 0164: A courier service tenant](../not-started/0164-a-courier-service-tenant.md)
