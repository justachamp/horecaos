# ADR 0079: Kitchen display device principal and enrolment

- Decision status: Accepted
- Implementation status: Partial — `V0192` creates `iam.device_principals` and
  `iam.device_enrolment_requests`; `iam.application.devices.DeviceEnrolmentService`
  implements the pairing-code enrolment and revocation flow behind
  `iam.api.devices.DeviceEnrolmentPort`; `iam.infrastructure.keycloak.KeycloakDeviceClientProvisioner`
  provisions and disables the per-device Keycloak service-account client;
  `iam.web.DeviceEnrolmentController` carries the two unauthenticated bootstrap
  endpoints; `kitchen.application.KitchenDeviceService` and
  `kitchen.web.KitchenDeviceController` carry the capability-gated approve,
  list and revoke surface under `kitchen.station.manage`; `PlatformRole.KITCHEN_DEVICE`
  is the narrow bundle a device is granted. A kitchen device can enrol, read the
  board, and mark a line ready today. Not built: any device class other than the
  one kitchen display this ADR names (`KITCHEN_KDS`) — a VDU or an expo screen
  under ADR 0041's own rollout step 4 would reuse this primitive with its own
  role bundle, not reopen this one; per-action attribution below the device
  (badge or PIN); and automatic secret rotation on a timer (rotation today is
  manual, through revoke-and-re-enrol). See
  [Implementation checklist](#implementation-checklist).
- Date proposed: 2026-09-09
- Date decided: 2026-09-09
- Deciders: Ayubkhon Abbosov (platform architecture), security
- Depends on: ADR 0025, ADR 0027, ADR 0028, ADR 0031, ADR 0033, ADR 0041, ADR 0045
- Supersedes / Superseded by: — / —
- Open inputs: Device secret rotation cadence and whether an enrolled device
  should be periodically re-attested rather than trusted indefinitely once
  enrolled (owner: Ayubkhon Abbosov, platform architecture — revisit before a
  multi-location pilot puts more than a handful of devices in the field).
  Whether VDU and EXPO device classes (ADR 0041's other two) reuse
  `iam.device_principals` with their own capability bundles or need a shape
  this ADR has not anticipated (owner: platform architecture, deferred to ADR
  0041's rollout step 4 — not structural: it adds an enum value and a role, it
  does not change this decision). Per-action attribution below the device —
  a badge tap or a PIN before a mutating action — is named in Consequences as
  a rejected-for-now alternative with its own revisit trigger (owner: product).
- Closed inputs: A device is not a person, and no attempt is made to make it
  read as one — see the Consequences section rather than a closed input, because
  the honest answer is a trade-off recorded, not a question resolved. A device
  is enrolled by a location-scoped human capability, never by a platform-wide
  one (2026-09-09, this record).

## Context

ADR 0025 built the platform's whole authorization model around one shape of
principal: a person who authenticated to Keycloak and holds capability grants
tied to their own subject. ADR 0049 later added three non-human or
non-staff-role shapes — a customer's platform session, a partner's confidential
client, a courier's own relationship — but every one of those still answers
"who caused this" with a name that traces back to exactly one person or one
external counterparty accountable for their own actions.

A kitchen display has none of that. It is a screen bolted to a wall, or a
tablet propped against a shelf, in a room full of people who each touch it
during a shift. ADR 0041 built the whole production-ticket aggregate this
screen exists to run — stations, routing, release, `kitchen.ticket.advance` —
and named the gap explicitly rather than guessing past it: "Kitchen devices
authenticate as devices, with a location-scoped grant and a bound station
filter: a tablet left on a counter signed in as the branch manager is an
unrevocable credential carrying a manager's capability set." ADR 0045 named the
same gap from the transport side, while sketching the shape an answer should
take: "A wall-mounted kitchen display has no person at it, so it authenticates
as a device principal enrolled through the OAuth 2.0 device authorization
grant: a manager approves a device code from their own session, the display
holds a refresh token bound to a per-device principal with a `LOCATION`-scoped
grant, and the console revokes it."

Neither ADR built it. Today a kitchen screen has exactly two options, and both
are wrong in a named way. It signs in as a real staff member's Keycloak
account, which is the "unrevocable credential carrying a manager's capability
set" ADR 0041 refuses, and which the manager cannot even see is still logged in
six months later on a screen in a kitchen. Or it has no principal at all, which
means every mutating endpoint it would need — `kitchen.ticket.advance` above
all — is unreachable, because ADR 0025 requires a capability grant behind every
mutation and there is no grant to hold.

This is a model change, not an endpoint. `iam` currently has one notion of
"principal": a Keycloak subject that traces to a person, checked by
`AuthenticatedActor`/`JwtCurrentActor` and authorized by `iam.grants` keyed on
that subject. `NonStaffPrincipal` (ADR 0049, ADR 0051) is the other shape
already in the codebase, and it is the wrong template for this problem by its
own declared contract: "It confers nothing... a customer principal carries no
global roles and no organization roles, so it satisfies no ADR 0003 tenant
rule and holds no ADR 0025 capability." A kitchen device is the opposite case —
it needs to hold real, if narrow, ADR 0025 capabilities, checked the ordinary
way, at a real scope.

Three platform decisions constrain the answer before any is written:

- **AGENTS.md**: "Use Keycloak as the identity and access-management platform
  ... Do not implement a second password or token issuer in the application."
  A device credential has to be a Keycloak credential.
- **AGENTS.md**: "Use separate Keycloak clients for each frontend, the Java
  resource server, Camel service accounts, and other machine clients." A
  device is exactly the "other machine clients" case this line already
  anticipates.
- **ADR 0028**: secrets are references, never values, and never reach git,
  logs, or an audit fact. A device's credential is exactly a secret by this
  definition, and it is unusual among this platform's secrets in one way: it
  is minted for, and eventually handed to, the thing that will hold it,
  rather than to an operator who types it into a form.

## Decision

**A device is a principal in the ADR 0025 sense — it authenticates to Keycloak
and holds real `iam.grants` rows — provisioned as its own confidential
Keycloak service-account client, never as a person's account and never as a
shared credential.**

**Identity.** Enrolling a device creates a dedicated Keycloak client
(`serviceAccountsEnabled = true`, `standardFlowEnabled = false`,
`directAccessGrantsEnabled = false`, confidential) whose service-account user
is the device's own, permanent, single-purpose Keycloak subject. That subject
is what `iam.grants.principal_subject` stores for the device, exactly the
column every staff grant already uses. Nothing in `AuthorizationService`,
`JdbcAuthorizationService`, `ResourceScopeVerifier`, `@RequiresCapability`, or
the ADR 0033 grant cache changes: a device's token is validated the same way a
staff token is, and its capability check runs the same query against the same
table. This is deliberate — the platform gets a whole new principal class by
adding rows to `iam.device_principals` and one enum constant to
`PlatformRole`, not by adding a second authorization code path next to the one
`EndpointCapabilityDeclarationTests` and `ModularArchitectureTests` already
guard.

**Enrolment is a pairing-code handshake, not a password, and not Keycloak's
raw device authorization grant either** — the raw RFC 8628 flow authenticates
the token as *the approving user*, which reproduces the exact shared-credential
failure this ADR exists to end (see Alternatives). The shape instead:

1. An unenrolled device calls `POST /api/v1/control-plane/kitchen/device-enrolments`
   with no credential at all — there is nothing to hold one yet — naming only
   the device class it wants to become (`KITCHEN_KDS`, the only one this ADR
   builds) and an optional cosmetic label (a hostname, never trusted for
   anything). The platform returns a long, opaque `deviceCode` (stored only
   hashed, the ADR 0040 `expected_value_hash` idiom) for the device to poll
   with, and a short, human-typeable `userCode` for a person to read off the
   screen, both expiring in ten minutes.
2. A location manager — holding `kitchen.station.manage` at that branch's
   `LOCATION` scope, from their own already-authenticated console session —
   types the `userCode` and a display name into
   `POST .../kitchen/devices/enrolments/{userCode}/approve`. This is the "a
   manager approves a device code from their own session" ADR 0045 already
   decided. Approval provisions the Keycloak client, inserts the
   `iam.device_principals` row scoped to exactly that location, and grants
   `PlatformRole.KITCHEN_DEVICE` — `{kitchen.ticket.read, kitchen.ticket.advance}`
   and nothing else — at that `LOCATION` scope. It writes one ADR 0027
   `SECURITY`-class audit fact naming the manager, the device, the location,
   and the reason.
3. The device keeps polling `POST .../device-enrolments/{deviceCode}/poll`.
   The **first** poll to observe `APPROVED` receives the Keycloak `client_id`
   and a freshly generated `client_secret` and, in the same transaction, spends
   the enrolment request — a conditional `UPDATE ... WHERE status = 'APPROVED'`
   that exactly one caller can win, the same idiom `CustomerVerificationService`
   already uses to redeem a one-time grant exactly once. No later poll, no log
   line, and no database row ever holds that secret again: the device is the
   only place it exists outside Keycloak itself.
4. From then on the device authenticates directly against Keycloak's token
   endpoint with `grant_type=client_credentials`, exactly like every other
   machine client `KeycloakConfiguration` already wires up, and calls the
   ordinary `kitchen.ticket.read` / `kitchen.ticket.advance` endpoints with the
   bearer token it gets back. No new HTTP surface exists for reading the board
   or marking a line ready — `KitchenBoardController`'s existing endpoints are
   the whole of what a device calls once enrolled.

**Revocation is not optional and is not a courtesy call to the device.**
`POST .../kitchen/devices/{deviceId}/revoke`, same `kitchen.station.manage`
capability, does two things: it revokes the device's `iam.grants` row through
the ordinary `GrantManagementService.revoke` path — cache-evicted immediately,
the same mechanism that already makes a revoked staff grant stop working on
the next request rather than at token expiry — and it disables the Keycloak
client so no new token can ever be minted for it again. The first is what
actually stops the device; the second is belt-and-suspenders against a token
that happened to be minted moments before. A lost or stolen screen is unusable
within one request, not within an access token's remaining lifetime.

**Scope.** A device principal is always `LOCATION`-scoped. There is no code
path that can enrol one at `TENANT`, `BRAND`, or `PLATFORM` scope —
`iam.device_principals.location_id` is `NOT NULL`, the approve command
constructs `ResourceScope.location(...)` directly rather than accepting a
scope type from the caller, and the composite foreign key
`(tenant_id, brand_id, location_id) REFERENCES tenant.locations (tenant_id, brand_id, id)`
is the same ancestry constraint `kitchen.stations` already carries. Combined
with `ResourceScopeVerifier` — already wired into every `LOCATION`-scoped
`@RequiresCapability` check — a device's grant cannot reach a sibling branch
even if the caller lies about the path, which is exactly the property
`KitchenBoardController`'s own isolation test already proves for a staff
principal and now proves for a device too.

**What a device may do, precisely.** `PlatformRole.KITCHEN_DEVICE` holds
`kitchen.ticket.read` and `kitchen.ticket.advance` and nothing else — reading
the board and starting or readying a line. It does not hold
`kitchen.ticket.recall`, `kitchen.ticket.release`,
`kitchen.ticket.release.override`, `kitchen.ticket.handover`,
`kitchen.station.manage`, `location.service-state.change`,
`delivery.manual_assign`, or `iam.grant.manage` — every one of the "four
dangerous powers on a kitchen screen" ADR 0041 already separated out.
`LOCATION_STAFF`, the human line-cook bundle, holds a strictly larger set
(the two kitchen capabilities above it, plus `order.place`, `customer.read`,
`dinein.session.manage`, and more); a device holds a strict subset of a
subset. This is deliberately the narrowest bundle that lets a screen do its
one job — mark a ticket ready — and no other.

**Enrolment and revocation share one capability, not a new one.**
`kitchen.station.manage` already gates "the head chef's own kitchen
configuration" (station layout and routing) at `LOCATION` scope, held by
`LOCATION_MANAGER`, `TENANT_ADMIN`, and `TENANT_OWNER`. Its own Javadoc already
named the gap this closes: "Not named by ADR 0041, which puts station and
device creation on a control-plane path without saying what guards it."
Enrolling and revoking a device is the same class of decision, made by the
same person, at the same scope, for the same reason a routing rule is —
physical configuration of one kitchen — so this ADR extends that capability's
Javadoc rather than minting `kitchen.device.manage` beside it. A new
capability earns its place only when a different person should hold it; no
argument here says one should.

**Grant mechanics reuse `GrantManagementService` exactly, never a parallel
insert.** Approval calls `grantSystemInitiated` — the same seam
`TenantOwnerAuthorityGrantorAdapter` already uses for ADR 0009's onboarding
step — because the capability decision was already made by
`@RequiresCapability(kitchen.station.manage, LOCATION)` on the approve
endpoint, and what happens next is a fixed, non-negotiable consequence (always
exactly `kitchen-device` at exactly the approved location) rather than an open
choice a granter is making. The alternative — requiring the approver to also
hold `iam.grant.manage` and calling the escalation-checked `grant` path —
would lock branch managers out of approving their own kitchen's devices,
since `iam.grant.manage` is a tenant-scoped power today's bundles reserve for
`TENANT_OWNER` and `TENANT_ADMIN`.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keycloak's raw OAuth 2.0 device authorization grant, used literally | The token that results authenticates *the person who approved it*, not a distinct device — a manager who approves a device code is handing the screen a token that is indistinguishable from their own. Every action from that screen would misattribute to the manager exactly the way a shared login does today, which is the failure ADR 0041 names and this ADR exists to end | Never for this shape. It remains the right primitive for a case where the device genuinely acts *as* the approving user, which this is not |
| One shared "kitchen device" Keycloak client or realm role, reused by every screen in a tenant | Indistinguishable devices and unrevocable individually — disabling it disables every kitchen screen the tenant owns at once, which is a worse blast radius than the shared-manager-login problem it would replace | Never. The whole value of a device principal is that one screen is one row |
| A static API key issued from the control plane, like a webhook secret, with no polling handshake | Still needs an out-of-band channel to reach the device, and skips the "a manager approves a device code from their own session" evidence ADR 0045 already decided — an API key pasted into a screen's settings has no session behind it to attribute the approval to. Harder to rotate too: rotating it means physically touching the screen with the new value, where a revoke-and-re-enrol here is the same operator flow either way | A device class exists that genuinely cannot poll — offline-first hardware with no outbound connectivity at enrolment time |
| Mutual TLS client certificates per device | No certificate-issuance or distribution infrastructure exists on the ADR 0034 one-box topology, and building one is a disproportionate answer to "a tablet needs a credential" | The platform already runs a PKI for another reason and the marginal cost of a device profile is near zero |
| Per-action human attribution: a badge tap or a short PIN before every mutating action | Reintroduces exactly the "a screen holds a person's credential" problem the enrolment mechanism exists to remove, just at a shorter interval and a lower-entropy secret (a 4-digit PIN shared among a shift is not meaningfully stronger evidence than "the device that was on the counter") | A dispute or a compliance finding shows that "which device" is provably insufficient evidence for a specific class of kitchen action, and the cost of a badge reader at the pass is judged worth it |
| Leave the gap open: no device principal, and the kitchen screen keeps signing in as a person | The status quo ADR 0041 and ADR 0045 both already named as the failure — an unrevocable credential carrying a manager's full capability set, sitting on a counter | Never. This is the record that closes the gap both of those ADRs left it in |
| Grant a device role at `BRAND` or `TENANT` scope, so one enrolment covers every branch | Convenient for a chain onboarding many branches at once, and exactly wrong: a device that can mark tickets ready at every location in a tenant from a screen bolted to one counter is a bigger blast radius than the shared-manager-login problem, not a smaller one | Never for a screen with a fixed physical location. A future device class with no fixed location (a courier's own handset, already covered by ADR 0042/ADR 0045's different principal shape) is a different problem |

## Consequences

### Positive

- The two named gaps close in the shape both ADRs already sketched: ADR 0041's
  "Kitchen devices authenticate as devices, with a location-scoped grant" and
  ADR 0045's "authenticates as a device principal enrolled through the [device
  authorization] grant... the console revokes it" are both now true rather
  than aspirational.
- A lost or stolen screen is revoked in one action, immediately — the same
  property ADR 0041 already claimed for the model it had not yet built: "A
  device is revocable in one row, which a shared manager login never was."
- No new authorization code path. A device is authorized by the exact same
  four ADR 0025 checks, the same cache, the same `ResourceScopeVerifier`, and
  the same `EndpointCapabilityDeclarationTests` gate as every staff principal,
  which is what makes this a small, reviewable change rather than a second
  security model living beside the first.
- A device's capability ceiling is enforced by the platform rather than by
  operator discipline: it is architecturally incapable of recalling a ticket,
  releasing a held order, dispatching a courier, or suspending a branch,
  regardless of who last used the console to approve it.

### Negative

- **A device's action names a device, never a person, and that is a genuine,
  accepted weakening of the evidence ADR 0027 records.** `KitchenBoardController`'s
  `ticket-items/{itemId}/ready` endpoint records `currentActor.get().subject()`
  as the actor; for a device that subject is the device's own Keycloak
  identity, not whichever cook's hand was on the screen. "Device X at Branch Y
  marked item Z ready" is the evidentiary ceiling for a shared kitchen
  screen, and if two cooks share one KDS across a shift, the trail cannot
  distinguish between them. This is *better* than the status quo it replaces
  — a shared manager login would misattribute every action to one named
  person who may never have touched the screen, which is a false positive,
  strictly worse than an honest "a device, not a person" — but it is *not* as
  strong as a personally-authenticated staff action on the same endpoint. The
  cheap mitigation actually taken here: the device's Keycloak `client_id` is
  minted with a `kds-device-` prefix and `iam.device_principals` is a real,
  queryable table keyed on the same `principal_subject` every `ticket_events`
  and audit-fact actor field already carries, so a reader who needs to know
  "was this a device" can resolve it with one join rather than guessing from
  the shape of a subject string. What is not done, and is named rather than
  hidden: no per-action badge or PIN, which is the only way to recover
  individual attribution on a shared screen, and doing so would recreate the
  exact "a screen holds a credential" failure this ADR exists to remove — see
  the Alternatives row above and its revisit trigger.
- **`iam` now provisions and disables live Keycloak clients on a request
  path**, not only at deployment time. Enrolment approval and revocation both
  now depend on Keycloak's Admin API answering, where before that dependency
  existed only for tenant onboarding. The ordinary ticket-advance path a
  device uses every few seconds during service is unaffected — it only
  touches `iam.grants` and JWT validation, both already load-bearing — but the
  rarer approve/revoke path is a new failure mode worth naming: a Keycloak
  outage during service means a manager cannot enrol a replacement screen or
  revoke a lost one until it recovers, though every already-enrolled device
  keeps working.
- **A new, narrowly-scoped Keycloak service-account credential**
  (`horecaos-device-provisioning`, holding only `manage-clients`) is added
  beside the two ADR 0009 already operates, rather than widening
  `horecaos-provisioning` to cover it. That is the least-privilege choice —
  `assign-service-account-roles.sh`'s own comment already records that
  `horecaos-provisioning` "deliberately excludes... manage-clients" — but it
  is a third credential to rotate, store, and audit, on a platform ADR 0034
  already runs with one operator.
- **Two new unauthenticated HTTP endpoints** — the enrolment begin and poll
  calls — are a new pre-authentication attack surface, the same class ADR
  0015's OTP challenge and ADR 0063's Telegram sign-in code already are. It is
  mitigated the same way those are: a rate limit per caller through ADR 0033,
  a hashed `deviceCode`, a short expiry, and a `userCode` space wide enough
  that guessing it inside the ten-minute window is not practical — but it is a
  cost, not a non-issue, and worth a security review's attention the same way
  those two already got one.
- **Device secret rotation is manual today.** There is no timer that expires
  or rotates a device's Keycloak client secret; a device is trusted
  indefinitely once enrolled, exactly as a staff refresh token is trusted
  until it is revoked. Recorded as an open input rather than solved here.

### Accepted trade-offs

- A device enrolled today can only ever be `KITCHEN_KDS`. VDU and expo are
  named in ADR 0041 and deliberately not built here — this ADR builds the
  primitive one device class proves works, not a taxonomy for classes that do
  not exist in code yet. Extending `DevicePrincipalClass` for either is an
  enum value and a role, not a redesign, and is left to ADR 0041's own rollout
  step 4.
- Enrolment needs a human physically at the console *and* physically able to
  read the code off the new screen in the same window — normally the same
  person doing both, but a chain that ships screens to a branch ahead of
  training staff to enrol them will find the ten-minute window a real
  constraint, not a formality. That is deliberate: a longer window is a longer
  window for someone to guess or intercept a `userCode`.

## Specification

### Physical model

```text
iam.device_principals
  id, tenant_id, brand_id, location_id                -- LOCATION scope, always
  device_class                                          -- 'KITCHEN_KDS' only, closed check
  display_name
  keycloak_client_internal_id, keycloak_client_id       -- Keycloak's own id, and the client_id string
  principal_subject                                     -- the service-account user id; what iam.grants stores
  status                                                 -- ACTIVE | REVOKED
  enrolled_by, enrolled_at
  revoked_by null, revoked_at null, revoked_reason null  -- all three or none, the V0127 pairing
  version, timestamps
  unique(keycloak_client_id), unique(principal_subject)
  fk (tenant_id, brand_id, location_id) -> tenant.locations (tenant_id, brand_id, id)

iam.device_enrolment_requests
  id, device_code_hash, user_code                        -- device_code is never stored in the clear
  requested_class, requested_label null
  status                                                  -- PENDING | APPROVED | CLAIMED | DENIED | EXPIRED
  tenant_id null, brand_id null, location_id null         -- filled in on approval
  device_principal_id null                                -- filled in on approval
  approved_by null, approved_at null, claimed_at null, denied_reason null
  expires_at, version, timestamps
  unique(device_code_hash)
  unique(user_code) where status = 'PENDING'
```

No secret value is ever stored. `device_code_hash` is a peppered hash of an
opaque value the device alone holds until it claims its credential;
`user_code` is short and human-facing but is spent (moved off `PENDING`) the
moment it is approved, so a guessed or overheard code is worthless once
enrolment completes. The Keycloak client secret itself is never written to
this database at all — Keycloak is its only system of record, and the device
is the only other place it exists.

### Ports

```java
// iam.api.devices
interface DeviceEnrolmentPort {
    EnrolmentBeginResult beginEnrolment(BeginEnrolment command);
    EnrolmentPollResult poll(String deviceCode);
    DevicePrincipalView approve(ApproveEnrolment command, String approverSubject);
    void revoke(UUID devicePrincipalId, String revokerSubject, String reason);
    List<DevicePrincipalView> list(UUID tenantId, UUID locationId);
}
```

`approve` performs no capability check of its own — the caller (kitchen's
`KitchenDeviceController`) has already been authorized by
`@RequiresCapability(kitchen.station.manage, LOCATION)` before this method is
reached, the same trust boundary `GrantManagementService.grantSystemInitiated`
already draws for `TenantOwnerAuthorityGrantorAdapter`. `roleCode` is a
parameter of `ApproveEnrolment` rather than hard-coded inside `iam`, which is
what lets a future VDU or expo enrolment reuse this port with its own role
without `iam` learning what a VDU is.

### Testing

- A device enrolled at one location is denied `kitchen.ticket.advance` at a
  sibling location and at brand scope, at both the application and the SQL
  boundary — the same isolation `KitchenBoardController`'s own tests already
  prove for a staff principal, run again for a device principal.
- A device holds `kitchen.ticket.read` and `kitchen.ticket.advance` and
  nothing else: a device principal's token is refused
  `INSUFFICIENT_CAPABILITY` on `kitchen.ticket.recall`,
  `kitchen.ticket.release`, `kitchen.station.manage`, and `iam.grant.manage`.
- A revoked device is refused on its very next request regardless of its
  access token's remaining validity, proving revocation is enforced by the
  grant, not by token expiry.
- Approving a device grants exactly `PlatformRole.KITCHEN_DEVICE` at exactly
  the approved `LOCATION` scope — never `TENANT` or `BRAND` — and the approval
  writes one ADR 0027 audit fact naming the human approver, the device, and
  the location.
- A `userCode` cannot be approved twice, an expired enrolment request cannot
  be approved, and a claimed enrolment's credential cannot be polled a second
  time — the same "spent exactly once" property `CustomerVerificationService`
  already proves for a redeemed grant.
- `PlatformRoleTests`' generic checks (`everyRoleGrantsSomething`,
  `aRoleNeverExceedsWhatItsScopeCanReach`) pass for `KITCHEN_DEVICE` with no
  new exemption, because its bundle is a strict subset of `LOCATION_STAFF`'s.

## Rollout and rollback

Ship behind no flag: the primitive is additive (`iam` gains two tables and one
role; nothing existing changes shape), and nothing calls it until a device
enrols. Rollback is revoking every enrolled device's grant and leaving the
tables in place as evidence — the same rollback shape ADR 0025 itself
describes for its own rollout.

## Implementation checklist

- [x] Add `iam.device_principals` and `iam.device_enrolment_requests` with the
      `LOCATION`-scope-only ancestry constraint (`V0192`).
- [x] Implement the pairing-code enrolment flow — begin, poll, approve,
      revoke — behind `DeviceEnrolmentPort`, with the device code hashed at
      rest and the Keycloak client secret never persisted.
- [x] Provision and disable per-device Keycloak service-account clients
      through a dedicated, minimally-scoped `horecaos-device-provisioning`
      credential (`manage-clients` only), never by widening
      `horecaos-provisioning`.
- [x] Add `PlatformRole.KITCHEN_DEVICE` — `{kitchen.ticket.read,
      kitchen.ticket.advance}` at `LOCATION` scope — and extend
      `kitchen.station.manage`'s own Javadoc to cover device enrolment and
      revocation rather than minting a new capability.
- [x] Wire `kitchen.web.KitchenDeviceController`'s approve/list/revoke surface
      under the existing tenant/brand/location kitchen path, each writing an
      ADR 0027 audit fact naming the human actor.
- [x] Add the two unauthenticated bootstrap endpoints to
      `SecurityConfiguration` and to `EndpointCapabilityDeclarationTests`'
      exemption list, on the same "no principal exists yet" reasoning the
      pre-account customer identity endpoints already carry.
- [x] Isolation, capability-ceiling, revocation-is-immediate, and
      single-claim tests.
- [ ] **Not built.** Any device class other than `KITCHEN_KDS` — deferred to
      ADR 0041's own rollout step 4, which owns the VDU projection and the
      expo/handover surface this ADR does not touch.
- [ ] **Not built.** Automatic secret rotation on a timer. Rotation today is
      revoke-and-re-enrol, the same operator action as replacing a lost
      device.
- [ ] **Not built.** Per-action attribution below the device (badge or PIN).
      Named in Consequences as a rejected-for-now alternative with a revisit
      trigger, not a gap in this checklist.

## Exit criteria

A kitchen display enrols with no password and no shared staff login; a
location manager approves it from their own already-authenticated session and
the approval is an ADR 0027 audit fact; the device reads its branch's board
and marks a line ready and nothing else, refused `INSUFFICIENT_CAPABILITY` on
every recall, release, station-management, dispatch, suspension, and
grant-management action; the device is denied at a sibling location and at
brand scope, at both the application and the SQL boundary; and revoking it
takes effect on its very next request, not at its token's expiry.

## References

- [ADR 0025: Fine-grained authorization and the capability model](../built/0025-fine-grained-authorization-and-capability-model.md)
- [ADR 0027: Audit evidence and approval model](../built/0027-audit-evidence-and-approval-model.md)
- [ADR 0028: Secrets management and credential lifecycle](../partial/0028-secrets-management-and-credential-lifecycle.md)
- [ADR 0031: HTTP API conventions](../built/0031-http-api-conventions.md)
- [ADR 0033: Caching, rate limiting, and shared runtime state](../built/0033-caching-rate-limiting-and-shared-runtime-state.md)
- [ADR 0041: Kitchen execution, production routing, and kitchen release](../partial/0041-kitchen-execution-and-production-routing.md)
- [ADR 0045: Real-time operational push and field telemetry](../partial/0045-realtime-operational-push-and-field-telemetry.md)
- [ADR 0049: Non-staff principal authorization](../built/0049-non-staff-principal-authorization.md)
