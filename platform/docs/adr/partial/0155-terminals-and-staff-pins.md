# ADR 0155: Terminals and staff PINs

- Decision status: Accepted — proposed by Claude (batch 19); accepted by the platform owner 2026-10-07
- Implementation status: Not started — no PIN, no terminal session and no per-person
  attribution on a shared device exists anywhere in code. A search of
  `platform/src/main/java` and the migrations for a PIN credential, a PIN digest, a
  terminal session or a lockout finds nothing (the only hits are map pins and
  `PINFL`, a courier's national identifier). What exists is the device half, and it
  attributes to a machine. `iam.device_principals` and `iam.device_enrolment_requests`
  (V0192) hold a location-scoped principal whose `device_class` is checked against
  one value, `KITCHEN_KDS` (`DevicePrincipalClass` has one constant, and `ck_device_principal_class`
  and `ck_device_enrolment_class` accept only it); `PlatformRole.KITCHEN_DEVICE` is
  `kitchen.ticket.read` plus `kitchen.ticket.advance`; `KitchenDeviceController` lists,
  approves and revokes under `kitchen.station.manage` and nothing lists devices of any
  other kind; `DeviceEnrolmentController` runs the unauthenticated begin and poll. Every
  action a tablet takes is recorded under the device's own Keycloak subject:
  `KitchenBoardController` passes `currentActor.get().subject()` to start, ready,
  recall, release and hand-over, and `kitchen.ticket_events.actor_type` is checked
  against `USER`, `SYSTEM_JOB` and `SERVICE` with a free `actor_id`. The ADR 0119 shell
  (`frontend/operations/src/app/device/`) renders a touch board and has no notion of a
  person. The console says so itself: the person card's security block
  (`staff.detail.security.notBuilt`) reads that sign-in method, last sign-in and terminal
  PIN are not tracked yet, and «Мой профиль» (`staff.myProfile.security.notBuilt`) says the
  same of the PIN. `StaffMembers` and `TenantRoleCatalog` already keep a device out
  of the staff list, and `iam.staff_members` (V0453) is the tenant-scoped record of who
  a person is, with a never-reused `display_reference`; it carries no credential. ADR
  0148 (staff MFA) is Not started and explicitly leaves device principals outside it.
- Date proposed: 2026-10-07
- Date decided: 2026-10-07
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: [ADR 0025](../built/0025-fine-grained-authorization-and-capability-model.md),
  [ADR 0027](../built/0027-audit-evidence-and-approval-model.md),
  [ADR 0028](../partial/0028-secrets-management-and-credential-lifecycle.md),
  [ADR 0029](../partial/0029-pii-protection-envelope-encryption-and-key-rotation.md),
  [ADR 0030](../built/0030-configuration-and-policy-resolution.md),
  [ADR 0031](../built/0031-http-api-conventions.md),
  [ADR 0033](../built/0033-caching-rate-limiting-and-shared-runtime-state.md),
  [ADR 0041](../partial/0041-kitchen-execution-and-production-routing.md),
  [ADR 0058](../partial/0058-telegram-notification-channels.md),
  [ADR 0062](../built/0062-staff-sign-in-happens-inside-the-platform.md),
  [ADR 0076](../partial/0076-time-ordered-identifiers-for-new-rows.md),
  [ADR 0079](../partial/0079-kitchen-display-device-principal-and-enrolment.md),
  [ADR 0119](../partial/0119-wave-p17-device-shell-auth-and-qr-pairing.md),
  [ADR 0139](../partial/0139-staff-identity-the-staff-person-record.md),
  [ADR 0148](../partial/0148-staff-multi-factor-authentication.md),
  [ADR 0151](../built/0151-a-wall-display-device-class.md)
- Supersedes / Superseded by: Supersedes ADR 0079's Alternatives row on per-action attribution
  («a badge tap or a short PIN before every mutating action», whose stated revisit trigger was a
  dispute or finding that «which device» is insufficient evidence and a judgement that the cost is
  worth it; Open input 1 records that the owner's row `X.3` is that judgement) and its Closed
  input «A device is not a person» for a device of an unlockable class in a `PERSON_OPTIONAL` or
  `PERSON_REQUIRED` mode; supersedes ADR 0079's «and nothing else» sentence about
  `KITCHEN_DEVICE` by one machine capability, `terminal.unlock`; narrows ADR 0139's «Password,
  MFA, sessions → Keycloak» row by a PIN that attributes and never authenticates; extends ADR
  0025 check 3 («an active grant gives the principal the required capability») by a second
  subject, the person, for those requests. Everything else in each of them is unchanged. The
  building wave records the reciprocal «Superseded by ADR 0155 for …» lines on ADR 0079 and ADR
  0139 and the extension on ADR 0025, and edits no argument, table or open input of any of the
  three. It also closes the first open input of ADR 0139, whether terminals and PINs stay a
  record of their own: they do, and this is it.
- Open inputs: each is closed on its proposed default if the owner accepts the record
  as written; the ones that name a person other than the owner stay with that person and
  the work they block is marked.
  - **Whether ADR 0079's revisit trigger has fired** (platform owner). Proposed default:
    yes. The trigger asked for evidence that «which device» is insufficient. Gap-map row
    `X.3` is that evidence stated in advance ("a kitchen tablet has no way to say which
    human pressed «ready»"), and the tenant behaviour it predicts, a shared login called
    `kuxnya`, is cheaper to prevent than to find. Accepting this record records the
    trigger as fired; it does not make a PIN mandatory anywhere (Decision 5).
  - **Whether a PIN verifier held by HorecaOS conflicts with AGENTS.md's "do not implement
    a second password or token issuer in the application" and ADR 0139's "Password, MFA,
    sessions → Keycloak"** (platform owner, security). Proposed default: it does not,
    on four conditions Decision 2 makes testable: the PIN is not a password (it cannot
    authenticate to anything, ever), the terminal session is not a token (it is accepted
    only together with the device's own Keycloak bearer and confers nothing), a PIN is
    accepted only by the `terminal-session` PIN endpoints (unlock, activation, change), from an
    enrolled unlockable device, at a location where the person is eligible (it is one PIN per person per tenant,
    Decision 3, so it is the session, not the PIN, that is bound to a device), and
    Keycloak remains the only verifier of every credential that can sign anyone in. If the
    owner reads the rule more strictly, the fallback is the Keycloak-native design in the
    Alternatives table, at a cost this record prices.
  - **The PIN's length** (security). Proposed default: six digits, with four allowed as a
    tenant choice (`terminal.pin.length`) because the spec offers it; the Policy card
    (Decision 7) states the guess odds beside the choice, using the figures of the next input.
    The length applies to the next PIN set; an existing PIN keeps the length it was set with.
  - **The lockout numbers** (security). Proposed default: five consecutive wrong PINs lock
    that person on that device for 15 minutes (the spec's rule), and twelve wrong PINs in a
    rolling 24 hours across every device lock the credential until it is reset. They are
    code constants, not a tenant's setting: a security control a tenant can weaken is not
    one. Twelve a day against a million six-digit PINs is about 1 in 83,000 a day; against
    ten thousand four-digit PINs it is about 1 in 830, which is the price of choosing four.
  - **Idle lock and session length** (operations). Proposed default: a terminal locks after
    five minutes without a user-initiated request carrying its session (a mutating call or the
    keypad's `touch`; the board's timed poll does not count, Decision 5)
    (`terminal.auto_lock_seconds`, per device override, 30 to 3,600) and a session ends after
    twelve hours whatever happens (`terminal.session.max_hours`).
  - **What the roster shows** (product, legal; ADR 0139's open input on names on shared
    screens). Proposed default: first name and the initial of the last name
    (`terminal.roster.display` = `NAME_INITIAL`), never a phone, photo or employee number,
    with `REFERENCE` (the non-personal `S-0142`) as the tenant's alternative.
  - **Whether a device starts requiring a person** (operations). Proposed default: no.
    Every existing and every new device is `DEVICE_ONLY`, exactly as today, until a manager
    switches it (`terminal.attribution.default` holds the default for a new one). Nothing a
    running kitchen does changes on the day this is built.
  - **How a person with no console access sets a first PIN** (platform owner, security).
    Proposed default: a holder of `staff.profile.manage` issues a single-use activation
    code in the person card, shown to the issuer once; the person is told in their profile
    that one was issued and by whom; every use is audited with the issuing person. It is the
    weaker of the two routes (the issuer could type the code first), which is why the
    person's own profile shows each PIN set and unlock afterwards.
  - **Who may unlock which device** (product). Proposed default: nobody is listed by hand.
    A person may unlock a terminal if they are an `ACTIVE` staff member holding, at that
    terminal's location, every capability the device class names for attribution (for the
    kitchen tablet, `kitchen.ticket.advance`). The spec's "which jobs may unlock it" is that
    rule, derived from grants rather than maintained twice.
  - **Which capabilities demand a person** (product, with ADR 0151's open input on the expo
    screen). Proposed default: none at launch. The mechanism exists (Decision 5); marking
    hand-over or a stop set from a device as person-only is the expo and stop records'
    decision.
  - **Where the screen lives** (product, frontend). Proposed default: Staff → Терминалы,
    with an IA row added by the wave that builds it (the IA has none, and its section 9
    stops at 9.4).

**To accept as written:** say "accept 0155". Every open input above is then closed on its
proposed default.

**Decision record, 2026-10-07.** Accepted by Ayubkhon Abbosov (platform owner) under the standing instruction "lets finish all" given the same day, which accepts every record proposed in batch 19 (ADRs 0154–0176) on the default each open input proposes. An input that names a person other than the owner, or an external fact (a device model, a legal wording, a provider capability, a dataset publication), stays with that owner as written and implementation proceeds without it, marking what waits. Implementation starts in operations batch 20 (2026-10-07).

## Context

Gap-map row `X.3` is `NOT BUILT`: "a kitchen tablet has no way to say which human pressed
«ready», so the first tenant with a shared login called `kuxnya` silently empties the audit
log of meaning". It is blocked because "no owning ADR: ADR 0139 (Accepted 2026-10-01)
splits terminals and PINs from the staff member record rather than folding them in, because
ADR 0079 already argued against a PIN security policy". `staff-and-access.md` §8 is the
design the row waits on, and §11.7 says what is built: "Nothing. No device registry, no PIN,
no device session, no per-device audit attribution."

**The spec's answer, and why it needs a decision rather than a build.** Section 8: "the
device holds a long-lived session; the person holds a short PIN. The device authenticates as
itself; each action is attributed to the person whose PIN unlocked it." It adds rules it
calls non-negotiable: a PIN "is not a password", is only valid "on enrolled devices at the
person's own branch", and "can never be used to sign in to the console in a browser"; it is
set by the person, only reset (cleared) by a manager; five wrong attempts lock "that person
on that device for 15 minutes" and raise a `SECURITY` audit event; and a PIN is never derived
from a phone number, a birth date or an employee number. Every one of those is right. None
says where the verifier lives, how a person with no PIN gets a first one without the manager
choosing it, what the device sends so the server can tell who is acting, what that does to
authorization, or what happens at the 24-hour mark of a patient guesser. Those are the
decision.

**What ADR 0079 refused, and what has changed.** ADR 0079's Alternatives table rejects
"per-action human attribution: a badge tap or a short PIN before every mutating action" for
two reasons: it "reintroduces exactly the 'a screen holds a person's credential' problem the
enrolment mechanism exists to remove", and "a 4-digit PIN shared among a shift is not
meaningfully stronger evidence than 'the device that was on the counter'". Both reasons are
true of the design they were arguing against, a PIN as the thing a device holds. Neither
applies to a PIN that is per person, locked out by person, never stored on the device and
never accepted without the device's own bearer, and the first reason is exactly what this
record keeps intact: the device still authenticates as itself (ADR 0079 unchanged) and holds
no person's credential. What changes is one overlay on top of it. The honest ceiling remains
what 0079 said: a PIN can be told to a colleague. The record does not pretend otherwise; it
makes misuse visible to the person whose name it is (Decision 8).

**Why this is not ADR 0148, and why it is not Keycloak's.** ADR 0148 puts a second factor
in front of a sign-in; a PIN signs nothing in. AGENTS.md says "do not implement a second
password or token issuer in the application", and ADR 0139 hands "password, MFA, sessions" to
Keycloak. A reader could take the PIN for a second password and a terminal session for a
second token. The distinction this record has to make, and make checkable, is that a PIN
verifies nothing but "the person at this device is who this device's screen says", and a
terminal session is a row that attributes and narrows, never a bearer that authorizes.
Decision 2 states it as five refusals.

**Why authorization has to be an intersection.** If a person's PIN replaced the device's
grant, a terminal would hold whatever its last user held, and a revoked cook's PIN would be a
way back in. If the device's grant alone authorized, a dishwasher could unlock the kitchen
tablet and press «ready» under a cook's name. ADR 0025's four checks run unchanged for the
device; this record adds one question for the person, asked of the same
`AuthorizationService`, so no second authorization path exists: may this person, at this
location, do what the endpoint declares?

**What a terminal is.** The spec's list (a kitchen tablet, an expo screen, a counter till, a
kiosk) mixes devices a person unlocks and devices nobody does. The wall display of ADR 0151
has no controls; the kiosk of ADR 0162 is touched by customers, and its «service PIN» is,
in §11.7's words, "a device maintenance code, not a staff identity, and must not be
conflated"; the print agent of ADR 0154 is software. This record therefore gives the class
set one property, whether a person unlocks it, and builds only the registry that lists every
device principal and the unlock path that applies to the classes where the answer is yes.

## Decision

**Keep every device an ADR 0079 principal that authenticates as itself, and add an
attribution overlay on the classes a person unlocks: a per-person, per-tenant PIN verified
by HorecaOS, a device-bound terminal session that records who is acting and can only narrow
what the device may do, and one class-neutral registry that lists every device and who is
at it.**

1. **A terminal is a device principal whose class a person may unlock.**
   `DevicePrincipalClass` gains an `unlockable()` property. Only `KITCHEN_KDS` is
   unlockable at launch; `KITCHEN_VDU` (ADR 0151), `KIOSK` (ADR 0162) and `PRINT_AGENT`
   (ADR 0154) are not and never get a roster. Each future class states the property in the
   record that adds it, together with the capability set that makes a person eligible.

2. **The PIN attributes; it never authenticates.** Five refusals, each a test:
   a PIN is never accepted as a sign-in to the console or to any endpoint but the
   `terminal-session` family; a terminal-session token is never accepted as a bearer, and a
   request carrying the session header without the device's own Keycloak token is
   refused before anything else runs; a session is valid only from the device it was issued
   to and only at that device's location; it grants nothing the device and the person do not
   both hold already (Decision 5); and no HorecaOS verifier exists for any credential that can
   sign a person in, so Keycloak stays the only one. The PIN is "a second factor on a device
   that is already authenticated" in the spec's words, and the record names it an
   attribution factor so nobody counts it as a login.

3. **The credential.** One PIN per staff member per tenant, in `iam.staff_pins`, keyed to the
   ADR 0139 member row. Its digest is `HMAC-SHA256(pepper, tenant ‖ member ‖ pinVersion ‖
   pin)` with the pepper held as a reference in ADR 0028's store (the `HandoverCodeHasher`
   precedent), compared in constant time, and re-keyed on the next successful unlock when the
   pepper rotates. A slow hash would add nothing to a million-entry space and the HMAC keeps
   a database dump from being a list of working PINs; the residual risk, that someone who
   holds the database and the pepper can recover any PIN in seconds, is accepted because that
   person can already read the platform. The person sets the PIN at a terminal, never in a
   browser and never chosen by a manager. Refused at entry, with the reason stated:
   a run of one digit, an ascending or descending run of the whole length, a short deny-list
   (`123456`, `654321`, `000000`, `121212`), and any PIN equal to the last digits of the
   person's own phone or employee number, which the server can decrypt and compare. Locks
   are durable, in the database and serialized per person by a row lock, and never in the
   ADR 0033 limiter, which is per replica and forgets on restart: five consecutive wrong
   PINs lock that person on that device for 15 minutes; twelve wrong in a rolling 24 hours
   across devices lock the credential until it is reset; success clears the consecutive
   count and never the 24-hour one. A lock raises a `SECURITY` audit fact and an operations
   alert (event class `TERMINAL_CREDENTIAL_LOCKED`, Specification) that names the
   `display_reference`, never the person.

4. **Setting, changing and resetting.** A person with no PIN, or a locked one, opens
   «Задать PIN» on the roster of a terminal and types a single-use **activation code**, then
   the new PIN twice; success opens a session. The code is issued either by the person, from
   «Мой профиль» on their own console session (`@StaffSelfAuthorized`), valid 15 minutes, or
   by a `staff.profile.manage` holder from the person card, valid four hours, shown once and
   audited with its issuer. Eight digits, hashed like the PIN, burned after five wrong tries.
   A manager's «Сбросить PIN» clears the credential and ends the person's sessions; the person
   then activates again. Changing a PIN needs the old one and an unlocked session. Ending a
   person's employment, or any future «end all sessions» action, ends their terminal sessions
   in the same transaction. A password reset (ADR 0098 calls `logoutEverywhere`) does not:
   a PIN is not the password, and a forgotten password is not a compromised PIN.

5. **Unlock, session and attribution.** The device reads its roster, the person taps a name
   and types a PIN, and the platform opens one **terminal session**: a 256-bit token stored
   as a SHA-256 digest, bound to the device and the member, valid until the idle limit or
   the maximum length, held by the device in memory only, so a reload locks the terminal. There is
   at most one live session per device and one per person across the tenant; opening a second
   ends the first with reason `SUPERSEDED`, so the live session is where the person is. The
   device sends the token in `X-Terminal-Session`. Only user-initiated traffic keeps a session
   alive: a mutating request (`POST`, `PUT`, `PATCH`, `DELETE`) extends `last_activity_at`, and so
   does the keypad's `POST …/terminal-session/touch`, which the shell sends on a tap or key press
   that is not itself a mutating call (opening a ticket, scrolling the board), at most once every
   15 seconds. A `GET`, and so the board's timed refresh (`BOARD_POLL_MS` in `device-shell.ts`),
   carries the header so its capability and attribution checks run and never extends the
   session: a terminal nobody touches locks at `terminal.auto_lock_seconds`, however many polls
   it sends, and ends at `terminal.session.max_hours` in any case. On every request from a device
   principal that carries it, the capability interceptor (a) runs ADR 0025's checks for the device,
   unchanged, (b) validates the session in the database (live, not idle-expired, this device,
   this location, the member still `ACTIVE`), and (c) asks `AuthorizationService.has` of the
   **person's subject** for the same capability at the same scope, refusing with
   `TERMINAL_PERSON_NOT_AUTHORIZED` when the answer is no. `CurrentActor` gains an
   attributed identity: the person's subject when a valid session is present, otherwise the
   device's. Code that records an actor reads the attributed one, so audit facts, ticket events
   and order attribution carry the same subject string the person's console actions carry.
   Each device has an **attribution mode**: `DEVICE_ONLY` (today: a session header is
   ignored and the device is the actor), `PERSON_OPTIONAL` (a session, when present, is the
   actor) and `PERSON_REQUIRED` (a mutating request without a live session is refused with
   `TERMINAL_UNLOCK_REQUIRED`, and the shell shows the lock). All devices start `DEVICE_ONLY`.

6. **One class-neutral registry.** A new read lists every `iam.device_principals` row at a
   location, of any class: name, class, branch, state, attribution mode, auto-lock, who is
   unlocked now, last seen and the build it reports. «Last seen» is stamped by any
   authenticated request from a device principal, at most once a minute per device in each
   node's memory and one bounded `UPDATE`, so no heartbeat endpoint exists and the signal is
   the device actually working, not a ping. A manager renames a device, sets its mode and
   auto-lock, and revokes it with a reason, which ends its sessions. Enrolment approval stays
   with the module that owns the class (kitchen's `kitchen.station.manage`, and the owning
   records of ADR 0154 and ADR 0162). The spec's «Перевыпустить код привязки» is **not**
   built as a console-issued code: ADR 0079's device displays its own code, so re-pairing is
   revoke, then pair the replacement, and the screen's «Заменить устройство» says so.

7. **The screens.** Staff → Терминалы is the spec §8 list and device record. The person
   card's «Безопасность» tab replaces «PIN на терминале — not tracked» with the state (not
   set, set, locked) and «Сбросить PIN» and «Выдать код активации». «Мой профиль» gains
   «Мой PIN»: the state, a request for an activation code, and the person's own last twenty
   unlocks (which device, when, how it ended). The kitchen shell gains the roster, the
   keypad and the lock; none of it shows unless the device's mode is not `DEVICE_ONLY`. The five
   tenant policy keys of the Specification are written on **Staff → Терминалы → Policy**, a card
   above the device list: each key with its resolved value and where it comes from, the PIN-length
   choice with the guess odds stated beside it (Open inputs 3 and 4), and a Save that writes the five
   in one transaction. They are tenant-scope values, with the per-device override of
   `auto_lock_seconds` and the device's `attribution_mode` kept on the device record. Reading needs
   `terminal.read` and writing `terminal.manage`, both held at `TENANT` scope (a location manager's
   grant does not reach it), and the keys are not tenant-visible in the generic configuration
   editor, so the card is their only write path and the capability is the control.

8. **The person can see their name being used.** The activity list in «Мой профиль» is the
   control that makes a shared PIN visible: a person who sees an unlock at a tablet they were
   not at has a reason to ask for a reset. It is not detection and not prevention, and the
   record says so (Consequences).

9. **What this record does not decide.** Badge or NFC as another way to open the same
   session, biometric unlock, attendance (`X.2`), person-only capabilities, the expo and
   hand-over class, and any customer-facing PIN. The session model is credential-agnostic:
   a badge reader is a second way to obtain the same row.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep the shared login (`kuxnya`), the status quo | Destroys every audit record in the building, which is the whole reason for the row | Never |
| Each person signs in on the tablet with their own Keycloak password through ADR 0062's direct grant | The strongest attribution, and the tablet then holds a person's refresh token, which is the failure ADR 0079 exists to end; and "nobody will type [it] on a greasy touchscreen forty times a shift" (§8) | Staff have passkeys or hardware keys a tablet can use, which ADR 0062 foreclosed for staff and a superseding record would have to reopen |
| The person's authenticator code (ADR 0148's TOTP) as the unlock | Stronger than a static PIN and needs the person's phone in a hand wearing a glove, in a kitchen; ADR 0148 is also not built | ADR 0148 ships and tenants ask for a stronger unlock on a till |
| A badge or NFC tap | Hardware per terminal; ADR 0079's own trigger names "a badge reader at the pass" as the cost to judge | A tenant buys readers: it opens the same session (Decision 9), so nothing here is redone |
| A PIN that identifies the person with no roster | Fewer taps, but PINs would have to be unique across a tenant, which leaks that a PIN is taken, and a wrong guess that lands on a colleague's PIN acts as them; lockout "of that person on that device" is impossible without a person | Never |
| A PIN per person per device | The cook re-sets it at every tablet; the credential is the person's, not the screen's | Never |
| A Keycloak-native PIN (a custom credential type and authenticator) | Keeps "Keycloak holds every credential" literal. It means maintaining a Keycloak extension for 26.7, a realm change, and a per-device lockout that Keycloak's counters cannot express; ADR 0148 already shows how Keycloak's failure counter misleads | The owner reads AGENTS.md as forbidding the verifier here (Open input 2) |
| Lockout in the ADR 0033 limiter | Per replica, forgotten on restart: a restart resets a guesser. A lockout is a correctness decision and ADR 0033 says none may read cache state | Never |
| The device's grant alone authorizes (a PIN only names the person) | A dishwasher can press «ready» under a cook's name, and a revoked cook's PIN is a way in | Never |
| The person's grants replace the device's | A terminal would hold whatever its last user held, and ADR 0079's ceiling on what a screen can ever do would be gone | Never |
| Require a person on every device from day one | Breaks every running kitchen on the day it ships, to fix evidence they do not yet miss | A tenant's audit finding is traced to a device still on `DEVICE_ONLY`; that tenant's `terminal.attribution.default` then changes, not the platform's |
| Let the manager choose or see a PIN (§8 forbids it) | A manager who knows a PIN can act as the person | Never |
| A shift clock-in (`X.2`) as the identity | Attendance is a different fact from authentication, and it is not built | `X.2` exists: a clock-in can order the roster, not replace the PIN |

## Consequences

### Positive

- «Who pressed ready» has an answer a manager can read in the activity log (§9) beside the
  person's console actions, under one subject, and a stolen or shared login is no longer
  the only way to run a shared screen.
- ADR 0079 holds: the tablet still authenticates as itself, holds no person's credential,
  is revoked in one row, and can do no more than its bundle allows.
- A PIN can only narrow. A person cannot do at a terminal what they may not do in the
  console, and revoking a person's grants stops their PIN working in the same request.
- The lock numbers do not depend on the process: a restart does not forgive a guesser.
- One registry shows every device, including the ones ADR 0151, 0154 and 0162 add, with the
  same «last seen».

### Negative

- A HorecaOS-held verifier, however narrow, is a credential store the platform did not have
  before, with a pepper to rotate and a table whose leak is bad (the digests alone are
  harmless without the pepper; with it, six digits are seconds).
- A PIN can be told to a colleague. Nothing here stops it: the person's own activity list
  makes it visible after the fact and not before. This is "better than a shared login", not
  as strong as a personal sign-in.
- Griefing exists: someone holding a tablet can fail twelve times against a colleague's name
  and lock the credential for the day. The remedy is an activation code, and the audit shows
  which device.
- The roster shows colleagues' first names and a last initial to whoever holds the tablet,
  and a device credential stolen from a tablet can enumerate them from anywhere (the secret
  sits in `localStorage` by ADR 0119's decision) and can guess PINs at the lock rate.
- Every site that records an actor must read the attributed one. A site that keeps reading
  `currentActor.get().subject()` attributes a person's action to the device without any error;
  the kitchen board is the first set (the controller sites listed in the checklist) and
  `kitchen.ticket_events` needs a `via_device_id` so the device is not lost.
- Existing KDS bundles gain `terminal.unlock`, which changes ADR 0079's sentence that the
  bundle holds those two capabilities "and nothing else" (the scoped supersession in the header).
  It is a machine-only capability that opens no board data.
- A shift change costs a tap and six digits per cook.
- Two ways to revoke a kitchen tablet (kitchen's and the registry's), by two capabilities.

### Accepted trade-offs

- `DEVICE_ONLY` stays the default, so the evidence the row exists for is opt-in per device.
  A tenant that never switches a device on has exactly today's audit trail.
- A PIN is a six-digit secret typed on a shared touchscreen in a room with people in it. No
  shuffled keypad in v1.
- A person who works at two branches has one PIN, and a reset at one branch resets it at
  both.
- The lockout constants are not tunable. A tenant that finds them too strict phones support,
  which is the intent.
- «Last seen» is the last authenticated request, not a ping, so a healthy idle kiosk that polls
  once a minute and a stuck one are told apart by how long since, not by a flag.

## Specification

### Physical model (schema `iam`; numbers reserved by the wave that builds it, checked against every worktree; every row has `tenant_id`; grants as noted)

```text
iam.device_principals  (V0192; additive columns)
  uq_device_principal_location (tenant_id, location_id, id)   shared with ADR 0154 and ADR 0162: the first of the
                                                               three migrations to merge adds it, the others find it
                                                               present and add nothing
  attribution_mode varchar(16) NOT NULL DEFAULT 'DEVICE_ONLY'   DEVICE_ONLY | PERSON_OPTIONAL | PERSON_REQUIRED
  auto_lock_seconds integer NULL                                 NULL = the tenant policy; 30..3600
  last_seen_at timestamptz NULL, last_seen_build varchar(64) NULL
  CHECK (attribution_mode = 'DEVICE_ONLY' OR device_class IN ('KITCHEN_KDS'))   -- restated per unlockable class
  ck_device_principal_class restated with every class in force on main (KITCHEN_VDU, KIOSK, PRINT_AGENT
  are added by ADR 0151, 0162 and 0154; the last migration to merge carries all of them)

iam.staff_pins
  tenant_id, staff_member_id        PK; (staff_member_id, tenant_id) -> iam.staff_members (id, tenant_id) [uq_staff_member_identity]
  status varchar(10)                NOT_SET | ACTIVE | LOCKED
  pin_digest varchar(64) NULL, pepper_version smallint NULL, pin_version integer, pin_length smallint NULL
  set_at timestamptz NULL, set_on_device_id uuid NULL, activated_by varchar(255) NULL    -- SELF or the issuing subject
  window_started_at timestamptz NULL, failures_in_window integer NOT NULL DEFAULT 0
  locked_at timestamptz NULL, locked_reason varchar(24) NULL
  version, created_at, updated_at
  CHECK ((status = 'ACTIVE') = (pin_digest IS NOT NULL))   -- a credential locked by the 24-hour rule drops its digest: only an activation can replace it

iam.staff_pin_activations
  id (Ids.newId), tenant_id, staff_member_id, code_digest varchar(64), issued_by varchar(255), issued_via varchar(8)  SELF | MANAGER
  status varchar(10)                PENDING | CONSUMED | EXPIRED | SUPERSEDED | BURNED
  failed_attempts smallint, expires_at, consumed_at null, consumed_device_id null, created_at
  unique (tenant_id, staff_member_id) WHERE status = 'PENDING'

iam.terminal_pin_locks
  tenant_id, device_id, staff_member_id     PK (device_id, staff_member_id)
                                            (tenant_id, device_id) -> iam.device_principals (tenant_id, id) [uq_device_principal_tenant_id]
                                            (staff_member_id, tenant_id) -> iam.staff_members (id, tenant_id) [uq_staff_member_identity]
  consecutive_failures smallint, locked_until timestamptz NULL, last_failure_at timestamptz NULL

iam.terminal_sessions
  id (Ids.newId), tenant_id, device_id, staff_member_id, location_id
  (tenant_id, location_id, device_id) -> iam.device_principals (tenant_id, location_id, id) [uq_device_principal_location]:
      the session's location is its device's location, enforced by the database and not by the service
  (staff_member_id, tenant_id) -> iam.staff_members (id, tenant_id) [uq_staff_member_identity]
  token_digest varchar(64) UNIQUE, opened_at, last_activity_at, idle_seconds integer, hard_expires_at
  ended_at NULL, ended_reason varchar(16) NULL    LOCKED | SUPERSEDED | REVOKED | PIN_RESET | EMPLOYMENT_ENDED | IDLE | MAX_AGE
  unique (device_id) WHERE ended_at IS NULL;  unique (tenant_id, staff_member_id) WHERE ended_at IS NULL
  index (tenant_id, staff_member_id, opened_at DESC)         -- the person's own list
```

`GRANT SELECT, INSERT, UPDATE` to `horecaos_application` on each (`iam.staff_pin_activations`
and `iam.terminal_sessions` are never deleted; a purge job removes sessions and activations
older than 400 days under its own grant). Row-level security joins when the `iam` schema's
ADR 0056 wave does. `kitchen.ticket_events` gains `via_device_id uuid NULL` (tenant-scoped
foreign key to `iam.device_principals (tenant_id, id)`), written whenever a session was the
attribution, so a board action names both the person and the screen. `StaffMembers` and
`TenantRoleCatalog` exclude every new machine role.

### Verification, in one transaction

```text
BEGIN
  SELECT * FROM iam.staff_pins WHERE (tenant_id, staff_member_id) = (...) FOR UPDATE   -- one attempt at a time per person
  refuse uniformly if: device not ACTIVE, class not unlockable, member not ACTIVE, member not eligible at this location
  if status = LOCKED or the (device, member) lock is in force -> TERMINAL_PIN_LOCKED { retryAfterSeconds }
  digest = HMAC(pepper[pepper_version], tenant | member | pin_version | pin); constant-time compare
  wrong: consecutive_failures + 1 (lock 15 min at 5); failures_in_window + 1 (window slides after 24 h; at 12 status = LOCKED and the digest is cleared); audit
  right: reset consecutive; end any session of this person elsewhere (SUPERSEDED) and of this device; insert session; audit
COMMIT
```

A uniform `TERMINAL_UNLOCK_REFUSED` answers a wrong PIN, an unknown member and an ineligible
person alike; only a lock is distinguished, because the legitimate person needs to know to wait.

### Eligibility and the roster

`iam` computes eligibility from the member's active grants through the same applicable-grants
view `AuthorizationService#viewFor` reads, never from a second table: a member is eligible at a
terminal when they are `ACTIVE`, not `ON_LEAVE` or `ENDED`, and `AuthorizationService.has` holds
for every capability in the device class's attribution set (`KITCHEN_KDS`: `kitchen.ticket.advance`)
at the terminal's `LOCATION` scope, so a grant at brand or tenant scope counts. The roster is that
list, ordered by label, and is cached for 30 seconds as an ADR 0033 registered accelerator because
it is a display; `unlocks` and `pin-activations` recompute eligibility every time. The label is
`NAME_INITIAL` or the `display_reference` per policy, and the response never carries a phone, a
photo, an employee number or the member's Keycloak subject; the opaque `memberId` is the staff
member row's id.

### Endpoints (ADR 0031; all under the OPERATIONS OpenAPI group; the device-facing ones sit at a location path so the ordinary `@RequiresCapability` strategy applies and no new authorization strategy is introduced)

```text
GET  /api/v1/tenants/{t}/brands/{b}/locations/{l}/terminal-session/roster          terminal.unlock   machine-held
POST …/terminal-session/unlocks            { memberId, pin }                          terminal.unlock   no Idempotency-Key (see below)
POST …/terminal-session/pin-activations    { memberId, activationCode, newPin }       terminal.unlock   opens a session
POST …/terminal-session/pin-changes        { currentPin, newPin }                     terminal.unlock   needs a live session
POST …/terminal-session/touch                                                        terminal.unlock   extends this device's session; 204, no body
POST …/terminal-session/locks                                                        terminal.unlock   ends this device's session

GET  …/locations/{l}/terminals                                                       terminal.read     every device principal at the location
PUT  …/locations/{l}/terminals/{deviceId}     { displayName, attributionMode, autoLockSeconds }   terminal.manage   If-Match
POST …/locations/{l}/terminals/{deviceId}/revoke   { reason }                         terminal.manage   Idempotency-Key
GET  …/locations/{l}/terminals/{deviceId}/sessions?cursor=                           terminal.read     who was unlocked, when, how it ended

GET  /api/v1/operations/tenants/{t}/staff/me/pin                                     @StaffSelfAuthorized(staff.self.manage)   state and own last 20 sessions
POST /api/v1/operations/tenants/{t}/staff/me/pin-activations                         @StaffSelfAuthorized      returns the code once
POST /api/v1/operations/tenants/{t}/staff/members/{memberId}/pin-activations         staff.profile.manage {reason}   TENANT, and the LOCATION twin ADR 0139 has
POST /api/v1/operations/tenants/{t}/staff/members/{memberId}/pin-reset               staff.profile.manage {reason}

GET  /api/v1/operations/tenants/{t}/terminal-policy                                  terminal.read    TENANT   the five keys, resolved, with where each comes from
PUT  /api/v1/operations/tenants/{t}/terminal-policy   { pinLength, autoLockSeconds, sessionMaxHours, rosterDisplay, attributionDefault }
                                                                                      terminal.manage  TENANT   Idempotency-Key
```

The responses of `unlocks`, `pin-activations` and the two activation issues carry a secret, so
they are not `@Idempotent` (a stored response would put a bearer or a code into
`platform.idempotency_records` for the retention day) and are listed by exact path in
`EndpointCapabilityDeclarationTests` with that reason, as the sign-in endpoints are; the
test's own rule that the next endpoint under `/terminal-session/` is not quietly exempted
along with them is kept. `touch` carries no secret and returns nothing; it needs no
`Idempotency-Key` because its only effect is a timestamp moved forward and a repeat is identical,
and it is listed by exact path with that reason as well. Roster labels, a name and a reason are
classified `@Classified(PERSONAL)` where they appear in a response an `@Idempotent` handler reaches;
`IdempotentResponseClassificationTests` is the guard. `Cache-Control: no-store` on the roster.
The device finds its tenant, brand and location from the self-read ADR 0151 specifies; if
that read's path is given a prefix no OpenAPI group claims, the build places it under
`/api/v1/session/**` (`OpenApiContractTests` refuses an uncategorised path), and the paths
here follow it.

Capabilities are new: `terminal.read` and `terminal.manage` (`LOCATION`; manage held by
`LOCATION_MANAGER`, `TENANT_ADMIN`, `TENANT_OWNER`, read also by `BRAND_MANAGER`), and
`terminal.unlock` (`LOCATION`), held by machine roles only and added to
`PlatformRole.KITCHEN_DEVICE`. The bundle is code-owned (ADR 0025), so existing devices gain
it without re-enrolment, which the build confirms by reading how `iam.roles` is synchronised
before relying on it. PIN reset and activation reuse `staff.profile.manage` exactly as ADR
0139 scopes it, rather than minting a third PIN capability.

### The interceptor, concretely

`CapabilityEnforcementInterceptor` stays the single place a capability is checked. After the
principal's own check it does, only when the principal is a device of an unlockable class:
read `X-Terminal-Session`; if absent, apply the device's mode; if present, resolve the digest,
require the live-session conditions, extend `last_activity_at` only when the request is a mutating
method or `terminal-session/touch` and then only when it is more than 15 seconds stale (a `GET` never
extends it, so the board poll cannot keep a session alive), and
`AuthorizationService.has(personSubject, capability, scope)`; publish the result as the request's
attribution. `PERSON_REQUIRED` refuses `POST`, `PUT`, `PATCH` and `DELETE` without a
session except the `terminal-session` endpoints. It adds one grant read per device request,
served by the same ADR 0033 grant cache `has` already uses; session validity is read from the
table, never from a cache.

### Policy keys (ADR 0030) and the audit facts (ADR 0027)

`terminal.pin.length` (4 or 6, tenant, default 6), `terminal.auto_lock_seconds` (default 300),
`terminal.session.max_hours` (default 12, 1 to 16), `terminal.roster.display`
(`NAME_INITIAL` or `REFERENCE`), `terminal.attribution.default` (default `DEVICE_ONLY`). All five are
registered not tenant-visible in the generic configuration surface
(`OperationsConfigurationController` filters on `ConfigurationKey#tenantVisible`; the build confirms
how before relying on it), and the `terminal-policy` endpoints above are their only tenant write path.
`SECURITY`-class facts: `terminal.pin.activation_issued` (issuer, member, via),
`terminal.pin.set`, `terminal.pin.changed`, `terminal.pin.reset` (with reason),
`terminal.pin.locked` (device, which rule), `terminal.unlock.refused` (the fifth consecutive
failure and the twelfth in a day only, not each one: each wrong attempt is on the table, and
an attacker must not be able to fill the audit log), `terminal.session.opened`,
`terminal.session.ended` (reason, except `IDLE` and `MAX_AGE`, which the row carries and a
lazy check derives), `terminal.device.configured` (through `ChangeDocuments.diff`),
`terminal.policy.changed` (through `ChangeDocuments.diff`, naming the person who saved the card) and
`terminal.device.revoked`. A person's PIN, its digest, an activation code and a session token
are redacted from every change document. An action attributed to a person carries the device
in `evidenceReference` as `terminal:<sessionId>` and in its change document, and its actor is the person
(`ActorRef.user`), so the activity log's «Кто» column shows a name and the device is one click
away.

### Observability and alerts

Metrics, outcome only: `horecaos.terminal.unlocks` (`SUCCEEDED`, `WRONG_PIN`, `LOCKED`,
`NOT_ELIGIBLE`), `horecaos.terminal.sessions.open` by class, `horecaos.terminal.pin.locks`.
Operations alerts through `OperationsAlertPort` (an ADR 0058 operations alert), naming
`display_reference` and device only: a locked credential (`TERMINAL_CREDENTIAL_LOCKED`), and a
`PERSON_REQUIRED` device unseen for the configured minutes during service
(`TERMINAL_DEVICE_UNSEEN`; the spec's "an offline kitchen tablet during service is the only urgent
row"). No Kafka event is published; a consumer would bring its schema and catalogue entry first
(ADR 0032).

`fanOut` takes an `eventClass`, and it reaches only chats subscribed to a class in the closed
`TelegramEventClass` set, backed by `integration.telegram_binding_events`'s
`ck_telegram_binding_event_class` (last widened by V0488). Neither class above is in it today, so
an alert raised under either would reach no one («silent on no subscriber»). The build therefore adds
`TERMINAL_CREDENTIAL_LOCKED` and `TERMINAL_DEVICE_UNSEEN` to `TelegramEventClass` (with their
labels) and restates the full `ck_telegram_binding_event_class` list in a migration, carrying every
value in force forward as V0488 does; each class is its own semantic template key, whose wording a
tenant authors as for `MARKETPLACE_CHANNEL_STALE`. ADR 0154 widens the same list for
`PRINTER_OFFLINE` and `PRINT_JOB_DEAD`: the last of the two migrations to merge carries every
value in force. The idempotency key base names the subject and the episode (the lock's `locked_at`,
the device's last-seen instant), so a replayed trigger reaches a chat once and a later lock alerts
again.

### Testing (each seen failing first)

- A terminal-session token presented as `Authorization` anywhere is 401; the header with no
  device bearer is refused before the handler; a staff token calling `unlocks` is refused by
  capability.
- Intersection: a person lacking `kitchen.ticket.advance` at the location cannot be
  eligible, and a session opened before their grant was revoked is refused on the next
  request with `TERMINAL_PERSON_NOT_AUTHORIZED`; a revoked person's PIN no longer opens one.
- Lockout survives a restart and is race-proof: forty parallel wrong attempts for one person
  count exactly forty and lock at five and at twelve; success does not clear the 24-hour
  count; the lock is per person per device and a second device is not locked by the first.
- One session per device and per person: unlocking elsewhere ends the first with `SUPERSEDED`.
- Idle lock against a polling board: a session whose only traffic is the timed `GET` refresh
  carrying `X-Terminal-Session`, with the clock advanced past `terminal.auto_lock_seconds`, is locked,
  and the next mutating request is refused `TERMINAL_UNLOCK_REQUIRED` in `PERSON_REQUIRED`; a
  `touch` or a mutating call moves the deadline; `terminal.session.max_hours` ends the session
  whatever its traffic. The shell's poll timer never calls `touch` (front-end test).
- The database refuses a `terminal_sessions` row whose `location_id` is not its device's location, and
  one whose device or member belongs to another tenant; `TenantScopedReferenceCatalogTests` stays
  green with `known_tenant_blind_references.tsv` empty.
- A chat subscribed to `TERMINAL_CREDENTIAL_LOCKED` and one subscribed to `TERMINAL_DEVICE_UNSEEN`
  each receive that alert once; a chat subscribed to neither receives nothing; every class in
  `TelegramEventClass` is admitted by the restated CHECK.
- The terminal policy: `PUT terminal-policy` by a location manager is refused (the grant does not reach
  `TENANT` scope) and by a tenant administrator succeeds, the five keys are neither listed nor writable
  through the generic configuration endpoints, and the card shows the guess odds beside the length.
- `PERSON_REQUIRED` refuses a start/ready without a session and accepts it with one; `DEVICE_ONLY`
  ignores the header and attributes to the device; every call site that records an actor in the
  kitchen board is covered by one test that fails if it still reads the device.
- A PIN equal to the last six digits of the person's phone, or of their employee number, a run,
  or a deny-listed value is refused with the reason; a manager cannot set or read one.
- The activation code is single-use, expires, burns after five wrong tries, and a second
  `PENDING` one supersedes the first.
- Revoking a device ends its sessions and the next request is refused; ending employment ends the
  person's sessions in the same transaction.
- No digest, token, code or PIN appears in any log line, audit change document, event or
  metric (the redaction test and a log-capture test, snapshotted under the appender lock).
- `EndpointCapabilityDeclarationTests`, `IdempotentResponseClassificationTests`,
  `OpenApiContractTests`, `DatabasePrivilegeTests` and `ModularArchitectureTests` pass.
- Front end: the shell renders no lock in `DEVICE_ONLY`; the keypad never stores a digit in
  `localStorage` and clears on lock; key parity for the new strings; the token is in memory only.

## Rollout and rollback

Ship with no behaviour change: the columns default to `DEVICE_ONLY`, the lock UI does not appear,
the registry lists what exists, and the new capability sits in a bundle nothing calls. Switch
one pilot tablet to `PERSON_OPTIONAL` beside the old way for a week, then to `PERSON_REQUIRED`.
Rollback is setting the device back to `DEVICE_ONLY`, which restores today's attribution at once;
the tables stay as evidence, and no other module's data changes shape except the nullable
`via_device_id`.

## Implementation checklist

- [ ] Owner accepts the record; Open input 2 is answered before the first migration.
- [ ] Migrations: the `iam.device_principals` columns and restated class CHECK,
      `uq_device_principal_location` unless ADR 0154 or ADR 0162 already carries it, the four new
      tables with grants, `kitchen.ticket_events.via_device_id`, and the restated
      `ck_telegram_binding_event_class` with the two new classes; the next free number checked in
      every worktree.
- [ ] `DevicePrincipalClass.unlockable()`; `Capability` gains `terminal.read`, `terminal.manage`,
      `terminal.unlock`; `PlatformRole.KITCHEN_DEVICE` gains `terminal.unlock`; bundle tests and the
      machine-role exclusions in `TenantRoleCatalog` and `StaffMembers`.
- [ ] The HMAC pepper as an ADR 0028 reference, and its startup refusal when absent (the
      `PartnerConfiguration` shape).
- [ ] `PinService`, the verification transaction, activations, `TerminalSessionService`, the purge job,
      the lock and employment-end hooks (`StaffMemberChanged`); the two `TelegramEventClass` values and
      their alert callers; the `terminal-policy` endpoints and the five keys' registration.
- [ ] `CurrentActor` attributed identity; the interceptor changes; the kitchen board call sites
      (`start`, `ready`, `recall`, `release`, `hand-over`, the release-schedule write and the device stop
      path `StopSource.KITCHEN_DEVICE` reserves) read the attributed actor; the audit facts.
- [ ] The registry endpoints, the self and manager PIN endpoints, the exact-path idempotency
      exemptions, the five OpenAPI baselines and the generated client.
- [ ] Staff → Терминалы and its Policy card, the person card's security panel, «Мой PIN», the shell's
      roster, keypad and lock (and its `touch` on user taps only), ru / uz-Latn / en strings, key parity.
- [ ] An IA row for the screen. In the building wave, set `Superseded by ADR 0155` on ADR 0079 and ADR
      0139 for the statements named in the header, and record the extension on ADR 0025; do not edit any
      of their arguments, tables or open inputs. This record edits none of them.
- [ ] Runbook: reset a PIN, find who was at a device, rotate the pepper.
- [ ] Tests listed above, each seen failing first.

## Exit criteria

On a pilot kitchen tablet switched to `PERSON_REQUIRED`, a cook taps her name, types her PIN and
presses «ready» on a ticket item; the ticket event and the audit fact name her, with the tablet
as the device, and the activity log shows her console and her tablet actions under one name. The
same tablet refuses «ready» with no one unlocked, refuses a dishwasher who holds no kitchen
capability, locks her for 15 minutes after five wrong PINs on that tablet while another tablet
still accepts her, locks her credential after twelve in a day, and, after her manager resets it,
lets her set a new one at the tablet with an activation code and without the manager ever seeing
it. Her profile lists the last unlocks. Revoking the tablet ends the session and the next request
fails, and none of it signs anyone into the console.

## References

- ADR 0025, ADR 0027, ADR 0028 (pepper as a reference), ADR 0029, ADR 0030, ADR 0031, ADR 0033
  (the limiter this does not use), ADR 0041, ADR 0062, ADR 0076, ADR 0079 (the primitive and the
  rejected row), ADR 0098, ADR 0119 (the shell and the stored credential), ADR 0139 (the person
  record and the split), ADR 0148 (MFA, which excludes devices), ADR 0151 (the self-read, the
  expo input), ADR 0154, ADR 0162
- `platform/docs/operations-gap-map.md` rows `X.3` (PART A and PART B), `0.1d`, `9.2`;
  `platform/docs/operations-spec/staff-and-access.md` §8, §9, §11.1, §11.7;
  `platform/docs/frontend-information-architecture.md` section 9
- `DevicePrincipalClass`, `DeviceEnrolmentPort`, `DeviceEnrolmentController`, `KitchenDeviceController`,
  `KitchenDeviceService`, `KitchenBoardController`, `CapabilityEnforcementInterceptor`,
  `AuthorizationService#has`, `CurrentActor`, `HandoverCodeHasher`, `PartnerConfiguration`,
  `StaffMembers`, `TenantRoleCatalog`, `StaffSelfAuthorized`, `StaffMemberController`,
  `StaffSelfController`, `ActorRef`, `AuditFact`, `OperationsAlertPort`; `V0030`, `V0192`, `V0453`
- `frontend/operations/src/app/device/`, `features/kitchen/devices-page.ts`,
  `core/i18n/messages/staff.ru.ts`
