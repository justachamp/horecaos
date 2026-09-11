# ADR 0100: The control plane sees every owner invitation

- Decision status: Proposed
- Implementation status: Built — V0215's `tenant.owner_invitation_events` written by `OwnerInvitationService` and `OwnerInvitationRelay` at every queue, send attempt, open, accept and resend; `JdbcOwnerInvitationEventStore` reads it; `OwnerInvitationView` carries `recipient` and `timeline`; `GET /api/v1/control-plane/owner-invitations` lists every unarchived tenant with an invitation or a linked owner, filterable, including the never-invited tenant as `NONE`; the recipient is read live from Keycloak and revealed only to `TENANT_ONBOARDING_MANAGE`, one `tenant.owner_invitation.recipient_revealed` fact per screen load; the control plane has the `/tenants/invitations` screen with its rail entry, the timeline on the onboarding panel and the owner column on the tenant directory. Covered by `OwnerInvitationFlowTests`, `OwnerInvitationOverviewTests`, `OwnerInvitationControllerEndpointTests` and the three Angular specs. No retention job trims the events table
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
   invitation's state. Nothing updates or deletes a row; the grant is
   `SELECT, INSERT` and says so.
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
   audit fact per screen load, purpose `tenancy.onboarding.invitation.recipient`,
   carrying how many recipients were revealed and never an address.
5. **No stored address, anywhere, still.** Not in `owner_invitations`, not in
   `owner_invitation_events`, not in an audit fact, not in a log line. The full
   address exists in a response body and nowhere else the platform owns, and
   the response of an unrecorded `GET` is not stored (ADR 0031's idempotency
   record covers effectful methods only).
6. **The tenant directory says when an owner is not set up.** The same overview
   feeds an "owner" column: a marker for any tenant whose owner has not
   accepted, linking to that tenant's onboarding screen. The column is read
   with the same optional-column discipline as plan and health — a caller
   without the capability gets a dash, not an error.

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
  being slow makes this screen slow.
- A staff email address now reaches a browser, which it did not before. A
  screenshot of the overview is a list of tenant owners' addresses.
- `owner_invitation_events` grows without bound. It is small (a handful of rows
  per tenant for the life of a tenant) and has no retention job.

### Accepted trade-offs

- The reveal is recorded per screen load, not per address. An operator who
  opens the overview twice produces two facts naming two counts, not a
  per-tenant trail — the same shape ADR 0027 already accepted for a customer's
  revealed address book.
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
  then tenant name.
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

`OwnerInvitationFlowTests` gains the history assertions against the migrated
schema — a queue, a deferred attempt, a send, an open, an accept and a resend
in one timeline, and the resend's reason and actor on its row.
`OwnerInvitationOverviewTests` covers the overview query, the `NONE` tenant,
the filters, the cap falling on settled tenants first, and that the full
address appears only for the capability. `OwnerInvitationControllerEndpointTests`
covers the endpoint itself: the reveal fact over HTTP, the state filter, a
platform-support caller refused the overview and given the mask on the panel,
and 401 for an anonymous one.
Angular specs cover the new screen, the extended panel and the directory
column.

## Rollout and rollback

Additive. Invitations that predate V0215 have no history and render an empty
timeline under their existing facts. Rolling back the frontend leaves the
endpoint unused; rolling back the migration is a `DROP TABLE` of a table
nothing else references.

## Implementation checklist

- [x] V0215: the events table, and the `(tenant_id, id)` unique on invitations
- [x] The service and the relay write an event wherever they change state
- [x] `OwnerInvitationView` gains `recipient` and `timeline`, additively
- [x] The overview query, endpoint and its capability check
- [x] The recipient reveal, with its audit fact
- [x] Control plane: the timeline on the panel, the overview screen and its
      rail entry, the directory's owner column
- [x] Tests: history, overview, reveal, and the three Angular specs

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
