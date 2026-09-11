# ADR 0097: HorecaOS sends its own email, and invites a tenant owner itself

- Decision status: Proposed
- Implementation status: Partial — the `mail` module over SMTP with its password from the secrets manager, V0210's `tenant.owner_invitations`, `OwnerInvitationService` and `OwnerInvitationRelay`, the owner step queueing an invitation for an account with no password, `StaffInvitationController`'s inspect and accept over `KeycloakStaffAccounts`, the owner email encrypted in the onboarding input, the control plane's invitation panel with resend and the operations console's `/invite` page, and, from ADR 0100, each invitation's append-only history (`tenant.owner_invitation_events`) shown as a timeline on that panel plus the cross-tenant overview `GET /api/v1/control-plane/owner-invitations` behind `/tenants/invitations` and the address-free `GET /api/v1/control-plane/owner-invitations/waiting` behind the tenant directory's owner column, tested in `SmtpPlatformMailerTests` against Mailpit, `OwnerInvitationFlowTests`, `OwnerInvitationOverviewTests`, `OwnerInvitationControllerEndpointTests`, `TenantOwnerLinkOrInviteTests` and against the live realm in `KeycloakOrganizationIntegrationTests`. Nothing is delivered until a provider's settings and the domain's DNS records exist (runbook `platform-email.md`); none are configured yet
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: the platform owner decided on 2026-09-11 that the platform sends email over SMTP through a hosted provider, and that HorecaOS, not Keycloak, sends the owner's invitation and hosts the page where they set their password. The structure below was proposed by Claude on those answers; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0008, ADR 0009, ADR 0028, ADR 0029, ADR 0062
- Supersedes / Superseded by: —
- Open inputs: which sending provider, and the sending domain's SPF, DKIM and DMARC records on horecaos.uz (platform owner); counsel's confirmation that a hosted provider outside Uzbekistan may process staff email addresses under the amended ZRU-547 (legal)

## Context

Onboarding's owner step (ADR 0008, ADR 0009) creates the owner's Keycloak
account with an unverified email and no password, adds it to the tenant's
organization, and grants tenant-owner authority. Nothing tells the owner. The
realm has no mail server, and staff sign in on a first-party page (ADR 0062),
so even Keycloak's own "forgot password" is out of reach. An owner can only
get in if someone sets a password for them by hand in Keycloak.

The platform has never sent an email. The notification channel enum names
EMAIL and marks it unwired. And the owner's address is stored in plain text
in every onboarding step's input, which ADR 0029 does not allow.

## Decision

1. **One way out for email.** The platform sends email over SMTP submission
   (port 587 with STARTTLS, or 465) to a hosted sending provider. Host, port,
   user name and sender address are configuration; the password is a secret
   reference (ADR 0028). A small `mail` module owns the connection and
   nothing else opens one. Without a configured host it refuses to send, and
   says so, instead of pretending. It never logs a recipient or a body.
2. **HorecaOS invites the owner.** When the owner step leaves an account with
   no password, it queues an invitation. A relay sends it: it reads the
   address from Keycloak at that moment, makes a one-time token, stores only
   the token's hash with a 72-hour expiry, and emails a link to the
   operations console in the language the operator chose for the owner. A
   failed send is retried with backoff; a resend replaces the token, so the
   earlier link stops working.
3. **The owner finishes on a first-party page.** The link opens a page in the
   operations console where the owner gives their name and a password. The
   platform checks the token, sets both in Keycloak, marks the address
   verified and spends the token. The realm's password policy applies.
4. **The control plane sees it.** The onboarding screen shows the invitation
   as queued, sent, opened, accepted, failed or expired, with when and why,
   and can resend it with a reason, audited.
5. **The address is not the platform's to keep.** It lives in Keycloak. The
   onboarding input keeps it encrypted (ADR 0029) until the account exists,
   the invitation stores none, and the control plane shows it masked.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keycloak sends its own "update password" email | Keycloak's styling and no Uzbek; the control plane could not see whether it went or was used; a second mail configuration to keep | Staff sign-in moves to Keycloak's hosted pages |
| Our own mail server in Uzbekistan | Reaching Gmail and Outlook inboxes needs reverse DNS and IP reputation a new server lacks | Counsel rules that addresses may not leave the country |
| The ADR 0020 notification pipeline | Built for a tenant's customers: consent, per-tenant templates and bindings, none of which a platform email to staff has | A tenant sends email to its own customers |
| Store the token and send it later | A stored token can be replayed by anyone who reads the table (ADR 0009) | Never |

## Consequences

### Positive

- An owner gets from "onboarding started" to signed in without anybody
  touching Keycloak by hand.
- The operator can see whether the invitation went out and whether it was
  used, and can resend it.
- The same mail module and token mechanism serve a staff "forgot password"
  next.

### Negative

- Nothing is delivered until a provider is chosen, its DNS records are
  published and its password is stored; until then invitations wait, marked
  as waiting for mail configuration.
- A hosted provider outside the country processes staff addresses, pending
  counsel.

### Accepted trade-offs

- The relay reads the address from Keycloak on every send, a network call
  per email, so that the platform stores no copy.

## Specification

- `mail` module: `PlatformMailer.send(OutgoingMail)` answering sent,
  not configured, rejected or failed; configuration `horecaos.mail.smtp.*`;
  password reference `horecaos.mail.smtp.password-reference`.
- `tenant.owner_invitations`: tenant, Keycloak subject, locale, status,
  token hash, expiry, attempts, next attempt, last error, sent, opened and
  accepted times. One row per tenant and owner; a resend reuses it.
- Public: `POST /api/v1/staff/invitations/inspect` and
  `POST /api/v1/staff/invitations/accept`, the token in the body and never a
  URL, rate-limited per address. Control plane:
  `GET` and `POST …/resend` on `/api/v1/control-plane/tenants/{tenantId}/owner-invitation`.
- Operations console: `/invite#token=…`, the token in the fragment so it
  reaches no server log.

## Rollout and rollback

Additive. With mail unconfigured, invitations queue and wait. Rolling back
leaves queued rows unread; accounts already set up are unaffected.

## Implementation checklist

- [x] Mail module over SMTP, with the password from the secrets manager
- [x] Invitation table, relay, token hash and expiry
- [x] Owner step queues an invitation for an account with no password
- [x] Public inspect and accept; Keycloak password, name and verified address
- [x] Owner email encrypted in the onboarding input
- [x] Control-plane state and resend; operations-console set-password page
- [x] Deployment configuration (`compose.production.yml`, `env.template`, runbook `platform-email.md`)
- [ ] A provider account and the domain's SPF, DKIM and DMARC records (platform owner)

## Exit criteria

A tenant onboarded with an owner email produces an email whose link lets the
owner set a password and sign in to the operations console, and the control
plane shows the invitation as accepted.

## References

- ADR 0009, ADR 0062, ADR 0028, ADR 0029
