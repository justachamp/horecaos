# External connections: the courier client

[ADR 0179](../../adr/partial/0179-the-courier-client.md). The courier app is built and tested
against facts only the owner, the tenant or a phone can supply. Each section below is one
such fact: what exists today, where the real value goes, and how to prove the connection.
Nothing here is a secret; the Keycloak client is public by design.

## 1. The courier transparency notice (the tenant's and counsel's wording)

**Why it matters.** ADR 0045's rollout makes this document the precondition for switching
telemetry ingest on: a self-employed courier is told what is collected, when, who can see it
and for how long (30 days), and the app shows a visible on-duty indicator whenever collection
is active. The tenant is the controller; the platform supplies the mechanism.

**What exists.** The courier-self endpoints that would collect a position exist
(`POST .../duty-sessions`, `POST .../telemetry/observations`, `courier.telemetry`) and no client
calls them yet. The shift tells its session when a break starts and when the shift ends, so
collection cannot outlive a shift. The versioned notice screen, its placeholder text and the
recorded acknowledgement that must precede a courier's first duty session arrive with client
part 2; **nothing is collected from a real courier before that and before the owner confirms
the wording.**

**Where the value goes.** The notice text is versioned in the client (one catalogue entry per
language and version). Replacing the placeholder with the agreed wording is a text change and a
version bump; a courier who acknowledged an earlier version is asked again.

**Prove it.** A courier on a handset sees the notice in their language before going on duty
for the first time, cannot go on duty without acknowledging it, and the acknowledgement is
recorded with the version. Until then the duty-session endpoint stays unused.

**Open input (owner: Ayubkhon Abbosov, to obtain from the tenant and counsel):** the statutory
basis for courier location processing under ZRU-547 and confirmation of the 30-day retention
(ADR 0045, open inputs). A narrower answer moves two ADR 0030 values, the collection gate and the
track retention days, and nothing else.

## 2. A real handset and the pre-production environment

**What exists.** The app is built and its sign-in is proven against a fake identity provider
that enforces PKCE (the specs) and against a real Keycloak 26.7.0 driven by a script with a
real user (the round trip in ADR 0179). Geolocation is exercised in specs by emulation, not on a
phone. Offers will be polled, not pushed.

**What is missing, and where each value goes.**

| Fact | Where it goes |
|---|---|
| A DNS name for the courier origin, e.g. `courier.staging.horecaos.uz` | the DNS zone, pointing at the host; the certificate is Caddy's (automatic) |
| The Keycloak client `horecaos-courier` for exactly that origin | created by `platform/infra/keycloak/create-courier-login-client.sh` (below) |
| `HORECAOS_COURIER_ORIGIN` (and `HORECAOS_COURIER_TENANT_ID` if the couriers work for a tenant other than the storefront's pilot tenant) | `/etc/horecaos/production.env` |
| A courier account | provisioned from the operations console (courier registration creates the Keycloak account and its organization membership; **it sets no password**, see step 2b) |
| A phone with GPS | the owner's |

**Connect it, in this order.**

1. Point DNS at the host.
2. Create the client. On the host, from the ops container, exactly as the bootstrap runbook
   runs `assign-service-account-roles.sh` (Keycloak publishes no port; the public auth origin
   refuses `/admin/*`):

   ```bash
   read -rsp 'Keycloak admin password: ' HORECAOS_KEYCLOAK_ADMIN_PASSWORD; echo
   docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env \
     run --rm --no-TTY \
     --volume "$(pwd)/platform/infra/keycloak:/keycloak:ro" \
     -e HORECAOS_KEYCLOAK_URL=http://keycloak:8080 \
     -e HORECAOS_KEYCLOAK_ADMIN_PASSWORD \
     -e HORECAOS_COURIER_ORIGINS=https://courier.staging.horecaos.uz \
     ops bash /keycloak/create-courier-login-client.sh
   unset HORECAOS_KEYCLOAK_ADMIN_PASSWORD
   ```

   It prints `==> Verified: public, PKCE S256, exact redirects: https://courier.staging.horecaos.uz/auth/callback`.
   It refuses a wildcard, an http origin, and a run that names localhost and a real origin
   together, so a mistake stops here.
2b. Give the one test courier a first password. Registration creates the account without one
   and no flow to set it exists yet (`CourierAccountProvisioningService` says so; the console
   never displays, derives or transmits a credential, couriers.md §18, and a courier with no
   work email cannot use a reset link). For the pre-production acceptance only: in the Keycloak
   admin console, realm `horecaos`, Users, the courier, Credentials, Set password with
   *Temporary* on, and give it to the courier in person; Keycloak makes them choose their own at
   first sign-in. How a real courier gets a first password at scale is an open input (ADR 0179).
3. Set `HORECAOS_COURIER_ORIGIN=https://courier.staging.horecaos.uz` in the environment file
   and `docker compose up -d`. Until it is set the edge serves nothing for the courier app
   (the compose default is an address nothing can reach).
4. Ask the owner whether to change how long a courier stays signed in. At the realm's defaults
   it is 30 minutes idle and 10 hours at most; the app in the foreground on duty never idles
   out. Changing it changes every account's session (ADR 0179, open inputs).

**Prove it.**

- `curl -s https://courier.staging.horecaos.uz/config.json` shows the public auth origin's realm
  as `issuerUrl`, the client `horecaos-courier` and the tenant id.
- On the phone, open the URL, sign in with the courier account, land on Today with the
  courier's label and branches. A tenant administrator's account and an account of another
  tenant must see "This account is not a courier here".
- `https://api…/api/v1/courier/tenants/<tenant>/me` with that courier's token answers 200; the
  same call from the courier origin's path for any non-courier prefix answers 404.
- Install the app to the home screen: it opens standalone.
- (Part 2 and after) go on duty with the screen on; the dispatcher's map shows the pin within
  ten seconds and loses it within the hour of the shift ending; lock the phone and note the
  gap, which ADR 0179 says is the limit of a PWA.

**Rollback.** Delete the Keycloak client (every courier session ends), unset
`HORECAOS_COURIER_ORIGIN`, and `docker compose up -d`. Close every duty session to stop
collection at the source (ADR 0045).

## 3. A push channel for offers (optional)

**What exists.** Nothing pushes to the courier app. Offers are polled while the app is open;
`offerTtl` defaults to 60 seconds, so a courier with the app closed learns of an offer when
they next open it, and it may have lapsed.

**Where the value goes.** ADR 0020's delivery channels do not yet include web push for the
courier app. When one exists it is an ADR 0026 installation (a provider key and its
reference), enrolled per courier device from the app, and the app keeps polling as the
fallback ADR 0045 requires of every live surface.

**Prove it.** An offer made to a courier whose app is closed raises a notification on their
phone and opens the app on the offer. Until then the polling path is the whole of the
mechanism, and the dispatcher's board is the place a lapsed offer is seen.
