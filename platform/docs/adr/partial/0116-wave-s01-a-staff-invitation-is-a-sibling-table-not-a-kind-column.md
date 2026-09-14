# ADR 0116: A staff invitation is a sibling table, not a `kind` column on the owner's

- Decision status: Proposed
- Implementation status: Partial — `tenant.staff_invitations` (V0313),
  `StaffInvitationService`, `JdbcStaffInvitationStore`, `StaffAccounts#create`/
  `#findByPhone`, and the invite/resend/revoke/inspect/accept endpoints on
  `StaffInvitationController` are built by wave `wave138-s01` and exercised by
  `StaffInvitationFlowTests` and `StaffInvitationCreateEndpointTests`. The
  People screen's own side is built too: `staff-invite-dialog.ts` to
  staff-and-access.md §4, the «Приглашён» pill (`staff-row.ts`'s `INVITED`
  status) sourced from `GET .../staff/invitations`, and resend/revoke wired
  through `staff-access-dialog.ts`'s two new modes. Not built: a settings
  screen for choosing the sending provider was never in this row's scope
  (ADR 0097 already carries that decision and SMTP is live against Mailpit);
  requireGrantable's finer, per-capability escalation check still runs only
  inside `GrantManagementService#grant`, after the Keycloak account already
  exists — see this record's Consequences and the wave report's open issues.
- Date proposed: 2026-09-14
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction
  of 2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0025 (capability model), ADR 0009 (organization
  membership), ADR 0029 (PII classification), ADR 0031 (idempotency and error
  contract), ADR 0097 (the owner invitation this record's table sits beside)
- Supersedes / Superseded by: —
- Open inputs: none for the decision this record makes; the platform owner
  may still want a say on whether a staff invitation should eventually gain
  its own event timeline the way ADR 0100 gave the owner table one (this
  record deliberately does not build that — see Consequences)

## Context

Gap map row `9.1a` — "invite a staff member" — was `NOT BUILT` and deferred
behind ADR 0097's sender inputs: which mail provider, SPF/DKIM/DMARC on
`horecaos.uz`, and counsel's answer on a foreign processor under ZRU-547.
Those inputs are no longer blocking. `SmtpPlatformMailerTests` exercises real
delivery against Mailpit, and pre-production has sent mail through it since
2026-09-13 (see `production-decisions` memory). So the deferral this row
carried no longer holds, and the platform owner's instruction of 2026-09-13
asks for staff-and-access.md §4 built: a manager invites a colleague by name,
phone and job; the invitation delivers by email when one was given and always
hands back a one-time link either way, because the manager sends it by hand
more often than not.

`OwnerInvitationService` already owns one invitation flow, over
`tenant.owner_invitations` (V0210) and its event history (V0215, ADR 0100).
The brief that started this wave asked, correctly, whether staff invitations
should generalise that table (a `kind` or `role` column) rather than adding a
new one. This record answers that question, because building the wrong shape
here is expensive to undo: `StaffInvitationController.inspect`/`.accept` are
the same two public, unauthenticated endpoints the owner accept page already
uses, so whichever shape is chosen has to keep serving both kinds of token
from one place indefinitely.

## Decision

Add `tenant.staff_invitations` (V0313) as a sibling table to
`tenant.owner_invitations`, owned by a new `StaffInvitationService`, rather
than adding a `kind`/`role` column to the owner table or its service.
`StaffInvitationController.inspect` and `.accept` — already the shape ADR
0097 gave this exact class — are extended to try an owner token first and
fall through to a staff token only when the owner store has genuinely never
heard of it (`reason=INVALID`, never `EXPIRED`), so one link format keeps
working for both kinds without either service knowing the other exists.

The two tables model different things, not the same thing with a label:

- `tenant.owner_invitations` is *waiting for an account*. `queueIfAbsent` /
  `uq_owner_invitation UNIQUE (tenant_id, subject_id)` enforce at most one
  live row per tenant, because there is exactly one owner and the row exists
  to chase them until they have a password.
- `tenant.staff_invitations` is a *receipt for work already done*. By the
  time `StaffInvitationService#invite` writes the row, the Keycloak account,
  the organization membership, and the `iam.grants` row all already exist —
  created in that order, in the same call — and a tenant may have many rows
  open at once, one per colleague mid-onboarding. There is nothing to queue
  and nothing to chase into existence; the row is only the one-time link.

Folding the second shape into the first would mean either dropping
`uq_owner_invitation`'s one-row-per-tenant constraint — weakening what the
owner table already proves and has been tested against since ADR 0097 — or
keeping it and making a staff invitation exclusive with itself per tenant,
which is simply wrong. A `kind` column earns its cost only when the two rows
it distinguishes are the same shape apart from the label; here they are not.

The new table also carries less than the owner table: no `attempts` /
`next_attempt_at` / a relay to claim them, because delivery is synchronous —
`StaffInvitationService#invite` calls `PlatformMailer#send` itself, outside
any transaction, right after committing the row, and records the outcome.
There is no background retry. A failed or unconfigured send is not a failed
invitation: the link the response already returned still works, and a
manager who wants a second attempt uses resend.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A `kind` column on `tenant.owner_invitations`, reusing `OwnerInvitationService` | The service's own methods assume one invitation per tenant end to end (`latestFor`, `queueIfAbsent`'s conflict target, `inviteFirst`'s "the first invitation for a tenant"); teaching all of it to also mean "one of several, for an account that already has a grant" doubles the conditionals in a class ADR 0100 already extended once. The owner table's tests (`OwnerInvitationFlowTests`, `OwnerInvitationOverviewTests`) would need to grow a second, unrelated axis to keep covering the owner path alone. | A shape the owner and staff flows must share appears — e.g. control-plane wants one cross-tenant "every open invitation, owner or staff" screen the way ADR 0100 gave owners. That is a read-model question, answerable with a view over both tables, not a reason to merge the tables themselves. |
| Generalise `OwnerInvitationRelay` into an async queue for staff too, matching the owner flow's shape exactly | Staff-and-access.md §4 is explicit that the response carries the link synchronously, every time — "the manager will need to send it themselves more often than not" — which an async queue does not provide by construction; the relay exists to retry a send, and this row's requirement is "hand back the link now," which an async attempt cannot promise before it has run. Building the relay anyway and *also* returning the link synchronously would mean two delivery paths for one message. | Volume: if staff invitations reach a rate where synchronous SMTP calls on the request thread measurably slow the endpoint, move the send to the same claim-and-retry shape the owner relay uses — the token and expiry are already computed before the send, so the row does not change, only who calls `PlatformMailer`. |
| No new ADR; record the decision as a dated status addition on ADR 0097 | ADR 0097 decided an owner's invitation, by a platform process, with no grantable job attached — TENANT_OWNER carries every permission by construction. Nothing about extending an existing accepted decision fits "for everyone else the inviter chooses the job," a materially different authorization shape (`GrantManagementService#requireGrantable`) that the owner flow never exercises. A status addition can say ADR 0097 does not cover this; it cannot honestly carry the decision itself. | Never — a decision this different from ADR 0097's needs its own record by ADR 0000's own rule that a status addition may not rewrite what was decided. |

## Consequences

### Positive

- The owner flow's tests, invariants and `uq_owner_invitation` constraint are
  untouched — nothing about this wave risked `OwnerInvitationFlowTests` or
  `OwnerInvitationOverviewTests`, both outside this wave's brief.
- A staff invitation's row is simpler than an owner's: no relay, no
  `attempts`/`next_attempt_at`, because the delivery model is genuinely
  simpler (synchronous, always-return-the-link).
- `StaffInvitationController` stays the one place either kind of token is
  presented, so `/invite` and its accept flow do not fork by invitation type
  on the frontend.

### Negative

- Two tables now answer "is this person's access still pending," and a
  future cross-tenant or cross-kind report (control-plane's own equivalent of
  ADR 0100's overview, for staff) has to read both rather than one.
- The `isUnrecognised` fallback in `StaffInvitationController` is a coupling
  the two services do not otherwise have: each must keep answering
  `reason=INVALID` for "never heard of this token" and reserve `EXPIRED` for
  "found, but too late," or the fallback silently stops working for one
  direction. Neither service enforces this contractually; it is asserted by
  `StaffInvitationControllerTests`' existing `anUnusableLinkNamesItsReason`
  and is worth remembering the next time either `live()` method changes.

### Accepted trade-offs

- No event timeline for staff invitations, unlike ADR 0100 gave the owner
  table. A staff invitation's status column (`QUEUED`/`SENT`/`OPENED`/
  `ACCEPTED`/`CANCELLED`) is the only history kept; there is no
  `tenant.staff_invitation_events` recording every attempt the way
  `tenant.owner_invitation_events` does. Accepted because nothing in
  staff-and-access.md §4 asks for a per-invitation timeline, and the audit
  facts this wave records (`tenant.staff_invitation.invited`/`.resent`/
  `.cancelled`/`.accepted`) already answer "who did what, when" through the
  general activity log (§9's `9.3`) — a purpose-built timeline would
  duplicate that for a screen nobody asked for.

## Specification

### Physical model

`tenant.staff_invitations` (V0313): `id`, `tenant_id`, `subject_id`,
`grant_id` (unique, `REFERENCES iam.grants`), `locale`, `status`,
`token_hash` (unique, SHA-256 hex), `expires_at`, `email_given`,
`invited_by`, `invited_at`, `sent_at`, `opened_at`, `accepted_at`,
`cancelled_at`, `cancelled_by`, `last_error_code`, `version`. No name, phone,
or email column — the same stance `tenant.owner_invitations` takes, and for
the same reason: the profile lives in Keycloak, and `subject_id` is the only
key back to it. The job's name and scope are read at query time by joining
`grant_id` through `iam.grants`/`iam.roles`, never denormalised onto this
table, so there is exactly one place that can drift from the grant it names.

### API

- `POST /api/v1/operations/tenants/{tenantId}/staff/invitations` —
  `@RequiresCapability(IAM_GRANT_MANAGE, mutating = true)`. Creates the
  account, the membership, and the grant (via
  `GrantManagementService#grant`, unchanged — the same refusal People's
  Add-job gives), then the invitation row. Returns the invite link once.
- `POST .../staff/invitations/{id}/resend`, `DELETE .../staff/invitations/{id}`
  — the same capability; revoke cancels the invitation and revokes the
  grant, one audit fact each.
- `GET .../staff/invitations` — every open invitation for the People screen.
- `POST /api/v1/operations/invitations/inspect` and `.../accept` — unchanged
  paths, extended to serve either kind of token per this record's Decision.

### Security

`StaffAccounts#create` never requires a database transaction to be open
(`ExternalCallTransactionBoundaryTests`' governing property, verified here by
`StaffInvitationFlowTests`' own boundary assertions rather than that shared
file, matching how `OwnerInvitationFlowTests` already asserts its own). The
invite token is a 256-bit random value; only its SHA-256 is ever stored, and
it is classified `@Classified(DataClass.PERSONAL)` on the two response DTOs
that carry it so idempotency's replay cache (ADR 0031 over ADR 0029) encrypts
it at rest the same way a QR token does elsewhere in this codebase.

## Rollout and rollback

Additive migration, no backfill. Rollback is deleting the unreleased rows and
leaving the table — Flyway is forward-only, so a rollback ADR would add a
migration that drops it, not edit V0313.

## Implementation checklist

- [x] V0313 `tenant.staff_invitations`
- [x] `StaffAccounts#create`/`#findByPhone`, `StaffAccounts.StaffAccount`
      gains a nullable `email` and a `username`
- [x] `StaffInvitationService` (invite/resend/revoke/inspect/accept)
- [x] `StaffInvitationController` extended for both endpoint families
- [x] `staff-invite-dialog.ts` built to staff-and-access.md §4
- [x] People screen's «Приглашён» pill sourced from `GET .../staff/invitations`,
      and resend/revoke wired through `staff-access-dialog.ts`

## Exit criteria

A manager without a Keycloak subject id can invite a colleague from the
People screen, the colleague receives a one-time link (by email, or read out
from the toast), sets a password, and signs in with the job the manager
chose already in effect — proven by `StaffInvitationFlowTests`' accept
scenario reading `JdbcAuthorizationService#viewFor` afterward.

## References

- `docs/operations-gap-map.md` row `9.1a`
- `docs/operations-spec/staff-and-access.md` §0, §4, §11.1
- ADR 0097 (owner invitation), ADR 0100 (owner invitation history)
