# ADR 0100: The control plane sees every owner invitation

- Decision status: Proposed
- Implementation status: Built — V0215's `tenant.owner_invitation_events` written by `OwnerInvitationService` and `OwnerInvitationRelay` in the same transaction as the state change each row records, and only when that guarded write matched the attempt it names; `JdbcOwnerInvitationEventStore` reads it; `OwnerInvitationView` carries `recipient` and `timeline`; `GET /api/v1/control-plane/owner-invitations` lists every unarchived tenant with an invitation or a linked owner, filterable, including the never-invited tenant as `NONE`; the recipient is read live from Keycloak and revealed only to `TENANT_ONBOARDING_MANAGE`, one `tenant.owner_invitation.recipient_revealed` fact per call that carries a recipient; `GET /api/v1/control-plane/owner-invitations/waiting` answers the same question without one — `{tenantId, state}` per unarchived tenant, no identity-provider read, no reveal — and is what the tenant directory's owner column asks; the control plane has the `/tenants/invitations` screen with its rail entry, the timeline on the onboarding panel and that column. Covered by `OwnerInvitationFlowTests`, `OwnerInvitationOverviewTests`, `OwnerInvitationControllerEndpointTests` and the three Angular specs. No retention job trims the events table
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0009, ADR 0025, ADR 0027, ADR 0029, ADR 0031, ADR 0097
- Supersedes / Superseded by: —
- Open inputs: whether an operator's view of a staff address needs a retention period on the reveal facts beyond ADR 0027's own (platform owner, with legal); whether the overview should reach archived tenants once the first tenant is archived (platform owner)

## Context

ADR 0097 gave the platform an invitation and the control plane a panel for it.
The panel answers one question — where does *this* tenant's invitation stand —
and it answers it from one row. `tenant.owner_invitations` holds a single
mutable row per tenant and owner: a resend clears `token_hash`, `expires_at`,
`sent_at`, `opened_at`, `last_error_code` and resets `attempts` to zero. That
row is a *position*, not a history. Everything that happened before the last
resend is gone from it.

An operator running onboarding for a new tenant asks a different set of
questions, and today they can answer none of them from the console:

1. **Who was it sent to?** The panel shows `d***a@example.uz`. The operator
   typed the address into the onboarding form themselves an hour earlier, and
   when the owner says "nothing arrived" the first thing to check is whether
   the address on file is the one they meant. A mask that hides everything
   between the first and last letter cannot distinguish `dilnoza@example.uz`
   from `d.karimova@example.uz`, which is exactly the mistake being looked for.
2. **What has actually happened to it?** Queued when, by whom; attempted how
   many times and with what outcome each time; opened when; accepted when. The
   row has five timestamps and one error code, and a resend erases four of
   them. After two resends "attempts: 1, last error: none" is true and useless.
3. **Who resent it, and why?** `queued_by` holds the last requeuer's subject
   and nothing else. The reason lives in an ADR 0027 audit fact, which is
   correct and is also not on this screen — an operator investigating an
   invitation should not have to compose an audit-log query with an action
   code they would have to know to look for.
4. **Which tenants are stuck?** There is no cross-tenant view at all. "Which
   tenants have an owner who never accepted?" is the question that matters at
   the scale the pilot is heading for, and answering it today means opening
   every tenant's onboarding screen one at a time. Worse, the case the platform
   found in wave 122 — a tenant whose onboarding linked an owner before
   invitations existed, so nothing was ever sent — produces *no* invitation row
   at all, and therefore shows up in no list that starts from the invitation
   table.
5. **Is a tenant's owner set up?** The tenant directory shows status, plan and
   open problems. A tenant sitting in `PROVISIONING` because its owner never
   opened an email looks exactly like a tenant that is mid-onboarding for any
   other reason.

There is a real privacy question inside question 1, and ADR 0097 answered it
one way. That answer deserves re-examination rather than inheritance, because
two facts about this platform make the owner's address unlike the customer
addresses ADR 0029 was written for:

- **The address is the Keycloak username.** Staff sign in with it (ADR 0062).
  It is not a contact detail attached to a person's account; it *is* the
  account's name. An operator with `IAM_GRANT_MANAGE` can already see staff
  usernames on the access screens.
- **The operator typed it.** `TENANT_ONBOARDING_MANAGE` is the capability that
  starts an onboarding run, and the run's owner step takes `ownerEmail` as
  input. The person the mask hides the address from is, in the overwhelmingly
  common case, the person who supplied it.

So the mask protects nothing against the holder of that capability, while
costing them the one check they need. It does still protect against everyone
else — a platform operator holding only `TENANT_READ`, a support engineer, a
screen-share, a screenshot in a chat. And ADR 0029's actual requirement is not
"nobody sees personal data"; it is that personal data is classified, that the
platform does not keep copies it does not need, and that every reveal is
attributable. All three survive showing the address to the capability that
supplied it, provided the reveal is recorded.

What must not change is the storage rule. ADR 0097's accepted trade-off — the
relay reads the address from Keycloak on every send so the platform keeps no
copy — is the load-bearing part, and a screen that showed the address by
denormalising it into a table would quietly undo it.

## Decision

1. **An invitation has a history, not just a position.**
   `tenant.owner_invitation_events` is an append-only child of
   `tenant.owner_invitations`: one row per queue, per send attempt outcome,
   per open, per accept, per resend, each with the attempt number it belongs
   to, the actor that caused it, and — for an operator's act — the reason they
   gave. The service and the relay write it wherever they already change the
   invitation's state, **in the same transaction as that change and only when
   the change applied**. Both halves of that are load-bearing, because the
   grant is `SELECT, INSERT`: a state write that committed without its row
   could never have the row added, and a row written for a guarded update that
   matched nothing — a relay whose lease a newer attempt or an operator's
   resend has taken over — could never be taken back. Nothing updates or
   deletes a row; the grant says so.
   One kind of event is deliberately not written every time it happens: a
   deferral that changes nothing. When the mailer reports that no mail server
   is configured the row is put back with its attempt rolled off (nothing was
   attempted), so the same deferral recurs every fifteen minutes for as long as
   that is true. It is recorded **once per distinct reason, until the reason
   changes** — a resend clears `last_error_code`, and so does the day mail
   starts working — with attempt `0`, which is what the panel beside it shows.
   The alternative, a row per pass, was rejected twice over: it would write
   ninety-six rows a day per queued invitation against a table with no
   retention job, and every one of them would claim to be attempt 1 beside a
   panel reading `attempts: 0`. A real send attempt is different and keeps its
   own row every time: there are at most eight of them and no two are the same
   attempt.
2. **The tenant's onboarding screen shows that history as a timeline.** The
   existing panel keeps its state pill and its facts; `OwnerInvitationView`
   grows a `timeline` field, additively, so no caller breaks.
3. **There is one cross-tenant overview.** `GET
   /api/v1/control-plane/owner-invitations`, `TENANT_ONBOARDING_MANAGE` at
   platform scope, filterable by state, listing every tenant that has an
   invitation *or* a linked owner — so the tenant nobody ever invited appears
   in it, in state `NONE`, which is the whole point. A row carries the tenant,
   the recipient, the state, the attempt count, the last error and the four
   times. The control plane gets a screen for it under Tenants.
4. **The recipient is shown in full to the capability that chose it.** A caller
   holding `TENANT_ONBOARDING_MANAGE` — at tenant scope for one tenant's panel,
   at platform scope for the overview — sees the address as Keycloak holds it,
   read live through `StaffAccounts` at the moment the screen loads. Every
   other caller keeps the ADR 0097 mask. The reveal is an ADR 0029 reveal: one
   audit fact per call that carries a recipient, purpose
   `tenancy.onboarding.invitation.recipient`, carrying how many recipients were
   revealed and never an address.
   "Per call that carries a recipient", not "per screen load", is the precise
   form and the two are only the same while every screen that reads an
   invitation renders an address. `revealedCount` is meant to answer "how many
   staff addresses was a person shown", and the data-protection console counts
   these facts as the platform's 30-day egress; a screen that asked for
   addresses it never displays would make both numbers mean something else.
   Hence decision 6.
5. **No stored address, anywhere, still.** Not in `owner_invitations`, not in
   `owner_invitation_events`, not in an audit fact, not in a log line. The full
   address exists in a response body and nowhere else the platform owns, and
   the response of an unrecorded `GET` is not stored (ADR 0031's idempotency
   record covers effectful methods only).
6. **The tenant directory says where an owner stands, from a projection that
   carries no address.** `GET /api/v1/control-plane/owner-invitations/waiting`
   returns `{tenantId, state}` for every unarchived tenant and nothing else:
   its query selects no `subject_id`, so no recipient is resolved, no
   identity-provider call is made, no address reaches the browser and no reveal
   fact is recorded. The column reads that, and the overview keeps its single
   caller — the screen that actually shows an address.
   A separate projection rather than a `recipients=false` flag on the overview,
   because a flag leaves revealing as the default and the next caller that
   forgets it silently reinstates the coupling; and a projection that resolved
   the addresses and dropped them would leave the identity-provider cost and
   the egress exactly where they are. Its capability is the overview's —
   `TENANT_ONBOARDING_MANAGE` at platform scope — deliberately, not by
   inheritance: which tenants are stuck is onboarding's business, and sharing
   the gate keeps one answer to "may this operator see the owner column".
   The projection answers four things and the column renders four things,
   because the operator has to be able to tell them apart: no owner linked or
   invited yet (`NO_OWNER` — a tenant created minutes ago, which the overview
   cannot express at all, since a tenant with nobody to chase is not in it),
   still waiting, set up, and not needed. A tenant the projection does not
   mention — an archived one, one past its cap, or every tenant when the caller
   lacks the capability — gets a dash. Absence is never rendered as an answer:
   the same optional-column discipline as plan and health, which assert nothing
   when they know nothing.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep the mask for everyone, as ADR 0097 decided | It hides the one character sequence the operator needs to verify against what they typed, from the person who typed it, and buys no protection from them — they can read the same address back out of Keycloak's admin console | Counsel rules that a staff address may not be displayed to platform staff at all, or staff sign-in stops using the address as the username |
| Store the address on the invitation row so the overview needs no Keycloak call | Undoes ADR 0097's storage decision for a latency saving; a stored copy also goes stale the moment someone corrects the address in Keycloak, and a stale address on an operator's screen is worse than none | Keycloak's admin API becomes a bottleneck the relay itself cannot live with |
| Derive the history from `audit.audit_events` instead of a new table | Audit is evidence for investigations, not a screen's read model; it has its own retention, its own scope filter, and no per-invitation index, and a UI that queries it teaches operators to read the audit log as an application feature | Audit gains a supported per-target projection with its own retention contract |
| Put the history in a JSONB column on the invitation row | The row is rewritten wholesale by every resend; the append-only guarantee would rest on every future author remembering to append rather than on the grant | Never — the grant is the guarantee |
| One row per resend in `owner_invitations` instead of a child table | Changes the `(tenant_id, subject_id)` uniqueness the queue, the token lookup and the relay's claim all depend on, for a history that a child table gives without touching them | The invitation gains a second recipient per tenant and the uniqueness has to change anyway |
| A per-row reveal audit fact instead of one per screen load | ADR 0027's own precedent (`CustomerBlacklistService#revealHistory`, `CustomerProfileService#revealAddresses`) is one fact per call: a fact per row would make a 40-tenant page 40 facts and make the count that the control exists to render meaningful, meaningless | A reveal ever becomes per-recipient rather than per-screen |
| A separate `GET .../owner-invitation/events` endpoint | A second round trip for data the panel always wants, and a second path to guard, for no case where the timeline is wanted without the state | The timeline grows a page of its own, or paging |
| Feed the directory's owner column from the overview, as this record first decided | It drags decision 4's reveal — a live Keycloak read and a full address per outstanding tenant, plus a `recipient_revealed` fact — onto the console's most-visited screen, which renders neither. Thirty-seven waiting tenants is thirty-seven addresses in a response body, a HAR file and any XSS on the origin, for a column that says "waiting", and thirty-seven people recorded as revealed to an operator who was looking at plans | Never for this column; revisit if a directory column ever needs to render a recipient |
| A `recipients=false` parameter on the overview instead of a projection | Leaves revealing as the default, so the coupling comes back the first time a caller forgets the parameter; and masking alone would keep the identity-provider read that is half the cost | The overview grows enough variants that one parameterised endpoint is clearly cheaper than several |
| Let the column read "set up" from a tenant's absence from the outstanding list | Absence has four causes and only one of them is "set up": a tenant nobody has linked an owner for, an archived one, one past the cap, and a caller who may not read invitations all look identical. A column that turns "I was not told" into a positive claim is worse than no column | Never — this is what the projection's `NO_OWNER` and the dash exist for |

## Consequences

### Positive

- "Nothing arrived" becomes answerable: the operator reads the address the
  platform will actually send to, and every attempt made against it with its
  outcome.
- A resend stops erasing evidence. The row still resets, and the history does
  not.
- The tenant onboarded before invitations existed — no invitation row, no
  owner, nothing sent — is visible in a list for the first time.
- Every operator who reads a staff address leaves a fact saying they did, which
  is a stronger control than a mask nobody could attribute.

### Negative

- One Keycloak admin call per listed row on the overview. A 200-row page is 200
  calls, and the page is capped at 200 for that reason; the identity provider
  being slow makes that screen slow. It is one screen: the directory's column
  reads the projection and costs one database query for the whole page.
- A staff email address now reaches a browser, which it did not before. A
  screenshot of `/tenants/invitations` is a list of tenant owners' addresses.
  Nothing else in the console shows one, and nothing else asks for one.
- `owner_invitation_events` grows without bound. It is small (a handful of rows
  per tenant for the life of a tenant) and has no retention job — which is only
  true because no event repeats on a timer; see decision 1's deferral rule, the
  one place where an event otherwise would.
- The projection is a second endpoint over the same rows, which two callers can
  now disagree about if only one of them is changed. They are kept honest by
  reading the same `tenant.owner_invitations` and the same `stateOf`, and the
  projection's extra state, `NO_OWNER`, is one the overview deliberately does
  not have rather than one it disagrees about.

### Accepted trade-offs

- The reveal is recorded per call, not per address. An operator who opens
  `/tenants/invitations` twice produces two facts naming two counts, not a
  per-tenant trail — the same shape ADR 0027 already accepted for a customer's
  revealed address book. A screen that shows no address produces none, so the
  count stays "addresses a person was shown" rather than "requests a browser
  happened to make".
- A deferral that says the same thing as the one before it is not recorded
  again. An operator reading the timeline of an invitation waiting on an
  unconfigured mail server sees one "delivery deferred — mail not configured"
  and the invitation's own `last_error_code`, not a line per fifteen minutes;
  what they lose is the ability to read the length of the wait off the number
  of lines, which `next_attempt_at` and the first line's own timestamp answer
  better.
- The overview excludes archived tenants. An archived tenant whose owner never
  accepted is not work anybody will do, and leaving it in the list forever
  would train operators to ignore the list.
- The cap of 200 is applied before the state filter, not after, so the screen
  is a cap rather than a page. The query sorts tenants whose owner is already
  set up last to make that harmless: every other filter is complete until a
  platform has more than 200 tenants still waiting on an owner. Paging, not a
  larger cap, is the answer if it ever is one — the cap exists because each row
  costs a Keycloak call.
- The timeline records only the *first* open. The link is opened by mail
  scanners and prefetchers as well as by owners; recording every GET would
  bury the owner's own open in noise, and `opened_at` has always meant the
  first one.

## Specification

### `tenant.owner_invitation_events` (V0215)

| Column | Type | Note |
|---|---|---|
| `id` | uuid PK | `Ids.newId()` |
| `tenant_id` | uuid NOT NULL | |
| `invitation_id` | uuid NOT NULL | |
| `event_type` | varchar(24) NOT NULL | `QUEUED`, `RESENT`, `SENT`, `SEND_DEFERRED`, `SEND_FAILED`, `OPENED`, `ACCEPTED`, `NOT_NEEDED` |
| `attempt` | integer NOT NULL | the send attempt the event belongs to, counted from one; 0 on a queue or a resend |
| `locale` | varchar(8) | the language of the email this event concerns |
| `outcome_code` | varchar(64) | what the event came to: a mail or identity failure code on a send attempt, the state it replaced on a resend; never a message |
| `actor_type` | varchar(16) NOT NULL | `SYSTEM_JOB`, `USER`, `OWNER` |
| `actor_reference` | varchar(255) | subject id or job name; never a display name, never an address |
| `reason` | varchar(1000) | the operator's own words for a resend; absent for machine events |
| `occurred_at` | timestamptz NOT NULL | from the platform clock |
| `recorded_at` | timestamptz NOT NULL DEFAULT `clock_timestamp()` | tiebreaks two events at one instant |

`FOREIGN KEY (tenant_id, invitation_id)` references
`tenant.owner_invitations (tenant_id, id)`, which the same migration adds as a
unique constraint. `GRANT SELECT, INSERT` — no update, no delete.

### API

- `GET /api/v1/control-plane/tenants/{tenantId}/owner-invitation` — unchanged
  path and capability (`TENANT_READ`); the view gains `recipient`
  (full when the caller holds `TENANT_ONBOARDING_MANAGE` at this tenant, else
  null) and `timeline`. `emailMasked` stays and stays masked for everyone.
- `GET /api/v1/control-plane/owner-invitations?state=…` —
  `TENANT_ONBOARDING_MANAGE`, platform scope. `state` is one of the six states,
  `NONE`, or `OUTSTANDING` (everything that is neither `ACCEPTED` nor
  `NOT_NEEDED`); absent means all. At most 200 rows, ordered by state urgency
  then tenant name. This is the only list that carries recipients, and the only
  one that records a reveal.
- `GET /api/v1/control-plane/owner-invitations/waiting` — same capability and
  scope, `{tenantId, state}` only. Every unarchived tenant, including the ones
  the overview leaves out because they have no owner to chase, which come back
  as `NO_OWNER`. No recipient, no `StaffAccounts` call, no reveal fact. Capped
  at 1000 rows — a bound rather than a cost, since the page is one query — and
  a tenant beyond the cap is absent, which its one caller renders as "not
  known".
- Resend is the existing
  `POST /api/v1/control-plane/tenants/{tenantId}/owner-invitation/resend`. The
  overview screen calls it per row; no new mutating endpoint exists.

### Reveal

`OwnerInvitationService` asks `AuthorizationService.has(subject,
TENANT_ONBOARDING_MANAGE, scope)` and, when it is held, records one
`tenant.owner_invitation.recipient_revealed` fact (`AuditClass.SECURITY`,
reason `tenancy.onboarding.invitation.recipient`, `changed` =
`{revealedCount: n}`, capability recorded) before returning any address. When
the identity provider does not answer, both the full and the masked address are
null and the states still render.

### Testing

`OwnerInvitationFlowTests` covers the history against the migrated schema — a
queue, a deferred attempt, a send, an open, an accept and a resend in one
timeline, with the resend's reason and actor on its row, and the address-free
assertion made with the two person-derived rows (`OPENED`, `ACCEPTED`) actually
present. It also covers what the history must *not* say: a rejected address
failing at the first attempt and never retrying, an owner who gained a password
before the send, a repeated deferral recorded once, an outcome a resend
overtook recorded not at all, and a failed history write taking its state write
down with it so the row comes due again.
`OwnerInvitationOverviewTests` covers the overview query, the `NONE` tenant, the
filters, the cap falling on settled tenants first, all seven urgency ranks in
one assertion with the alphabetical tiebreak, `NOT_NEEDED` behaving like
`ACCEPTED` everywhere, that the full address appears only for the capability,
and that the projection tells the four owner cases apart while reading no
address and recording no reveal.
`OwnerInvitationControllerEndpointTests` covers the endpoints themselves: the
reveal fact over HTTP with the capability and scope it names, the whole address
on the panel for the onboarding capability and the mask for a caller without
it, a tenant-scoped operator reading one tenant's address and not another's,
the state filter, the projection revealing nothing, a platform-support caller
refused both lists, and 401 for an anonymous one.
Angular specs cover the new screen, the extended panel, the directory column in
each of its five renderings, that the directory asks the projection and never
the overview, and that it asks nothing at all without the capability. Two
router specs assert, over every array of sibling routes in the file including
nested `children`, that no path — literal or itself parameterised — is declared
behind a parameterised one that would swallow it, and that `**`, which swallows
everything, is last in its array.

## Rollout and rollback

Additive. Invitations that predate V0215 have no history and render an empty
timeline under their existing facts. Rolling back the frontend leaves the
endpoint unused; rolling back the migration is a `DROP TABLE` of a table
nothing else references.

## Implementation checklist

- [x] V0215: the events table, and the `(tenant_id, id)` unique on invitations
- [x] The service and the relay write an event wherever they change state, in
      that change's own transaction and only when it applied
- [x] `OwnerInvitationView` gains `recipient` and `timeline`, additively
- [x] The overview query, endpoint and its capability check
- [x] The recipient reveal, with its audit fact
- [x] The address-free `/owner-invitations/waiting` projection, with the four
      owner states the directory renders
- [x] Control plane: the timeline on the panel, the overview screen and its
      rail entry, the directory's owner column
- [x] Tests: history, overview, reveal, the projection, and the Angular specs

One thing the record said and the build did not do: the attempt number is
one-based, not zero-based. The relay claims a row by incrementing `attempts`
before it tries, so the row it holds already carries the number of the attempt
about to be made, and a history that renumbered it would disagree with the
`attempts` count on the panel beside it. Zero is what an event that is not a
send attempt carries -- a queue, a resend -- which is what the column means
now.

## Exit criteria

An operator holding `TENANT_ONBOARDING_MANAGE` opens `/tenants/invitations`,
sees every tenant whose owner has not set up an account with the address each
invitation will go to, resends one with a reason, and then finds that resend —
with their own subject and their reason — on the invitation's timeline on that
tenant's onboarding screen; an operator holding only `TENANT_READ` sees the
same screens with `d***a@example.uz` in place of every address.

## References

- ADR 0097, ADR 0029, ADR 0027, ADR 0025, ADR 0031, ADR 0062
