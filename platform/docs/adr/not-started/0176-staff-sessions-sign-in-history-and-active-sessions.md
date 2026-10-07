# ADR 0176: Staff sessions: sign-in history and active sessions

- Decision status: Accepted — proposed by Claude (batch 19); accepted by the platform owner 2026-10-07
- Implementation status: Not started — a staff member can sign in and sign out of this
  device and nothing else about their sessions. What exists is the sign-in path and one
  Keycloak operation, not a history or a list. `StaffSessionController` (ADR 0062) takes
  a username and password and `StaffDirectGrantClient.signIn` exchanges them with
  Keycloak on the confidential `horecaos-staff-login` client with the scope `openid
  offline_access organization`, so every staff refresh token is an offline token and
  Keycloak answers `refresh_expires_in: 0`; the controller reads the caller's address
  (`getRemoteAddr`) only to hash it with the username into a rate-limit bucket key, and
  reads no `User-Agent` at all. `DELETE
  .../auth/sessions/current` revokes one refresh token (RFC 7009, best effort).
  `KeycloakStaffAccounts#logoutEverywhere` ends every session of an account and is
  called only by `PasswordResetService` (ADR 0098), after a reset; it makes two admin
  calls, `POST /users/{id}/logout` and `DELETE /users/{id}/consents/{client}`, because
  the first removes only online sessions and the staff tokens are offline ones. There is
  no table, endpoint, capability or screen for sign-in history or active sessions;
  «Мой профиль» renders the sentence `staff.myProfile.security.notBuilt` where
  «Безопасность» should be. `infra/keycloak/realm/horecaos-realm.json` sets no
  session lifespan and no event configuration, so Keycloak's defaults apply to both.
  `StaffSecurityFact` (ADR 0098) is the audit shape for what happens to a staff
  account, and `iam.staff_members (tenant_id, principal_subject)` (V0453,
  `uq_staff_member_subject`) is the key a per-person record can reference.
- Date proposed: 2026-10-07
- Date decided: 2026-10-07
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0003, ADR 0007, ADR 0009, ADR 0025, ADR 0027, ADR 0029, ADR 0030,
  ADR 0031, ADR 0033, ADR 0057, ADR 0062, ADR 0098, ADR 0139, ADR 0148
- Supersedes / Superseded by: — (edits no record's text, but it reopens one line of ADR
  0139 and one rejected row of ADR 0098 and changes how ADR 0098's reset is implemented;
  each is named here. It stays inside ADR 0139's boundary row "Password, MFA, sessions:
  Keycloak, its own flow" and inside ADR 0062's direct grant.
  (1) One line of ADR 0139, "What this record deliberately does not decide: MFA state
  and active-session projection (`staff-and-access.md` §11.6 and §11.9)", is reopened
  for sessions only; MFA state is ADR 0148's and is not touched here.
  (2) One rejected row of ADR 0098 (Accepted), "List the account's offline sessions and
  delete them one by one", is reopened for listing sessions and ending a single session
  only. That row was rejected because resolving the sign-in client's UUID needs
  `view-clients`, which the provisioning credential does not hold (probed live: `403`),
  and its revisit trigger is "The provisioning credential gains client-read roles for
  another reason". That trigger has not fired: `infra/keycloak/README.md` still lists
  only `manage-organizations`, `manage-users`, `view-users` and `query-users` for
  `horecaos-provisioning`, and this record grants nothing. So this record does not claim
  the trigger; it avoids the reason, by reading the client UUID from the deployment
  property `horecaos.keycloak.staff-login-client-uuid` instead of calling
  `view-clients`. What stays unproven is whether the list and delete endpoints accept
  `view-users` and `manage-users` alone, and the probe (first open input) establishes
  it: if either refuses them, the row stays rejected, no role is granted to make it
  work, and only the history table and «Выйти везде» ship.
  (3) How ADR 0098's reset is implemented changes, not what it decides:
  `PasswordResetService` calls `StaffSessionLedger` only after `logoutEverywhere` has
  succeeded, and marks every row of that subject, in every tenant, `PASSWORD_RESET` only
  when `sessionsEnded` is true. When `logoutEverywhere` fails, the rows stay as they are
  and ADR 0098's `sessionsEnded: false` answer and `iam.password_reset.sessions_not_ended`
  fact stand untouched, because a history that says sessions ended when they did not is
  the failure ADR 0098 calls worse than none)
- Open inputs: each is closed on its proposed default if the owner accepts the record
  as written; the ones that name a person other than the owner stay with that person
  and the work they block is marked.
  - **Three Keycloak behaviours this record leans on and has not yet probed**
    (engineering). That `GET /admin/realms/{realm}/users/{id}/offline-sessions/{clientUuid}`
    lists the sessions the direct grant creates; that `DELETE
    /admin/realms/{realm}/sessions/{id}?isOffline=true` ends one of them and its refresh
    token then answers 400; and that the access token the direct grant returns carries a
    `sid` claim equal to that session's id. None is established by the repository:
    ADR 0098 proved `logoutEverywhere` against the live 26.7 realm and nothing else
    here. Proposed default: a throwaway-container probe in the manner of
    `infra/keycloak/spikes/mfa-lockout-probe.py`, written first and re-run when the
    pinned image changes. The probe also asks whether those two calls accept the
    provisioning credential as it is (`view-users` and `manage-users`, no client role).
    If the listing does not work, including a `403` for want of a client role, which
    this record does not cure by granting one (ADR 0098's rejected row then stays
    rejected), the screen shows the platform's own rows with a state derived from them
    and says so; if `sid` is absent, no row is marked as the current session; if the
    single delete does not work or is refused, only «Выйти везде» is offered. The build
    of the history table and of «Выйти везде» waits on none of the three.
  - **How the platform learns the staff-login client's internal id** (engineering,
    operations). The offline-session endpoint takes the client's UUID, not its
    `clientId`, and the provisioning credential that backs `KeycloakStaffAccounts`
    holds `manage-users`, `view-users` and `query-users` and, deliberately, no client
    role (`infra/keycloak/README.md`). Proposed default: a deployment property,
    `horecaos.keycloak.staff-login-client-uuid`, written by
    `infra/keycloak/create-staff-login-client.sh` and read at startup; no new realm role
    is granted to any service account. It is also how this record avoids the `view-clients`
    `403` that ADR 0098's rejected row records (Supersedes).
  - **How long a sign-in row is kept** (legal, security). Proposed default: 180 days
    from `signed_in_at`, an ADR 0030 key (`iam.staff_sign_in_retention_days`, range 30 to
    730, platform and tenant), swept in report-only mode first as ADR 0029 requires for
    a destructive job. ADR 0029's provisional 24 months for `PERSONAL` data is the
    ceiling; the row holds none.
  - **Whether a manager may see a colleague's sign-ins or end their sessions**
    (legal, product). Spec §3 tab 4 pictures «Последний вход», «Активные сеансы» and
    «Завершить все сеансы» on the person card. Proposed default: no, in v1. Sessions
    belong to the person: a Keycloak session is global to the account and not to one
    tenant (ADR 0139 declined to disable an account for that reason), a manager's view of
    when a colleague signs in is workplace monitoring, and ending employment already ends
    every grant in the tenant. The person card keeps its named absence.
  - **Whether to tell a person when a new device signs in** (product, security).
    Proposed default: no. A message needs a delivery provider the platform has not yet
    chosen (ADR 0146 is the SMS contract) and an alert nobody asked for becomes
    noise; the history screen is the check a person makes when they are worried.
  - **Whether failed sign-ins are recorded against a person** (security). Proposed
    default: no. ADR 0062 forbids telling an existing account from a missing one, and
    attributing a failure to a subject needs the lookup that tells them apart.
  - **Session lifetime** (security). ADR 0148 asks whether the lifetime of an offline
    session should change for accounts with a second factor and leaves it unchanged.
    Proposed default: unchanged here too; this record's history is what makes the
    question measurable, since it shows how long sessions actually live.
  - **Whether platform administrators on the control plane get the same screen**
    (platform owner). Proposed default: out of scope. A platform-scope account holds no
    tenant and no staff-member row, so there is no tenant to attribute a row to; the
    sign-in path records only when the token names at least one tenant.
  - **The wording of the confirmation** (product). Proposed default: it names how many
    sessions will end, says that this device signs out too and that the account's
    sessions in every company it belongs to end with it, and says that a request
    already in flight may still succeed for a few minutes (Keycloak's access-token
    lifetime, five minutes by default; the realm file sets none, and ADR 0098 recorded
    the same limit as accepted).

**To accept as written:** say "accept 0176". Every open input above is then closed on
its proposed default.

**Decision record, 2026-10-07.** Accepted by Ayubkhon Abbosov (platform owner) under the standing instruction "lets finish all" given the same day, which accepts every record proposed in batch 19 (ADRs 0154–0176) on the default each open input proposes. An input that names a person other than the owner, or an external fact (a device model, a legal wording, a provider capability, a dataset publication), stays with that owner as written and implementation proceeds without it, marking what waits. Implementation starts in operations batch 20 (2026-10-07).

## Context

Gap-map row `X.5` (Мой профиль, staff self-service, spec §10) is `PARTIAL`. «Личные
данные» is built (`0.2c`); what is left in «Безопасность» is named as absent: "password
or PIN, sign-in history, active sessions and MFA". MFA has its record, ADR 0148, and
password change has ADR 0098's reset. The row's own "Blocked by" says what remains has
no owner: "sign-in history and active sessions have no decision record
(`staff-and-access.md` §11.6 and §11.7)". The spec's §11.6 is short and exact: "Keycloak
holds all three. Nothing projects them and no endpoint exposes them", with the pointer
that the projection "likely belong[s] to the new staff-identity ADR". ADR 0139 then drew
its boundary the other way and left sessions to Keycloak, so the question this record
answers is the one ADR 0139 asked of the next one: what does the platform record and
show, given that Keycloak stays the authority.

**What Keycloak holds and exposes, as far as this repository knows.**

| Fact | Where | How it is read |
|---|---|---|
| A person's sessions | Keycloak, per account and global to the realm, not per tenant | Admin API, two lists: online sessions (`/users/{id}/sessions`) and offline sessions per client (`/users/{id}/offline-sessions/{clientUuid}`). Not probed here |
| That the platform's sessions are offline ones | `StaffDirectGrantClient.SCOPE` requests `offline_access`; the class records `refresh_expires_in: 0` as verified live | By the scope: a staff refresh token is an offline token, so the list that matters is the second |
| Start and last access of a session, the client, the address | Keycloak's session representation | Admin API. Keycloak's own address is a full one and is not what this record shows |
| A browser or device name | Nowhere in Keycloak's session | Only the platform, which receives the `User-Agent` at its own sign-in endpoint, can see it |
| Which tenant a session belongs to | Nowhere: a session is the account's | The token's `organization` claim at sign-in (read by `JwtCurrentActor`), which names the organizations the account belongs to |
| Sign-in and failure events | Keycloak's event store, only if enabled on the realm | The realm file enables none; Keycloak's defaults apply. Events are per account, not per tenant |
| Ending sessions | Keycloak | `POST /users/{id}/logout` removes online sessions only; deleting the consent for the sign-in client removes the offline tokens as well (`logoutEverywhere`, proved on two devices in ADR 0098). Neither revokes an access token already issued |
| How long an offline session lives | Keycloak's realm defaults | Not set by the realm file; the idle timeout is Keycloak's default and has not been read from the running realm |

Two consequences shape the rest. First, the one thing a person wants to see in a list
of sessions, "is this my phone?", is a fact only the platform has, because the platform
is the party the browser talks to at sign-in (ADR 0062). Second, because a session is
global and a person can belong to two tenants (ADR 0009 keeps that case honest), what is
shown inside one tenant's «Мой профиль» is the account's sessions, and an action on them
is the account's, which the screen must say.

**The surface that is asked for.** Spec §10 pictures «Безопасность» as "active sessions
with a «Выйти везде» action", and spec §3's «Безопасность» tab for a manager lists the
same with «Последний вход» beside it. The first is a person acting on their own
account. The second is a manager reading and acting on someone else's, and this record
treats it differently on purpose: the constraint that makes the choice non-obvious is
that the platform could show a manager when and from what a colleague signs in, and
that doing so is monitoring an employee through an account that may also serve another
employer.

## Decision

**Keep Keycloak the authority for sessions, add a platform record of sign-ins so a
person can recognise their own, show the account's live sessions to that person only,
and give them one audited action to end them all.**

1. **Keycloak stays the authority; the platform adds a record, not a second source of
   truth.** Whether a session is live is Keycloak's answer. The platform's row says what
   the session was (a closed-vocabulary device label and the times) and never decides
   that it is still alive. A row the platform thinks is open and Keycloak does not list
   reads as ended.

2. **Record a sign-in at the platform's own sign-in endpoint, after the token is
   minted, and never let the record fail the sign-in.** `StaffAuthService` receives the
   issued token pair, reads the session id (`sid`) and the organization claim from the
   access token, derives a device label from the `User-Agent`, and writes one row per
   tenant the token names, in its own transaction. A failure is a counter and a log line
   with no identity, and the person is signed in anyway. A sign-in whose token names no
   tenant (a platform-scope account) is not recorded. A refresh updates `last_seen_at`,
   at most once in ten minutes, by a conditional write.

3. **The device label is three small closed sets, and nothing else about the device is
   kept.** Browser family, operating-system family and form (desktop, phone, tablet),
   mapped from the `User-Agent` by a short allow-list parser in the repository. The
   raw header, the address, a coarse network prefix, a position and any device
   identifier are not stored, logged or put on an event. The record holds the tenant, the
   subject and the closed labels, and no personal data beyond the subject id.

4. **The list is Keycloak's, labelled by the platform's rows.** A person's «Сеансы» list
   reads the account's offline sessions from Keycloak, joins them to the platform's rows
   by session id, and shows each with its device label, its sign-in time and its last
   activity. A session with no row (signed in before this record shipped, or by a path
   that does not record) shows as «Неизвестное устройство» with Keycloak's times only.
   The session whose id equals the caller's `sid` is marked «Это устройство». If
   Keycloak cannot be asked, the screen shows the platform's rows, says that the state
   is the platform's record and not Keycloak's, and still offers the action.

5. **Sessions are the person's own, and in v1 only they see or end them.** There is no
   manager view of a colleague's sign-ins, no manager action that ends another person's
   sessions, and no new capability: the routes use the existing
   `@StaffSelfAuthorized(staff.self.manage)` strategy of ADR 0139. A manager who needs a
   departed colleague locked out ends their employment, which ends every grant in the
   tenant; the account's sessions in another company are not theirs to end.

6. **«Выйти везде» is one audited, rate-limited, idempotent act.** It calls
   `StaffAccounts#logoutEverywhere` (both Keycloak calls), marks the caller's rows in
   this tenant ended with reason `SIGNED_OUT_EVERYWHERE`, writes an ADR 0027 `SECURITY`
   fact, and returns; the console then clears its own tokens and goes to the sign-in
   page, because this device is among the sessions ended. The reason is picked from a
   closed list and never typed. Rows in other tenants are not touched: they read as
   ended because Keycloak no longer lists the session, and the sweeper closes them by
   age. Ending one session at a time is built only if the open input about
   `DELETE /sessions/{id}` is closed in its favour, which is what reopens the row of ADR
   0098 named under Supersedes.

7. **History is operational, not evidence.** A sign-in is not an ADR 0027 fact; the
   rows are kept 180 days and swept, and nothing audits a sign-in. What is audited is the
   one act that changes the state of an account's sessions. Every path that ends all of
   an account's sessions marks the rows through one application service so that history
   says why. ADR 0098's reset is one of them, on one condition: it marks rows
   `PASSWORD_RESET` only after `logoutEverywhere` has succeeded and its `sessionsEnded`
   is true, and then for the subject's rows in every tenant, because the unauthenticated
   reset path has no tenant. If Keycloak did not end the sessions, the rows are left
   open and ADR 0098's `sessions_not_ended` fact is the only record, as it is today.

8. **No failed attempts, no notifications, no network address in v1.** Each is named in
   the Alternatives table with what would reopen it.

9. **This record leaves two neighbouring rows where they are.** MFA state in
   «Способ входа» is ADR 0148's projection and fills a row this screen reserves; the
   password hand-off is ADR 0098's reset flow; the terminal PIN is the record for row
   `X.3`. None is built or decided here.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Use Keycloak's event store as the sign-in history | The realm enables no events; they are per account, not per tenant, so a manager of one company would read a person's sign-ins to another; the session list shows no browser name; and it adds a retention and storage setting on a service the platform does not own | Keycloak events are enabled for another reason and a tenant-neutral, self-only history is acceptable |
| List Keycloak's sessions with no platform record | The only fields are times, a client name and a full address. A person cannot tell which of three "Chrome" sessions is a stolen laptop, and the one fact that helps, the device, is the one Keycloak does not have | Never as the whole answer; it is the degraded mode when the platform's rows are missing |
| Store the sign-in address or a coarse prefix | Useful only with a geo-lookup the platform does not have, and an address is personal data with a retention question. A device and a time are enough for "is this mine" | A person cannot recognise a session from device and time alone, or security asks for network evidence, and a geo provider has been through ADR 0034's processor register |
| Show a manager each person's last sign-in and sessions (spec §3 tab 4) | Workplace monitoring through a global account; a manager of one tenant would read activity belonging to another. "Has the invitee ever signed in" is already answered by `employment_status` `PENDING` or `ACTIVE` | Legal confirms a lawful basis and product names a decision that needs it; then a last-sign-in timestamp alone, behind its own capability |
| Let a manager end a colleague's sessions | A Keycloak session is the account's, and ending it signs the person out of every company they work for. ADR 0139 declined to disable the account for the same reason; ending employment already removes access in this tenant | A lost-device incident where the person cannot act for themselves; then a platform-support action through ADR 0081's session, not a tenant capability |
| Audit every sign-in as a `SECURITY` fact | One fact per sign-in with ten years' retention (ADR 0027's default) is a large store of data about when people log in, with no consumer. The terminations, which change state, are audited | Security asks for authentication events as evidence after an incident in which the history had aged out |
| Notify the person of a new-device sign-in | Needs a delivery provider and a message nobody has asked for; adds a path for a sign-in to cause an outbound send | ADR 0146's provider is live and security wants it |
| Record failed attempts against the person | Attributing a failure needs the account lookup that ADR 0062 and ADR 0098 spent effort keeping from telling a caller whether an account exists | Never from the sign-in path; Keycloak's own brute-force events if they are ever enabled |
| Issue platform sessions (an own token store) so every session is the platform's to list and end | A second token issuer, which AGENTS.md forbids: Keycloak owns credentials and sessions | Never |
| Drop `offline_access` so sessions are ordinary online ones | Would change how long the console stays signed in after a browser restart, which ADR 0062 chose; it is a product and security decision of its own, not a side effect of a history screen | ADR 0148's session-lifetime input is answered |
| Drop this record and leave «Безопасность» as the named absence | Costs nothing and leaves a person who loses a phone with no way to end its session short of the password reset | Never; «Выйти везде» is the cheap half and the history is what makes it usable |

## Consequences

### Positive

- A person who loses a phone can end every session from the screen they already use, with
  the confirmation naming what will happen, and the act leaves an audit fact.
- A person can recognise their own sessions by device and time, which Keycloak alone
  cannot show.
- The platform records no address, no raw `User-Agent` and no position, so the table
  holds no personal data beyond the subject id.
- Every path that ends an account's sessions says why in the history, including the
  password reset that already existed, when it did end them.

### Negative

- The screen depends on three Keycloak behaviours not yet probed and on a client UUID the
  platform does not hold today; each has a fallback and each fallback is a weaker screen.
- A session on the list is global to the account. A person who works for two companies
  sees the same session in both and ending it ends it in both, which the screen has to
  say in plain words and which a reader can still misread.
- A row per tenant fans a sign-in out to as many rows as the person has tenants.
- Access tokens already issued survive «Выйти везде» for their lifetime. The act is
  immediate for refresh and bounded, not instant, for a request already holding a token.
- A manager has no view of a colleague's sign-ins and no way to end their sessions, which
  some tenants will want and which is a deliberate refusal.

### Accepted trade-offs

- The device label is coarse. Two Chrome-on-Windows sessions from different offices look
  identical; the person is told when each was last active and decides.
- The history is not evidence. If security later needs to say who signed in on a given
  day, rows older than 180 days are gone.
- Sign-ins before this ships have no label and read as unknown devices.
- A request in flight when «Выйти везде» is pressed may complete.

## Specification

### Ports and ownership

`iam` owns all of it. A new port `iam.api.accounts.StaffSessions` has two operations,
`list(subjectId)` and, if the probe allows, `end(subjectId, sessionId)`; its Keycloak
adapter `KeycloakStaffSessions` sits beside `KeycloakStaffAccounts`, uses the same
provisioning credential and the same timeouts, and has a recorded fake (ADR 0007's
discipline, as the other adapters have). `StaffSessionLedger` is the application service
that writes sign-in rows, updates `last_seen_at`, and is the single place rows are marked
ended, so the reason is always recorded: the sign-out-everywhere route calls it to end the
account's sessions and mark the caller's rows in this tenant, and `PasswordResetService`
calls its marking operation for the subject in every tenant after, and only after, its own
`logoutEverywhere` has succeeded (Audit, below). `StaffAuthService` calls the ledger after
a token is issued and after a refresh.

### Physical model

Additive migration; the number is reserved by the wave that builds it (check every
sibling worktree).

```text
iam.staff_sign_ins
  id uuid pk (Ids.newId())
  tenant_id uuid not null
  principal_subject varchar(255) not null
  keycloak_session_id varchar(64) not null
  browser_family varchar(24) not null     -- CHROME, SAFARI, FIREFOX, EDGE, YANDEX, SAMSUNG, OTHER
  os_family varchar(16) not null          -- WINDOWS, MACOS, LINUX, ANDROID, IOS, CHROMEOS, OTHER
  device_form varchar(12) not null        -- DESKTOP, PHONE, TABLET
  signed_in_at timestamptz not null
  last_seen_at timestamptz not null
  ended_at timestamptz null
  end_reason varchar(24) null             -- SIGNED_OUT, SIGNED_OUT_EVERYWHERE, PASSWORD_RESET, ENDED_ONE, EXPIRED
  fk (tenant_id, principal_subject) -> iam.staff_members (tenant_id, principal_subject)   -- uq_staff_member_subject
  unique (tenant_id, keycloak_session_id)
  check (ended_at is null) = (end_reason is null)
  check browser_family, os_family, device_form, end_reason each in their closed set
  index (tenant_id, principal_subject, signed_in_at desc)
  GRANT SELECT, INSERT, UPDATE, DELETE TO horecaos_application     -- DELETE only for the retention sweeper
```

The three label columns are `INTERNAL`: closed vocabularies with no personal content.
There is no column for an address, a header, a position or a device id, and a canary test
asserts that none of them can be added by accident (below). `principal_subject` is the
same opaque string `iam.grants` and the audit trail already key an actor by.

### APIs (ADR 0031) and limits (ADR 0033)

```text
GET  /api/v1/operations/tenants/{tenantId}/staff/me/sessions
       -> { keycloak: AVAILABLE | UNAVAILABLE,
            items: [{ id, deviceLabel: { browser, os, form } | null, signedInAt, lastSeenAt,
                      current: boolean, state: ACTIVE | ENDED, endReason | null }] }
POST /api/v1/operations/tenants/{tenantId}/staff/me/sessions/terminations
       { scope: "ALL", reasonCode: LOST_DEVICE | SHARED_DEVICE | SUSPECTED_COMPROMISE | JUST_IN_CASE }
       Idempotency-Key required; 204 on success
POST /api/v1/operations/tenants/{tenantId}/staff/me/sessions/{sessionId}/terminations
       built only if the probe allows; same body and rules, scope "ONE"
```

All three are `@StaffSelfAuthorized(Capability.STAFF_SELF_MANAGE)`: the handler resolves
the account from the token subject and never from a supplied id, and a session id that is
not the caller's answers as an unknown one. Request bodies box optional fields (Jackson 3
refuses a missing primitive). The list is rate limited at 30 a minute per principal and
the terminations at 5 an hour; an exceeded limit answers `RATE_LIMIT_EXCEEDED` with the
retry interval. The terminations route is a non-GET with an idempotency key, so a double
click ends the sessions once and replays the same answer. If Keycloak is unreachable the
route refuses with a Problem Details body and ends nothing, and the console says the
sessions were not ended, because a falsely reassuring "done" is the failure that matters.
The route is in the `operations` OpenAPI surface group (ADR 0057).

### The Keycloak calls, and the credentials they need

`list` calls `GET /admin/realms/{realm}/users/{id}/offline-sessions/{clientUuid}` and,
because a person may also hold an online session, `GET /users/{id}/sessions`, and merges
them by id; `end` calls `DELETE /sessions/{id}?isOffline=true` (or the online form). All
are expected to need only `manage-users` or `view-users`, which the provisioning credential
holds, and no new realm role; that is an expectation the probe tests (first open input), not
a fact the repository establishes. The client UUID is the deployment property named in the open inputs. Keycloak's
address field in a session is never copied out of the adapter. A call is made on demand
when the screen opens and never in a loop.

### Policy key (ADR 0030)

`iam.staff_sign_in_retention_days`, Integer, default 180, range 30 to 730, settable at
`PLATFORM` and `TENANT`, owned by `iam`, declared in an `IamConfigurationKeys` registry
with the tenancy drift test. A sweeper deletes rows older than the resolved value in
report-only mode first and logs only a count.

### Audit (ADR 0027), events (ADR 0032), personal data (ADR 0029)

- Facts, `SECURITY` class, written through `StaffSecurityFact.byStaffMember` in the
  transaction that marks the rows: `iam.staff_session.terminated_all` and, if built,
  `iam.staff_session.terminated`. Target type `iam.staff_account`; `because` is the
  reason code; `before` and `after` carry the count of active sessions and nothing
  about a device. The activity log needs a sentence per code in ru, uz-Latn and en
  (`activity-log-action-codes-coverage.spec.ts` fails the build without them).
- ADR 0098's `PasswordResetService` calls `StaffSessionLedger` only after
  `logoutEverywhere` has succeeded. When `sessionsEnded` is true it marks every row of
  that subject, in every tenant (the reset path is unauthenticated and has no tenant),
  `PASSWORD_RESET`, so one reason vocabulary covers both acts. When `logoutEverywhere`
  fails it does not call the ledger: the rows and ADR 0098's existing
  `iam.password_reset.sessions_not_ended` fact are left exactly as they are, and the
  answer stays `sessionsEnded: false`. No new fact is written for the reset; the
  `iam.password_reset.accepted` fact already carries `sessionsEnded`. The call runs
  outside the reset's transaction, as `logoutEverywhere` does, and a failure of the
  marking itself is logged and counted and changes neither the answer nor the fact: the
  rows then read as ended because Keycloak no longer lists the session, and the sweeper
  closes them by age.
- No Kafka event: nothing consumes one, and ADR 0139 made the same choice for the same
  reason. A future event would carry no label and no subject.
- The table holds no `PERSONAL` field. `StaffSessionLedger` and the parser never log the
  header or the address, and a test fails if either reaches a logger or a column.
- Metrics: sign-ins recorded and failed to record, sessions terminated by scope, Keycloak
  list failures; labels are outcome and nothing identifying.

### Console

«Мой профиль» replaces the `staff.myProfile.security.notBuilt` sentence with a «Сеансы»
card: the list, «Это устройство» on the current row, «Неизвестное устройство» where there
is no row, a line saying whose sessions these are («сеансы вашей учётной записи, общие
для всех компаний, где вы работаете»), and «Выйти на всех устройствах». The action opens
a confirmation that follows the open input's wording, asks for a reason from the closed
list, and after success clears the tokens (`StaffTokenStore`) and routes to the sign-in
page. When the list is unavailable the card says so and still offers the action. The
password and second-factor rows of the same section are left to ADR 0098 and ADR 0148,
and the card reserves no placeholder for the manager's view, which is not built. Every
string ships in ru, uz-Latn and en, and the card is lazy-loaded like the rest of the page.

### Testing

Each assertion says what would still be true if the code were broken.

- **Against a real Keycloak container**, the probe script of the first open input,
  re-run when the pinned image changes, covering the three behaviours and `logoutEverywhere`
  across an offline and an online session.
- **Adapter tests** against the recorded fake (ADR 0007): listing, merge by id, a
  timeout, a malformed body, a 404 on a session already gone.
- **Sign-in is never failed by recording**: a ledger that throws leaves the response a
  201 with tokens and a counter incremented.
- **Fan-out and isolation**: a token naming two organizations writes two rows, each
  readable only through its own tenant's path; another member's session id answers as
  unknown; a member of tenant B cannot read tenant A's rows.
- **Canary**: sign in with a distinctive `User-Agent` and a distinctive address, then scan
  every text column of the table, the audit fact, the application log and the response
  for either string; none appears.
- **Parser vectors**: a table of real header strings to the three labels, with `OTHER`
  for everything unrecognised and no exception on a hostile one (oversized, control
  characters).
- **Ending**: «Выйти везде» ends an offline and an online session on a real account (the
  refresh answers 400 afterwards), is idempotent under a repeated key, writes exactly one
  fact in the same transaction as the row update, and rolls both back together; the rate
  limit refuses the sixth in an hour; Keycloak down ends nothing and says so.
- **Reset path**: a completed password reset whose `logoutEverywhere` succeeds marks
  every row of that subject, in two tenants, `PASSWORD_RESET`; one whose
  `logoutEverywhere` fails (the recorded fake refuses the call) leaves every row open,
  still answers `sessionsEnded: false`, still records `sessions_not_ended`, and never
  calls the ledger; a ledger that throws after a successful logout changes neither the
  answer nor the fact.
- **Front end**: the card's states (loading, list, unavailable, empty, error), the
  confirmation naming a count, and tokens cleared after success.

## Rollout and rollback

Write the probe first and run it; its result goes in this record's follow-up note and
decides which of the fallbacks apply. Then ship the migration, the ledger and the
recording on sign-in with no screen, so history accumulates before anyone reads it. Then
the list and «Выйти везде» behind no flag, since the first is self-only and the second
changes nothing until pressed. The single-session end follows only if the probe allows it.
Rollback is removing the screen and the recording call: the table is additive and inert,
`logoutEverywhere` is unchanged, and ADR 0098's reset behaves as it did.

## Implementation checklist

- [ ] Owner accepts the record or answers the open inputs.
- [ ] Probe script under `infra/keycloak/spikes/` and its recorded result.
- [ ] `horecaos.keycloak.staff-login-client-uuid` written by
      `create-staff-login-client.sh`, read at startup, documented in the production runbook.
- [ ] Migration for `iam.staff_sign_ins`; `GRANT`s; the closed-set checks.
- [ ] `StaffSessionLedger`, the device-label parser and its vectors; `StaffAuthService`
      calls it after sign-in and refresh; `PasswordResetService` calls it after, and only
      after, `logoutEverywhere` succeeds.
- [ ] `StaffSessions` port, `KeycloakStaffSessions` adapter and its fake.
- [ ] The three routes; rate limits; idempotency; OpenAPI baselines for all five
      documents and the generated clients.
- [ ] `IamConfigurationKeys` with the retention key; the report-only sweeper.
- [ ] Audit facts and their sentences in three languages.
- [ ] «Сеансы» card in «Мой профиль», the confirmation, the sign-out path, strings.
- [ ] `staff-and-access.md` §11.6 and gap-map row `X.5` updated when this lands.

## Exit criteria

A staff member signs in on two devices, opens «Мой профиль», sees both sessions with their
browser, system and form and the times, with «Это устройство» on the right one; presses
«Выйти на всех устройствах», chooses a reason, confirms, and is returned to the sign-in
page; the refresh token of the other device then answers 400; the history shows both
sessions ended with the reason; the activity log shows one fact for the act with a count
and no device; and no row, fact or log line in the system contains an address or a raw
`User-Agent`. A password reset that ends the sessions makes the history say
`PASSWORD_RESET` for every tenant of that person; one whose session end fails leaves the
history as it was and keeps ADR 0098's `sessionsEnded: false` answer. With Keycloak
unreachable the card says so, offers the action, and ends nothing.

## References

- ADR 0003, ADR 0007 (recorded fakes), ADR 0009 (a person in two tenants), ADR 0025,
  ADR 0027, ADR 0029, ADR 0030, ADR 0031, ADR 0033, ADR 0057 (the `operations` surface
  group), ADR 0062 (staff sign-in inside the platform), ADR 0081 (support sessions), ADR
  0098 (`logoutEverywhere`, offline tokens, the rejected one-by-one row, the reset's
  `sessionsEnded`), ADR 0139 (Keycloak boundary, self strategy), ADR 0146, ADR 0148
  (MFA, session-lifetime input)
- `platform/docs/operations-gap-map.md` row `X.5` of §9 (and `0.2c`)
- `platform/docs/operations-spec/staff-and-access.md` §3 (tab 4), §10, §11.6, §11.7, §11.9
- `StaffSessionController`, `StaffAuthService`, `StaffDirectGrantClient` (`SCOPE`,
  `revoke`), `KeycloakStaffAccounts#logoutEverywhere`, `PasswordResetService`,
  `StaffSecurityFact`, `JwtCurrentActor`, `StaffSelfAuthorized`; `V0453`
- `infra/keycloak/README.md`, `infra/keycloak/realm/horecaos-realm.json`,
  `infra/keycloak/spikes/mfa-lockout-probe.py`, `infra/keycloak/create-staff-login-client.sh`
- `frontend/operations/src/app/features/staff/my-profile-page.html`,
  `core/auth/staff-token-store.ts`
