# ADR 0162: Kiosk device pairing

- Decision status: Accepted — proposed by Claude (batch 19); accepted by the platform owner 2026-10-07
- Implementation status: Not started — a kiosk can be registered as a sales channel and
  cannot be paired, listed or revoked as a device, and no kiosk client exists. What is
  there is the channel and the primitive, side by side and unjoined. The channel:
  `SalesChannelSystemType.KIOSK` and `ck_sales_channel_system_type` (V0020);
  `tenant.sales_channels` (with `guest_orders_allowed` and a nullable
  `provider_installation_id`) and `tenant.sales_channel_locations`, which says where a
  channel sells; `QrKioskHallPricing` and `catalog.qr_kiosk_price_plane` (a kiosk takes the
  hall's prices, built, `4.4d`); `PromotionValidator`'s channel set and the stop and
  notification-template channel variants (V0392, V0407, V0462) all name KIOSK;
  `FiscalTerminalKind.KIOSK` and `fiscal.fiscal_terminals` (V0247) give a kiosk its fiscal
  identity "at the cost of one row" (ADR 0038), unlinked to any device; the console's
  channel-setup hub renders the KIOSK section as a locked card reading «Not built yet»
  (`channel-setup-page.html`, `settings.channelSetup.kiosk.locked`). The primitive:
  `DevicePrincipalClass` has one constant, `KITCHEN_KDS`, and V0192's
  `ck_device_principal_class` and `ck_device_enrolment_class` accept only it;
  `DeviceEnrolmentController.begin` takes the class from the request body and its
  response carries no console link; approval exists only as
  `KitchenDeviceController`/`KitchenDeviceService` under `kitchen.station.manage`, which
  hard-codes the role `kitchen-device`, while `DeviceEnrolmentService.approve` reads the
  *requested* class off the pending row and the role from the caller, so with a second class
  in the enum nothing in code stops one class's code being approved with the other's role.
  The ADR 0119 shell hard-codes `deviceClass: 'KITCHEN_KDS'` (`device-session.ts`),
  asks the installer to type tenant, brand and location, and renders a QR of the user
  code alone. No kiosk application exists: the information architecture lists "the kiosk
  device app" among three separate deployables, and what the console has is `q-kiosk-frame`
  (`shared/ui/kiosk-frame.ts`), a 9:16 preview of the menu (`X.28`).
- Date proposed: 2026-10-07
- Date decided: 2026-10-07
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: [ADR 0025](../built/0025-fine-grained-authorization-and-capability-model.md),
  [ADR 0027](../built/0027-audit-evidence-and-approval-model.md),
  [ADR 0030](../built/0030-configuration-and-policy-resolution.md),
  [ADR 0031](../built/0031-http-api-conventions.md),
  [ADR 0036](../partial/0036-sales-channels-and-location-serviceability.md),
  [ADR 0038](../partial/0038-legal-entities-fiscal-receipts-and-product-classification.md),
  [ADR 0079](../partial/0079-kitchen-display-device-principal-and-enrolment.md),
  [ADR 0082](../built/0082-a-feature-flag-is-a-boolean-configuration-key.md),
  [ADR 0087](../built/0087-a-module-is-sold-on-its-own-unit-and-switches-features-on.md),
  [ADR 0119](../partial/0119-wave-p17-device-shell-auth-and-qr-pairing.md),
  [ADR 0151](../built/0151-a-wall-display-device-class.md),
  [ADR 0155](../not-started/0155-terminals-and-staff-pins.md) (still Proposed: see the
  open input on accepting this record before it)
- Supersedes / Superseded by: Supersedes ADR 0079's note that `roleCode` is a parameter of
  `ApproveEnrolment`, for every class; supersedes ADR 0151 Decision 3's placement of the
  class-to-role map in `KitchenDeviceService` (the map moves to `DeviceEnrolmentService`, and
  ADR 0151's narrowing rule is unchanged); reopens ADR 0119 Decision 5's sentence «ADR 0079
  keeps a pairing code typed, never scanned, and this component does not reopen that
  decision» and ADR 0079's typed-`userCode` step for the KIOSK class only, and nothing else
  in any of them changes. For the kiosk class the QR carries a link to the console's approval
  page, scanned by the manager's own phone camera, and the typed code stays. The building wave
  records the reciprocal «Superseded by ADR 0162 for …» lines on ADR 0079 and ADR 0151 (and the
  reopened sentence on ADR 0119) and edits neither the argument, the tables nor the open inputs
  of any of the three. This record also answers, for the kiosk, ADR 0119's open input «Device
  scope discovery», by the same self-read ADR 0151 Decision 5 gives the kitchen. It does not
  reopen the parity matrix's decline of «Self-service kiosk hardware integration»:
  provisioning, certification and field support of kiosk hardware, bank terminals, receipt
  printers and marking scanners stay declined, and Decision 8 lists every field of Delever's
  kiosk form with what becomes of it.
- Open inputs: each is closed on its proposed default if the owner accepts the record
  as written; the ones that name a person other than the owner, or an external fact, stay
  with that person and the work they block is marked.
  - **How a kiosk places an order and takes payment** (platform owner, product). Proposed
    default: not decided here, and the principal is given nothing to order with: its role
    holds one read capability (Decision 1). The kiosk application is a separate deployable
    that waits for a pilot tenant to name physical kiosks, as the parity matrix says. The
    direction the next record should test is the guest-cart path the model already carries
    (`guest_orders_allowed`, `guest_reference_hash`, ADR 0015's guest claim, which the
    storefront controller does not open), through a short-lived kiosk order session minted by
    the device on its channel, never `order.place`, which would let a customer-facing device
    call the operator's order endpoints. Blocks: the kiosk application, and nothing in this record.
  - **Whether the QR deep link is acceptable where ADR 0079 and ADR 0119 say a code is
    typed, never scanned** (platform owner, security). Proposed default: yes, for the kiosk
    only; the kitchen display stays typed until a record says otherwise (the approval page
    refuses any class but `KIOSK`, Specification). The link only opens the console's approval
    page on the manager's own signed-in session, prefilled with the code; it approves nothing,
    the manager still confirms that the code on the screen in front of them matches, and the
    typed code is unchanged. The link is built by the platform from
    `horecaos.frontends.operations-origin`, never from anything the device sends.
  - **Whether this record is accepted before ADR 0155** (platform owner). ADR 0155 is still
    Proposed, and three things this record relies on are specified there and nowhere else: the
    `last_seen_at` and `last_seen_build` columns stamped by any authenticated device request and
    the class-neutral registry (Decisions 5 and 6, and the «last-seen under two minutes» exit
    criterion), and `DevicePrincipalClass.unlockable()` (Decision 1). Proposed default: accept
    ADR 0155 first or in the same reply («accept 0155 and 0162»). If only 0162 is accepted, its
    building wave builds those pieces itself to ADR 0155's specification (the two columns and
    their once-a-minute stamp, `unlockable()` false for `KIOSK`, and the kiosk's own list in
    the KIOSK section, which Decision 6 already specifies), and ADR 0155's wave adopts them
    unchanged instead of adding them a second time.
  - **Which capability approves a kiosk** (platform owner). Proposed default: a new
    location-scoped `kiosk.device.manage`, not `kitchen.station.manage` and not tenant-wide
    `channel.manage`. ADR 0079's own rule is that a new capability "earns its place only when a
    different person should hold it", and the person who may attach a customer-facing device
    that will take orders and money to a branch is not the head chef.
  - **Whether pairing is limited by the kiosk units a tenant bought** (finance). Proposed
    default: no gate. ADR 0087 lets a module carry "the quantity agreed", and no entitlement key
    for a kiosk exists. The registry's count of active kiosks is surfaced to the control plane's
    module screen beside that quantity, and a gate (a `LockedState`) is a later decision.
  - **Idle media, the 9:16 content type and its 1 MB cap** (product). Proposed default:
    deferred. It needs the purpose column ADR 0010 lacks and nothing reads it until a kiosk
    application exists.
  - **Custody of a device credential on a customer-reachable device** (security). Proposed
    default: accepted as ADR 0119 left it (the credential is in the browser's `localStorage`),
    on the condition that the kiosk runs under an operating-system kiosk lockdown, with a
    runbook that revokes on any theft and re-pairs on any hardware service visit. There is no
    rotation timer, as ADR 0079's open input still says.
  - **A «service PIN»** (product, security). Proposed default: declined. It is a device
    maintenance code (`staff-and-access.md` §11.7: "must not be conflated" with a staff PIN),
    and there is nothing on a kiosk for it to protect: no application exists, and the shell has
    no settings screen. The control that matters is revoking the device. Revisit when a kiosk
    application has a maintenance mode and the operating system cannot lock it.
  - **Which locations may host a kiosk** (operations). Proposed default: a location where the
    chosen KIOSK channel has an `ACTIVE` row in `tenant.sales_channel_locations`.
  - **Linking a kiosk to its fiscal terminal** (finance, ADR 0038). Proposed default: no link.
    The fiscal terminal is resolved by location and kind (`KIOSK`) when an order is fiscalized,
    which is the record that lets a kiosk order exist.
  - **Kiosk hardware** (product). Proposed default: stays declined. A tenant that fits a printer
    in a cabinet uses ADR 0154's agent at its location as for any printer, and certification and
    field support remain outside HorecaOS.

**To accept as written:** say "accept 0162" (preferably in the same reply as "accept 0155").
Every open input above is then closed on its proposed default.

**Decision record, 2026-10-07.** Accepted by Ayubkhon Abbosov (platform owner) under the standing instruction "lets finish all" given the same day, which accepts every record proposed in batch 19 (ADRs 0154–0176) on the default each open input proposes. An input that names a person other than the owner, or an external fact (a device model, a legal wording, a provider capability, a dataset publication), stays with that owner as written and implementation proceeds without it, marking what waits. Implementation starts in operations batch 20 (2026-10-07).

## Context

Gap-map row `10.5` is `PARTIAL` and says what is left: "only kiosk device pairing remains: no
device registry, no pairing flow, and `DevicePrincipalClass` carries no kiosk principal to pair
against." PART B names the input: "a decision on the kiosk device registry — hardware, pairing
UX, and the principal class it authenticates as." Three questions in one sentence, and they have
three different answers.

**The principal class is the easy one, and it is only easy because ADR 0079 anticipated it.**
A kiosk is a screen bolted to a floor in a fixed place: a `LOCATION`-scoped device principal in
the ADR 0079 sense, enrolled by a human, revocable in one row. ADR 0079's Alternatives are
explicit that a role "at BRAND or TENANT scope, so one enrolment covers every branch" is wrong
"for a screen with a fixed physical location", and that is a kiosk. ADR 0151 has just written
down how a class is added: an enum value, a role, one capability, a narrow read and a
server-side configuration. This record follows that pattern, and pays for one thing ADR 0151
touched and did not finish: approval.

**Approval has a hole that a second class opens.** `DeviceEnrolmentService.approve` reads the
requested class from the pending enrolment and takes the role from its caller;
`KitchenDeviceService.approve` always passes `kitchen-device`. With one class the two cannot
disagree. With a kiosk in the enum, a kiosk's pairing code approved through the kitchen's
endpoint would create a customer-facing device granted `kitchen.ticket.advance`, and a kitchen
tablet's code approved through a kiosk endpoint would hold the kiosk's role. ADR 0151 Decision 3
closes it for the kitchen's narrowing case; this record closes it for the primitive, so that no
class added later can be approved through another module's endpoint.

**Pairing UX is the part the spec leaves at «device credentials» and ADR 0119 left at "typed".**
Settings §10.5 lists the kiosk form as "device login and password", and the IA row says "device
credentials". Both describe a person's account on a machine, the shared credential ADR 0079
refuses. The ADR 0119 shell is built and stores nothing like that: it holds a Keycloak
service-account secret that was issued once, to the device, and no human typed. What it does ask
of whoever installs it is the tenant, brand and location, typed, because "none names it". The
installer of a kiosk in a mall is not a restaurant employee with a console. ADR 0151's self-read
removes the typed scope for the kitchen; the same read removes it here. What remains is the
approval, and a manager approving a code read off a lobby screen is slower and likelier to
mistype than a manager scanning it, which is the whole case for the link.

**Hardware is declined, and what that does and does not cover.** The parity matrix declines
"self-service kiosk hardware integration (Arcus2 bank terminals, 58/80 mm receipt printers, …)"
as "a hardware product line with device provisioning, certification, field support and a per-device
fiscal identity, gated on physical terminals in restaurants", keeping the kiosk "a recognised sales
channel … from day one — that costs a row". Settings §10.5 turns that into a form that "ships with
identity, branch, booking customer profile, payment/order types and idle media", the fiscal identity
from the location's legal entity, and the printer, terminal and marking block as a `LockedState`.
This record does not reopen the decline. It builds the thing the decline calls «identity»: a
principal, a pairing, a registry and a revocation for a device the tenant owns and installs. It
provisions nothing, certifies nothing and supports no one's hardware.

**What does not exist is a kiosk to pair.** There is no kiosk application, and the order path a
kiosk would use is undecided. The storefront's checkout needs a signed-in customer: `CartService`
and `CheckoutEligibilityGuard` honour a channel's `guest_orders_allowed` and the cart schema takes a
`guest_reference_hash`, but ADR 0019 records that guest carts are "supported by the schema and
refused by the storefront controller", so no caller can reach that path. The operator's order
endpoint needs `order.place` and files the order under the operator's channel, not the kiosk's. That
is why Decision 7 gives the principal exactly one read. A device that holds `order.place` at a location is a device any customer who
reaches its credential can use as an operator; the safest default for a class whose application
does not exist is to grant it nothing it cannot yet use, and add the first write in the record that
decides it.

## Decision

**Add one device class, `KIOSK`, a customer-facing device bound to a KIOSK sales channel at one
branch, paired by a code the manager confirms, listed and revoked in the console, and granted one
read capability; make approval derive the role from the approved class so no class can be approved
through another module's endpoint; and keep kiosk hardware, a service PIN and the ordering path
out.**

1. **A class, a role, one capability.** `DevicePrincipalClass` gains `KIOSK`; both V0192
   constraints are restated in full with every class in force on `main` (ADR 0151 adds
   `KITCHEN_VDU`, ADR 0154 `PRINT_AGENT`: the last migration to merge carries all of them).
   `PlatformRole` gains `KIOSK_DEVICE` (`kiosk-device`, `LOCATION`), excluded from
   `TenantRoleCatalog` and `StaffMembers` like every machine role, holding exactly
   `kiosk.device.read`. `unlockable()` is false (ADR 0155): nobody unlocks a kiosk with a PIN, and
   a customer is never a staff identity. A second capability, `kiosk.device.manage`, is held by
   `LOCATION_MANAGER`, `TENANT_ADMIN` and `TENANT_OWNER` and by no machine.

2. **A kiosk is bound to a channel, and the database says so.** A kiosk sells through one KIOSK
   channel, at one location where that channel is `ACTIVE`; it does not create a channel, and many
   kiosks share one. `tenant.kiosk_devices` records the binding (Specification) with foreign keys
   that refuse a non-KIOSK channel, a location the channel has no binding row at, and a location
   other than the one its principal is enrolled at (the service also refuses an `INACTIVE`
   binding). The V0046 lesson applies twice: a foreign key references a unique constraint on
   exactly its own columns, and every one of these carries `tenant_id` (a reference between two
   tenant-scoped tables that leaves it out fails `TenantScopedReferenceCatalogTests`, whose
   allowlist is empty and stays so). The target tables' own keys do not qualify: the channel
   table's tenant-bearing unique is `(tenant_id, id)`, which cannot carry the type, and the
   channel-location table's primary key is `(channel_id, location_id)`, which carries no tenant.
   So the migration adds `uq_sales_channel_type (tenant_id, id, system_type)` to the channel table
   and `uq_sales_channel_location_identity (tenant_id, channel_id, location_id)` to the
   channel-location table, and the device's location rides on `uq_device_principal_location
   (tenant_id, location_id, id)` on `iam.device_principals`, shared with ADR 0154 and ADR 0155
   (Specification, Model). A tenant that needs separate menus or prices per kiosk makes a channel
   per kiosk with the mechanism ADR 0036 already has.

3. **Approval takes a class, not a role.** `ApproveEnrolment.roleCode` is replaced by the approved
   class; `DeviceEnrolmentService` owns a closed class-to-role map and a rule that the approved class
   must equal the requested one, except the narrowing ADR 0151 Decision 3 allows (a requested
   `KITCHEN_KDS` may be approved as `KITCHEN_VDU`). A kiosk's code approved as anything else, and any
   other class's code approved as a kiosk, is refused naming both classes. The grant's reason string
   stops hard-coding "kitchen device".

4. **The pairing flow.** The kiosk shell (a mode of the ADR 0119 `/device` shell, chosen by the
   installer at the one setup step that remains: *Kitchen display* or *Kiosk*) calls
   `POST /api/v1/control-plane/device-enrolments` with `deviceClass: KIOSK`. The response gains
   `approvalUrl`, built by the platform as the operations origin plus the approval page's path plus
   the pending `userCode`, and never containing the device code. The shell shows the code in large
   type and `q-qr-code` of `approvalUrl`, counts down the ten minutes, and begins a fresh enrolment
   when it expires. The manager scans with their phone's own camera (the shell does no scanning),
   lands on the console, signs in if needed, and sees *Settings → Channel setup → KIOSK → Pair a
   kiosk* with the code prefilled and a read of the pending request (class, requested label, expiry).
   They choose the branch from those where they hold `kiosk.device.manage`, the channel from the
   KIOSK channels active at it, and a display name, and confirm with a button that says, in the
   page's own words, that the code on the screen in front of them is the one shown. Approval is
   `POST …/kiosk/enrolments/{userCode}/approve`. A typed code works identically and the page asks the
   same confirmation. Nothing is approved by arriving on the page.

5. **After approval the kiosk needs nothing typed.** The device claims its credential once, as in
   ADR 0079, mints tokens as in ADR 0119, and reads the self-read of ADR 0151, which for a kiosk
   returns its tenant, brand, location, timezone and `kiosk: { channelId, channelCode }`. The
   typed scope step of ADR 0119 is gone for this class. The shell polls the self-read once a minute,
   which is also its presence signal (ADR 0155 stamps «last seen» on any authenticated device
   request).

6. **The registry, revocation and replacement.** *Pair a kiosk* sits beside a list of the branch's
   kiosks in the KIOSK section of channel setup, behind the same location scope bar the other
   settings screens use: name, channel, state, last seen, build, enrolled by, revoked by and why.
   A manager renames a kiosk, moves it to another KIOSK channel active at the same branch
   (`If-Match`), and revokes it with a reason. Revocation is ADR 0079's, unchanged: the grant is
   revoked and the Keycloak client disabled, so the next request is refused and the next token mint
   fails, the shell clears its stored credential and returns to the pairing screen. «Replace» is
   revoke, then pair the new unit; there is no console-issued code, because the device displays its
   own. Every kiosk also appears in ADR 0155's class-neutral Terminals list, read-only for what is
   kiosk-specific.

7. **What the kiosk can do in this record.** Authenticate, read its own record, and read
   `GET …/kiosk/configuration` under `kiosk.device.read`: the channel (code, name, status), the
   branch (name, timezone, current service state and the next time it is open), the fulfilment modes
   and payment methods enabled on the channel's matrix, and the price-plane channel code. These come
   from the tables ADR 0036 owns and carry no personal data and no secret. It cannot read the board,
   an order, a customer or a settings document, and it cannot place an order. Every capability a
   kiosk later needs arrives with the record that decides how it orders, as an addition to this role's
   bundle.

8. **Every field of Delever's kiosk form, and what becomes of it.**

   | Delever field | Here |
   |---|---|
   | Kiosk name | The device's display name |
   | Legal company name, ИНН for fiscalization, VAT rate | Not stored on the kiosk: the location's legal entity and tax profile (ADR 0038, ADR 0018) |
   | Branch | The device's `LOCATION` scope |
   | Customer profile orders are booked under | Deferred with ordering (Open input 1); nothing reads it before then |
   | Payment types, order types | The channel's matrix (`channel_payment_methods`, `channel_fulfillment_modes`), read through the configuration |
   | Company address | The location's address |
   | Printer width (58/80 mm) | A printer's fact in ADR 0154, not a kiosk's |
   | Service PIN | Declined (Open input) |
   | Device login and password | Never: the device's own service-account credential, issued once to the device and shown to no one |
   | Status | The principal's status (`ACTIVE` or `REVOKED`) |
   | Idle media, content type | Deferred (Open input) |
   | Fiscal operator, fiscal URL or IP, terminal protocol | Declined hardware integration; fiscal identity is a `fiscal.fiscal_terminals` row of kind `KIOSK` (ADR 0038, V0247) |
   | Marking endpoint, kiosk marking id, marking toggle | Declined; marking is the fiscal and classification records' |
   | Table service | The channel's fulfilment modes and ADR 0047 where it applies |
   | Available reports | Declined: a kiosk is a channel slice of the sales report (statistics.md §2.7) |

9. **Behind a flag.** `feature.kiosk_devices` (ADR 0082, off) gates the Pair and Registry
   cards and the self-read's kiosk member; the class and the migration are inert without rows.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Enrol a kiosk as `KITCHEN_KDS` with a different role | A class is the closed set ADR 0079 wants precisely so one deployment cannot spell it two ways, and the kitchen's endpoints would then approve and list it | Never |
| A staff login for the kiosk, as Delever's «device login and password» | A shared credential in a public place with a person's capability set, the failure ADR 0079 ends | Never |
| Identify the kiosk as a registered storefront app under ADR 0070 (an app id and an origin allowlist) | A browser app's identity cannot be revoked per physical unit, and ADR 0070 is about third-party storefronts. The device principal gives per-unit revocation; an app identity, once ADR 0070 is built, would sit beside it and say which application runs on the unit | ADR 0070 is built and the kiosk application is registered as one: the principal then adds per-device revocation to an app identity |
| One sales channel per physical kiosk, as Delever does | Every report and rule keyed on the channel then multiplies per unit, which statistics.md §2.7 calls "a precedent that ends in eleven reports" | A tenant needs a price plane or a menu per kiosk, which the binding already allows by giving each kiosk its own channel |
| Typed codes only (ADR 0079's and ADR 0119's position) | Fine beside a kitchen pass; for a kiosk in a lobby or a mall it is eight characters read off a screen by a phone user. The link adds a prefill and nothing else | A security review finds the confirmation step does not cover a hostile QR on a counterfeit screen: the page drops to typed-only |
| Keep `roleCode` a parameter of approval and trust each module | It is what let one class's code take another class's role, and with three modules approving devices it is three chances to misspell it. ADR 0079's reason for the parameter (`iam` need not learn what a VDU is) is spent: `DevicePrincipalClass` and both class CHECKs are `iam`'s and already name every class. Hence the scoped supersession of that note | Never |
| Grant the kiosk `order.place` now so it can order | A customer-facing device holding the operator's order capability at a location, in a record that has not decided how a kiosk orders or is paid | The ordering record decides it; the bundle grows then |
| A kiosk hardware certification and provisioning programme | The parity matrix's reasons stand: certification, field support, a per-device fiscal identity, gated on physical terminals in restaurants | A pilot tenant names kiosk hardware and a vendor will certify it |
| An application service PIN now | There is nothing to protect on a kiosk that has no application and no settings screen | A kiosk application has a maintenance mode and the OS cannot lock it |
| Gate pairing on a purchased kiosk quantity now | No entitlement key exists and the price list is unwritten (ADR 0087) | Finance sells kiosk units and a tenant exceeds its quantity |

## Consequences

### Positive

- A kiosk is a listed, revocable, location-bound principal before any customer-facing code rides
  on it, and it can be paired, shown in a registry and killed with the same machinery as a kitchen
  tablet.
- The approval hole closes for every class, not only this one: a class cannot be approved through
  another module's endpoint, whatever is added later.
- Nobody types a tenant, a brand or a location into a lobby tablet.
- The registry shows a kiosk's channel, branch, last seen and build, which is what an operator who
  discovers a dark kiosk needs.
- The decline of kiosk hardware holds: nothing here certifies, provisions or supports a device.

### Negative

- A principal with one read is a class nothing yet uses. The record builds identity and lifecycle
  for an application that does not exist, which is the "no call site means Partial" smell, and its
  status line will say so; the value is that pairing, binding and revocation are proved before orders
  depend on them, and that a tenant can plan its installation.
- A device credential sits in `localStorage` of a machine customers can touch. Revocation is fast
  and does not stop a secret already copied from being replayed against Keycloak until the client is
  disabled; the control is the operating-system lockdown, which HorecaOS does not provide.
- The QR shows the console's origin to the public. The origin is already the public sign-in page, so
  nothing is disclosed that a search would not, and the page still demands a signed-in manager.
- The approve path's signature changes in `iam`, which ADR 0151 changes too, and the two records
  place the class-to-role map differently (ADR 0151 Decision 3 in `KitchenDeviceService.approve`,
  this record in `DeviceEnrolmentService`). Supersedes settles it: the map lives in
  `DeviceEnrolmentService`, and ADR 0151's narrowing rule (a requested `KITCHEN_KDS` approved as
  `KITCHEN_VDU`) is a row of that map. Whichever of the two builds first builds the map there; the
  other adapts to it and adds only its own rows.
- One more capability, `kiosk.device.manage`, and one more table beside the principal.
- A kiosk bound to a channel that is later archived keeps working as a device and sells nothing:
  its configuration read reports the channel's status, and the registry flags it.

### Accepted trade-offs

- The class cannot do anything a customer would notice. That is deliberate.
- A scanned QR from a counterfeit screen can mislead a manager as a mistyped code can; the
  confirmation step and the manager's physical presence at the kiosk are the control, and neither
  is cryptographic.
- A kiosk replaced in place loses its history with the unit: a new unit is a new principal.
- No per-kiosk report: orders will carry the device id when ordering lands, and the channel slice
  remains the reporting unit.

## Specification

### Model (additive; numbers reserved by the wave that builds it, checked against every worktree)

```text
iam.device_principals / iam.device_enrolment_requests   ck_device_principal_class and ck_device_enrolment_class
                                                         restated with every class in force ('KITCHEN_KDS', ... 'KIOSK')
iam.device_principals                                    + uq_device_principal_location (tenant_id, location_id, id)
                                                         shared with ADR 0154 and ADR 0155: the first of the three
                                                         migrations to merge adds it, the others find it present and
                                                         add nothing (a migration that finds it present drops its own copy)
tenant.sales_channels                                    + uq_sales_channel_type (tenant_id, id, system_type)
tenant.sales_channel_locations                           + uq_sales_channel_location_identity (tenant_id, channel_id, location_id)
                                                         its primary key (channel_id, location_id) carries no tenant_id

tenant.kiosk_devices
  tenant_id, device_id                    PK (device_id)
  brand_id, location_id                   (tenant_id, brand_id, location_id) -> tenant.locations (tenant_id, brand_id, id)
                                          (tenant_id, location_id, device_id) -> iam.device_principals (tenant_id, location_id, id)
                                          [uq_device_principal_location]: the kiosk's location is its principal's location
  channel_id, channel_type varchar(16)    CHECK (channel_type = 'KIOSK');
                                          (tenant_id, channel_id, channel_type) -> tenant.sales_channels (tenant_id, id, system_type)
                                          [uq_sales_channel_type]
                                          (tenant_id, channel_id, location_id) -> tenant.sales_channel_locations (tenant_id, channel_id, location_id)
                                          [uq_sales_channel_location_identity]
  version, created_at, updated_at

PlatformRole.KIOSK_DEVICE   {KIOSK_DEVICE_READ}   LOCATION                 excluded from TenantRoleCatalog and StaffMembers
Capability.KIOSK_DEVICE_READ   ("kiosk.device.read",   "kiosk", "device.read")     machine only
Capability.KIOSK_DEVICE_MANAGE ("kiosk.device.manage", "kiosk", "device.manage")   LOCATION_MANAGER, TENANT_ADMIN, TENANT_OWNER
```

`GRANT SELECT, INSERT, UPDATE ON tenant.kiosk_devices TO horecaos_application` (no delete: a revoked
kiosk is history). The row is inserted in the approval transaction beside the principal; a `KIOSK`
principal with no row (a defect, or an approval that half-committed) reports `kiosk: null`, the shell
shows «not configured», and the registry flags it. The first FK pins the kiosk to its
principal's own location, through `uq_device_principal_location`; the second references the
channel's `(tenant_id, id, system_type)`, through `uq_sales_channel_type`; the third the
channel-location pair, through `uq_sales_channel_location_identity`. A kiosk therefore cannot be
bound to a channel of another type, to a channel of another tenant, to a location the channel has
no binding row at, or to a location other than the one its principal is enrolled at, and every
reference names `tenant_id`, so `TenantScopedReferenceCatalogTests` passes with
`known_tenant_blind_references.tsv` empty and no entry is added for this table. The service
additionally refuses an `INACTIVE` binding, and rebinding re-checks all of it.

### Endpoints (ADR 0031; under the OPERATIONS group like the kitchen's; capabilities per ADR 0025)

```text
POST /api/v1/control-plane/device-enrolments                 (existing, unauthenticated)  response gains approvalUrl
GET  /api/v1/tenants/{t}/brands/{b}/locations/{l}/kiosk/enrolments/{userCode}   kiosk.device.manage   the pending request: class, label, expiry; no secret
POST …/kiosk/enrolments/{userCode}/approve   { displayName, channelId }          kiosk.device.manage   Idempotency-Key
GET  …/kiosk/devices                                                              kiosk.device.manage   active and revoked, with channel and last seen
PUT  …/kiosk/devices/{deviceId}              { displayName, channelId }          kiosk.device.manage   If-Match
POST …/kiosk/devices/{deviceId}/revoke       { reason }                          kiosk.device.manage   Idempotency-Key
GET  …/kiosk/configuration                                                       kiosk.device.read     the calling device's own location
GET  <the ADR 0151 self-read>                                                    gains kiosk: { channelId, channelCode, channelName, channelStatus }
```

`approve` refuses, each with a stable code and both classes named in `detail`: an unknown or expired
code (404), a code that requested another class, a channel that is not a KIOSK channel or not active
at this location (422), and a location where the caller lacks the capability (403, by scope, as every
`LOCATION` endpoint). `DeviceEnrolmentPort` gains `peek(userCode)` (read-only, class-neutral, no
secret, rate-limited per approver on the ADR 0033 limiter with approval's own budget, because it is a
second oracle on the same short code) for the approval page, and its `approve` takes
the approved class. `kiosk/configuration` carries only: the channel's id, code, display name and status;
the branch's name and IANA timezone; its service state and next-available time; the channel's enabled
fulfilment modes and payment-method codes; the price-plane channel code; and a version for caching.
The device sends nothing identifying; the location comes from the path and is verified against its own
principal by `ResourceScopeVerifier`.

### The approval page, concretely

Route `settings/channel-setup/kiosk-pairing` (lazy-loaded with the other channel-setup facets), reached
from the QR or from the KIOSK card's *Pair a kiosk*. It reads `peek`, shows the requested class and
label as escaped text (the label is whatever the device sent, never trusted, never a credential, at most
120 characters), the expiry, and the branch and channel choosers. A class other than `KIOSK`, an expired
or already-approved code, and a caller with the capability at no branch each render their own explanation
in place of the form. A signed-out manager is sent through the console's ordinary sign-in and returned to
the same URL.

### Audit (ADR 0027), PII (ADR 0029), observability

`SECURITY` facts, each naming the approver or revoker and the device: `kiosk.device.enrolled`
(requested and approved class, channel, branch, display name, the requested label), `kiosk.device.rebound`
(through `ChangeDocuments.diff`) and `kiosk.device.revoked` (with the reason). Nothing here is personal
data, and no secret, device code or credential reaches a log, an audit change document or a metric. The
approver's name is resolved through `StaffDirectory` for display only. Metrics, outcome only:
`horecaos.kiosk.pairings` (`APPROVED`, `REFUSED_CLASS`, `EXPIRED`), `horecaos.kiosk.devices.active`.
No Kafka event: nothing consumes one (ADR 0032).

### Testing (each seen failing first)

- Approval: a requested `KIOSK` approved through the kitchen endpoint is refused, a requested
  `KITCHEN_KDS` through the kiosk endpoint is refused, and the role granted always follows the approved
  class (read back from `iam.grants`); ADR 0151's allowed narrowing still passes.
- The database refuses a `tenant.kiosk_devices` row for a non-KIOSK channel, for a channel of another
  tenant, for a location where the channel has no binding row and for a location other than its
  principal's (a principal enrolled at A, a row naming B where the channel is bound), and refuses a
  rebind to any of them; the service refuses an `INACTIVE` binding with a stable code. The test
  seeds a second tenant with its own KIOSK channel and binding, so a tenant-blind key would
  accept the cross-tenant row and the test would fail.
- `TenantScopedReferenceCatalogTests` stays green with `platform/tools/checks/known_tenant_blind_references.tsv`
  empty: no allowlist entry is added for `tenant.kiosk_devices`.
- A kiosk token reads `kiosk/configuration` and is refused, with the capability named, the kitchen
  board, an order read, a customer read, a settings read and `order.place`; it holds no capability
  but `kiosk.device.read`.
- A kiosk at location A is refused location B's configuration, at the endpoint and by the composite
  keys; a manager with `kiosk.device.manage` at A cannot approve, list or revoke at B.
- `approvalUrl` is the configured operations origin plus the page path plus the `userCode`, is identical
  whatever `Host` and `X-Forwarded-*` the request carried, and never contains the device code.
- Landing on the approval page approves nothing: no endpoint is called until the confirmation.
- Revoke: the next request of the kiosk is refused, its next token mint fails and the shell returns to
  pairing with its stored credential cleared (the ADR 0119 spec, extended for the class).
- The role is absent from `TenantRoleCatalog` and from the staff list; the migration test accepts `KIOSK`
  and rejects an unknown class in both tables.
- `EndpointCapabilityDeclarationTests`, `OpenApiContractTests`, `ModularArchitectureTests`,
  `DatabasePrivilegeTests` green; front end: no scope typed for a kiosk, key parity, the card gated by
  `feature.kiosk_devices`.

## Rollout and rollback

Ship dark: `feature.kiosk_devices` is off, the KIOSK card stays «Not built yet», nothing changes for a
tenant with no kiosk. The approval change in `iam` ships first and alone, because it is a correction that
protects the classes already in flight, and its tests are the ones above. Enable the flag for the pilot
tenant when a unit exists; pair one kiosk beside a real manager's phone; watch the registry for a day.
Rollback is revoking the device and switching the flag off. The enum value, the table and the role are
inert without rows.

## Implementation checklist

- [ ] Owner accepts the record.
- [ ] `iam`: `DevicePrincipalClass.KIOSK` and `unlockable() = false`; `DeviceEnrolmentPort.approve` takes
      the approved class, the class-to-role map and the requested-versus-approved rule in
      `DeviceEnrolmentService`, `peek`; `KitchenDeviceService.approve` adapted; the grant reason no longer
      hard-codes "kitchen device"; the begin response gains `approvalUrl` from
      `horecaos.frontends.operations-origin`.
- [ ] Flyway: both class CHECKs restated with every class in force; `uq_sales_channel_type` and
      `uq_sales_channel_location_identity`; `uq_device_principal_location` unless a sibling
      migration (ADR 0154 or ADR 0155) already carries it; `tenant.kiosk_devices` with its four
      tenant-bearing foreign keys, granted; the next free number checked in every worktree.
- [ ] `PlatformRole.KIOSK_DEVICE`, `Capability.KIOSK_DEVICE_READ` and `KIOSK_DEVICE_MANAGE`, bundle
      changes and the invariant tests; `TenantRoleCatalog` and `StaffMembers` exclusions.
- [ ] `tenancy`: the kiosk service, controller and configuration read over the channel, location and
      service-state tables; audit facts; the flag; the five OpenAPI baselines and the generated client.
- [ ] The ADR 0151 self-read answers the kiosk member (built there or here, whichever lands first, to
      that record's own specification).
- [ ] Console: the KIOSK card (pair, list, rename, rebind, revoke), the approval page, scope bar,
      ru / uz-Latn / en strings, key parity; the shell's setup step becomes a class choice, the kiosk
      mode shows the pairing screen and a status screen naming the channel and branch, and the typed
      scope entry is removed for this class.
- [ ] A runbook: install a kiosk under OS lockdown, revoke on theft, re-pair on a service visit.
- [ ] The building wave sets the reciprocal «Superseded by ADR 0162 for …» fields on ADR 0079 (`roleCode`
      as an approval parameter; the typed `userCode`, for the KIOSK class) and ADR 0151 (Decision 3's
      placement of the class-to-role map) and records the reopened sentence on ADR 0119; it edits none
      of their arguments, tables or open inputs. ADR 0079's Implementation status line records a third
      class; settings.md §10.5 and the gap-map row `10.5` are updated by that wave.
- [ ] Tests listed above, each seen failing first.

## Exit criteria

At a pilot branch, a manager points a phone at a kiosk's screen, lands on the console's approval page
with the code filled in, confirms it matches the screen, chooses the branch and the KIOSK channel and
approves. Nobody types a tenant, brand or location into the kiosk; it shows that it is paired to that
channel at that branch, appears in the registry with a last-seen under two minutes, and reads its
configuration. A kitchen display's code cannot be approved there, a kiosk's code cannot be approved in
the kitchen, and the kiosk can read nothing else. The manager revokes it from the registry without
signing anyone out, the kiosk returns to the pairing screen on its next request, and changing the
approver's password changes nothing about any kiosk.

## References

- ADR 0010 (idle media needs a purpose column), ADR 0018, ADR 0025, ADR 0027, ADR 0030, ADR 0031,
  ADR 0032, ADR 0036 (channels, locations, the matrix), ADR 0038 (kiosk terminal kind), ADR 0047,
  ADR 0070 (the storefront app identity), ADR 0079 (the primitive, the scope rule), ADR 0082,
  ADR 0087 (modules sold per unit), ADR 0119 (the shell, the QR, the typed scope), ADR 0151 (the class
  pattern, the self-read, narrowing), ADR 0154 (a printer at a branch), ADR 0155 (the registry and
  `unlockable()`)
- `platform/docs/operations-gap-map.md` rows `10.5` (PART A and PART B), `X.28`, `7.7c`, `4.4d`;
  `platform/docs/operations-spec/settings.md` §10.5 KIOSK; `platform/docs/delever-parity-matrix.md`
  (kiosk hardware, kiosk channel); `platform/docs/frontend-information-architecture.md` row `10.5` and
  PART 2's «Deliberately excluded from operations»; `platform/docs/operations-spec/staff-and-access.md` §11.7 (the service PIN)
- `DevicePrincipalClass`, `DeviceEnrolmentPort`, `DeviceEnrolmentService`, `DeviceEnrolmentController`,
  `KitchenDeviceService`, `KitchenDeviceController`, `SalesChannelSystemType`, `ChannelSetupController`,
  `QrKioskHallPricing`, `FiscalTerminalKind`, `TenantRoleCatalog`, `StaffMembers`; `V0020`, `V0046`,
  `V0192`, `V0247`, `V0392`, `V0407`, `V0462`
- `frontend/operations/src/app/device/device-session.ts`, `device-shell.ts`,
  `shared/ui/qr-code.ts`, `shared/ui/kiosk-frame.ts`,
  `features/settings/channel-setup/channel-setup-page.html`, `features/kitchen/devices-page.ts`
