# ADR 0139: Staff identity: the staff person record

- Decision status: Accepted
- Implementation status: Partial — built in operations batch 17 (wave `w4-staff-identity`):
  the tenant's own record of each staff member (`V0453`, encrypted through
  `FieldProtection`, with the retention columns), the reference that is never reused
  (`V0454`), a branch's contact person (`V0455`) and a member's emergency contact
  (`V0456`); the `iam.api.staff` port (`StaffDirectory`, `StaffMemberCards`,
  `StaffMemberRegistry`, `StaffPhotos`) over a `(tenant, subject)` cache evicted on
  write, which replaces `CachedStaffDisplayNames`; row creation inside the
  invitation, promotion to `ACTIVE` on acceptance and in the owner's `completeSetup`;
  `StaffMemberReconciler` (backfill for accounts that predate the record, plus the
  completion gauges) and `StaffMemberRetentionSweeper` in report-only mode; the
  member, `me`, photo, end-employment, emergency-contact and branch-contact
  endpoints, the four capabilities and `@StaffSelfAuthorized`; audit facts through
  `ChangeDocuments` with the redaction set extended; `MappingEntityType.OPERATOR` and
  the POS operator pairing; names on the operator leaderboard and the live operator
  band; and the console (People list and card, «Мой профиль», branch contact persons,
  emergency-contact panel, shell chip, i18n parity, lazy-loaded); a photo's picture
  leaving the store when it is removed, replaced, loses an upload race or its owner is
  anonymised (`StaffPhotos.discard` marks the private tenant-owned asset
  `DELETION_REQUESTED` in the caller's transaction and `MediaAssetDeletionWorker`
  removes the objects and renditions and marks it `DELETED`); and a branch contact list
  that shows a colleague who has left as a reference alone, marked `formerColleague`,
  and refuses to list one. Not built: the drift
  half of ADR 0009 (the reconciler only reconciles grants into staff members and
  promotes `PENDING` ones, it does not detect drift in `iam.principals` or
  `iam.tenant_membership_links`); a retention sweeper that deletes (it ships
  `REPORT_ONLY` until legal approves a sample); ending a
  `PENDING` member does not cancel its outstanding invitation, and ending employment is
  `ENDED` first and then a per-grant revoke, not one transaction; operator names on the
  report export (`ReportExportService`); the CRM log's «Оператор» column, the call log
  and the presence view, which still print the Keycloak subject; a branch manager
  (`LOCATION_MANAGER`) holds no `location.write`, so branch contacts are read-only for
  that job; and another member's photo cannot be set from the console. (The
  `@Idempotent` responses of other modules were not audited for personal data while
  `ClassificationScanner` did not descend into lists; batch 18 made it descend and ran it
  over every `@Idempotent` handler. That scan is only as strong as its name heuristic, and
  the heuristic has no word for a staff member's name (`PROTECTED_TERMS` holds `firstname`,
  `lastname`, `fullname` and `personname`, not `name`). The run found three false positives,
  now declared, and missed one true positive: `OrderLatenessPolicyEditorController
  .LevelResponse.approvedByName`, filled from `StaffDirectory#namesOf` in the reply to the
  idempotent `POST .../order-lateness-policy` and stored in clear in
  `platform.idempotency_records` for the retention day. It is declared
  `@Classified(PERSONAL)` now, and `IdempotentResponseClassificationTests` fails for any
  component named like `approvedByName`, `createdByDisplayName` or `operatorName` that an
  `@Idempotent` response reaches without a declaration. A staff name under a component name
  that pattern does not cover is still unseen, so the rule for the next author is to declare
  every staff-name field where it is added.)
- Date proposed: 2026-09-29
- Date decided: 2026-10-01
- Deciders: proposed by Claude (wave batch 14); Ayubkhon Abbosov (platform
  owner) decides. Written from `platform/docs/operations-gap-map.md` rows `0.1d`,
  `0.2c`, `9.2`, `9.2b`, `9.2c` and `X.5`, held for an ADR since batch 3 on the
  strength of `operations-spec/staff-and-access.md` §11.1.
- Depends on: ADR 0003, ADR 0009, ADR 0010, ADR 0025, ADR 0026, ADR 0027,
  ADR 0029, ADR 0031, ADR 0033, ADR 0043, ADR 0060, ADR 0097, ADR 0116
- Supersedes / Superseded by: —
- Open inputs:
  - Whether ADR 0079's revisit trigger for per-action human attribution (a badge
    or PIN on a shared kitchen screen) has fired, and whether terminals and PINs
    (row `X.3`) and staff rosters (row `X.2`) stay separate records as this one
    proposes or are folded in as `staff-and-access.md` §11.1 recommended. This
    record splits them because their open questions are different ones (a PIN
    security policy that ADR 0079 already argued against, and an ADR 0042
    amend-or-fold question for shifts) and neither should hold the person record
    hostage; the split needs the owner's sign-off (platform owner).
  - The labour-law retention period for an ended employee's personal data.
    ADR 0029's provisional default (`PERSONAL` is kept while the account is
    active, then 24 months) applies until legal answers, and the retention
    sweeper this record specifies stays report-only until then (legal).
  - The lawful basis and the notice for holding a third party's contact details
    as an emergency contact. The person named never dealt with the platform, so
    the tenant's basis for asking for it, and what the staff member must tell
    that person, is a legal question this record does not answer (legal).
  - Whether ranking named individuals on a shared wallboard is acceptable, and
    whether the TV variant shows names or the non-personal `display_reference`.
    This record makes names visible to holders of the same capability that reads
    the board and marks the wallboard variant as an open choice, because a live
    ranking of employees is workplace monitoring in a way a closed-day report is
    not (product, legal).
  - Which jobs hold `staff.profile.manage` at `LOCATION` scope, so a branch
    manager can edit the people of their own branch. This record proposes
    `TENANT_OWNER` and `TENANT_ADMIN` at `TENANT` scope and `LOCATION_MANAGER` at
    `LOCATION` scope for people whose every active grant is inside that location.
    ADR 0103 (Proposed) is about who may manage grants and is not decided here;
    the two must not be confused (product, platform owner).
  - A staff analogue of ADR 0049's self strategy. `staff.self.manage` cannot be
    enforced by ADR 0025's scope-coverage rule, because a grant at `LOCATION` scope
    never covers a `TENANT` route and most staff hold only a location grant (see
    Context). This record proposes `@StaffSelfAuthorized`, modelled on
    `@CourierSelfAuthorized`: the capability must be held at any scope in the tenant
    and the handler touches only the caller's own row. That is a new authorization
    strategy beside the four `EndpointCapabilityDeclarationTests` knows, and the
    Alternatives table records the fallback that adds none. It needs the platform
    owner's and security's sign-off before anything is built (platform owner,
    security).
  - Whether a staff member may ever change their own sign-in identifier (the
    phone that is their Keycloak username) or their reset email from the console.
    This record says no in v1: both are identity facts that need a verified
    change flow, and the Delever parity matrix leaves the question open ("does
    Personal Data include phone/login change or MFA"). A verified-change flow is
    its own decision (security, product).


**Decision record, 2026-10-01.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "accept all" over ADRs 0136–0144. Every open input above is closed on the default this record proposes for it; where a record defers an input to a named owner, that deferral stands as written and implementation proceeds without it. Implementation starts in operations batch 17 the same day.

## Context

**Six gap-map rows have waited on the same missing record.** `0.1d` (live
operator leaderboard) says that even a counting endpoint would print Keycloak
subject UUIDs, because no staff person record with a display name exists.
`0.2c` (personal data on «Моя работа») says a staff member cannot view or correct
their own name or phone. `9.2` (the staff person record) says every name on
every Staff screen and every actor in the audit log is a UUID. `9.2b` (contact
persons) was rescoped in batch 12 to a named contact person, because
`tenant.locations.contact_phone` already covers the branch-phone half. `9.2c`
(operator to POS operator id) is `PARTIAL`: `PosAdapter.OrderExport` carries
`operatorExternalId` and nothing writes the mapping row that resolves it. `X.5`
(«Мой профиль»; the T20 wave note calls it `9/X.5`) has «Мои должности» and the
Telegram self-link built, and renders
a named-absence note for «Личные данные» and most of «Безопасность». The
`staff-and-access.md` §11.1 heading is the summary: "no name, no phone, no
photo, no ADR".

**What the code holds today, checked against the source and not against the
gap map.**

- **Keycloak is already the store, by accident of sequence.**
  `KeycloakStaffAccounts#create` posts `firstName`, `lastName` and an
  `attributes.phone` list at invitation, sets the username to the normalised
  phone, and `#completeSetup` rewrites `firstName` and `lastName` when the
  invited person accepts. `StaffAccounts#displayName` reads "First Last" back
  and its own Javadoc calls that "interim per the staff-identity ADR".
  ADR 0009's status line records a password-grant trap on blank names
  (keycloak#36108), closed by writing non-blank names while the realm's User
  Profile relaxes the requirement, so this record keeps writing a name to
  Keycloak at creation instead of depending on that relaxation.
- **Names reach two call sites, through one cache that knows no tenant.**
  `StaffDisplayNames#displayName(String subjectId)` is injected into
  `AuditQueryService` (the activity log's actor column) and
  `OperationsOrderController` (the order detail's created-by and accepted-by
  lines); `OperatorTodayCountsService` leaves the name to its caller and its
  Javadoc says so. It is cached as `staff.display_names` (ten minutes, "TTL only
  (no name-change event yet)" in `CacheRegistry`) under the subject alone. The
  port's Javadoc is careful that a caller resolves only subjects already present
  in a result it was authorised to read, but the signature carries no tenant, so
  that care is convention and not structure.
- **The People screen has no names at all.** `GrantView` (mirrored in
  `staff-api.ts`) carries `principalSubject` and nothing else about the person,
  and the header comment of `staff-row.ts` says outright that "no display name,
  phone, or photo exists anywhere".
- **The reporting leaderboard prints subjects.** `OperatorAttribution` resolves
  `accepted_by_actor_id`, then `created_by_actor_id`, then a `channel:<code>`
  pseudo-operator, and its Javadoc records that a `USER` row is "a bare Keycloak
  subject" until this ADR lands. `GET .../reporting/operator-leaderboard` is
  `REPORTING_READ` at `TENANT` scope and states the same limit in its
  `@Operation` text.
- **The two IAM evidence tables hold identifiers only, on purpose.** `V0057`
  says a principal is "a realm and a subject id; the profile behind it stays in
  Keycloak", and `iam.principals` is the one deliberately not tenant-scoped
  table `V0057` marks as such. No code reads or writes either table, so the
  ADR 0009 membership-drift half is still unbuilt for the same reason.
- **The POS operator mapping has a reader and no author.**
  `PosOrderExportService#resolveOperatorExternalId` looks up entity type
  `"OPERATOR"` in `integration.provider_entity_mappings` with the accepted-by
  subject parsed as a UUID. `MappingEntityType`, the enum the 10.8b mapping pane
  offers, has no `OPERATOR` value, so no screen can write the row. `CloposAdapter`
  deliberately never reads the field (Clopos documents no operator field on
  order creation), and no other POS adapter exists in this build.
- **Other artefacts key on the raw subject and will keep doing so.**
  `iam.grants.principal_subject` is `varchar(255)`;
  `integration.telegram_staff_links.principal_subject` (V0105),
  `tenant.staff_invitations.subject_id` (V0313) and the order actor columns
  (`accepted_by_actor_id`) carry the same string.
- **A branch phone is already a person's phone.** `tenant.locations.contact_phone`
  (V0023) is stored in clear, on purpose, as the published line for customers
  and couriers, and its own comment says it is "frequently a manager's mobile in
  practice". A named contact person for the branch is a different thing from the
  published line, and today it has nowhere to live.
- **A courier already has a person record, and it is a different principal
  class.** `fulfillment.couriers` (V0040) carries `principal_subject`, a
  non-personal `display_reference` and an envelope-encrypted
  `protected_full_name` under ADR 0029, with a self-employed engagement file
  beside it. It is the model to copy for shape and the wrong table to reuse.
- **A grant serves only the routes whose path names its level.** ADR 0025 says a
  scope "covers downward, never upward or sideways". In code,
  `JdbcAuthorizationService#hasGrant` asks whether a grant's scope
  `covers` the scope the endpoint declares (`ResourceScope#covers`), and
  `CapabilityEnforcementInterceptor#scopeOf` builds that scope from `tenantId`,
  `brandId` and `locationId` taken from the path (or a required request parameter
  of the same name). So a `TENANT` declaration is satisfied by a tenant grant only;
  a `LOCATION` declaration needs `brandId` and `locationId` in the route or the
  interceptor throws `IllegalStateException` on the first request; and
  `EndpointCapabilityDeclarationTests` refuses a `TENANT` declaration under a path
  that names a brand or a location. `LOCATION_MANAGER` and `LOCATION_STAFF` are
  `LOCATION`-scope bundles in `PlatformRole`, so nearly every person this record
  describes holds a location grant. The existing self-service precedent has the
  problem this implies: `TelegramStaffLinkCodeController#issue` declares
  `INTEGRATION_TELEGRAM_STAFF_LINK_ISSUE` at `TENANT` scope, and the capability is
  carried by the `LOCATION` bundles, so a line cook's grant does not cover the
  route (by reading; no test drives that endpoint with a location grant). A table
  that places a capability at a scope without giving the route that scope's path
  cannot serve the holder it names.
- **Staff are effectively one-tenant today, but the model does not say so.**
  `StaffInvitationService#rejectIfPhoneTaken` refuses an invitation whose phone
  already has a Keycloak account outside the inviting tenant's organization,
  without naming it. `V0057`'s own comment insists that a person can hold
  membership of several tenants (the case ADR 0009 exists to keep honest), and an
  owner or a HorecaOS support session can still land the same subject in two
  tenants. A design that assumes the refusal
  will always hold would be tenant-blind the day it stops.

**The constraint that makes the choice non-obvious is tenant isolation meeting
personal data.** The person is global (one Keycloak subject) and the employment
is not (this tenant hired them, this tenant knows their spoken languages and
their employee number). Whatever holds the profile has to let two tenants know
different things about the same subject without either reading the other's, has
to keep name and phone under ADR 0029's envelope, and has to let a manager
sort and filter people who are otherwise only ciphertext.

## Decision

**Give every tenant its own staff member record.** A new table `iam.staff_members`,
owned by the `iam` module, holds one row per `(tenant, Keycloak subject)`. It
carries the profile facts a tenant needs to tell its people apart and reach them:
first name, last name, contact phone, a private photo, interface and spoken
languages, employment status and dates, and an employee number. It is the
answer to "who is this?" for every staff screen, every audit actor, every order
attribution, the POS operator mapping and the leaderboard. It is not an
authorization record (`iam.grants` stays that) and not an authentication record
(Keycloak stays that).

**Draw the Keycloak boundary once, in a table, and hold to it.**

| Fact | Authoritative store | Who may change it, and how |
|---|---|---|
| First and last name (what the tenant shows) | `iam.staff_members`, per tenant | The person (self-service) or a holder of `staff.profile.manage` |
| Contact phone (what colleagues call) | `iam.staff_members`, per tenant | Same as name |
| Photo, spoken languages, interface language | `iam.staff_members`, per tenant | Same as name |
| Employment status, dates, employee number | `iam.staff_members`, per tenant | A holder of `staff.profile.manage` only; never the person |
| Sign-in identifier (username, the normalised phone at invitation) | Keycloak | Not editable from the console in v1 (Open input) |
| Reset and verification email | Keycloak, read on demand | Not copied, per ADR 0097's stance; not editable in v1 |
| Password, MFA, sessions | Keycloak | Keycloak's own flow; HorecaOS never sees the value |
| Job and where it is given | `iam.grants` | `iam.grant.manage`, unchanged |

Keycloak's `firstName` and `lastName` are written once, at account creation and
at invitation acceptance, because the token's `name` claim and the password grant
(ADR 0009, keycloak#36108) are safest with something non-blank there. **They are
never mirrored from later profile edits.** A subject who works in two tenants has two profiles, and a mirror would
make the last tenant to edit a name the name the other tenant's token shows.

**Protect the personal fields with the ADR 0029 scheme and nothing invented
here.** Names, phone and employee number are `PERSONAL`, stored through
`FieldProtection` with associated data binding the ciphertext to the tenant,
table, column and row. A keyed lookup hash beside the phone and the employee
number gives exact-match search without a decrypt, and for the employee number
uniqueness too. It does not give the contact phone uniqueness, on purpose: see the
next paragraph.
Employment status, the two date columns, the language columns and a
non-personal `display_reference` (`S-0142`, tenant-unique, never reused, the
same idea as a courier's) stay in clear so the screens can sort, filter and
count on them. `display_reference` is what an event payload, a log line or a
wallboard may carry when a name must not appear.

**Map a Keycloak subject to a person by one rule.** A row exists for a subject
in a tenant if and only if that subject is staff of that tenant. It is created
where the platform first learns the name, in the transaction that already writes
the neighbouring evidence: `StaffInvitationService#invite` (status `PENDING`,
beside `tenant.staff_invitations`), the owner's `completeSetup` at onboarding
(status `ACTIVE`), and a one-off backfill for accounts that predate this record.
Invitation acceptance moves `PENDING` to `ACTIVE` and updates the name the
person typed. Device principals (ADR 0079), partner clients (ADR 0049), couriers
(a different record) and HorecaOS support sessions (ADR 0081) get no row, and the
directory answers "no such member" for them so callers keep their existing
labelled rendering.

**The member row is written last, and a failure there undoes the invitation.**
`StaffInvitationService#invite` does its irreversible work first: the Keycloak
account (`accounts.create`), the organization membership (`ensureMembership`) and
the grant (`grants.grant`, which commits in its own transaction). Only then does it
open the one local transaction that writes the invitation row and its audit fact.
That last transaction has no cleanup today: an exception in it leaves a live grant
and a Keycloak account with no invitation, and `rejectIfPhoneTaken` then refuses
every later invitation for the phone as "already has an account". Putting the member
insert in that transaction adds ways for it to fail, so this record does three
things. It removes the avoidable ones. `(tenant_id, principal_subject)` cannot
collide, because the subject was created a moment earlier and an existing in-tenant
account is refused by `rejectIfPhoneTaken` before anything is written.
`display_reference` is allocated from a per-tenant counter row that the allocating
statement locks, so two concurrent invitations queue and never collide. And the
contact-phone index is not unique (next paragraph). It writes the member row, the
invitation row and the audit fact in that one transaction, so they commit or roll
back together. And a failure of that transaction, whatever its cause, runs the same
orphan cleanup the grant step already has: `abandonOrphanedAccount` is extended to
revoke the grant it is handed (`GrantAuthority#revoke`, audited) before it deletes the
account, because at this step a grant exists and a deleted account would otherwise
leave authority resting on nothing. The original exception is rethrown, and a
cleanup that itself fails leaves the existing `tenant.staff_invitation.orphan_left`
fact for an operator. The same cleanup closes the hole the invitation row alone has
today. Invitation acceptance promotes the row `PENDING` to `ACTIVE` and stores the
typed name in the transaction that records `tenant.staff_invitation.accepted`, which
runs after Keycloak has taken the password, so a rejected password leaves the row
`PENDING` with nothing to undo.

**A contact phone is a way to reach someone, not an identity.** Sign-in identity is
the Keycloak username, which is unique realm-wide and already guarded by
`rejectIfPhoneTaken`. The contact phone starts as that same number and then belongs
to the person: a cook may set it to the kitchen's shared mobile, and a new hire's
personal sign-in number may equal a number a colleague has already made their contact
phone. Making the contact phone unique per tenant would turn either into a failure
of the invitation after its irreversible steps, and would refuse a shared kitchen
line. So `phone_lookup_hash` is a plain index for exact-match search and never a
constraint, a phone edit checks format only and can never conflict with a colleague,
and no response ever reveals that another member holds the same number.

**Replace `StaffDisplayNames` with one tenant-scoped read port.**
`iam.api.staff.StaffDirectory` answers three questions and no others: the display
name for a subject in a tenant, the same for a batch of subjects in one call,
and the member id for a subject in a tenant. It is deliberately not a directory a
caller can search or enumerate; it resolves only subjects a caller already holds,
now with the tenant in the signature so the rule is structural. The cache is
re-keyed to `(tenant, subject)` and evicted on every profile write, which retires
the "TTL only" caveat. The Keycloak name lookup is kept only as the fallback for
a subject with no row, and is removed once the backfill has run everywhere.

**Add four capabilities and no more, and give each holder a route at its own
level.** The read, the manage, the audited emergency-contact read, and a
self-service capability carried by every tenant-visible job. A capability held at
`LOCATION` or `BRAND` scope is only useful if a route exists whose declared scope
that grant covers, so the branch-scoped operations are specified under
`.../brands/{brandId}/locations/{locationId}/...` (and `.../brands/{brandId}/...`
for the read), beside the tenant-wide ones for `TENANT` holders. Self-service cannot
use scope coverage at all, because a person's own profile belongs to no location:
it is authorised by holding the capability at any scope in the tenant, and by the
handler touching only the caller's own row (Specification, Open inputs).

**Let a person edit their own profile, and only their own.** Self-service is
authorised by `@StaffSelfAuthorized(staff.self.manage)`, a session-derived check
and not a scope-coverage one, so a cook with a location grant reaches it. It covers
first and last name, contact phone, photo, spoken languages and interface
language. It does not cover employment, the sign-in identifier, the reset
email, the password or MFA. The password hands off to Keycloak's own flow.

**Give contact persons two homes with real foreign keys, not one polymorphic
table.** A branch contact person (`tenant.location_contact_persons`) and a staff
member's emergency contact (`iam.staff_emergency_contacts`) each reference their
owner by a tenant-scoped key. A branch contact is either a colleague (a staff
member id, no copy of anyone's phone) or an outside person (a protected name and
phone). An emergency contact is a third party's data and is read only through the
audited capability. The published branch line `tenant.locations.contact_phone` is
unchanged and stays the only number customers and couriers see.

**Key the POS operator mapping by staff member id.** Add `OPERATOR` to
`MappingEntityType` (not sourced, an operator types the till's id by hand, the
way `COURIER` already works) and store `horecaos_entity_id = staff_members.id`.
The export path resolves subject to member id through `StaffDirectory`, then
mapping to external id. The mapping is authored in the existing 10.8b pane, which
lists staff members by name, and shown read-only on the person's card.

**Resolve leaderboard names at the web layer, never in `reporting`.**
`reporting` keeps returning `operatorPrincipalId` and never holds a name (ADR
0029, ADR 0043). The controller composes names through
`StaffDirectory#namesOf` for the rows it returns, so the 7.5 report and the new
live band (row `0.1d`, an `ordering` read like `OperatorTodayCounts`, not a
report) print people, and pseudo-operators such as `channel:BOT` still resolve to
nothing and keep their typed rendering.

**End employment as one act that also ends access.** «Завершить работу» sets
`ENDED` and `employed_until`, then revokes each of the person's active grants in
the tenant through `GrantManagementService`, one audited revoke per grant, and
never as one transaction (ADR 0039's rule for bulk mutations). It does not
disable the Keycloak account, because that account may serve another tenant and
ADR 0009 has not decided a per-user disable. An `ENDED` member who still holds an
active grant is a drift shown at read time, not a stored flag.

**What this record deliberately does not decide.** Terminals and PINs (`X.3`),
staff rosters and attendance (`X.2`), MFA state and active-session projection
(`staff-and-access.md` §11.6 and §11.9), per-user disable (§11.3), tenant-defined
jobs (ADR 0025's closed input) and payroll. The person record gives each of them
a stable key to attribute to and nothing more.

### What each row gets

| Row | Reachable after this record is built |
|---|---|
| `9.2` | People list and person card show name, masked phone, photo, employment and languages from `iam.staff_members`; audit and order actors resolve to names; `GET/PUT .../staff/members` |
| `0.2c` and `X.5` | «Личные данные» edits name, phone, photo and languages; the shell chip reads the profile and not the stale token claim; password is a hand-off to Keycloak |
| `9.2b` | Branch contact persons on the location screen; staff emergency contacts on the person card, audited |
| `9.2c` | `OPERATOR` rows written from the mapping pane, resolved by the POS export path |
| `0.1d` | Live operator band with names; 7.5 report rows with names |
| Not unblocked | `X.2`, `X.3`, `9.1b`, per-user disable, MFA state |

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep Keycloak as the profile store and make reads cheaper (a longer cache, a bulk admin call) | It fixes the number of round trips and none of the real problems: the record is global while employment is per tenant, a name or phone edit by one tenant would show in another's views, nothing can be sorted or filtered without loading every user, and there is nowhere for a photo, a language set or an employee number to live | A deployment guarantees one tenant per realm, so the global record and the tenant record are the same thing |
| A global person record keyed by Keycloak subject (extend `iam.principals` with the profile) | `V0057` made `iam.principals` PII-free and non-tenant on purpose, and giving it a profile lets tenant A read or rewrite what tenant B typed about the same human. It also makes erasure a cross-tenant act and defeats ADR 0029's per-tenant key scope | HorecaOS runs a marketplace where one person keeps one portable profile across restaurants, with the person's consent |
| Reuse `fulfillment.couriers` as a general workforce table | A courier is another principal class with its own engagement model (`SELF_EMPLOYED` only, refused for employees in V0040), lifecycle and owner (`fulfillment`). Folding staff in would couple the People screen to dispatch and force a migration of a table dispatch depends on | A tenant employs couriers as `EMPLOYEE` (ADR 0042 refuses it today) and payroll needs one record per human |
| Mirror every profile edit into Keycloak, keeping Keycloak the source and HorecaOS a cache | Two tenants overwrite each other's name in the one shared Keycloak user; every edit becomes an Admin API call that can fail after the local write; it repeats the class of bug ADR 0009's status line records | Never for a person who can belong to more than one tenant |
| Hold names in clear as ordinary business data (`INTERNAL`) | A person's name and phone are `PERSONAL` by ADR 0029's own definition, and the platform has one protection scheme for exactly this. Wrongly protecting a field costs a decrypt; wrongly not protecting it costs a leak | Never |
| Audited reveal for every staff phone view, mirroring `customer.pii.reveal` | Right for hundreds of thousands of customers and marketing exports, heavy for a manager calling the cook on shift. The list payload carries a masked phone and the full number appears only on the single-person response, which removes the bulk-scraping path at no ceremony cost | The first incident of directory scraping, or a legal instruction to audit staff-data reads |
| One polymorphic `contact_persons` table (`subject_kind` plus `subject_id`) | A polymorphic id cannot carry a foreign key, so a contact could name another tenant's location and only application code would notice; `V0053`'s `tax_profile_id`, which its own comment calls a gap, shows the cost of a reference the database cannot check. Two tables cost one small helper for the shared protected pair | A third kind of owner (a supplier, a courier) needs contacts too, at which point a shared table with a real per-kind key set is worth revisiting |
| Fold terminals, PINs and staff shifts into this record (the §11.1 recommendation) | Their open inputs are unrelated to the person record's: a PIN policy ADR 0079 argued against with a stated revisit trigger, and ADR 0042's amend-or-fold question for shifts. Bundling them keeps six ready rows waiting on two unready ones | The owner prefers a single record and is prepared to close both inputs first |
| Key the POS operator mapping by Keycloak subject (today's `resolveOperatorExternalId`) | Nothing writes those rows, so no data has to move, but the subject is an authentication artefact and every other mapping (`VARIANT`, `COURIER`) keys on the HorecaOS entity. The pane needs a list of named people to map, which is the member row | A POS adapter needs the mapping before the member row exists for an account, which the backfill is meant to prevent |
| Declare the branch reads and `staff.self.manage` at `TENANT` scope and let the service accept a location grant (the first draft, and the shape `TelegramStaffLinkCodeController` has) | The interceptor refuses the location grant before the service runs, so the widening cannot live in the service; it has to be an interceptor rule, which is a new strategy that does not say so, and the declared scope would no longer describe what the route enforces | Never |
| Self-service through `LOCATION`-scope routes that take `brandId` and `locationId` as required request parameters (the `OperationsCourierController#rosterEntries` shape), plus a `TENANT` twin | Adds no authorization strategy, so it is the fallback if the owner declines `@StaffSelfAuthorized`. The cost is that a profile belongs to no location: the client passes a location only to satisfy the check, a tenant-level holder with no brand yet needs the twin, and the audit records an own-row edit at a branch scope | The owner declines a new strategy |
| Let each surface fetch its own names from Keycloak (status quo, per surface) | N Admin API calls per list, the cache keyed without a tenant, and no way to sort by name. The two current callers already show the shape of the problem | Never |

## Consequences

### Positive

- Every UUID-valued name gets one structural answer: the People list, the card,
  the audit log's actor column, the order detail's «принял» line and both
  leaderboards read the same port, in one batch call instead of one lookup per
  row, and none of them can read another tenant's row.
- Tenant isolation is by construction, not by convention: the row, the cache key
  and the port signature all carry the tenant, and a subject in two tenants has
  two rows that never see each other.
- The personal data of staff enters the ADR 0029 scheme, so it is covered by
  per-tenant keys, associated-data binding, the future re-encryption job and
  tenant offboarding, without a second mechanism.
- `reporting` stays free of personal data. The names are composed at the
  controller from the same directory the audit and order screens use, so a
  leaderboard cannot disagree with the audit log about who someone is.
- The POS operator mapping and the leaderboard need nothing from Keycloak at
  request time, so a Keycloak outage no longer blanks every name in the console.
- Ending employment closes access in the same act, which removes the worst
  version of the leaver problem (a colleague who left and still holds a grant).

### Negative

- **The same human's name now lives in two places by design.** Keycloak keeps the
  setup-time name for the token and the password grant; the tenant row keeps the
  name the console shows. They will drift, and the Keycloak account console (if
  anyone opens it) will show the old one. The rule that the console never reads
  the Keycloak name once a row exists is the only guard.
- **A backfill is required, and until it has run everywhere the code carries two
  paths.** Accounts created before this record have a name only in Keycloak. The
  fallback lookup stays until every environment reports zero subjects with an
  active grant and no row, and the backfill reads Keycloak once per subject.
- **The session token's `name` claim goes stale after a self-service edit.** The
  console shell currently reads the name from the token (`auth.ts`); it has to
  read the profile instead, and any other place that trusted the claim has the
  same problem.
- **Names cannot be sorted or searched in SQL.** The People list loads the
  tenant's members, decrypts in the application and sorts and filters there, with
  RU collation. That is acceptable for the hundreds of people a tenant has and is
  the wrong design for a tenant with tens of thousands.
- **Every read and branch write exists at up to three levels.** A tenant route, a
  brand route and a location route call one service method each, which triples the
  route table and the authorization tests in exchange for a grant level that can
  actually reach its own data. The brand level exists for the read only.
- **A person in two tenants is edited twice.** Language and photo are entered per
  tenant. The platform gives up the convenience of one profile to keep isolation.
- **A contact phone and a sign-in phone can now differ.** They are the same value
  at invitation and diverge as soon as the person edits the contact phone. The
  card has to say which is which, and support will get the question. Two members
  can also hold the same contact phone, so a phone search may return more than one
  person and the list never treats a shared number as an error.
- **Emergency contacts store a third party's data with no relationship to the
  platform.** The lawful basis and notice are open (legal). If they cannot be
  answered, that table is the first thing to remove.
- **A live ranking of named employees is workplace monitoring.** The mechanism
  makes it easy, and the policy questions in Open inputs are the only brake.
- **Ending employment can partly fail.** N grants are revoked one by one, so a
  failure in the middle leaves an `ENDED` member with residual access; the
  read-time drift flag and a retry are the remedy, not atomicity.
- **The shared audit redaction is name-based.** `ChangeDocuments` matches field
  names by substring against a fixed list (`phone`, `firstname`, `lastname`, ...).
  `employeeNumber` and `emergencyContact` are not in it, so the implementation
  must add them, and a field named `givenName` would have been recorded in clear.

### Accepted trade-offs

- A profile edit by one tenant is never visible to another, even when it is the
  same human, in exchange for isolation and per-tenant erasure.
- Names are decrypted for a list on every read rather than cached in a shared
  store, in exchange for keeping plaintext personal data out of any cache that
  leaves the process. The name cache is in-process, ten minutes, evicted on write.
- The full phone appears on the single-person response without an audit record,
  in exchange for not putting ceremony in front of a manager who needs to call
  someone. The list payload is masked and there is no export.
- Employment here is a status, two dates and a number. No contract, salary,
  position title or national identifier is stored, because a payroll record is a
  different system with different retention and different law.

## Specification

### Physical model (additive; numbers reserved by the wave that builds it)

```text
iam.staff_members
  id uuid primary key                                  -- Ids.newId()
  tenant_id uuid not null references tenant.tenants
  principal_subject varchar(255) not null              -- the same string iam.grants carries
  display_reference varchar(32) not null               -- 'S-0142', tenant-unique, never reused; from iam.staff_member_counters
  protected_first_name text null                       -- ADR 0029 PERSONAL, AAD-bound
  protected_last_name text null
  protected_phone text null
  phone_lookup_hash varchar(64) null                   -- keyed per tenant; exact match only
  protected_employee_number text null
  employee_number_hash varchar(64) null
  photo_asset_id uuid null                             -- PRIVATE visibility, TENANT owner
  ui_locale varchar(8) null                            -- 'ru' | 'uz' | 'en'
  spoken_languages varchar(8)[] not null default '{}'
  employment_status varchar(16) not null               -- PENDING | ACTIVE | ON_LEAVE | ENDED
  employed_from date null
  employed_until date null
  version integer not null default 1
  created_at, updated_at timestamptz
  unique (tenant_id, principal_subject)
  unique (tenant_id, display_reference)
  unique (id, tenant_id)
  foreign key (photo_asset_id, tenant_id)
    references media.assets (asset_id, tenant_id)      -- uq_media_assets_tenant_scoped (V0058); a null photo is not checked
  index  (tenant_id, phone_lookup_hash) where phone_lookup_hash is not null   -- NOT unique: a contact phone may be shared
  unique (tenant_id, employee_number_hash) where employee_number_hash is not null
  check (employment_status <> 'ENDED' or employed_until is not null)
  check (employed_until is null or employed_from is null or employed_until >= employed_from)

iam.staff_member_counters                            -- allocates display_reference; locked by the allocating statement
  tenant_id uuid primary key references tenant.tenants
  last_reference integer not null default 0             -- INSERT ... ON CONFLICT DO UPDATE SET last_reference = last_reference + 1 RETURNING

tenant.location_contact_persons
  id, tenant_id, location_id                           -- (tenant_id, location_id) -> tenant.locations
  relationship_code varchar(24) not null               -- MANAGER | OWNER | LANDLORD | SECURITY | MAINTENANCE | OTHER
  staff_member_id uuid null                            -- (tenant_id, staff_member_id) -> iam.staff_members
  protected_name text null, protected_phone text null  -- exactly one of {staff_member_id} or {name, phone}
  version, created_at, updated_at

iam.staff_emergency_contacts
  id, tenant_id, staff_member_id                       -- (tenant_id, staff_member_id) -> iam.staff_members
  relationship_code varchar(24) not null               -- SPOUSE | PARENT | CHILD | SIBLING | FRIEND | OTHER
  protected_name text not null, protected_phone text not null
  sort_order smallint not null                         -- check 1..3, unique per member: at most three
  version, created_at, updated_at
```

The photo reference is written as the two-column key on purpose. `media.assets` is
keyed on `asset_id` alone, and a single-column reference into it lets one tenant's row
point at another tenant's private object, which `V0069` had to repair for a courier's
registration certificate (and which turned the endpoint that wrote it into an
existence oracle for asset ids). A staff photo is personal data (ADR 0029), so the
same failure here would store a durable pointer to another tenant's private image.
`tools/checks/tenant_scoped_references.py` and `TenantScopedReferenceCatalogTests`
refuse the single-column form, and this record adds nothing to their allowlist. The
constraint is the backstop, not the check: the service resolves the asset in the
caller's own tenant through `MediaAvailability` before it stores the id, requires
`PRIVATE` visibility and `TENANT` owner scope on it (`MediaAvailability` answers only
"displayable in this tenant", so this needs a narrow sibling method on the port that
ADR 0010 owns), and answers one not-found for an asset of another tenant and for one
that does not exist, as `CourierEngagementService` does for evidence media.

Every table is granted to `horecaos_application` in its own migration
(`SELECT, INSERT, UPDATE`, plus `DELETE` on the two contact tables where a row
is genuinely removable; never `DELETE` on `staff_members`). A staff member row is
never deleted: audit facts, order attribution and mappings refer to it, and the
retention sweeper anonymises it in place. The protected columns use the classes
`PERSONAL`; `phone_lookup_hash` and `employee_number_hash` use the per-tenant
lookup key. The tables are tenant-owned rows that join the set ADR 0056 has not
yet migrated to row-level security, and are covered by the application-enforced
tenant predicate and `TenantScopedReferenceCatalogTests` until that schema moves.

### Capability placement (ADR 0025)

A capability's scope is the scope the route declares, and a grant serves the route
only if its own scope is that scope or a broader one (Context). So each operation a
branch or brand holder needs exists at that level, with `brandId` and `locationId` in
the path (the shape `LocationServiceOperationsController` already uses), and a
tenant-wide twin for `TENANT` holders. A member is visible at a scope when at least
one of their active grants sits at that scope or below it; a member with no active
grant is visible at `TENANT` scope only. A location route answers "no such member" for
a member without an active grant at that location, the same answer an unknown id
gets, so it is no existence oracle.

| Capability | Holds | Declared at | Notes |
|---|---|---|---|
| `staff.profile.read` | `TENANT_OWNER`, `TENANT_ADMIN`, `BRAND_MANAGER`, `LOCATION_MANAGER` | `TENANT`, `BRAND` and `LOCATION` routes (below) | Returns name, masked phone (list) or full phone (single member), photo, employment, languages. A `LOCATION_MANAGER` reaches the `LOCATION` routes for their own branch and no other |
| `staff.profile.manage` | `TENANT_OWNER`, `TENANT_ADMIN`; `LOCATION_MANAGER` (Open input) | `TENANT` and `LOCATION` routes | Edits another person's profile, employment and employee number and their emergency contacts. On a `LOCATION` route the service refuses unless every one of the member's active grants is inside that location. Ending employment is a `TENANT` route only in v1, because it also needs `iam.grant.manage` at the scopes being revoked and no branch job holds that until ADR 0103 decides; the service checks the second capability with `AuthorizationService#require`, as `GrantManagementService` does, since a method carries one `@RequiresCapability` |
| `staff.emergency-contact.read` | `TENANT_OWNER`, `TENANT_ADMIN`, `LOCATION_MANAGER` | `TENANT` and `LOCATION` routes | Every read writes an ADR 0027 fact; there is no bulk read; the same location-membership rule as above |
| `staff.self.manage` | every tenant-visible job (the eight of `TenantRoleCatalog`), because a finance clerk edits her own phone too | none: `@StaffSelfAuthorized` | Held at any scope in the tenant is enough; there is no coverage comparison. The handler resolves the member from the token subject and tenant, never from a supplied id, so it can act on the caller's own row only. `EndpointCapabilityDeclarationTests` gains the strategy and rejects it combined with `@RequiresCapability` |

Branch contact persons reuse `location.read` and `location.write` at `LOCATION`
scope on a route that names the brand. The T20 note in the gap map names
`LOCATION_MANAGE`; the registry has `LOCATION_WRITE`, and this record follows the
registry.

### APIs (ADR 0031: `If-Match` version on updates, `Idempotency-Key` on creates)

```text
# tenant-wide, declared at TENANT scope: TENANT_OWNER, TENANT_ADMIN
GET  /api/v1/operations/tenants/{tenantId}/staff/members?brandId=&locationId=&status=&q=   staff.profile.read
GET  /api/v1/operations/tenants/{tenantId}/staff/members/{memberId}                        staff.profile.read
PUT  /api/v1/operations/tenants/{tenantId}/staff/members/{memberId}                        staff.profile.manage
POST /api/v1/operations/tenants/{tenantId}/staff/members/{memberId}/end-employment        staff.profile.manage (+ iam.grant.manage in the service)
GET  /api/v1/operations/tenants/{tenantId}/staff/members/{memberId}/emergency-contacts     staff.emergency-contact.read
PUT  /api/v1/operations/tenants/{tenantId}/staff/members/{memberId}/emergency-contacts     staff.profile.manage

# brand-wide, declared at BRAND scope: BRAND_MANAGER and every broader holder
GET  /api/v1/operations/tenants/{tenantId}/brands/{brandId}/staff/members?locationId=&status=&q=   staff.profile.read
GET  /api/v1/operations/tenants/{tenantId}/brands/{brandId}/staff/members/{memberId}               staff.profile.read

# one branch, declared at LOCATION scope: LOCATION_MANAGER of that branch and every broader holder
GET  .../brands/{brandId}/locations/{locationId}/staff/members?status=&q=                  staff.profile.read
GET  .../brands/{brandId}/locations/{locationId}/staff/members/{memberId}                  staff.profile.read
PUT  .../brands/{brandId}/locations/{locationId}/staff/members/{memberId}                  staff.profile.manage
GET  .../brands/{brandId}/locations/{locationId}/staff/members/{memberId}/emergency-contacts   staff.emergency-contact.read
PUT  .../brands/{brandId}/locations/{locationId}/staff/members/{memberId}/emergency-contacts   staff.profile.manage
GET  .../brands/{brandId}/locations/{locationId}/contact-persons                           location.read
PUT  .../brands/{brandId}/locations/{locationId}/contact-persons                           location.write

# the caller's own row, @StaffSelfAuthorized(staff.self.manage): any active grant in the tenant
GET  /api/v1/operations/tenants/{tenantId}/staff/me
PUT  /api/v1/operations/tenants/{tenantId}/staff/me
POST /api/v1/operations/tenants/{tenantId}/staff/me/photo
```

The `brandId`, `locationId` filters on the tenant and brand lists narrow a result and
never widen a scope; the scope of a route is only what its path names, which is what
`CapabilityEnforcementInterceptor` reads. Each tenant-wide and branch route pair calls
one service method, so the coverage rule lives in one place.

`q` matches a decrypted name substring in the application and nothing else. A
phone search, if the product wants one, is a `POST` with the number in the body
(not built by this record), because a number in a query string lands in an access
log (ADR 0029; the same rule `orders.md` §2.8 applies to orders). Request DTOs
box every optional field, because Jackson 3 refuses a missing primitive
(`MALFORMED_BODY`), and are tested with the console's real JSON. Photo upload goes through the ADR 0010 pipeline with
`PRIVATE` visibility and returns a short-lived signed URL, never a public one.

### Audit and events

- Facts (ADR 0027, `ChangeDocuments.diff` and `created`, never a flat `.changed`):
  `staff.member.created`, `staff.member.updated`, `staff.member.employment_ended`,
  `staff.member.anonymised`, `staff.emergency_contact.read`,
  `staff.emergency_contact.updated`, `iam.location_contact.updated`. The actor
  is the human, and on a self-edit the actor and the target are the same subject.
- Personal keys appear in a change document as `[redacted]` markers. The shared
  redaction set gains `employeenumber` and `emergency` so those keys are caught,
  and a test asserts that every protected staff field key is redacted.
- No Kafka event in v1: nothing consumes one, and any future event carries only
  the member id, `display_reference` and status, never a name or a phone (ADR 0029,
  ADR 0032). Cache eviction is an in-process call on the write path.

### Backfill and the read fallback

A resumable, idempotent runner (unique `(tenant_id, principal_subject)` makes a
second run a no-op) visits each distinct `(tenant, subject)` with an active grant,
skips device, partner and support subjects, reads the Keycloak name and `phone`
attribute once, and inserts `ACTIVE` when the account has a password and `PENDING`
otherwise. A subject with no Keycloak name gets a row with null names, which the
schema permits on purpose, and renders as its `display_reference` until someone
fills it in; the edit form requires a first name before it will save. The runbook reports per
tenant: created, skipped by class, and any subject Keycloak could not answer for
(left for retry, never guessed). Until every environment reports zero unbacked
active subjects, `StaffDirectory` falls back to `StaffAccounts#displayName` for a
subject with no row.

### Retention and erasure

An ended member's personal fields are overwritten in place after
`employed_until` plus the retention period (ADR 0029 provisional, legal to
confirm): names, phone and employee number are nulled, the photo asset is
deleted, emergency contacts are deleted, `display_reference` and the status
history remain so audit and order attribution still resolve to "former staff
S-0142". `StaffMemberRetentionSweeper` starts in report-only mode and needs sampled
proof before it enforces, as ADR 0029 already requires of every destructive
retention job. A data-subject request from a former employee follows the same
transition on demand.

### Security and observability

- No name, phone or employee number reaches a log, metric, trace, event, error
  message or dead-letter summary (ADR 0029); log lines carry `display_reference`.
- Self-service `PUT` is rate-limited per subject (ADR 0033). A phone change
  validates the format and refreshes the lookup hash; it never conflicts with a
  colleague, because a contact phone is not unique (Decision).
- Metrics: `horecaos.iam.staff.members{status}`, `horecaos.iam.staff.unbacked_active`
  (active subjects with no row, the backfill's completion gauge),
  `horecaos.iam.staff.ended_with_access` (the drift count).

### Testing

- Authorization by level, each written to fail first: a `location-manager` grant at
  branch A reads and edits branch A's members through the `LOCATION` routes, gets 403
  from the `TENANT` routes and from branch B's routes, and gets "no such member" for
  a member of branch A's sibling; a `brand-manager` grant reads through the `BRAND`
  routes; a `location-staff` cook (a `LOCATION` grant only) reads and edits their own
  profile through `staff/me` and cannot address another member through it;
  `EndpointCapabilityDeclarationTests` accepts every route above and rejects
  `@StaffSelfAuthorized` combined with `@RequiresCapability`. A separate test drives
  `POST /api/v1/tenants/{tenantId}/staff/telegram/link-codes` with a `location-staff`
  grant, which no test does today; it is expected to fail, which turns the finding in
  Context into a failing test before it is a fix (the fix itself is ADR 0060's to
  own, not this record's).
- Tenant isolation: a subject in two tenants gets two rows; a read in tenant A
  never returns tenant B's row, and a ciphertext moved between rows fails to
  decrypt. The directory refuses a cross-tenant lookup.
- A protected field is not readable through the reporting role, and a captured
  log of the whole flow contains no name or phone.
- Photo isolation: tenant B setting `photo_asset_id` to tenant A's private asset id is
  refused by PostgreSQL (foreign key violation) when the service is bypassed, and by
  the service with the same not-found a random uuid gets, so the two answers cannot be
  told apart; an asset of the caller's tenant that is `PUBLIC` or not `TENANT`-owned is
  refused too.
- The People list, the audit log and the order detail resolve the same name for
  one subject; a self-edit is visible on all three after the write with no wait
  for a TTL (the cache eviction).
- The invitation is all or nothing: with the member insert made to fail (the
  transaction throws), no grant remains active for the invited subject, the Keycloak
  account is deleted so the phone can be invited again, the original error reaches
  the caller, and a cleanup that also fails writes `orphan_left`. A cook who sets her
  contact phone to the kitchen mobile does not stop a manager inviting a new hire
  whose sign-in phone is that number; two members may then share a contact phone and
  both are found by an exact-match search. Two concurrent invitations in one tenant
  get different `display_reference` values and both succeed.
- Ending employment revokes every active grant, survives a mid-way failure with
  the drift flag raised, and a retry completes it.
- `OPERATOR` mapping round trip: a row written from the pane resolves through
  `PosOrderExportService` for an order accepted by that member, and resolves to
  nothing for an order accepted by a member with no mapping.

## Rollout and rollback

Additive only. The migration creates four tables and grants; nothing existing is
altered. Ship the tables and the invitation-time insert first, with reads still
on the Keycloak path; run the backfill per environment and watch
`unbacked_active` fall to zero; then move the two name callers, the People
list, the today-counts card and the two leaderboards to `StaffDirectory`; then
retire the Keycloak fallback and the TTL-only cache entry. Rollback at any step before the fallback
is removed is to point the callers back at `StaffAccounts#displayName`. After the
fallback is removed, rollback is a forward migration that stops reading the
table, not an edit to an applied one, and the rows stay.

## Implementation checklist

- [ ] Flyway migration for the four tables, indexes, checks and `GRANT`s (numbers
      reserved by the wave that picks this up; check every active worktree).
- [ ] Four capabilities in `Capability`, bundles in `PlatformRole`, and the
      registry snapshot, with `EndpointCapabilityDeclarationTests` green.
- [ ] `@StaffSelfAuthorized` and its interceptor branch (capability held at any
      scope in the tenant, own row only), only after the Open input is signed off;
      the tenant, brand and location route pairs over one service method each.
- [ ] `iam.api.staff.StaffDirectory` (named interface) with `nameOf`, `namesOf`
      and `memberIdOf`, a JDBC implementation over `FieldProtection`, a
      `(tenant, subject)` cache evicted on write, and the Keycloak fallback.
- [ ] Row creation in `StaffInvitationService#invite` (inside the invitation's
      transaction, after the grant), `abandonOrphanedAccount` extended to revoke the
      grant when that transaction fails, promotion to `ACTIVE` in `accept` (in the
      transaction that records the acceptance), and in the owner's `completeSetup`.
- [ ] Backfill runner, runbook and the `unbacked_active` gauge.
- [ ] Member list, single member, update, `me`, photo, end-employment and
      emergency-contact endpoints; ending employment revokes through
      `GrantManagementService`.
- [ ] `tenant.location_contact_persons` endpoints on the location screen.
- [ ] Move `AuditQueryService` and `OperationsOrderController` to
      `StaffDirectory`, and compose the name on the operator-today-counts card at
      its controller; delete `CachedStaffDisplayNames` once the fallback is
      retired.
- [ ] `MappingEntityType.OPERATOR`, the pane listing members, and
      `resolveOperatorExternalId` keyed by member id
      (`PosOrderExportOperatorAttributionTests` updated).
- [ ] Names on `GET .../reporting/operator-leaderboard` and the new live
      operator band (an `ordering` read); pseudo-operators unchanged.
- [ ] Audit facts through `ChangeDocuments`; extend the redaction set; add the
      test that every protected staff key is redacted.
- [ ] Console: People list names, person card identity block and tabs, the
      «Личные данные» section of «Мой профиль», branch contact persons, the
      emergency-contact panel, and the shell chip reading the profile; ru,
      uz-latn and en keys with the parity spec green; new screens lazy-loaded.
- [ ] `StaffMemberRetentionSweeper` in report-only mode.
- [ ] Domain and API tests for every behaviour above, each written to fail first.

## Exit criteria

As a branch manager, call the branch's members route and see the names, masked
phones and employment status of the people who work at your branch, and no one else;
the same call for another branch, and the tenant-wide route, are refused. (The People
screen stays out of a branch manager's reach until ADR 0103 or a successor lets that
job see Staff at all, so this half is verified at the API until then.) As a line cook
holding only a location grant, edit your own name and phone from «Мой профиль». Open
the audit log, an order's «принял» line and the operator leaderboard and see the
same names, with the edit visible on all of them without waiting. Map a POS operator
id to a named colleague and see it on the next order that colleague accepts. End
someone's employment and find that they hold no job in the tenant and the console
shows them no access, that their history still names them, and that no other tenant
could read any of it.

## References

- `platform/docs/operations-gap-map.md` rows `0.1d`, `0.2c`, `9.2`, `9.2b`, `9.2c`,
  `X.5`, and the T20 note
- `platform/docs/operations-spec/staff-and-access.md` §3, §10, §11.1, §11.3,
  §11.5, §11.6, §11.7, §11.9, §11.11
- `platform/docs/delever-parity-matrix.md` (personal account and operator
  mapping rows and the open questions under them)
- ADR 0009 (`iam.principals` and `iam.tenant_membership_links`, identifiers only)
- ADR 0025 (capabilities and downward-only scope coverage; no tenant-defined roles
  in v1), ADR 0049 (`@CourierSelfAuthorized`, the self-authorization precedent) and
  ADR 0103 (grant management scope, Proposed)
- `ResourceScope#covers`, `JdbcAuthorizationService#hasGrant`,
  `CapabilityEnforcementInterceptor#scopeOf`, `EndpointCapabilityDeclarationTests`,
  `LocationServiceOperationsController`, `OperationsCourierController#rosterEntries`,
  `TelegramStaffLinkCodeController`
- ADR 0026 (`provider_entity_mappings`) and ADR 0060 (staff Telegram link and the
  self-service capability precedent)
- ADR 0029 (envelope encryption, data classes, provisional retention)
- ADR 0042 (courier person record and the shift model this record leaves alone)
- ADR 0079 (device principal and the rejected per-action PIN)
- ADR 0097 and ADR 0116 (owner and staff invitations, and the email stance)
- `V0023` (`tenant.locations.contact_phone`), `V0040` (`fulfillment.couriers`),
  `V0057` (IAM evidence tables), `V0105` (staff Telegram link), `V0313`
  (`tenant.staff_invitations`)
