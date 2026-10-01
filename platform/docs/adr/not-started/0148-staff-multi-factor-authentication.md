# ADR 0148: Staff multi-factor authentication

- Decision status: Proposed
- Implementation status: Not started — a staff member signs in with a password and
  nothing else, and there is no endpoint, table, policy or screen for a second
  factor. What exists is the shape of the problem. `StaffDirectGrantClient.signIn`
  sends `grant_type=password`, a username and a password to Keycloak 26.7's token
  endpoint as the `horecaos-staff-login` confidential client (ADR 0062), and
  nothing else; the request has no `totp` field. `StaffAuthService` maps a
  Keycloak "not fully set up" answer to `ACCOUNT_ACTION_REQUIRED` ("Contact a
  platform administrator") and every other refusal to one uniform "Invalid
  credentials", so an account given the `CONFIGURE_TOTP` required action today
  would be locked out with no screen to satisfy it. `horecaos-realm.json` defines
  no authentication flow and no OTP policy, so the realm runs Keycloak's defaults
  (a direct-grant flow with a conditional OTP step that engages only for a user
  who already has an OTP credential), with brute-force protection on
  (`failureFactor` 8, `maxFailureWaitSeconds` 900) and a password policy of 12
  characters. `KeycloakStaffAccounts` already wraps the admin API for `find`,
  `create`, `setPassword`, `findSubjectIdByLogin` and `logoutEverywhere`. The
  segmented code field `q-otp-input` is built and tested in
  `frontend/operations/src/app/shared/ui` with no consumer (row `X.38`), and the
  control plane does not have it. `docs/operations-spec/staff-and-access.md`
  §11.9 records that "whether a person has a second factor is a Keycloak fact with
  no projection".
- Date proposed: 2026-10-01
- Date decided: —
- Deciders: proposed by Claude (wave batch 17); Ayubkhon Abbosov (platform owner)
  decides
- Depends on: ADR 0003, ADR 0025, ADR 0027, ADR 0028, ADR 0030, ADR 0033, ADR 0050,
  ADR 0062, ADR 0097, ADR 0098, ADR 0116, ADR 0139
- Supersedes / Superseded by: — (stays inside ADR 0062's direct-grant sign-in and
  ADR 0139's boundary that Keycloak is the authority for "password, MFA,
  sessions"; the one place it leans on a reading of ADR 0139 is flagged in the
  first open input below)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written.
  - **Whether the pinned Keycloak will register an OTP credential from a secret the
    platform supplies** (engineering). Community reports say the admin API accepts
    a `credentials` element of type `totp` with `secretData`, and also say an
    ordinary user cannot create one through the account API; neither has been run
    against 26.7 here. This is the first thing to prove, in a spike a day long.
    Proposed default: build Decision 3 as written if it works; if it does not,
    enrol through Keycloak's own `CONFIGURE_TOTP` required-action page (the
    alternative below), which needs the owner to accept one narrow exception to
    ADR 0062's "the browser never sees Keycloak".
  - **How to read ADR 0139's "HorecaOS never sees the value"** (platform owner).
    Decision 3 has the platform generate the secret and hold it in memory for the
    length of one enrolment, then hand it to Keycloak and forget it. ADR 0062 made
    the same trade for the password ("the app touches the password — acceptable
    here because … Keycloak remains the only verifier"). Proposed default: read
    ADR 0139 as "never stores or verifies", which is how ADR 0062 reads itself.
  - **Who must have a second factor** (platform owner, security). Proposed default:
    every account holding a platform-scope grant, always; tenant accounts by a
    tenant setting that is off by default, with `OFF`, `SENSITIVE_ROLES` (owner,
    administrator, finance, brand manager) and `ALL_STAFF` as its values. Device
    principals (ADR 0079) and customers (ADR 0051) are outside this record.
  - **The realm's brute-force threshold** (operations). A wrong password will now
    cost Keycloak two failed attempts (Decision 2). Proposed default: raise
    `failureFactor` from 8 to 10, so five wrong passwords still lock the account.
  - **Whether the pinned Keycloak's recovery-code authenticator is supported**
    (engineering). Proposed default: none at launch; recovery is a second
    authenticator or an administrator reset (Decision 5).
  - **Session lifetime for accounts with a second factor** (security). The staff
    refresh token is minted with `offline_access`, and `StaffDirectGrantClient`
    records that Keycloak then reports `refresh_expires_in: 0`; a second factor at
    sign-in is only as strong as the lifetime of the session it opens. Proposed
    default: unchanged by this record, and recorded here so the question is asked.
  - **Where `q-otp-input` lives for two consoles** (frontend). Proposed default:
    copy it to the control plane now and promote it when ADR 0101's shared library
    reaches both apps.

**To accept as written:** say "accept 0148". Every open input above is then
closed on its proposed default.

## Context

Gap-map row `X.38` is `PARTIAL`: "the component (6-cell code entry, paste-fill,
auto-advance) is built and tested but has no consumer and no backend: staff MFA has
no endpoint anywhere". Its own source comment says why the component stopped
there — "whether a staff factor is enrolled in Keycloak or modelled on the
platform is the staff-identity ADR's call" — and the staff-identity ADR (0139) has
since drawn the line: "Password, MFA, sessions → Keycloak. Keycloak's own flow;
HorecaOS never sees the value." So the decision this record makes is not whether
Keycloak owns the factor; it is *how a first-party sign-in page and a first-party
enrolment screen sit in front of a Keycloak-owned factor* without breaking what
ADR 0062 built.

**Why that is not obvious.** ADR 0062 deliberately replaced the redirect with a
direct grant: the backend takes the username and password to Keycloak and the
browser never talks to it. Three consequences of that choice bear on MFA, and one
is a Keycloak fact the platform cannot fix.

- **The direct grant can carry a code, but cannot ask for one.** Keycloak's
  direct-grant flow reads an extra `totp` form parameter for a user who has an OTP
  credential. When the user has one and the parameter is missing, the answer is
  `invalid_grant`, indistinguishable from a wrong password. A sign-in page cannot
  tell "enter your code" from "wrong password", and — because ADR 0062 forbids an
  enumeration oracle — it must not be told by an answer that differs for existing
  and non-existing accounts.
- **A required action cannot be satisfied.** A user with `CONFIGURE_TOTP` pending
  gets "Account is not fully set up" from the direct grant, which the platform
  already maps to `ACCOUNT_ACTION_REQUIRED`. There is no screen that completes the
  action, because the action's page lives on the Keycloak origin the browser never
  visits.
- **ADR 0062 foreclosed WebAuthn for staff** ("a future need there means a
  superseding record, not a quiet re-enable"). TOTP is the second factor that fits
  the direct grant; this record does not reopen that.

**Who needs it, and how much.** A platform-scope account can enter any tenant
(ADR 0081) and change platform policy; a tenant owner or finance user can reveal
customer data, export it, and change who may. A cashier on a shared terminal is a
different case: a phone-based code on a till that changes hands every shift is a
cost without a matching risk, and the operations apps have a separate PIN story
for terminals (staff-and-access §8). ADR 0098's password reset and ADR 0097's
mail relay already give the platform an email channel and a way to end a session
(`logoutEverywhere`); what they must not become is a way around a second factor.

**What ADR 0098 teaches about recovery.** Its reset flow lets whoever holds a
mailbox set a new password. That is right for a password and fatal for a factor: if
a reset link could also remove the second factor, mailbox plus password would be
one factor and the second would decorate the sign-in page. Recovery of a *factor*
is therefore never an email link.

## Decision

**Keep Keycloak as the only long-term holder and the only verifier of a staff
member's TOTP credential; put a first-party second step on the sign-in page, a
first-party enrolment screen behind it, and a recovery path that is an audited
administrator action and never a link.**

1. **TOTP only, one policy, held in Keycloak.** Authenticator apps with the
   standard parameters (6 digits, 30-second period, HMAC-SHA-1, look-ahead 1). The
   realm file declares the OTP policy explicitly instead of inheriting defaults. An
   account may hold up to two authenticators, so a second device is the recovery
   path that needs nobody else. The platform stores no secret, no recovery code and
   no copy of the factor.

2. **The code is a second step, signalled without an oracle.** `POST
   …/auth/sessions` gains an optional `otp` field forwarded to Keycloak as `totp`.
   When Keycloak refuses and no code was sent, the platform asks a second,
   password-only Keycloak client whether the password alone is right. That client's
   direct-grant flow has no OTP step, its tokens are never released (the adapter
   returns a `PasswordVerified` value that has no token field, so a bug cannot hand
   one out), and it is called only on the failure path. If the password is right
   the answer is `MFA_REQUIRED` and the page shows the code step with
   `q-otp-input`; if it is wrong the answer is the same uniform "Invalid
   credentials" as today. If a code was sent and refused, a verified password makes
   the answer `MFA_CODE_INVALID`. Both distinguishing answers are reachable only by
   someone who already knows the password. The happy path for an account with no
   factor is one Keycloak call, unchanged. Cost: a wrong password is two failed
   attempts in Keycloak's counter (first open input).

3. **Enrolment is in the product and re-proves the password.** The enrolment screen
   asks for the current password again, shows the secret as a QR code
   (`q-qr-code` already exists) and as text, and takes the first code in
   `q-otp-input`. The platform generates 20 random bytes, returns them inside a
   sealed, 10-minute, single-purpose token (so no table holds a pending secret),
   and on confirmation registers the credential with Keycloak through the admin
   API, then proves it by performing a direct grant with password and code; if that
   fails the new credential is deleted and the user is told. Nothing in the
   platform verifies a login code itself.

4. **Where the requirement is enforced.** After a successful password-only sign-in
   (an account with no factor), the platform evaluates the requirement for the
   subject: platform-scope grants always, tenant grants by the tenant's setting
   (ADR 0030 configuration key, `OFF` by default). If a factor is required and
   absent, the issued refresh token is revoked and the answer is
   `MFA_ENROLMENT_REQUIRED` with a short-lived enrolment ticket valid only for the
   enrolment endpoints. Enrolment is also offered at the end of invitation
   acceptance (ADR 0097, ADR 0116), when the account is new, which narrows the window in
   which a stolen first password can claim the factor. The same enforcement ships
   in three phases behind one setting: `OFF`, `PROMPT` (offer, do not require),
   `REQUIRED`.

5. **Recovery is a second authenticator or an administrator, never a link.**
   - A second device is added by someone already signed in with a valid code.
   - A lost device is reset by an administrator: a new capability
     `iam.staff.mfa.reset` removes the person's OTP credentials, ends their
     sessions, writes an ADR 0027 fact with the reason, and emails the person (ADR
     0097). For a platform-scope account the reset needs a second signature (ADR
     0027, ADR 0050 fail-closed). A tenant owner's own reset is performed by
     platform support under ADR 0081's time-boxed, reasoned session.
   - The password reset flow (ADR 0098) is left exactly as it is and cannot remove
     a factor.
   - A host-side break-glass script beside `infra/keycloak/create-platform-admin.sh`
     removes a factor when no administrator can: for the case where the last
     platform administrator is the one who lost a phone.

6. **State is read from Keycloak, not copied.** A staff list or profile asks
   Keycloak for the person's credential list, cached for sixty seconds (ADR 0033),
   and shows "enrolled / not enrolled" and each device's label and date: the
   «Способ входа» field on the person card's Безопасность tab (§3, Tab 4) and a
   column on the staff list. No projection table. This answers
   `staff-and-access.md` §11.9.

7. **Every enrol, add, remove and reset is audited and notified.** The person is
   emailed for each. An audit fact never contains a secret, a code or an
   authenticator label that identifies a person.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| The platform stores TOTP secrets and verifies codes itself | A second authenticator and a second secret store beside Keycloak, against ADR 0003 and the repository rule not to run a second credential issuer; the platform would own recovery codes, replay windows and clock skew | Keycloak cannot be made to accept programmatic enrolment *and* its own page is unacceptable |
| Enrol on Keycloak's own `CONFIGURE_TOTP` page (a redirect, then back) | Honours ADR 0139's wording to the letter and needs no secret handling, but reintroduces the redirect and callback the owner removed in ADR 0062 as "a jarring seam" and "broken in practice", requires a public client again, and needs a themed realm in three languages | The spike in the first open input fails |
| A custom Keycloak authenticator or REST extension (distinguishable "OTP required", an enrolment endpoint) | The cleanest protocol, with no second-client probe and no double failure count. Also a Java extension one person must build, version and re-test on every Keycloak upgrade | The probe in Decision 2 proves fragile in operation |
| Always show the code field on sign-in | No probe and no Keycloak change; every cashier sees a field that is not theirs, and a missing code is still an unexplained "Invalid credentials" for the person who needs it | Never as the only affordance |
| Reveal the code field after the first failure and remember it per browser | Simple and oracle-free, but the first sign-in on any new browser fails visibly for every enrolled user and counts against the lockout | The probe's second client is refused |
| SMS code as the second factor | Weaker (SIM swap, no receipt on some subscribers), costs money per sign-in, and makes console sign-in depend on the SMS gateway's availability (ADR 0146) | Staff without a smartphone must be covered |
| WebAuthn or passkeys | The strongest option and phishing-resistant, but foreclosed by ADR 0062 while the direct grant stands | The direct grant is replaced (a superseding record) |
| Email code | Mailbox plus password is one factor in practice; ADR 0098 already treats the mailbox as a recovery channel | Never |
| A "trust this device for 30 days" cookie | Defeats the purpose on shared terminals and adds a server-side device list to keep | Platform admins only, if complaints justify it |
| Require a factor for every staff member on day one | Cashiers and kitchen leads on shared terminals; one operator to field every lost-phone call | A tenant policy asks for it, which the setting already allows |

## Consequences

### Positive

- Row `X.38` gets its consumer and its backend, and the most powerful accounts in
  the product stop depending on a password alone.
- Keycloak keeps sole ownership of the factor; the platform adds no secret store.
- Recovery is audited and notified, and cannot be reached with a mailbox.
- The tenant chooses how far to go, and the platform's own accounts do not wait on
  that choice.

### Negative

- Enrolment depends on a Keycloak behaviour not yet proven against 26.7, and the
  fallback re-opens a decision the owner made on 2026-09-02.
- The probe client is a second route to "password verified" and a place a bug would
  be a bypass. It is contained by what the adapter can return, not by vigilance,
  and it needs its own test that no token escapes.
- A wrong password costs two failures in Keycloak's counter; the realm threshold
  moves, and the realm file only applies on first import, so a live realm needs the
  hardening runbook's step.
- The first enrolment of a fresh account is still protected by the password alone.
  Someone holding a stolen password for an account that has not yet enrolled can
  enrol their own device first. Enrolment at invitation acceptance, the email on
  every enrolment, and the administrator reset bound the damage; they do not
  remove it.
- One operator is the recovery path for a locked platform administrator, "alone,
  sometimes asleep" (ADR 0034). The break-glass script exists for that night.
- TOTP needs a correct device clock, and a person who changes phones without
  moving their authenticator is locked out until a second device or an
  administrator intervenes.
- An already-issued refresh token is not retroactively covered. Switching the
  requirement on must end the affected accounts' sessions (`logoutEverywhere`) or
  it protects nothing for existing sessions.

### Accepted trade-offs

- No recovery codes at launch: the second authenticator and the administrator
  carry recovery. A lost single device costs a support action.
- No WebAuthn, by the terms of ADR 0062.
- Cashiers on shared terminals are outside the default requirement.

## Specification

### Endpoints (ADR 0031; both staff surfaces, as sign-in)

```text
POST /api/v1/{control-plane|operations}/auth/sessions
     body gains optional otp (6 digits)
     answers: 200 session | 401 UNAUTHENTICATED (uniform) | 401 MFA_REQUIRED | 401 MFA_CODE_INVALID
              | 403 MFA_ENROLMENT_REQUIRED { enrolmentTicket, expiresAt } | 403 ACCOUNT_ACTION_REQUIRED
POST …/auth/mfa/enrolments                 bearer or enrolmentTicket, password -> { sealedSecret, otpauthUri, secret, expiresAt }
POST …/auth/mfa/enrolments/confirm         { sealedSecret, code, password, label } -> 204 (+ session when begun from a ticket)
DELETE …/auth/mfa/authenticators/{id}      password + a valid code from another authenticator
GET  …/staff/{personId}/mfa                iam.staff.mfa.read -> { enrolled, authenticators[{id,label,createdAt}], requirement }
POST …/staff/{personId}/mfa/resets         iam.staff.mfa.reset, If-Match, Idempotency-Key, reason
```

Sign-in and the enrolment endpoints are unauthenticated or ticket-authenticated by
design, in the same `permitAll` category as the six ADR 0062 paths, each added to
`SecurityConfiguration` with its reason. All are rate limited per ADR 0033 (the
sign-in budget of five a minute per address and username is unchanged and now also
counts code attempts). The reset is a mutating endpoint and declares its capability
at the scope of the person's tenant, or platform scope for a platform account.

### Configuration

```text
horecaos.iam.mfa.enforcement            OFF | PROMPT | REQUIRED     platform accounts; deploy setting
iam.staff_mfa_requirement               OFF | SENSITIVE_ROLES | ALL_STAFF   tenant ConfigurationKey, ADR 0030
```

`PlatformRole` gains a code-owned flag for the sensitive roles. Neither setting
names a person.

### Keycloak

```text
horecaos-staff-login            unchanged: direct grant, default flow, conditional OTP
horecaos-staff-password-check   new confidential client; direct grant only; a flow with
                                username and password validation and no OTP; no scopes,
                                no offline_access; a one-second access token
realm: OTP policy declared (totp, 6, 30, HmacSHA1, look-ahead 1); failureFactor 10
```

### Observability and audit

Counters `horecaos.auth.staff.mfa{step,outcome}` with bounded labels
(`challenge`, `confirm`, `reset`; `ok`, `invalid`, `required`). Audit facts
`iam.staff.mfa.enrolled`, `.authenticator_added`, `.authenticator_removed`,
`.reset` through `ChangeDocuments`, carrying the subject's reference, the actor and
the reason — never a secret, a code or a label.

### Testing

- The direct-grant client sends `totp` when given a code; a missing code on an
  enrolled account yields `MFA_REQUIRED` only after the password-only client
  confirms the password, and the uniform failure otherwise (against the live dev
  realm and against the fake).
- The password-only adapter's return type has no token; a test asserts it by
  reflection so a later edit cannot add one quietly.
- Enrolment: a good code registers and a bad code leaves no credential behind; the
  sealed token expires, cannot be replayed, and is useless on another account.
- Enforcement: platform account with no factor gets `MFA_ENROLMENT_REQUIRED` and its
  token is revoked; a tenant account under `OFF` signs in unchanged.
- Recovery: a reset removes factors, ends sessions, audits, emails; a platform-scope
  reset waits for the second signature; the password reset cannot remove a factor.
- No code, secret or sealed token reaches a log appender.

## Rollout and rollback

Spike first (first open input). Then realm changes in a staging realm: the OTP
policy, the password-check client, the threshold. Then the backend and both
consoles with enforcement `OFF`: accounts can enrol and enrolled accounts are asked
for a code, and nothing else changes. Then `PROMPT` for platform accounts, then
`REQUIRED` with a `logoutEverywhere` for the affected subjects. Tenant setting last,
one tenant at a time. Rollback is the setting: `OFF` stops requiring; an enrolled
account still needs its code, because Keycloak enforces what is configured, and the
reset action is how that is undone for a person.

## Implementation checklist

- [ ] The Keycloak spike; its result recorded on this ADR.
- [ ] Realm file: OTP policy, `horecaos-staff-password-check` client and flow,
      `failureFactor`; the live-realm runbook step.
- [ ] `StaffDirectGrantClient` sends `totp`; the password-only adapter; the answers
      above in `StaffAuthService`; `ErrorCode` entries.
- [ ] `KeycloakStaffAccounts`: list, add and remove OTP credentials; required-action
      handling.
- [ ] Enrolment endpoints and the sealed token; the confirm-by-grant proof and its
      compensation.
- [ ] Requirement evaluation after password sign-in; the deploy setting and the
      tenant `ConfigurationKey`; role flag.
- [ ] `iam.staff.mfa.read` and `iam.staff.mfa.reset` capabilities and bundles; the
      approval action for platform-scope resets; audit facts; emails in uz / ru / en.
- [ ] Break-glass script and its runbook entry.
- [ ] Sign-in second step, enrolment screen and authenticator list in both consoles
      on `q-otp-input`, `q-qr-code`; `q-otp-input` available to the control plane.
- [ ] Staff list MFA column (spec §11.9) from the cached Keycloak read.
- [ ] Update `staff-and-access.md` §11.9 and ADR 0062/0139 status notes.
- [ ] Tests listed under Testing, each seen failing first.

## Exit criteria

A platform administrator who has never enrolled signs in, is taken to enrolment,
scans the QR code, enters the first code and arrives signed in; signs out and back
in with a password and a code; and, with the phone lost, is reset by a second
administrator and re-enrols. A wrong password and an unknown username still answer
identically. No authenticator secret, code or sealed token appears in any log,
and the realm holds the only copy of the factor.

## References

- ADR 0003, ADR 0025, ADR 0027, ADR 0028, ADR 0030, ADR 0033, ADR 0034, ADR 0050,
  ADR 0062 (direct grant, foreclosed WebAuthn), ADR 0079, ADR 0081, ADR 0097,
  ADR 0098, ADR 0116, ADR 0139 (the Keycloak boundary), ADR 0146
- `platform/docs/operations-gap-map.md` row `X.38`; `platform/docs/operations-spec/staff-and-access.md` §11.9
- `StaffDirectGrantClient`, `StaffAuthService`, `StaffSessionController`,
  `KeycloakStaffAccounts`, `SecurityConfiguration`; `platform/infra/keycloak/realm/horecaos-realm.json`,
  `platform/infra/keycloak/README.md`, `create-platform-admin.sh`
- `frontend/operations/src/app/shared/ui/otp-input.ts`, `…/q-qr-code`, both consoles'
  `sign-in-page.ts`
- Keycloak community discussions of the direct grant and a missing `totp`
  (forum.keycloak.org, "Direct Grant prompt for OTP if configured" and "Conditional
  OTP for Direct Grant") and of creating an OTP credential through the admin API,
  as read on 2026-10-01; neither is verified against 26.7 here
