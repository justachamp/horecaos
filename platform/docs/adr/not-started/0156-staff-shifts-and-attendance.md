# ADR 0156: Staff shifts and attendance

- Decision status: Proposed — proposed by Claude (batch 19); the platform owner decides
- Implementation status: Not started — nothing records an hour of work for a person
  who is not a courier. What exists is the courier model this record borrows from and
  the person record it hangs off, not a staff shift. `fulfillment.courier_shifts`
  and `courier_shift_breaks` (V0040) and `fulfillment.courier_roster_entries` (V0262)
  carry a courier's plan and fact, served by `CourierShiftController` and
  `OperationsCourierController` and drawn by the console's courier «Посещаемость»
  screen (row `3.5`, `features/delivery/shifts-page`); they cannot hold a cook:
  `courier_id` and `engagement_id` are `NOT NULL` foreign keys into the courier
  tables, `ck_shift_open_source` allows only `'COURIER'`, and `paid_seconds` feeds
  `courier_settlement_periods`. `iam.staff_members` (V0453, ADR 0139) says who a
  person is to a tenant and nothing about their hours; ADR 0139 leaves rosters and
  attendance out on purpose. `Capability` holds `COURIER_SHIFT_*` and `STAFF_PROFILE_*`
  and no `staff.shift.*`; `PlatformRole` bundles carry none. The nearest evidence of
  a person being at work is attribution on orders (`ordering.orders.created_by_actor_id`
  and `accepted_by_actor_id`, V0029, `ix_orders_created_by`, read by
  `OperatorTodayCountsService`), and kitchen tickets are advanced by a shared device
  principal (`KITCHEN_DEVICE`, ADR 0079), so no kitchen action names a human. The
  reporting side has the pieces an hours fact needs and no hours fact: `BusinessDayService`
  and `reporting.business_day_policies`, `DayCloseService` building `fact_*` rows
  from ports, and `ReportExportRegistry`, a code-owned catalogue of four export
  definitions served by `ReportExportController` under `POST
  /api/v1/tenants/{tenantId}/reporting/exports`. The console has no shifts screen
  for staff, no «Смены» tab on the person card (`staff-member-detail-pane`), and
  no clock-in control anywhere.
- Date proposed: 2026-10-07
- Date decided: —
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0025, ADR 0027, ADR 0029, ADR 0030, ADR 0031, ADR 0032, ADR 0033,
  ADR 0042, ADR 0043, ADR 0056, ADR 0057, ADR 0079, ADR 0139
- Supersedes / Superseded by: — (amends no record and edits none. It fills the hole
  ADR 0139 names in "What this record deliberately does not decide: staff rosters
  and attendance", and it closes ADR 0139's open input "whether terminals and PINs
  (row `X.3`) and staff rosters (row `X.2`) stay separate records or are folded in"
  for rosters on its proposed default, separate. It reopens, for staff who are
  employees and for nobody else, two rejected rows of ADR 0042: "Manager-controlled
  shift state" and "Pay hours from the roster a manager authored". Both were
  rejected because a courier is self-employed and a manager who directs the hours
  of a self-employed person reclassifies the engagement; neither reason applies to a
  person the tenant employs. For couriers both stay rejected, and ADR 0042's
  trigger for them, an `EMPLOYEE` engagement type, has not fired)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written; the ones that name a person other than the owner stay with that
  person and the work they block is marked.
  - **Which working-time rules bind a tenant here** (legal). Maximum shift length,
    mandatory rest, night work, overtime and public-holiday premiums. Nothing in
    the repository states them and this record does not guess. Proposed default:
    the platform records hours and enforces none of these rules, warns about none
    of them, and computes no premium; the one numeric limit in the schema, sixteen
    hours per entry or shift, is a data-sanity bound that catches a mistyped date
    and is not a legal limit. Blocks: nothing in the build; blocks any later
    warning or premium.
  - **How long attendance records must be kept, and whether keeping them needs a
    notice** (legal). Proposed default: shifts are kept for as long as the tenant
    exists and are never deleted; the identity behind a row is anonymised in place
    by ADR 0139's retention sweeper (report-only until legal answers), so an old
    shift reads as «S-0142» and not as a name. No delete job is built. Blocks: only
    a future purge.
  - **Whether recording a person's hours is workplace monitoring that needs the
    tenant to notify its staff first** (legal, product). Proposed default: the
    feature is off for every tenant until the tenant turns on
    `workforce.attendance.enabled`, the console shows the notice text legal supplies
    at the moment it is switched on, the switch is an audited ADR 0030 write, a
    person sees their own shifts, and the record collects no position, no network
    address, no screen content and no device identifier. Blocks: switching the flag
    on in production, not the build.
  - **Which jobs hold the new capabilities** (platform owner, product). Proposed
    default, in Specification: `staff.shift.read`, `staff.shift.manage` and
    `staff.shift.approve` for `TENANT_OWNER`, `TENANT_ADMIN`, `BRAND_MANAGER` and
    `LOCATION_MANAGER`, with `TENANT_FINANCE` added to read; `staff.shift.export` for
    `TENANT_OWNER`, `TENANT_ADMIN` and `TENANT_FINANCE`; `staff.shift.clock` for
    `LOCATION_MANAGER` and `LOCATION_STAFF`. ADR 0103 (who manages grants) is not
    decided here.
  - **The variance threshold, the retroactive-edit window and the maximum open
    shift** (finance, operations). Proposed default: 30 minutes (the spec's own
    number), 7 days and 14 hours, all ADR 0030 keys a tenant changes without a
    release.
  - **Whether the person who edits hours may also approve their variance**
    (finance). Proposed default: yes, and the audit fact records both people;
    `workforce.shift.approver_must_differ` is a tenant key, off. A branch with one
    manager would otherwise be unable to approve anything.
  - **The terminal path** (platform owner; the record for row `X.3`, which has no
    number in this batch). Clock-in from a shared tablet needs an identity below
    the device (a badge or a PIN), which ADR 0079 declined to build and ADR 0139
    split out. Proposed default: not built here; the shift schema allows only the two
    console sources, and the record that decides terminals adds the third source
    value in its own migration.
  - **Pay periods and locking** (finance). Proposed default: none. Facts are keyed
    by business date; a shift edited after its day closed is caught by ADR 0043's
    recut as a divergence and alerts, and no period is locked in v1.
  - **The payroll file** (finance). Proposed default: a CSV from ADR 0043's export
    machinery with the columns in Specification and no vendor-specific layout, to be
    revised when a tenant names the system that receives it.

**To accept as written:** say "accept 0156". Every open input above is then closed on
its proposed default.

## Context

Gap-map row `X.2` («Смены», staff shifts and attendance, spec §7 of
`operations-spec/staff-and-access.md`) says what is missing in one sentence: "A
branch manager cannot roster or record a single hour for kitchen or floor staff, so
the «часы» a payroll conversation needs exist only for couriers." Its "Blocked by"
says why it has not moved: "Needs a decision: amend ADR 0042 or give staff shifts a
record of their own — ADR 0139 (Accepted 2026-10-01) leaves them out of the staff
member record on purpose; the spec is explicit it must not become a third shift
model. No ADR has been drafted for either." The spec's §11.11 states the same fork
and asks for the decision, and its screen 9.6 has no row in the information
architecture, so the spec is the only description of what the screen is.

**What the courier model gives, and what it cannot give a cook.** ADR 0042 splits
the plan from the fact: a roster entry (`DRAFT → PUBLISHED → … CONSUMED | MISSED |
CANCELLED`) is the plan and a shift (`OPEN → … CLOSED`) is the fact, paid seconds
come only from the shift, and an open shift is an authorization gate where
`courier.shift.enforcement` is `ENFORCED`. The spec's table (§7) says three of its
four properties are wrong for branch staff, and the code shows why they are not
merely a matter of configuration.

| | Courier (ADR 0042, built) | Branch staff (this record) |
|---|---|---|
| Who opens a shift | Only the courier, by a database constraint (`ck_shift_open_source`), because a manager who can open one "can create paid hours for someone at home" and directing a self-employed person's hours is the fact pattern that reclassifies the engagement | A person clocks in, or a manager records it for them. The tenant employs them and directing their hours is the ordinary employer's act |
| What it gates | Offers, where enforced | Nothing. A cook off shift who marks a dish ready has done the restaurant a favour |
| Money it feeds | `courier_settlement_periods`, the ledger and payouts | No money. Hours leave as facts for whoever runs payroll |
| What the roster means | "An offer, not a rota": `PUBLISHED` invites and the courier answers `ACCEPTED` or `DECLINED` | A rota. An employee is told when to work and does not accept |
| Cash at close | A declaration and a shortfall reconciliation | None |
| Variance against plan | `variance_seconds`, approved before pay | The same discipline, unchanged |

The courier tables are shaped by the left-hand column: two `NOT NULL` foreign keys
into courier tables, a constraint that forbids the manager path, and a settlement
period that reads `paid_seconds`. Widening them to staff would make each of those
invariants conditional, and the reason they are unconditional today is that the
conditions they guard are expensive to get wrong. ADR 0042 also says in terms that it
is not a payroll system of record, and a record of employees' hours is the input to
one.

**ADR 0139 chose to keep this out, and gave its reasons.** Folding "terminals, PINs
and staff shifts" into the person record is a rejected alternative there: their open
questions "are unrelated to the person record's", and bundling them "keeps six ready
rows waiting on two unready ones". That reasoning still holds, so the home of the
shift is not `iam.staff_members`. The person record does give the shift what it
needs: a stable key (`iam.staff_members (id, tenant_id)`, `uq_staff_member_identity`),
a non-personal handle that may appear in a fact or an export (`display_reference`,
«S-0142»), and an employment status that says who may be rostered.

**What the console and the data can already say about presence, and what they
cannot.** The spec's «Сегодня» list ranks six conditions, one of which is the most
valuable and the hardest: "Работает без смены — actions recorded today with no open
shift". Attribution exists for orders (an operator who typed or accepted one is a
named subject) and does not exist for kitchen work, where a shared device principal
advances every ticket. So the condition can be derived honestly for the people who
take orders and for nobody who only cooks, until the record that gives a shared
terminal a human identity (row `X.3`) lands. The spec is right that this signal is
for the manager to fix a record afterwards, never to refuse work at the time.

**What payroll can be given.** A restaurant owner's payroll conversation needs, per
person and per day, how many hours were worked, how many of them were planned, and
which were approved. ADR 0043 already defines how a number like that is made
trustworthy: a code-owned metric id and version, a fact keyed by `business_date`
(computed from the tenant's `reporting.business_day_start`), a close job that builds
it, a recut that compares rather than overwrites, and an export that is
asynchronous, capability-gated and audited. `reporting` has no staff-hours fact, and
`ReportExportRegistry`'s own Javadoc says the next export is "one new entry plus one
new branch in `ReportExportService`, not a second job queue". The platform does not
hold a wage rate, a tax identity or a bank account for an employee, and this record
does not add them.

**The constraint that makes the choice non-obvious** is that two reasonable-looking
shortcuts each reach a wrong answer quietly. Reusing the courier tables keeps one
"shift" in the system and turns four of its invariants into conditions. Gating
service on an open shift enforces a timesheet by stopping the kitchen on the Friday
the rota was not published. The decision below keeps the courier model's vocabulary
and variance discipline, drops its gate and its cash, and puts the staff fact where it
cannot be mistaken for a permission.

## Decision

**Record non-courier staff hours in a shift record of their own, in a new `workforce`
module, built on ADR 0042's plan-and-fact vocabulary and variance discipline but
sharing none of its tables, its self-employment rules or its authorization gate.
Treat a shift as a timesheet and never as a permission, and give payroll hours as
ADR 0043 facts and a code-owned export, not pay.**

1. **A record of its own; ADR 0042 is not amended.** The record is neither a widening
   of `fulfillment.courier_shifts` nor a section of ADR 0139. Names carry over
   (roster entry, shift, variance, reason code, break) so a manager who knows the
   courier screen can read the staff one, and the three-line arithmetic (worked
   seconds, paid seconds, variance) is stated here and pinned by a shared test
   vector file; no shared-kernel class is introduced for it.

2. **A new module `workforce` owns the schema `workforce`.** Two tables:
   `workforce.staff_roster_entries` and `workforce.staff_shifts`. `iam` stays the
   identity and authorization module and gains nothing but the capabilities. The
   module depends on `iam.api.staff` (`StaffDirectory` for names, only in the export
   adapter) and on `tenancy.api` (locations), declares the ports it needs from
   others, and is consumed by `reporting` through a port, the way `pricing` is
   consumed for promotion redemptions.

3. **The roster is a rota, not an offer.** An entry runs `DRAFT → PUBLISHED →
   CONSUMED | MISSED | CANCELLED` (and `DRAFT → CANCELLED`). There is no `ACCEPTED`
   or `DECLINED`: an employee is scheduled, and a person who cannot work tells the
   manager, who edits the entry. `CONSUMED` is set when a shift opens against the
   entry; `MISSED` is set by a sweeper after `planned_end` with no shift, and the
   console words it as a coverage fact and not a disciplinary one, as ADR 0042 does.
   A person cannot hold two live entries that overlap, at any branch, by a database
   exclusion constraint.

4. **The shift is the fact and the only source of paid time.** A shift runs `OPEN →
   CLOSED`, or `OPEN → AWAITING_APPROVAL → CLOSED` when its variance is over the
   threshold, and a mistaken shift is `VOIDED` with a reason and is never deleted.
   `paid_seconds` is wall time from start to end less `break_seconds`. A person has
   at most one open shift, and no two live shifts of one person overlap, both by
   database constraint.

5. **A shift is never a gate.** No endpoint, policy key, screen or background job
   refuses an order action, a kitchen action or a sign-in because no shift is open.
   There is no `ENFORCED` mode and no key that would add one. «Работает без смены»
   is a derived notice on the manager's screen, computed from order attribution, and
   its only effect is that the manager can open or correct a shift afterwards.

6. **Attendance has two sources in v1, both at a branch, neither with a position.**
   A person clocks in and out from the console at a branch where they hold an active
   grant, and a manager opens, closes and corrects a shift for someone at a branch
   they manage. A self clock event takes its time from the server's clock and never
   from the request; a manager's entry names its time and, when it is in the past
   beyond a grace, a reason code from a closed list. A clock-in needs no published
   roster entry: the Friday with no rota still has hours. No GPS, geofence, IP or
   device identifier is read or stored. The terminal source is reserved by name and
   not built: the shift's source column admits only `CONSOLE_SELF` and
   `CONSOLE_MANAGER`, and the record that gives a shared device a human identity adds
   `TERMINAL` in its own migration, because a column value no writer produces is the
   configuration that silently does nothing.

7. **Time is corrected, never overwritten, and every correction leaves a fact.** A
   manager's edit is a conditional write on the shift's version with a closed-list
   reason code and an ADR 0027 audit fact whose before and after documents carry the
   old and new times. A shift older than `workforce.shift.retroactive_edit_days`
   (default 7) can be edited only by a holder of `staff.shift.approve`. There is no
   second history table: the audit log is the history, and its retention is ADR
   0027's.

8. **Variance is judged at close and approved by a person who looked.** At close the
   service matches the shift to its roster entry, computes `variance_seconds` as paid
   minus planned (null when there is no plan: null is not zero), snapshots the
   resolved threshold onto the shift, and moves it to `AWAITING_APPROVAL` when the
   absolute variance exceeds `workforce.shift.variance_threshold_minutes` (default 30).
   A shift in that state is excluded from payroll facts until a holder of
   `staff.shift.approve` approves it, with a reason code, one shift at a time:
   approval is deliberately not a bulk action. The approver may be the person who
   edited the shift unless the tenant sets `workforce.shift.approver_must_differ`.
   ADR 0027's `ApprovalService` is not used for this, and the Alternatives table says why.

9. **Nothing is closed with invented hours.** A shift left open longer than
   `workforce.shift.max_open_hours` (default 14) is flagged on the manager's screen
   as «Смена не закрыта» and is never closed by a job. An export whose range holds
   such a shift, or one still `AWAITING_APPROVAL`, refuses with a new stable
   `ErrorCode`, `ATTENDANCE_UNRESOLVED_SHIFTS`, and a count, unless the caller asks to
   include unresolved shifts, in which case they appear with their status and null paid
   seconds.

10. **Payroll receives hours, not pay.** The day close builds
    `reporting.fact_staff_shift` from a `workforce` port; the registry gains the
    metrics `staff_hours.paid.v1`, `staff_hours.planned.v1` and
    `staff_hours.variance.v1`; and `ReportExportRegistry` gains `STAFF_ATTENDANCE`.
    HorecaOS computes no wage, no overtime, no night or holiday premium, no deduction
    and no tax, and stores no wage rate, tax identifier or bank detail for an
    employee.

11. **Off until a tenant opts in.** `workforce.attendance.enabled` is a `TENANT`-scope
    key, default false. While it is false, the `workforce` routes answer
    `RESOURCE_NOT_FOUND`, as routes that do not exist for this tenant, the screens and
    the person-card tab are absent, and the export definition refuses. Turning it on is
    an ADR 0030 write, so it is audited with its actor and reason, and the console shows
    the notice text legal supplies (Open inputs).

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Amend ADR 0042 and widen `fulfillment.courier_shifts` and `courier_roster_entries` to staff | Two `NOT NULL` courier foreign keys, a constraint that forbids the manager path, a settlement consumer of `paid_seconds`, and a `fulfillment` module that owns dispatch would each become conditional on a person's class. ADR 0042's own reasons for manager-closed shifts being refused are about self-employment and would have to be re-argued row by row | Couriers are employed as `EMPLOYEE` (ADR 0042's own trigger) and payroll needs one hours record per human |
| Make shifts a section of ADR 0139 and a table in `iam` beside `staff_members` | ADR 0139 rejected folding shifts into the person record for reasons that still hold. `iam` is the identity and authorization module, and a time record has another change rate, another volume and a consumer (`reporting`) the identity module should not know | `workforce` stays at two tables with one consumer for two releases and the module boundary costs more than it protects |
| Gate order or kitchen actions on an open shift, as for couriers | Spec §7: it "stops service to enforce a timesheet". The rota is often not published and a cook off the clock who marks a dish ready has done the restaurant a favour | Never as a default. If a tenant asks for a reminder, an `ADVISORY` mode that only warns on the manager's screen can be added; a mode that refuses work is not offered |
| Pay from the roster (planned hours) | Pays a person who did not come, and the author of the roster is usually the one approving. ADR 0042 rejected the same for the same reason | Never for pay |
| Derive hours from activity (order attribution, audit facts, sign-in) with no clock-in | Kitchen work names no human today, and a sign-in says a session exists, not that a person is at the pass. It would turn a guess into hours someone is paid for | Per-action human attribution on shared devices exists (row `X.3`); even then it strengthens «Работает без смены» and does not become pay |
| Integrate a third-party timekeeping or payroll provider now | No provider is chosen and no tenant names one. An adapter is built against a provider's real contract, not a guessed one | A tenant names its system. The export is the seam; the adapter follows ADR 0026 and ADR 0007 with a recorded fake |
| Clock-in with GPS or a geofence, as couriers do | A cook is on a branch PC or a tablet; there is no radius to be inside, and a position of an employee is `PERSONAL_SENSITIVE` data with a purpose nobody here needs | A tenant needs remote sign-in and legal accepts the data (not before) |
| Per-break clocking | Staff do not tap for a break, and ADR 0042's break rows exist because a courier's duty state depends on them. A planned break on the roster entry, adjustable by the manager, serves a timesheet | A tenant must evidence mandatory rest periods (legal) |
| Route variance approval through ADR 0027's `ApprovalService` | The shared model resolves a policy per tenant and, for a new action, defaults to `ALLOW_WITHOUT_APPROVAL`, which would silently remove the gate; the console has no screen that authors an approval policy (the gap-map `9.4` residue). A state machine with `staff.shift.approve` gives the guarantee the spec asks for, that a person looked, with no policy to configure | An approval-policy console exists and a tenant wants two signatures on hours |
| Lock a pay period against edits | Needs a period definition nobody has supplied, and a locked month with a wrong hour needs a correction mechanism this record would then have to build. ADR 0043's recut already alerts on a closed day that changes | A tenant reports a payroll dispute caused by an edit after export |
| Build payroll: wage rates, overtime, deductions, payslips | A tax-filing and labour-law obligation in a market whose rules nobody here owns. ADR 0042 refused it for couriers for the same reason | A payroll ADR of its own, with legal's answers to the first open input |

## Consequences

### Positive

- A branch manager can roster a week, see who is working, correct a forgotten
  clock-out and hand payroll a file, which is the whole of row `X.2`.
- Courier invariants stay exactly as strict as they are: the manager-opens-nothing
  constraint and the settlement coupling are untouched.
- Hours reach payroll through the same fact, metric and export machinery as every
  other number, so a recut that finds a closed day changed raises an alert rather than
  a silent correction.
- The feature is off until a tenant, having read the notice, turns it on, and it can
  be turned off again without losing a row.

### Negative

- The platform now has two shift tables. "Hours worked at this branch this week" is
  a union of two sources unless a report decides otherwise, and the two vocabularies
  must be kept in step by a human and a test vector file.
- A new module is a boundary to maintain: a migration, a Modulith declaration, key
  registries, a capability set and role-bundle edits in `iam`.
- «Работает без смены» is blind to the kitchen. A cook who never takes an order is
  never flagged, and the screen has to say so rather than imply coverage it lacks.
- A self clock-in is only as true as the session behind it. A shared login records
  hours for whoever holds it, which is the problem row `X.3` exists to solve, not this
  record.
- Seven new ADR 0030 keys are seven more things a tenant can set wrongly.

### Accepted trade-offs

- A manager can edit hours, and the control is an audit fact and a retroactive-edit
  window rather than a second signature. A tenant with one manager cannot have more.
- Break precision is low: a planned break is deducted whether or not it was taken,
  and a manager corrects it by hand.
- No pay period is locked, so an edit after payroll ran is detected (a divergence
  alert) and not prevented.
- Hours are kept indefinitely until legal answers, anonymised by identity and not
  deleted, which keeps an employment record the person might ask to have removed.

## Specification

### Module, ports and ownership

```text
workforce (new module, schema workforce)
  api:       StaffShiftFactSource        reporting's day close reads closed-shift facts through it
             StaffAttendanceExportPort   the export adapter; resolves identity through StaffDirectory
             AttendanceBusinessDay       the tenant's business date for an instant; reporting implements it,
                                         as it does for ordering, courier, customers and inventory
  uses:      iam.api.staff.StaffDirectory (names, export only), tenancy.api (locations, config keys)
  reads:     ordering.api.OperatorActivity  (new, in ordering): the subjects who created or accepted an
             order at a location in a window; backs «Работает без смены» and nothing else
```

`ModularArchitectureTests` is the gate. `reporting` never reads a `workforce` table;
it calls the port, as `DayCloseService` calls `PromotionRedemptionSource`.

### Lifecycle and arithmetic

```text
roster entry:  DRAFT -> PUBLISHED -> CONSUMED | MISSED | CANCELLED        DRAFT -> CANCELLED
shift:         OPEN -> CLOSED
               OPEN -> AWAITING_APPROVAL -> CLOSED                          (|variance| over threshold)
               any non-VOIDED -> VOIDED                                     (reason; never deleted)

worked_seconds   = ended_at - started_at
paid_seconds     = worked_seconds - break_seconds                           (break from the matched entry's
                                                                             planned break unless a manager sets it)
planned_seconds  = (planned_end - planned_start) - planned_break           (snapshot from the matched entry; null if none)
variance_seconds = paid_seconds - planned_seconds                           (null if no plan; null is not zero)
business_date    = the tenant's business date of started_at                 (ADR 0043; recomputed when started_at is edited)
```

A shift matches the entry of the same person and branch whose `planned_start` is
nearest to `started_at` within `workforce.roster.match_window_minutes` (default 240)
and that is still `PUBLISHED`; the entry becomes `CONSUMED` in the same transaction.
The closing paths that need variance read the entry through the shift's own
`roster_entry_id`, never by re-searching.

### Physical model

Additive migration, number reserved by the wave that builds it (check every sibling
worktree, as AGENTS.md says). Every row carries `tenant_id`; every foreign key
includes it. `GRANT USAGE ON SCHEMA workforce` and `GRANT SELECT, INSERT, UPDATE` on
both tables to `horecaos_application`; nothing is deleted, so no `DELETE` is granted.
The schema is not in ADR 0056's RLS set (`inventory` is the only schema in it today);
tenant isolation is by composite keys, a tenant predicate in every statement, and the
cross-tenant negative tests below.

```text
workforce.staff_roster_entries
  id uuid pk, tenant_id, brand_id, location_id, staff_member_id
  status DRAFT|PUBLISHED|CONSUMED|MISSED|CANCELLED
  planned_start timestamptz, planned_end timestamptz, planned_break_minutes smallint default 0
  created_by varchar(255), published_at, published_by, cancel_reason_code null
  version integer, created_at, updated_at
  fk (tenant_id, brand_id, location_id) -> tenant.locations (tenant_id, brand_id, id)
  fk (staff_member_id, tenant_id)       -> iam.staff_members (id, tenant_id)    -- uq_staff_member_identity
  unique (id, tenant_id)
  check planned_end > planned_start and planned_end - planned_start <= interval '16 hours'
  check planned_break_minutes between 0 and 240
  check publication pair, and status publication rules as courier_roster_entries (V0262)
  exclude using gist (tenant_id with =, staff_member_id with =,
                      tstzrange(planned_start, planned_end) with &&)
          where (status in ('DRAFT','PUBLISHED','CONSUMED','MISSED'))        -- btree_gist exists since V0025

workforce.staff_shifts
  id uuid pk, tenant_id, brand_id, location_id, staff_member_id
  roster_entry_id uuid null      fk (roster_entry_id, tenant_id) -> staff_roster_entries
  status OPEN|AWAITING_APPROVAL|CLOSED|VOIDED
  started_at timestamptz, ended_at timestamptz null
  open_source  CONSOLE_SELF|CONSOLE_MANAGER, close_source null (same two values)
  break_seconds integer default 0
  worked_seconds bigint null, paid_seconds bigint null
  planned_seconds bigint null, variance_seconds bigint null
  variance_threshold_minutes smallint null          -- the ADR 0030 value resolved at close
  business_date date
  edit_reason_code null, approved_by varchar(255) null, approved_at null, approval_reason_code null
  voided_by varchar(255) null, voided_at null, void_reason_code null
  version integer, created_by varchar(255), created_at, updated_at
  fk (tenant_id, brand_id, location_id), fk (staff_member_id, tenant_id), unique (id, tenant_id)
  unique (tenant_id, staff_member_id) where status = 'OPEN'                   -- one open shift per person
  exclude using gist (tenant_id with =, staff_member_id with =,
                      tstzrange(started_at, coalesce(ended_at, 'infinity')) with &&)
          where (status <> 'VOIDED')
  check (ended_at is null) = (close_source is null)
  check status in ('CLOSED','AWAITING_APPROVAL') -> ended_at, worked_seconds, paid_seconds not null
  check ended_at > started_at and break_seconds between 0 and worked_seconds
  check status <> 'VOIDED' or (void_reason_code is not null and voided_by is not null)
```

`created_by`, `published_by`, `approved_by` and `voided_by` are Keycloak subjects, the
string `iam.grants.principal_subject` and the order actor columns already carry. A
shift stores no name, no phone, no position and no network address.

### Capabilities (ADR 0025)

| Capability | Held by | Declared at |
|---|---|---|
| `staff.shift.read` | `TENANT_OWNER`, `TENANT_ADMIN`, `TENANT_FINANCE`, `BRAND_MANAGER`, `LOCATION_MANAGER` | `LOCATION` routes (one branch), `BRAND` and `TENANT` reads |
| `staff.shift.manage` | `TENANT_OWNER`, `TENANT_ADMIN`, `BRAND_MANAGER`, `LOCATION_MANAGER` | `LOCATION` routes only; a broader grant covers them downward |
| `staff.shift.approve` | the same four | `LOCATION` routes |
| `staff.shift.export` | `TENANT_OWNER`, `TENANT_ADMIN`, `TENANT_FINANCE` | the export route's `TENANT` scope; the identity column group only |
| `staff.shift.clock` | `LOCATION_MANAGER`, `LOCATION_STAFF` | none: `@StaffSelfAuthorized(staff.shift.clock)` |

Self routes follow ADR 0139's strategy: the capability is held at any scope in the
tenant and the handler acts on the caller's own member row, resolved from the token
subject, never from a supplied id. A clock-in names a branch and the service refuses
(`404`, the same answer an unknown branch gets) unless the caller holds an active grant
at or above that branch. A manager route answers "no such member" for a person with no
active grant at that branch, so it is no existence oracle, as ADR 0139's branch routes.
`EndpointCapabilityDeclarationTests` covers every route. Rostering requires the person's
`employment_status` to be `ACTIVE`.

### APIs (ADR 0031: `If-Match` on updates, `Idempotency-Key` on creates; bodies box optional fields)

```text
# one branch, LOCATION scope: .../tenants/{tenantId}/brands/{brandId}/locations/{locationId}
GET  .../staff/roster?from=&to=                                   staff.shift.read      the «Неделя» grid
POST .../staff/roster/entries                                     staff.shift.manage    create a DRAFT entry
PUT  .../staff/roster/entries/{entryId}                           staff.shift.manage    If-Match
POST .../staff/roster/entries/{entryId}/cancellations             staff.shift.manage    reason code
POST .../staff/roster/publications        {from, to}              staff.shift.manage    DRAFT -> PUBLISHED for the range; names count and people in the confirm
POST .../staff/roster/copies              {fromWeek, toWeek}      staff.shift.manage    previous week as DRAFT
GET  .../staff/shifts/today                                       staff.shift.read      the «Сегодня» list, status derived
GET  .../staff/shifts?from=&to=&memberId=                         staff.shift.read      cursor paged
POST .../staff/shifts                     {memberId, startedAt, reasonCode?}   staff.shift.manage   open for someone
POST .../staff/shifts/{shiftId}/closures  {endedAt, breakSeconds?, reasonCode?} staff.shift.manage  If-Match
PUT  .../staff/shifts/{shiftId}           {startedAt?, endedAt?, breakSeconds?, reasonCode}        staff.shift.manage  If-Match
POST .../staff/shifts/{shiftId}/voidings  {reasonCode}                           staff.shift.manage
POST .../staff/shifts/{shiftId}/approvals {reasonCode}                           staff.shift.approve  If-Match, one at a time

# brand and tenant reads of the same lists: BRAND and TENANT scope, staff.shift.read

# the caller's own row, @StaffSelfAuthorized(staff.shift.clock)
GET  /api/v1/operations/tenants/{tenantId}/staff/me/attendance     open shift, next published entries, last shifts
POST /api/v1/operations/tenants/{tenantId}/staff/me/attendance/clock-ins    {locationId}
POST /api/v1/operations/tenants/{tenantId}/staff/me/attendance/clock-outs   {}

# export, the existing route: POST /api/v1/tenants/{tenantId}/reporting/exports
#   reportKey STAFF_ATTENDANCE; report.export, plus staff.shift.export for the identity group
```

`GET .../staff/shifts/today` returns, per person, the derived status of spec §7 in the
spec's order: `LATE` (a published entry whose start passed by more than
`workforce.roster.late_after_minutes`, default 10, with no shift), `MISSED_TODAY`,
`WORKING_WITHOUT_SHIFT` (the person created or accepted an order at this branch today
and has no open shift; only order attribution can raise it), `OPEN_TOO_LONG`,
`WORKING`, `CLOSED`. Every statement of "today" uses the tenant's business day, and a
future day never renders as missed. The list is polled every thirty seconds; no
realtime channel is added.

### Console

The people section gains «Смены» (spec 9.6) at `staff/shifts`, with the «Неделя» grid
(dashed outline for an empty cell, the `isToday` guard, keyboard as the spec states)
and the «Сегодня» list; the person card gains a «Смены» tab that is absent, not
empty, for a person who holds no branch-scope job and has no entry; «Моя работа»
(`0.2`) gains a card with the clock-in and clock-out controls, the next published
shifts and the open shift's start. Every string ships in ru, uz-Latn and en. The
screen states, in words, that «Работает без смены» sees only people who take orders.

### Policy keys (ADR 0030; declared in `WorkforceConfigurationKeys` and the tenancy registry, with the drift test)

| Key | Type, default | Settable at |
|---|---|---|
| `workforce.attendance.enabled` | Boolean, false | `TENANT` |
| `workforce.shift.variance_threshold_minutes` | Integer, 30, range 5 to 240 | `TENANT`, `BRAND`, `LOCATION` |
| `workforce.shift.approver_must_differ` | Boolean, false | `TENANT` |
| `workforce.shift.retroactive_edit_days` | Integer, 7, range 0 to 60 | `TENANT` |
| `workforce.shift.max_open_hours` | Integer, 14, range 4 to 16 | `TENANT`, `BRAND`, `LOCATION` |
| `workforce.roster.late_after_minutes` | Integer, 10, range 1 to 120 | `TENANT`, `BRAND`, `LOCATION` |
| `workforce.roster.match_window_minutes` | Integer, 240, range 30 to 480 | `TENANT` |

The variance threshold is a setting, not a policy document, so it is not pinned by id;
the resolved value is copied onto the shift at close, so changing it in October does
not re-judge September.

### Facts, metrics and the payroll export (ADR 0043)

`reporting.fact_staff_shift` is partitioned by `business_date` like the other facts
and carries `tenant_id`, `shift_id`, `business_date`, `location_id`, `brand_id`,
`staff_member_id`, `staff_display_reference`, `planned_seconds`, `worked_seconds`,
`break_seconds`, `paid_seconds`, `variance_seconds`, `approval_status`,
`had_roster_entry` and `metric_calculation_version`. It holds `CLOSED` shifts only; an
`OPEN` or `AWAITING_APPROVAL` shift is not a fact until it settles. The close job
builds a day from `StaffShiftFactSource`; the settle recut re-derives the day and
raises a divergence rather than overwriting, so a shift edited after its day closed
produces the alert ADR 0043 specifies.

The registry gains `staff_hours.paid.v1` (sum of `paid_seconds`, completed and approved
shifts only), `staff_hours.planned.v1` and `staff_hours.variance.v1`, each with the
sentence finance signs and `openQuestion` stating that no working-time rule is
applied. Until they are signed they report provisional, like every other metric.

`STAFF_ATTENDANCE` is a new `ReportExportDefinition` with one new branch in
`ReportExportService`, backed by `StaffAttendanceExportPort` so the identity columns
are resolved in `workforce` through `StaffDirectory` and never in `reporting` (ADR
0139). Columns: `staffDisplayReference`, `businessDate`, `locationId`,
`plannedSeconds`, `workedSeconds`, `breakSeconds`, `paidSeconds`, `varianceSeconds`,
`approvalStatus`, `startedAt`, `endedAt`, `timezone`, and the identity group
`employeeNumber` and `staffName`, which the definition marks as its PII group. The
definition gains one field, `piiCapability` (default `customer.pii.export`, so no
existing report changes), and `STAFF_ATTENDANCE` names `staff.shift.export`; a caller
without it receives the file without the identity group, as ADR 0043 already does.
The port and the day close read one query owner (`StaffShiftHours`), and a test pins
that the export's total equals the fact's total for the same range. The export is
asynchronous, quota-bounded and audited with its row count, as ADR 0043 requires. Night
and holiday premiums are not computed; the facts carry instants and the location's
IANA zone so a payroll system can.

### Audit (ADR 0027), events (ADR 0032), personal data (ADR 0029)

- Facts, written in the changing transaction through `ChangeDocuments`:
  `workforce.roster.drafted`, `.published`, `.cancelled`; `workforce.shift.opened`,
  `.closed`, `.corrected` (before and after times), `.voided` and `.approved`;
  switching the feature on is recorded by the ADR 0030 write's own fact
  (`tenant.configuration_value.set`). The actor is the human; a self clock-in has
  actor and target equal. A fact carries the member's
  `display_reference`, never a name, a phone or an employee number.
- No Kafka event in v1: nothing consumes one, since `reporting` reads through a port.
  If one is added later it carries ids, `display_reference`, status and the branch,
  never a name (ADR 0032, ADR 0139).
- Classification: a shift is `INTERNAL` business data keyed by a pseudonymous member
  id; identity lives in `iam.staff_members`, which is protected and anonymised in
  place. The export's identity group is the one place a name and an employee number
  meet hours, and it is gated and audited. No new `PERSONAL` column exists.

### Observability and testing

Metrics carry `source`, `outcome` and nothing that identifies a member: shifts opened
and closed, shifts awaiting approval (gauge), open shifts older than the maximum,
roster entries missed, export outcomes. An alert names the count, not the people.
Tests, each asserting what would still be true if the code were broken:

- Constraint tests against a real database: the roster exclusion, the one-open-shift
  index, the shift overlap exclusion, the `VOIDED` rule, a cross-tenant member or
  branch refused by the composite keys.
- Service tests that drive the real path with an advancing clock (open at 09:04,
  close at 18:22, break and variance computed, threshold snapshotted), and never write
  a closed shift by hand; a threshold change after close leaves the old shift's
  verdict alone.
- Capability tests: a `LOCATION_STAFF` holding only a branch grant reaches the self
  routes and is refused a sibling branch with `404`; `EndpointCapabilityDeclarationTests`
  passes with the strategy unchanged.
- Derivation tests with explicit timestamps on every fixture row (two rows created in
  one millisecond tie locally and reorder in CI): the six statuses, the `isToday`
  guard, and a person who only cooks never raising `WORKING_WITHOUT_SHIFT`.
- Export tests: the export total equals the fact total; an open or awaiting shift in
  range refuses with its count; a caller without `staff.shift.export` gets no identity
  columns; a recut after a post-close edit raises a divergence.
- Request DTO tests with the console's real JSON (Jackson 3 refuses a missing
  primitive), and the front-end specs for the grid's dashed cell and the clock card.

## Rollout and rollback

Ship dark: migration, module, keys, capabilities and routes behind
`workforce.attendance.enabled` false, with the export definition refusing while it is
off. Then the manager screens and the self card for one pilot tenant whose owner has
accepted the notice. Then the facts and the export, signed off by whoever runs that
tenant's payroll against a month of their own spreadsheets. Rollback at any step is
setting the flag false: the screens disappear, the routes answer
`RESOURCE_NOT_FOUND`, the export refuses, and every row stays. Nothing is migrated
out of the courier tables, so there is nothing to migrate back.

## Implementation checklist

- [ ] Owner accepts the record or answers the open inputs; legal answers the first
      three before the flag is switched on anywhere real.
- [ ] Module `workforce`, schema, the two tables and their constraints; Modulith
      declaration; `ModularArchitectureTests` green.
- [ ] Capabilities and role bundles in `iam`; `EndpointCapabilityDeclarationTests`.
- [ ] `WorkforceConfigurationKeys`, the tenancy registry entries and the drift test.
- [ ] Roster service and routes; shift service, the matcher, variance, edit window.
- [ ] Self routes with `@StaffSelfAuthorized`; clock-in branch check.
- [ ] `ordering.api.OperatorActivity` and the derived `today` list.
- [ ] `AttendanceBusinessDay` implemented in `reporting`; `StaffShiftFactSource`;
      `reporting.fact_staff_shift`; the three metric definitions; the close and recut.
- [ ] `STAFF_ATTENDANCE` export, `piiCapability`, `StaffAttendanceExportPort`.
- [ ] Sweeper that sets `MISSED`; the open-too-long flag.
- [ ] Audit facts and their sentences in the activity log's action dictionary
      (`activity-log-action-codes-coverage.spec.ts` fails the build without them).
- [ ] Console: «Смены» screen, person-card tab, «Моя работа» card, notice dialog,
      ru / uz-Latn / en strings, lazy-loaded so the initial budget is unchanged.
- [ ] Generated clients and the OpenAPI baselines for all five documents.
- [ ] Runbook: what to do when a shift is left open, and how to read a divergence alert.
- [ ] ADR 0139's "does not decide" line and the gap-map rows `X.2` and `9.2` updated
      when this lands.

## Exit criteria

On a pilot tenant with the flag on, a branch manager rosters a week for kitchen and
floor staff, publishes it, and on the day sees who is late, who is working and who has
a shift open too long; a cook clocks in and out from the console and a manager corrects
a forgotten clock-out with a reason, leaving an audit fact with the old and new times;
a shift past the threshold waits for approval and stays out of the fact until approved;
the day closes and `fact_staff_shift` matches the sum of the closed shifts; an export
for a month produces a file whose total equals the fact total, carries names only for a
caller holding `staff.shift.export`, and refuses with a count while a shift is still
open. No order action anywhere is refused for want of a shift. With the flag off, the
routes, the screens and the export all answer as absent.

## References

- ADR 0025, ADR 0027, ADR 0029, ADR 0030, ADR 0031, ADR 0032, ADR 0033, ADR 0042
  (shifts, roster, variance, self-employment reasoning), ADR 0043 (facts, business
  day, exports), ADR 0056, ADR 0057, ADR 0079 (shared device principal), ADR 0139
  (person record, what it leaves out)
- `platform/docs/operations-gap-map.md` row `X.2` of §9 (and `9.2`, `3.5`, `0.2`)
- `platform/docs/operations-spec/staff-and-access.md` §3 (tab 3 «Смены»), §7 (screen
  9.6), §11.11 (rosters and shifts), §11.1
- `V0040` (`courier_shifts`, `courier_shift_breaks`), `V0262` (`courier_roster_entries`),
  `V0453` (`iam.staff_members`), `V0025` (`btree_gist`), `V0029` (order attribution)
- `CourierShiftController`, `OperationsCourierController`, `StaffDirectory`,
  `StaffSelfController`, `DayCloseService`, `ReportExportRegistry`,
  `ReportExportService`, `BusinessDayWindowsAdapter`, `OperatorTodayCountsService`
- `frontend/operations/src/app/features/delivery/shifts-page`, `features/staff`
  (`staff-member-detail-pane`), `features/today/my-work-page`
