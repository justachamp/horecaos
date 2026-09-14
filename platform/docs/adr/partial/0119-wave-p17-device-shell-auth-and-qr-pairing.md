# ADR 0119: Wave P17 — device-shell authentication, credential storage, and QR pairing display

- Decision status: Proposed
- Implementation status: Partial — `frontend/operations/src/app/device/`
  (`DeviceSession`: setup, the ADR 0079 pairing handshake, `client_credentials`
  token minting; `DeviceBoardApi`: the board read and the start/ready
  mutations) and `shared/ui/q-qr-code` (`qr-encode.ts`, a from-scratch
  ISO/IEC 18004 byte-mode encoder) are built and wired into the `/device`
  shell and the Kitchen → Devices pairing screen
  (`features/kitchen/devices-page.ts`), plus the payments `qrPayload` field
  (`features/finance/payments/payments-page.ts`). Not built: a backend
  device-scope-discovery ("whoami") endpoint (see Open inputs), QR versions
  beyond 5 / multi-block Reed–Solomon interleaving, and device secret
  rotation (ADR 0079's own open item, unchanged by this record). The QR
  encoder has not been verified against a physical scanner in this
  environment — see Consequences.
- Date proposed: 2026-09-14
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction
  of 2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0079, ADR 0062, ADR 0028, ADR 0035, ADR 0025
- Supersedes / Superseded by: — / —
- Open inputs: **Device scope discovery** — whether a future backend
  "whoami"/JWT-claims endpoint replaces the local, per-device manual setup
  step this wave builds, or manual entry stays the permanent answer (owner:
  Ayubkhon Abbosov, platform architecture — revisit if a chain onboarding
  many branches at once makes per-device manual setup a real operational
  cost, per this record's own Alternatives table). **Keycloak CORS/Web-Origins
  configuration** for the per-device confidential clients, allowing this
  frontend's own origin to complete the browser-side `client_credentials`
  POST ADR 0079 directs — this record assumes it is set, and has not
  verified it against a live Keycloak instance in this wave (owner: platform
  ops/production).

## Context

ADR 0079 built the backend half of a kitchen display's identity — enrol,
read the board, mark a line ready — and named plainly what it did not build:
"A kitchen device can enrol, read the board, and mark a line ready today"
was true of the API and false of any shipped client, a gap `2/X.2` (device
enrolment and registry) and `X/X.2` (the KDS fullscreen shell) name in the
operations gap map. This wave builds the first client. Three forces make its
shape non-obvious enough to record rather than leave to be read out of the
diff:

**ADR 0062 says the browser never talks to Keycloak — for a different
principal.** `environment.ts`'s own comment states it plainly: "this
application no longer talks to Keycloak at all," and `bearerTokenInterceptor`
exists to attach exactly one token, the signed-in staff member's, to every
same-origin request. ADR 0079's own Decision text, however, directs a device
to authenticate "directly against Keycloak's token endpoint with
`grant_type=client_credentials`, exactly like every other machine client
`KeycloakConfiguration` already wires up" — a deliberate, narrow exception
for a principal ADR 0062 was never written about. A frontend engineer who
only reads ADR 0062 could reasonably conclude the device shell must proxy
through the backend too; it must not, and without a record saying so, the
next person to touch `device/` has to reconstruct that reasoning from ADR
0079 alone.

**The existing HTTP stack is built for one principal, and a device is not
it.** `ApiClient`/`bearerTokenInterceptor` unconditionally attach whatever
`StaffTokenStore` holds to every same-origin request via `setHeaders`, which
*overwrites* any `Authorization` header already on the request. A KDS tablet
and a manager's own signed-in session can share one browser profile — the
laptop used once to approve the device, a support technician's machine — so
routing device traffic through the shared client risks a device's action
being silently re-attributed to whichever staff member happens to be signed
in on that browser: exactly the shared-credential failure ADR 0079 exists to
end, reproduced one layer up the stack rather than solved.

**A device's Keycloak `client_secret` is issued to the browser itself, with
nowhere else to live, and the platform's existing storage precedent is
tuned for the wrong threat model.** `StaffTokenStore`'s refresh token
deliberately lives in `sessionStorage`, not `localStorage`, specifically so
a stolen till tablet loses its session on reboot. A KDS is the opposite
case: a screen bolted to a wall for months, expected to resume after a power
cut with nobody physically present to re-type anything, whose grant is
already revocable server-side in one row the instant it needs to stop
working (ADR 0079). Applying the staff precedent here would make a routine
reboot into a support call.

**Nothing tells an enrolled device its own tenant/brand/location.** ADR
0079's Keycloak client carries no custom claim naming them, and the two
unauthenticated bootstrap endpoints (`begin`/`poll`) never learn the
approving manager's location either — only the manager's own
`KitchenDeviceController.approve` call, on the console side, knows it. Some
answer is needed for a device's very first board call, which is
tenant/brand/location-scoped in the URL path the same way every staff call
is.

## Decision

1. **Device authentication lives in its own `frontend/operations/src/app/device/`
   module, entirely outside `core/auth`.** It never imports and never reads
   `Auth`, `StaffTokenStore`, or `bearerTokenInterceptor`.
2. **Every network call the device module makes is a plain `fetch`, never
   Angular's `HttpClient`.** No interceptor written for a different
   principal — today's staff bearer, or a future one — can attach to,
   inspect, or override a device request, because none of them run on this
   path at all. `DeviceSession.accessToken()` mints its own token by POSTing
   `grant_type=client_credentials` straight to the `tokenEndpoint` the
   backend's `poll` response names (ADR 0079 step 4) — the one deliberate,
   narrowly-scoped exception to ADR 0062's "the browser never talks to
   Keycloak," reserved for exactly the principal ADR 0079 built that
   contract for.
3. **The device's Keycloak `client_id`/`client_secret`/`tokenEndpoint` are
   stored in `localStorage`, not `sessionStorage`** — the opposite of
   `StaffTokenStore`'s choice, deliberately: a KDS must resume unattended
   after a reboot, and its grant is revocable server-side in one row the
   moment it needs to stop working, so surviving a restart costs nothing the
   staff precedent's reasoning was protecting against.
4. **A device's tenant/brand/location is entered once, locally, by whoever
   installs it** (`DeviceSession.saveSetup`) — a plain setup step before
   enrolment begins, not fetched from any endpoint, because none names it.
   Recorded here as a real trade-off, not a hidden assumption — see Open
   inputs.
5. **`shared/ui/q-qr-code` renders a QR bitmap from a plain string,
   client-side, display only.** A from-scratch byte-mode ISO/IEC 18004
   encoder (error-correction level L, versions 1–5, 106 bytes maximum); a
   longer payload degrades to a text fallback rather than an incorrect code.
   It performs no scanning and no camera access — ADR 0079 keeps a pairing
   code typed, never scanned, and this component does not reopen that
   decision.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Proxy the device's `client_credentials` grant through the platform backend, the same shape ADR 0062 uses for staff sign-in | Needs a new backend endpoint outside this frontend-only wave's scope, and blurs a boundary ADR 0079 already drew deliberately: the device mints its own tokens directly, exactly like every other machine client `KeycloakConfiguration` wires up, with the platform never seeing or forwarding the secret after issuance | A future device class needs the backend to inspect or rate-limit the grant itself, beyond the two bootstrap endpoints ADR 0079 already covers |
| Reuse `ApiClient`/`bearerTokenInterceptor` for device calls, keyed off a second token store the interceptor checks first | Keeps one HTTP stack, but makes every future change to staff auth (the interceptor, the refresh flow, the same-origin check) a file a device-auth reviewer must also reason about, and reintroduces the exact "which token wins" ambiguity this record's Context names | Never, unless the interceptor chain is rebuilt around an explicit, per-request principal type rather than one global store |
| Store the device credential in `sessionStorage`, matching `StaffTokenStore` | Matches convention but is wrong for this principal: a KDS rebooted after a power cut would lose its credential and sit unusable until someone re-enrols it by hand — the opposite of what a wall-mounted device needs | Never for a fixed-location device; a future *mobile* device class might want the staff-tablet threat model back |
| A backend endpoint that resolves a device's own scope from its access token (a "whoami"), so no local setup step is needed | Real, and likely the right long-term answer, but a new backend endpoint outside a frontend-only wave — and ADR 0079's device JWT carries no tenant/brand/location claim today for such an endpoint to key off without a Keycloak protocol-mapper change | A chain onboarding many branches at once makes manual per-device setup a real operational cost (see Open inputs) |
| Support the full QR versions 1–40 × 4 error-correction levels, matching every commercial generator | Needs multi-block Reed–Solomon interleaving this wave does not build, for headroom neither of this wave's own two payloads (a short `userCode`, a provider checkout URL) needs | `P38`'s table QR cards, or a future `qrPayload`, need more than 106 bytes |

## Consequences

### Positive

- A KDS enrols, reads its board, and marks a line ready with no browser code
  path that can accidentally use, leak into, or be overridden by a staff
  member's own session — the isolation ADR 0079 designed for the backend now
  holds on the client too, and is exercised by `device-board-api.spec.ts`'s
  own "rejects a staff session" tests.
- The device's own secret never inherits `sessionStorage`/`localStorage`
  conventions built for a different threat model without a recorded reason;
  a future device class (VDU, EXPO — ADR 0041's own rollout step 4) inherits
  a working, documented pattern rather than staff auth's assumptions by
  default.
- `q-qr-code` gives the payments `qrPayload` field (`payments-api.ts:93`,
  previously dropped by the console entirely) and `P38`'s future table QR
  cards a renderer with no new npm dependency and no CDN script — this
  frontend's node_modules is a symlink onto a package-lock-pinned tree, and
  every external script an artifact loads elsewhere in this platform is
  CDN-allowlisted; neither route fits a library pulled in for one component.

### Negative

- **Two independent HTTP stacks now exist in this frontend** — the shared
  `ApiClient`/interceptor pipeline and the device module's raw `fetch`
  calls — so a future cross-cutting change (a new required header, a new
  correlation-id scheme) must be applied in two places, or an explicit
  decision made not to apply it to devices, rather than once.
- **A device's tenant/brand/location is operator-entered, unverified local
  state**, not a server-issued fact. A technician who mistypes it during
  setup gets a device that calls the wrong branch's board until someone
  notices — nothing ties the entered scope to where the device was
  physically approved, because nothing on the wire connects the two today.
- **The device's Keycloak `client_secret` sits in `localStorage`** —
  readable by any script that ever runs on this origin (an XSS bug, a
  compromised dependency) for as long as the device stays enrolled, with no
  rotation timer (ADR 0079's own open input, unchanged here). Revocation is
  fast — one row, next request — but does not by itself stop a secret
  already exfiltrated from being replayed against Keycloak's token endpoint
  directly until the Keycloak client is also disabled.
- **`q-qr-code`'s encoder has not been verified against a physical QR
  scanner in this environment** — no camera or reference decoder is
  available here. It is tested structurally (`qr-encode.spec.ts`: matrix
  dimensions, exact finder/timing-pattern shape, determinism, reactivity to
  input, the 106-byte ceiling) but real-world scannability on a printed
  table card or a manager's phone should be spot-checked with an actual
  device before this reaches production kitchen hardware.

### Accepted trade-offs

- Enrolling a device takes two local, human steps rather than one — typing
  its tenant/brand/location once, then the pairing handshake itself.
  Deliberate given the alternative is a new backend endpoint out of this
  wave's scope; see Open inputs.
- `q-qr-code` caps out at 106 bytes (QR versions 1–5, error-correction level
  L, byte mode only) and falls back to plain text past that, rather than
  implementing the multi-block Reed–Solomon interleaving the higher versions
  need.

## Specification

### `DeviceSession` (`device/device-session.ts`)

- `localStorage['horecaos.kds.setup']` — `{tenantId, brandId, locationId}`,
  written by `saveSetup`.
- `localStorage['horecaos.kds.credential']` — `{tokenEndpoint, clientId,
  clientSecret}`, written the first time `pollOnce` observes `APPROVED` with
  a credential (ADR 0079's own "claimed exactly once" property — a later
  poll of the same code carries none, so nothing here ever double-writes).
- `accessToken()` mints via `POST tokenEndpoint` (`grant_type=client_credentials`,
  `client_id`, `client_secret`, `application/x-www-form-urlencoded`), caches
  in memory only (a reload simply mints again), and refreshes 30 seconds
  ahead of the token's own `expires_in`. A 401/403 from Keycloak forgets the
  stored credential — the disabled-Keycloak-client half of an ADR 0079
  revoke reaching the device on its very next token mint.

### `DeviceBoardApi` (`device/device-board-api.ts`)

Two calls, both over `fetch` with `Authorization: Bearer <device token>`:
`GET .../kitchen/tickets?stream=live&limit=50` and
`POST .../kitchen/ticket-items/{itemId}/start|ready` (with a minted
`Idempotency-Key`, ADR 0031). Path strings are read from
`core/api/operations-paths.ts` (pure functions, no `ApiClient` dependency)
so this file and the staff console's own `kitchen-api.ts` cannot silently
disagree about where an endpoint lives. A 401/403 here (the grant itself
revoked, cache-evicted on the very next request per ADR 0079) drops the
shell back to its enrolment screen.

### `q-qr-code` / `qr-encode.ts` (`shared/ui/`)

See `qr-encode.ts`'s own doc comment for the full algorithm notes (mode
indicator, Reed–Solomon over GF(256) with primitive polynomial `0x11D`, a
fixed mask 0 rather than the "best of eight" penalty search — any of the
eight standard masks, correctly declared in the 15-bit format-information
field, produces an equally valid symbol). `encodeQrMatrix(text: string):
QrMatrix` throws `QrCapacityExceededError` past 106 bytes; `<q-qr-code
[value]="…" [size]="…" [label]="…" />` renders it as an inline SVG, pure
black on pure white regardless of light/dark theme (a scanner's own
binarisation step needs that contrast, not this console's palette).

## Rollout and rollback

Additive only — a new top-level route (`/device`) and a new shared
component; nothing existing changes shape. Rollback is deleting the route
and leaving `iam.device_principals` rows exactly as ADR 0079's own rollback
already describes: revoke every enrolled device's grant and leave the tables
in place as evidence.

## Implementation checklist

- [x] `device/device-session.ts` — setup, the ADR 0079 pairing handshake
      (`beginEnrolment`/`pollOnce`), `client_credentials` token minting,
      `localStorage` credential storage
- [x] `device/device-board-api.ts` — board read, start/ready, over `fetch`
      with the device's own bearer
- [x] `device/device-shell.ts` — the three-state fullscreen shell (setup /
      enrol / board), the offline banner (`navigator.onLine` +
      `online`/`offline` events), touch-scale CSS
- [x] `shared/ui/q-qr-code` — byte-mode encoder, versions 1–5, EC level L
- [x] Kitchen → Devices pairing screen
      (`features/kitchen/devices-page.ts`) — list / approve-by-typed-user-code
      / revoke-with-reason, `KITCHEN_STATION_MANAGE`
- [x] `q-qr-code` wired into the payments `qrPayload` field
- [ ] Not built: a backend device-scope-discovery ("whoami") endpoint —
      Open inputs
- [ ] Not built: QR versions beyond 5 / multi-block Reed–Solomon interleaving
- [ ] Not built: device secret rotation UI (ADR 0079's own open input,
      unchanged here)
- [ ] Not verified: `q-qr-code`'s output against a physical QR scanner —
      Consequences

## Exit criteria

An unenrolled device, given its branch's identifiers once, displays a user
code and a QR rendering of it; a manager on Kitchen → Devices approves it by
typing that code, never scanning it; the device claims its Keycloak
credential exactly once and, from then on, reads its board and marks lines
ready using only that credential — even in a browser profile that also
holds a live staff session, which the device module never reads, references,
or can be overridden by; going offline shows the banner within one
`online`/`offline` event without losing the enrolled credential; and
revoking the device from Kitchen → Devices stops it within one board poll.

## References

- [ADR 0079: Kitchen display device principal and enrolment](../partial/0079-kitchen-display-device-principal-and-enrolment.md)
- [ADR 0062: Staff sign in inside the platform](../built/0062-staff-sign-in-happens-inside-the-platform.md)
- [ADR 0028: Secrets management and credential lifecycle](../partial/0028-secrets-management-and-credential-lifecycle.md)
- [ADR 0035: Angular frontend platform and design system adoption](../partial/0035-angular-frontend-platform-and-design-system-adoption.md)
- [ADR 0025: Fine-grained authorization and the capability model](../built/0025-fine-grained-authorization-and-capability-model.md)
- `frontend-information-architecture.md` PART 2 §2 (Kitchen — device shell), PART 4 "Template-level gaps" and "QRCode display + DataMatrix scan input"
- `docs/operations-gap-map.md` rows `X/X.2`, `2/X.2`, `X.35`
