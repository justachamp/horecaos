# ADR 0151: A wall-display device class

- Decision status: Proposed
- Implementation status: Not started — a kitchen wall runs as a person. The
  device primitive of ADR 0079 knows exactly one class:
  `DevicePrincipalClass` has one constant, `KITCHEN_KDS`; `ck_device_principal_class`
  and `ck_device_enrolment_class` (V0192) accept only that value;
  `PlatformRole.KITCHEN_DEVICE` is `kitchen.ticket.read` plus
  `kitchen.ticket.advance`; `KitchenDeviceService.approve` grants that role and no
  other, "never a parameter". The read a wall needs exists and is narrow:
  `GET …/kitchen/vdu` (`KitchenBoardController.vdu`, `KITCHEN_TICKET_READ` at
  `LOCATION`) returns fired tickets with no order id, no buffer facts, no dish name
  and no note, optionally narrowed to one station. The screen that shows it,
  `/wallboard/vdu` (`WallboardVduPage`), is a sibling of the console shell guarded by
  `authGuard`: it runs on a signed-in staff session, with the station chosen from a
  local `<select>` that a reload forgets and a placeholder timezone
  (`Asia/Tashkent`) in place of the branch's own. The projection is not the only
  thing that page reads. It also resolves its location from `GET /session/context`,
  lists the stations for that `<select>` (`kitchen.ticket.read`), reads the tenant's
  lateness policy from `GET …/orders/lateness-policy` (`order.read`) to colour and
  rank its tickets, and opens the push stream (`…/operations/streams`, which needs
  `location.read`, and `kitchen.ticket.read` on the `kitchen_board` channel). The tablet shell `/device`
  (ADR 0119) authenticates as a device but renders only the touch board with start
  and ready. Gap-map row `2.4` records the choice honestly: "A dedicated VDU device
  class is deliberately not added: ADR 0079 names `KITCHEN_KDS` as the only built
  pairing class and explicitly defers a VDU/EXPO bundle."
- Date proposed: 2026-10-01
- Date decided: —
- Deciders: proposed by Claude (wave batch 17); Ayubkhon Abbosov (platform owner)
  decides
- Depends on: ADR 0025, ADR 0027, ADR 0028, ADR 0030, ADR 0031, ADR 0041, ADR 0045,
  ADR 0062, ADR 0079, ADR 0119
- Supersedes / Superseded by: — (ADR 0079 anticipated this: a VDU "would reuse this
  primitive with its own role bundle, not reopen this one", "it adds an enum value
  and a role, it does not change this decision")
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written; the ones that name another owner stay with them.
  - **Whether the station a wall shows is enforced or only configured** (product,
    operations). Proposed default: enforced for a device caller (the server applies
    the configured station and ignores the request's), because a wall that can be
    pointed at another station by editing a URL is a convenience, not a boundary.
    It is not a security boundary against the same branch's staff.
  - **The expo screen** (product, with ADR 0079's per-action attribution input).
    Handover and verification need to know who handed goods over. Proposed default:
    not decided here and not started; see Decision 7.
  - **The supervisor wallboard** (`/wallboard`, the live board) (product). Proposed
    default: stays on a staff session, for the reason in Decision 7.
  - **Whether a device reads its own scope instead of being typed once**
    (platform architecture, ADR 0119's open input). Proposed default: yes for
    kitchen devices through the read in Decision 5, with the typed setup kept as the
    fallback; this record does not close ADR 0119's input for any other class.
  - **Whether the manager sees when a wall last read** (operations). Proposed
    default: yes, updated at most once a minute by the display's own read.

**To accept as written:** say "accept 0151". Every open input above is then
closed on its proposed default.

## Context

Row `2.4` ("Display board (VDU)") has shipped everything that needed no decision: a
dedicated `GET …/kitchen/vdu` projection, a TV-legible shell outside the console
chrome, a station filter and a freshness banner. What is left, in the row's own
words, is "a VDU device class", which ADR 0079 deferred "rather than pre-deciding
it". This record decides it, because the thing that is shipped is wrong in a way the
shipped KDS tablet is not.

**A wall display is the case ADR 0079 was written for, and it is not covered.** The
record's Context describes the failure for a kitchen tablet: signed in as a real
staff member, it is "an unrevocable credential carrying a manager's capability set,
and the manager cannot even see it is still logged in six months later". A VDU on a
wall is that tablet with no keyboard and a longer life. Today it is signed in as
whoever last typed a password on it.

| Property | The wall on a staff session (today) | A device principal (ADR 0079) |
|---|---|---|
| Whose authority | The signed-in person's whole capability set, reachable by anyone with a keyboard, because the wall is a route of the same single-page console | Exactly the bundle the device was enrolled with |
| Revocation | End that person's sessions (which signs them out everywhere) or disable them; nothing lists "this TV" | One row, one click, listed on Kitchen → Devices |
| What ends it | The person changing their password or being reset (ADR 0098 calls `logoutEverywhere`), a refresh token's lifetime, a browser restart (the refresh token is in `sessionStorage`, ADR 0062) | The device's own credential, held for a fixed-location screen on purpose (ADR 0119 rejects `sessionStorage` for it) |
| Attribution | Anything done from it names a person who is not there | Names the device |
| Configuration | In the page; lost on reload | On the device's record |
| Who knows it exists | Nobody | The manager who approved it |

The first two rows are the point. The wall needs to read its own record and one
projection, and nothing else (Decision 2 lists every read, because the page it
reuses makes five); the session it runs on can do anything its owner can.

**The shipped class is the wrong fit in the other direction.** `KITCHEN_KDS` can
read the board and call `kitchen.ticket.advance`. A touch KDS needs the second; a
VDU, in ADR 0041's words, is "the same fired tickets, no controls". Enrolling a
wall as `KITCHEN_KDS` hands anyone who walks past a touch-capable TV the power to
mark a line ready, which is the reading the gap map's note ("this wave reuses
`KITCHEN_KDS`'s own capabilities") would have if it were made permanent.

**What the platform already decided, which carries the answer.** ADR 0079: a device
is a principal in the ADR 0025 sense, provisioned as its own confidential Keycloak
service-account client, granted a role at `LOCATION` scope, enrolled by a
location-scoped human capability, and its class is a closed, code-owned set because
"free text means one deployment spells a class `kds` and another `KDS-1`". ADR 0041:
"VDU is a projection with a station filter and a device registration, not a state",
with a station filter that belongs to the device row. ADR 0025: the capability is the
unit of authority, and a bundle may grant no more than a role needs.

**Two things look like wall displays and are not VDUs.** The supervisor's live board
(`/wallboard`, IA `0.1e`) shows counters across a brand's branches, which is a
manager's screen for several locations; ADR 0079's invariant that a device is
`LOCATION`-scoped, always, is why it cannot be a device. And the expo screen (the
pass, where goods are handed to a courier) needs to know *who* handed them over,
which is the per-action attribution ADR 0079 rejected for now.

## Decision

**Add one device class, `KITCHEN_VDU`, a read-only wall display with its own role,
its own capability, a server-side display configuration, and an approval step that
can narrow a request and never widen it; leave the live board and the expo screen
where they are, with their reasons recorded.**

1. **A new class and a new role.** `DevicePrincipalClass` gains `KITCHEN_VDU`; both
   V0192 constraints are restated in full with the new value (the V0145 precedent for
   restating a CHECK). `PlatformRole` gains `KITCHEN_VDU_DEVICE`
   (`kitchen-vdu-device`, `LOCATION`) holding exactly one capability, and is
   excluded from `TenantRoleCatalog` as `KITCHEN_DEVICE` is: no person is ever
   granted it. The Javadoc sentence on `KITCHEN_DEVICE` that says no device is ever
   granted any other role is rewritten to say each class has exactly one.

2. **A capability for the wall, not a reuse of the ticket read, and a projection
   that carries everything the wall renders.** `kitchen.display.read` (`LOCATION`)
   guards the VDU projection and nothing else. Every existing bundle that holds
   `kitchen.ticket.read` also holds `kitchen.display.read`, so no staff user loses the
   VDU page; a role-bundle test makes that an invariant rather than a habit. The wall
   therefore cannot call `GET …/kitchen/board` or a single ticket (both
   `kitchen.ticket.read`), and cannot advance anything. The alternative of reusing
   `kitchen.ticket.read` is in the table below.

   A device that holds one capability can make only the calls that capability opens,
   and `WallboardVduPage`, the rendering Decision 6 reuses, makes five. Each is
   accounted for here, so that no read is left to fail quietly on a 403 and fall back
   to a default:

   | What the page does today | What it needs today | In wall mode |
   |---|---|---|
   | Resolve its location: `GET /session/context` | nothing (a self-read) | `GET /api/v1/devices/me` (Decision 5); `/session/context` is not called |
   | List stations for the `<select>`: `GET …/kitchen/stations` | `kitchen.ticket.read` | not called: the station is the device's own (Decision 4) and its display names ride in `devices/me`; the `<select>` does not exist in wall mode |
   | Read the tickets: `GET …/kitchen/vdu` | `kitchen.ticket.read`, becoming `kitchen.display.read` | the one data read |
   | Read the lateness policy: `GET …/orders/lateness-policy` | `order.read` | **carried in the projection** (below) |
   | Open the push stream: `GET …/operations/streams` | `location.read`, and `kitchen.ticket.read` on the channel | **not opened**; the wall polls every ten seconds, the fallback ADR 0045 requires of every surface anyway |

   *The lateness policy.* The wall colours and ranks a ticket from the tenant's
   `ordering.lateness` document (ADR 0030; what its thresholds mean is ADR 0150's):
   the at-risk and late thresholds per fulfilment mode, the fallback for a ticket with
   no promise, and the tenant's late colour (rows `X.39` and `10.3b`). Without the policy the client does what it does
   on any refusal: `LatenessPolicyApi.read` swallows the 403 and answers `null`, and
   `LatenessPolicyTracker.policy` then serves `PLATFORM_DEFAULT_LATENESS_POLICY` (at
   risk 5 minutes before the promise, late at the promise, 45 minutes when there is
   none). The kitchen TV shows "on time" for a ticket the manager's console shows
   late, and nothing on the screen says why. So `VduBoardResponse` gains a
   `lateness` member: the policy resolved at the location of the call, in the shape
   `OrderLatenessPolicyController.LatenessPolicyResponse` already serves (the three
   modes' thresholds and `lateColour`), read through a small port the ordering
   module publishes in `ordering.api` because `OrderLatenessPolicyService` sits in
   its `application` package. The resolution is the cached one the boards already
   use. It is tenant configuration, not customer data. The manager's preview page
   reads it from the projection too, so there is one source and one request fewer a
   minute; the server's own 60-second resolution cache still decides how soon a
   published change shows. `GET …/orders/lateness-policy` is unchanged and still
   needs `order.read`.

   *The stream.* The wall does not open it. A device cannot satisfy
   `…/operations/streams` (`location.read`) without a second capability, and the
   channel's own capability is `kitchen.ticket.read`; widening either is a change to
   a shared endpoint that this record does not need. The stream is an accelerator
   (ADR 0045), so the cost is up to ten seconds of delay on a screen read from across
   a room; the manager's preview on a staff session keeps the stream.

3. **Approval names the class, and may only narrow.** The device's request for a
   class is a claim, shown to the approver on Kitchen → Devices. `KitchenDeviceService.approve`
   takes the approved class and derives the role from a closed class-to-role map
   (never a parameter, as today). A device that asked to be a `KITCHEN_KDS` may be
   approved as a `KITCHEN_VDU`; one that asked to be a `KITCHEN_VDU` cannot be
   approved as a `KITCHEN_KDS`. The audit fact `kitchen.device.enrolled` records the
   requested and the approved class.

4. **The display's configuration is the device's, held by the kitchen.** A table
   `kitchen.device_displays` keyed by the device (tenant-scoped, one row per VDU)
   holds the station filter (null means the whole branch) and the time the display
   last read. A manager with `kitchen.station.manage` sets the station from Kitchen →
   Devices. For a device caller, `GET …/kitchen/vdu` applies the configured station
   and ignores the request's `station`; for a human caller it behaves as it does
   today.

5. **A device can ask what it is.** `GET /api/v1/devices/me` answers, for the
   calling device principal, its id, class, tenant, brand and location, the branch's
   display name and IANA timezone, and (for a VDU) its station with its display
   names in the three languages. It resolves the
   caller through `iam.device_principals.principal_subject`, which is unique, so it
   needs no Keycloak protocol-mapper change — the obstacle ADR 0119 records. It is a
   read of the caller's own record and is declared as such, in the category of
   `GET /session/context` (an endpoint that needs no capability because a principal
   reading itself learns nothing a refusal would not), under whatever exemption rule
   `EndpointCapabilityDeclarationTests` requires for a self-read. The VDU shell then needs no typed setup and
   stops using the placeholder timezone.

6. **The wall is a mode of the existing device shell.** After enrolment `/device`
   calls the read above and renders the touch board for a `KITCHEN_KDS` and the wall
   for a `KITCHEN_VDU`, reusing the wall's rendering from `WallboardVduPage`. The rendering is
   reused; the data loading is not. Wall mode takes its location and station from
   `devices/me`, and its tickets and lateness policy from the projection, and makes no
   other request (the table in Decision 2). The page's station list, its policy
   tracker and its stream subscription are staff-session behaviour and stay on the
   preview route. No control exists in that mode. `/wallboard/vdu` remains for a manager previewing a
   wall on a laptop and says so; a tenant installing a TV is directed to the device
   path.

7. **What this record does not do, and why.**
   - *The live board is not a device.* It spans a brand's branches and a device is
     `LOCATION`-scoped by an invariant ADR 0079 states and V0192 enforces with a
     foreign key to a location. It stays on a staff session; the revisit trigger is a
     counts-only capability and a location-scoped variant that a branch TV would
     really use.
   - *The expo screen is not started.* Handover and verification attribute an act to
     a person, and ADR 0079 rejected "a badge tap or a PIN before a mutating action"
     for now. A device cannot be the actor of a handover.
   - *The customer-facing queue screen* (a pickup-ready board) is another read, for
     another audience, with its own privacy question; it is not this class.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep walls on a staff session | Works, and nothing to build. It is exactly the failure ADR 0079 refuses, with a longer life and no keyboard to notice | Never as the answer for a TV in a kitchen |
| Enrol walls as `KITCHEN_KDS` | No new code. Gives a wall `kitchen.ticket.advance`; a touch-capable TV becomes a control surface for anyone passing | Never |
| A person-style read-only staff account per branch ("tv@branch") | Same unrevocable shared credential with a password to type on a TV, a human subject in the audit log, and a session that a password reset ends | Never |
| Reuse `kitchen.ticket.read` for the wall | One fewer capability, and the VDU endpoint keeps its present annotation. But the wall could then call the touch board read and every future read added under that capability | The capability list becomes hard to hold in anyone's head and the bundles never diverge |
| One `WALL_DISPLAY` class with a surface column (VDU, live board, expo) | A single enum value and an extensible shape, but a two-dimensional class-to-role map for surfaces that differ in scope (the live board is brand-wide) and in need of attribution (expo) | A second wall surface with a `LOCATION` scope and no attribution need exists |
| A kiosk link carrying a long-lived key in the URL | No enrolment, and a bearer in browser history, proxy logs and a QR code (ADR 0028) with no listing and no revocation but rotation | Never |
| An unauthenticated per-branch URL with an unguessable id | Nothing to revoke, and the projection carries operational facts | Never |
| Enforce the station in the token (one Keycloak client per station) | A boundary for what is a display convenience, multiplying clients by stations | Station-scoped data becomes sensitive |

## Consequences

### Positive

- A wall is a listed, revocable, narrow principal; a manager can see it, change what
  it shows, and kill it without signing anyone out.
- A password reset no longer blanks the kitchen's TVs.
- The wall holds exactly one capability and cannot advance a ticket.
- A restarted TV comes back showing the right station and the right timezone
  with nobody typing.
- The pattern for the next class is written down: a closed enum value, one role, one
  capability, a narrow projection and a server-side configuration.

### Negative

- A new capability that must ride along in every bundle holding the ticket read. The
  invariant test guards the drift, and the cost is one more capability in a registry
  that is code-owned and grows by a release each time.
- A second table beside the device principal and a second place the station can
  disagree with a human's view.
- Two ways to run a VDU (device and manager preview) to keep in step.
- A physical TV is physically reachable. A stolen one holds a credential that reads
  ticket sequence numbers, provider references and the location's lateness
  thresholds, and cannot do more; it is revoked
  in one click, and until then it can be pointed at a laptop.
- The class is a claim until approved; a manager who approves whatever appears on the
  Devices page approves what an attacker nearby requested. The pairing code and the
  location-scoped approval are the control, unchanged from ADR 0079.

### Accepted trade-offs

- The live board and the expo screen remain as they are, each for a stated reason.
- The station is enforced for devices and not for the branch's own staff.
- A wall polls every ten seconds and has no push channel. Giving it one means
  deciding what a device principal may ask of `…/operations/streams`, which is a
  separate record.
- Last-seen is a write on a read, bounded to once a minute per device.

## Specification

### Model (additive; numbers reserved by the wave that builds it)

```text
iam.device_principals / iam.device_enrolment_requests   ck_device_principal_class,
                                                        ck_device_enrolment_class restated:
                                                        ('KITCHEN_KDS', 'KITCHEN_VDU')
kitchen.device_displays
  tenant_id, location_id, device_id (-> iam.device_principals on (tenant_id, id),
                                       uq_device_principal_tenant_id),
  station_id uuid null (-> kitchen.stations on (id, tenant_id, location_id),
                          uq_station_identity),
  last_read_at timestamptz null, created_at, updated_at, version
PlatformRole.KITCHEN_VDU_DEVICE   {KITCHEN_DISPLAY_READ}   LOCATION
Capability.KITCHEN_DISPLAY_READ   ("kitchen.display.read", "kitchen", "display.read")
```

Grants as `V0035`. A station of another location is refused by the composite key;
that the row's location is the device's own is checked by the service, or by a new
`(tenant_id, id, location_id)` unique on the device principal if the build prefers the
database to say it.

### Endpoints (ADR 0031)

```text
GET  …/kitchen/vdu                                  kitchen.display.read, LOCATION   (was kitchen.ticket.read);
                                                    response gains lateness: the resolved ordering.lateness
                                                    policy (three modes + lateColour) at the call's location
GET  /api/v1/devices/me                             the caller's own device record; a device principal only
PUT  …/kitchen/devices/{deviceId}/display           kitchen.station.manage, LOCATION, If-Match,
                                                    Idempotency-Key: { stationId | null }
POST …/kitchen/devices/enrolments/{userCode}/approve   body gains deviceClass (the approved class)
GET  …/kitchen/devices                              response gains class, requested class, station, lastReadAt
```

`GET …/kitchen/vdu` for a device caller reads the station from `device_displays`;
the unauthenticated enrolment `begin` keeps accepting a requested class and nothing
more. `me` never returns a secret, a Keycloak client id or a token.

### Audit and observability

`kitchen.device.enrolled` gains requested and approved class; a new
`kitchen.device.display_configured` through `ChangeDocuments.diff` names the station
before and after. Reads write no audit fact. A counter `horecaos.kitchen.display.reads`
carries only the outcome. A device with no read for a configurable period appears as
"not seen" on Kitchen → Devices.

### Testing

- A `KITCHEN_VDU` device reads `…/kitchen/vdu` and is refused `…/kitchen/board`, a
  ticket read, `…/advance`, `…/kitchen/stations`, `…/orders/lateness-policy` and
  `…/operations/streams` (403, with the capability named); a `KITCHEN_KDS` device is
  unchanged.
- **The wall's colours are the tenant's.** With a tenant `ordering.lateness` document
  that is not the platform default (at risk 10 minutes, late colour `#c0392b`) and a
  location override on top of it, the projection's `lateness` equals the resolved
  policy and not the default, for a device caller and a human caller alike. Seen
  failing first against a projection without the member.
- Approval: a requested KDS approved as VDU succeeds; a requested VDU approved as KDS
  is refused; the role granted follows the approved class (read back from
  `iam.grants`).
- Bundle invariant: every `PlatformRole` containing `KITCHEN_TICKET_READ` also contains
  `KITCHEN_DISPLAY_READ`, so a staff user still reads the VDU page.
- A device caller's `station` parameter is ignored in favour of the configured one; a
  human's is honoured.
- `me` answers for an active device, 401/403 for a revoked one and for a staff token,
  and exposes no secret.
- The migration test accepts `KITCHEN_VDU` and rejects an unknown class in both
  tables.
- `TenantRoleCatalog` does not offer the new role; `EndpointCapabilityDeclarationTests`
  and `ModularArchitectureTests` green.
- Front end: the device shell renders no control in wall mode; a restart restores the
  station; key parity for the new strings.
- **Wall mode reads exactly what it is entitled to.** Against the HTTP mock, wall mode
  requests `devices/me` and the projection and nothing else, and opens no stream, so a
  read added later without a capability to carry it fails the spec instead of failing
  quietly on a 403. Given a non-default policy in the projection, a ticket past that
  policy's late threshold but inside the platform default's is painted late in the
  tenant's colour (seen failing first against a wall that ignores the policy it was
  sent).

## Rollout and rollback

Ship the class, role, capability and the bundle change together with the endpoints,
behind no flag: nothing changes for a branch that enrols no VDU. Enrol one wall at a
pilot branch beside its staff-session wall, compare, then retire the session wall.
Rollback is revoking the device and returning to the manager preview; the extra enum
value and table are inert without rows.

## Implementation checklist

- [ ] Owner accepts the record.
- [ ] Flyway: both CHECKs restated; `kitchen.device_displays`; granted.
- [ ] `DevicePrincipalClass.KITCHEN_VDU`; `PlatformRole.KITCHEN_VDU_DEVICE`;
      `Capability.KITCHEN_DISPLAY_READ` added to every bundle with the ticket read;
      `TenantRoleCatalog` exclusion; the `KITCHEN_DEVICE` Javadoc corrected.
- [ ] `KitchenDeviceService.approve` takes the approved class and maps it to a role;
      the audit fact carries both classes.
- [ ] `GET /api/v1/devices/me`; the display-configuration `PUT`; the `vdu` read applies
      the station for a device caller and records the last read.
- [ ] `VduBoardResponse.lateness` and the `ordering.api` read port behind it; the
      preview page and wall mode take the policy from the projection and the wall no
      longer uses `LatenessPolicyTracker`; the `StreamChannel.KITCHEN_BOARD` Javadoc
      ("rather than a new capability ... nothing here for a fourth capability to
      separate") rewritten, because it is no longer true of a wall.
- [ ] Device shell wall mode; Kitchen → Devices shows class, station and last seen and
      lets a manager set the station; the manager preview route says it is a preview.
- [ ] ru / uz-latn / en strings; the key-parity spec green; the initial-bundle budget
      unchanged (the device route is already lazy).
- [ ] Update ADR 0079's status line (a second class), ADR 0041's rollout step 4 and
      ADR 0119's open input (answered for kitchen devices).
- [ ] Tests listed under Testing, each seen failing first.

## Exit criteria

A manager enrols a TV by pairing code, approves it as a wall for the grill station,
and the TV shows the grill's tickets after a power cut with nobody touching it, late
in the tenant's own colour at the tenant's own thresholds, having made no read but its
own record and the projection. The
same TV cannot read the touch board or advance a ticket. The manager revokes it from
Kitchen → Devices without signing anyone out, and the password reset of the person who
paired it changes nothing about any wall.

## References

- ADR 0025, ADR 0027, ADR 0028, ADR 0031, ADR 0041 (displays and devices, rollout step
  4), ADR 0045, ADR 0062, ADR 0079 (the primitive, the deferred VDU and EXPO), ADR 0098,
  ADR 0119 (device shell, the whoami input)
- `platform/docs/operations-gap-map.md` rows `2.4`, `0.1e`; wave `T02`
- `DevicePrincipalClass`, `PlatformRole`, `Capability`, `TenantRoleCatalog`,
  `KitchenDeviceService`, `KitchenDeviceController`, `KitchenBoardController`,
  `KitchenStationController`, `DeviceEnrolmentService`, `GrantController`
  (`/session/context`); `OrderLatenessPolicyController` and
  `OrderLatenessPolicyService` (`order.read`; the cached resolution the projection
  reuses); `OperationsStreamController` and `StreamChannel.KITCHEN_BOARD`;
  `V0192`, `V0145`
- `frontend/operations/src/app/device/`, `wallboard-shell/wallboard-vdu-page.ts`,
  `core/lateness-policy-api.ts`, `core/lateness-policy.ts` (the platform default),
  `core/realtime/realtime-client.ts`,
  `features/kitchen/devices-page.ts`, `app.routes.ts`
