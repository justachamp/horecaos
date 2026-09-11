# ADR 0098: A staff member who forgot their password asks for a reset

- Decision status: Proposed
- Implementation status: Partial — the whole flow is built and tested: `iam.password_resets` (V0213), the relay and its email in uz/ru/en, `findByLogin`/`setPassword`/`logoutEverywhere` over Keycloak, three endpoints on each staff prefix, and both consoles' sign-in link, request page and reset page. What is missing is not code: ADR 0097's open input is still open, so no deployment has a mail provider and nothing has actually been delivered to a staff member. Resets queue and wait, and the requester sees the same answer as everyone else
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0025, ADR 0028, ADR 0029, ADR 0033, ADR 0062, ADR 0097
- Supersedes / Superseded by: —
- Open inputs: whether a reset should also clear an account's outstanding Keycloak required actions, or leave an account that was never set up to the ADR 0097 invitation instead (platform owner); whether sixty minutes is the right link lifetime for an operator working a shift, or whether it should be shorter on the control plane than on operations (platform owner)

## Context

Staff sign in on a first-party page inside each console (ADR 0062): the
browser never reaches Keycloak, so Keycloak's own "forgot password" link is
not on any screen a staff member can see. The realm has no mail server of its
own either, which is why ADR 0097 gave the platform SMTP and an invitation
relay in the first place.

The consequence today is that a cashier who forgets their password cannot get
back in at all. Somebody with Keycloak admin access has to set a password for
them by hand and then tell them what it is — over a channel nobody chose, with
a value nobody rotates. That is the exact operation ADR 0028 exists to
prevent, performed by the people most likely to be trusted with it.

ADR 0097 already built everything the flow needs except the flow: a `mail`
module that sends over SMTP, a one-time token whose hash alone is stored, an
expiry, a retrying relay with backoff, a first-party page that sets a password
through Keycloak's admin API, and a control plane that can see whether the
email went. Its own Consequences section says so — "the same mail module and
token mechanism serve a staff 'forgot password' next."

Two forces make the shape non-obvious.

**Enumeration.** A reset request takes a login. Any answer that differs
between "this account exists" and "it does not" turns the endpoint into a
directory of the platform's staff, on an endpoint that is unauthenticated by
necessity. ADR 0062 already made sign-in answer identically for a wrong
password and an unknown username for this reason; a reset request that says
"no such user" would give back what sign-in refuses to.

**A reset is a credential change, not a login.** Whoever holds the link can
set the password of an account they have not otherwise proven they hold. The
holder of a *previous* password — a dismissed employee, a shared terminal
somebody stayed signed in on — keeps a live Keycloak session and a refresh
token that outlives the password change, because Keycloak does not end
sessions when a password is reset. So the reset has to end them, or the reset
is not a recovery, it is a second key cut for the same lock.

## Decision

1. **One request endpoint per console, always answering the same way.** A
   staff member asks from the sign-in page of whichever console they use, by
   username or email address. The platform answers `202 Accepted` with no
   body, always: for a live account, an account that does not exist, an
   account with no address, and an account whose reset was already queued. The
   page says "if the account exists, an email is on its way" and nothing more
   specific, because nothing more specific is known to it.
2. **A reset is a row in `iam.password_resets`, never a token.** When the
   login resolves to a Keycloak account with an address, the platform queues a
   reset: the subject, the console it was asked from, the locale, a status,
   attempts and a next attempt. A relay makes the token at send time, emails
   it, and keeps only its SHA-256 with a sixty-minute expiry. The table holds
   no address and no token, exactly as `tenant.owner_invitations` does.
3. **One live reset per account, and the newest wins.** A second request
   replaces the first: the row is reused, the stored hash is cleared, and the
   link already sent stops working at once. Asking twice therefore cannot
   accumulate live links, and cannot be used to flood an address either,
   because the second request only requeues what the first queued.
4. **The link opens the console it was asked from.** `/reset-password#token=…`
   on the operations origin or the control-plane origin, the token in the
   fragment so that it reaches no server or proxy log. The page inspects the
   token, then accepts it with a new password and nothing else — no name, no
   email, no verified flag. Keycloak's password policy applies and a refusal
   names the rule, the way ADR 0097's invite page already does.
5. **Accepting ends every other session, which takes two admin calls.** The
   token is spent, and the platform then calls `POST /users/{id}/logout`
   *and* `DELETE /users/{id}/consents/{staff-login-client}`. The second is
   not belt and braces: ADR 0062's direct grant asks for `offline_access`, so
   a staff refresh token is an **offline** token, and Keycloak's admin logout
   removes only regular sessions. Probed against the live 26.7 realm while
   this record was written — sign in, refresh, `POST .../logout` answers
   `204`, refresh again, still `200`. Deleting the consent for the sign-in
   client is what revokes the offline grants; verified on two devices at once,
   both refresh tokens refused afterwards, and the account able to sign in
   again immediately with the new password. A `404` from that call
   ("Consent nor offline token not found") is success: it is the state the
   reset is trying to reach.
6. **Two limits, not one.** The request endpoint is rate-limited per caller
   address through ADR 0033, and the one-live-reset rule above caps what any
   number of requests can produce for a single account. The first bounds a
   scan across accounts; the second bounds an attack on one.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keycloak's own "Forgot password?" — its `execute-actions-email` or the login page's reset link | ADR 0062 removed the redirect that would take a staff member to a Keycloak page at all, so the link has nowhere to appear; Keycloak's mail settings are a second mail configuration to keep correct, its templates have no Uzbek, and neither console could say whether a reset was sent or used | Staff sign-in moves back to Keycloak's hosted pages |
| A different answer for an unknown login, so the page can say "we have no account with that address" | Turns an unauthenticated endpoint into a staff directory, and gives away precisely what ADR 0062 makes sign-in refuse to give away | Never |
| Reuse `tenant.owner_invitations` for resets too | Its unique key is one row per tenant and owner, and a reset has no tenant: control-plane staff belong to no organization. Overloading the table would also make "the owner never finished onboarding" and "an operator forgot their password" the same row, with one status machine for two different stories | Never |
| A reset that also sets the name and verified flag, like ADR 0097's accept | A reset is not a setup. Marking an address verified because somebody opened a link is defensible for an invitation the platform sent to an address it chose; doing it on every reset would let a stale address quietly become "verified", and overwriting a name the person themselves set is a silent edit nobody asked for | Never |
| Leave other sessions alone and rely on the access token's own expiry | A Keycloak refresh token outlives the password change by design, so "I reset my password" would not remove the person the reset was performed because of | Never |
| `POST /users/{id}/logout` on its own, as this record first proposed | It answers `204` and leaves every staff refresh token working, because they are offline tokens and admin logout does not touch offline sessions. A revocation that reports success and revokes nothing is worse than none: the staff member is told the other sessions are gone | Staff sign-in stops requesting `offline_access` |
| Drop `offline_access` from the staff sign-in scope, so ordinary logout suffices | The scope is there so a console survives a browser restart (`StaffDirectGrantClient`); removing it would sign every operator out whenever they close the tab, to simplify a call that is made once per reset | The consoles gain another way to survive a restart |
| List the account's offline sessions and delete them one by one | Needs `view-clients` to resolve the client's UUID first, which the provisioning service account deliberately does not hold — probed live, it answers `403`. Two calls become four and a new role grant | The provisioning credential gains client-read roles for another reason |
| Send the link to the address the requester typed | Accepts an address from an unauthenticated caller and mails a credential to it; the address the platform sends to must be the one Keycloak already holds for that subject | Never |
| A single shared `/api/v1/staff/auth/password-resets` path for both consoles | ADR 0057's invariant is that every published path belongs to exactly one surface group, so one shared path would land in neither console's generated client; ADR 0062's sign-in endpoints are duplicated per prefix for the same reason | ADR 0057 gains a shared staff surface group |

## Consequences

### Positive

- A staff member who forgets their password recovers without anyone opening
  Keycloak, and without a password travelling over a chat message.
- The mail module, the token mechanism, the backoff and the page shape are
  ADR 0097's, already built and already tested; this record adds a table, a
  relay, three endpoints per console and two pages per console.
- Ending every other session makes the reset a real revocation, so it is also
  the answer to "somebody still has my password".

### Negative

- Nothing is delivered until ADR 0097's open input is closed: a sending
  provider, its DNS records and its password. Until then a reset queues and
  waits, and the requester sees the same "an email is on its way" as everyone
  else — a deliberately uninformative answer that is also uninformative when
  mail is genuinely not configured. The runbook is where an operator finds
  that out.
- A staff account with no email address in Keycloak cannot recover this way at
  all, and cannot be told so. Those accounts need an administrator.
- Ending every session signs the person out of every device they were using,
  including the ones they still hold. That is the point, and it is still a
  surprise the first time.
- `iam` gains an outbound mail port of its own (`StaffEmailSender`) rather
  than calling `PlatformMailer` directly, because the `mail` module already
  reads its SMTP password through `iam`'s secret manager and a direct call the
  other way would make the two modules cyclic. The port duplicates a small
  outcome type. The alternative — moving the secret seam out of `mail` so it
  becomes a true leaf — is the better fix and a larger one than this record.

### Accepted trade-offs

- The relay reads the address from Keycloak on every send, a network call per
  email, so that the platform stores no copy. ADR 0097's trade-off, unchanged.
- Sixty minutes is shorter than an invitation's seventy-two hours, because a
  reset is asked for by somebody sitting at the screen, while an invitation
  waits for somebody who does not know it is coming.
- An access token already issued survives the reset for the rest of its own
  short lifetime. It is a self-contained JWT and nothing server-side
  invalidates one; what the reset removes is the ability to get another. The
  alternative is a realm-wide not-before push on every reset, which would sign
  out every staff member of every tenant to end one person's session.
- Revoking the consent also clears the account's recorded consent for the
  sign-in client, not only its offline tokens. Nothing turns on that here —
  the realm does not require consent for a first-party client — but it is a
  second effect of a call made for the first.
- The uniform `202` means a mistyped address produces silence rather than a
  correction. The page says so in as many words.

## Specification

### Schema (V0213)

`iam.password_resets`, no tenant column — a staff account belongs to an
identity provider, and control-plane staff belong to no organization at all:

| Column | Why |
|---|---|
| `id` | `Ids.newId()` |
| `subject_id` | the Keycloak subject; `UNIQUE`, which is the one-live-reset rule |
| `console` | `CONTROL_PLANE` or `OPERATIONS`; which origin the link points at |
| `locale` | `uz`, `ru` or `en`, from the requesting page |
| `status` | `QUEUED`, `SENT`, `ACCEPTED`, `FAILED` |
| `token_hash` | SHA-256 hex, `UNIQUE`, null unless `SENT` |
| `expires_at` | sixty minutes from the send |
| `attempts`, `next_attempt_at`, `last_error_code` | the relay's own state |
| `requested_at`, `sent_at`, `opened_at`, `accepted_at` | when each step happened |
| `version` | optimistic lock |

Expired is not a state: it is `SENT` with `expires_at` in the past, read at
the moment it matters. `GRANT SELECT, INSERT, UPDATE` to
`horecaos_application`; `UPDATE` is required by the `FOR UPDATE` the accept
path takes.

### Identity provider

`StaffAccounts` gains three operations, all on the provisioning credential
that already creates these accounts:

- `findByLogin(usernameOrEmail)` — exact username first, then exact email;
  empty when neither resolves or the account has no address.
- `setPassword(subjectId, password)` — the `reset-password` admin call and
  nothing else. It must not touch `firstName`, `lastName` or `emailVerified`,
  which is what separates it from ADR 0097's `completeSetup`.
- `logoutEverywhere(subjectId)` — `POST /admin/realms/{realm}/users/{id}/logout`
  for the regular sessions, then
  `DELETE /admin/realms/{realm}/users/{id}/consents/{staff-login-client}` for
  the offline ones, tolerating that call's `404`. The client id comes from
  `horecaos.keycloak.staff-login-client-id`, the same property ADR 0062's
  sign-in reads, because the grants being revoked are the ones it issued.

### HTTP

Public, unauthenticated, on both staff prefixes (`{console}` is
`control-plane` or `operations`):

- `POST /api/v1/{console}/auth/password-resets` — `{login, locale}`, always
  `202` with no body.
- `POST /api/v1/{console}/auth/password-resets/inspect` — `{token}` in the
  body, answering which console, the masked login and the expiry, or `404`
  with a reason of `INVALID` or `EXPIRED`.
- `POST /api/v1/{console}/auth/password-resets/accept` — `{token, password}`,
  answering `204`.

Each is permitted one path at a time in `SecurityConfiguration` with the
reason, exempted by exact path in `EndpointCapabilityDeclarationTests` (both
loops), and rate-limited per caller address through ADR 0033.

### Consoles

Both consoles get a "Forgot password?" link on the sign-in page, a
`/forgot-password` request page that always shows the same sentence, and a
`/reset-password` page that mirrors ADR 0097's invite page — inspect, then a
new password twice, then a named policy refusal, then the sign-in page.

### Configuration

`horecaos.frontends.control-plane-origin`, beside the operations origin ADR
0097 added, wired from `HORECAOS_CONTROL_PLANE_ORIGIN` in
`deploy/compose.production.yml` (the variable already exists in
`env.template` and the Caddyfile).

## Rollout and rollback

Additive: one table, three endpoints per prefix, two pages per console, one
scheduled relay. With mail unconfigured, resets queue and wait exactly as
invitations do. Rolling back leaves queued rows unread and changes no
account; a reset already accepted has already happened at Keycloak and is not
undone by rolling back the platform.

## Implementation checklist

- [x] `iam.password_resets` (V0213) and its store
- [x] `StaffAccounts.findByLogin`, `setPassword`, `logoutEverywhere` over Keycloak
- [x] Request, inspect and accept on both staff prefixes, permitted and rate-limited
- [x] The relay, its token, expiry, replacement and backoff, and the email in uz/ru/en
- [x] `horecaos.frontends.control-plane-origin` wired through deployment
- [x] Both consoles: the sign-in link, the request page and the reset page
- [x] Runbook: password resets ride the same mail settings
- [ ] A mail provider, so that a reset actually arrives (ADR 0097's open input, not this record's)

## Exit criteria

A staff member who asks for a reset from either console's sign-in page
receives an email whose link lets them set a new password, signs in with it,
and finds that a session they had open elsewhere has been ended — while a
request for a login that does not exist produces the same answer on screen and
no email at all.

## References

- ADR 0097 (the mail module, the token mechanism, the invite page this mirrors)
- ADR 0062 (first-party staff sign-in on two prefixes)
- ADR 0028, ADR 0029, ADR 0033
