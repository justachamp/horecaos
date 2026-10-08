# ADR 0148: Staff multi-factor authentication

- Decision status: Accepted
- Implementation status: Partial — built and tested over HTTP (operations batch 19, 2026-10-07):
  the password-only probe client and its realm flow, asked first on every sign-in
  (`StaffPasswordCheckClient`, `infra/keycloak/create-staff-password-check-client.sh`,
  OTP policy declared in the realm file); the second step on both staff surfaces
  (`StaffAuthService`: password right and no code asked for `MFA_REQUIRED`/`MFA_CODE_REQUIRED`,
  a wrong code and a wrong password still answer identically); the platform-owned code budget on
  the ADR 0033 limiter (burst 5, 5 an hour, per account by a hash of the subject, charged after
  the password is proven and before a code reaches Keycloak) with its metric and alert; enrolment
  with the AES-256-GCM sealed secret and ticket and the confirm-by-grant proof
  (`StaffMfaService`, `iam/web/StaffMfaController`); listing, adding and removing authenticators
  in Keycloak (`KeycloakStaffAccounts`); requirement evaluation (`MfaPolicy`: platform grants
  always, tenant accounts by `iam.staff_mfa_requirement` `OFF`/`SENSITIVE_ROLES`/`ALL_STAFF`);
  `iam.staff.mfa.read` and `iam.staff.mfa.reset`, the audited administrator reset (platform
  scope behind a second signature, `IAM_STAFF_MFA_RESET`, V0500), the three emails in uz / ru /
  en, the break-glass script and its runbook; the second step, the enrolment screen and ticket
  page, the authenticator card in Мой профиль, the administrator panel, the staff list column and
  the tenant requirement card in the operations console, and the second step and enrolment in the
  control plane, on `q-otp-input` and a QR encoder written for the console's byte budget. Not
  built: the offer to enrol at invitation acceptance; ending a tenant's existing sessions when its
  requirement is switched on (they hold until they expire); a control-plane screen that starts a
  platform-scope reset or lists platform accounts (the API takes the member id, the support roles
  hold no `STAFF_PROFILE_READ` to find one from the operations list); enforcing that a code came
  from a *different* authenticator than the one being removed (Keycloak does not expose it; the
  rule enforced is a valid code and at least one authenticator left); the two secrets this needs
  per environment (the password-check client's and the enrolment sealing key) are references that
  have to be seeded in OpenBao and the live realm reconciled by the runbook step, neither done
  here. The Keycloak spike's result is recorded in the implementation note below.
- Date proposed: 2026-10-01
- Date decided: 2026-10-07
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
    a `credentials` element carrying `secretData` for an OTP credential, and also
    say an ordinary user cannot create one through the account API. A throwaway
    26.7.0 container, run on 2026-10-03 with
    `infra/keycloak/spikes/mfa-lockout-probe.py`, showed the following. The admin
    API accepted an `otp` credential (`subType` `totp`, `secretData.value`) when
    the user was created and on `PUT /users/{id}` for a user that already existed,
    and a sign-in with the password and a current code then succeeded. The direct
    grant answered a missing code and a wrong code alike with HTTP 400
    `invalid_grant` "Invalid user credentials", and refused a code used a second
    time inside its 30-second step. A wrong code is counted by the brute-force
    detector, and a successful password-only grant by another client clears that
    count (Context, "What Keycloak's failure counter does"). Not yet run: two OTP
    credentials on one account, deleting one by id, and the account-API path. Those
    are still the first thing to prove, in a spike a day long.
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
  - **How many code attempts an account gets** (security). Keycloak's own lockout
    cannot be the control for the code step, because the password-only probe that
    Decision 2 needs clears its failure count (Context). The platform therefore
    owns this limit. Proposed default: a burst of 5 code attempts, then 5 more an
    hour, per account (not per address), on the ADR 0033 limiter that already guards
    sign-in. That is at most about 120 attempts a day; with look-ahead 1 three of
    the million codes are valid at any moment, so a guesser who already holds the
    password has roughly a 1-in-2,800 chance a day, and the owner of the account
    can lock themselves out for an hour at worst.
  - **The realm's brute-force settings** (operations). Proposed default: unchanged
    (`failureFactor` 8, quick-login check 1,000 ms / 60 s). Decision 2 asks the
    password-only client first so that a wrong password stays one failure, as it is
    today; an earlier draft that asked it after the refusal made it two failures
    milliseconds apart and disabled the account for a minute on the first typo.
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

**Decision record, 2026-10-07.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "lets finish all" over every record still Proposed on this date. Every open input above is closed on the default this record proposes for it; an input that names a person other than the owner, or an external fact (a licence term, a provider capability, a tax treatment, an account that does not exist yet), stays with that owner as written and implementation proceeds without it, marking what waits. Implementation of what this record decides and has not yet built starts in operations batch 19 and 20 (2026-10-07).

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

**What Keycloak's failure counter does, which the sign-in step cannot ignore.** An
earlier draft of this record asked a password-only client "is the password right?"
after Keycloak refused a sign-in, and counted on Keycloak's lockout to stop
code-guessing. A throwaway Keycloak 26.7.0 carrying this platform's brute-force
settings (`failureFactor` 8, quick-login check 1,000 ms / 60 s) shows both halves
were wrong. `infra/keycloak/spikes/mfa-lockout-probe.py` reproduces every line
below and exits non-zero if a later Keycloak image behaves differently.

- A wrong one-time code is counted like a wrong password. With no probe in the
  picture, the eighth wrong code (right password) disables the account.
- A successful password-only grant clears the count, whichever client made it.
  Fifteen wrong codes, each followed by one successful grant of a password-only
  client, leave `numFailures` at 0, the account enabled, and the right code still
  signing in. Calling that client only when no code was sent changes nothing: one
  code-less request between two guesses clears the count in the same way (ten
  rounds, no failure left).
- Two failures less than a second apart disable the account for 60 seconds. A wrong
  password followed at once by a failing password-only call is exactly that pair:
  `numFailures` 2, `disabled` true, and the right password and code refused until it
  lapses. With the password-only client asked first, the same wrong password is one
  failure and the right password and code sign in at once.

So a password-only probe costs something that no wording of "Invalid credentials"
hides. Called after a refusal, it turns every typo into a minute-long lockout. And
its success gives anyone who holds the password a way to keep Keycloak's counter at
zero while guessing a six-digit code, which is the dangerous half: TOTP is the whole
second factor, and with the counter silent only the per-address sign-in budget
(which a rotating address defeats) stands between a phished password and a
distributed guess. The lockout for the code step therefore cannot be Keycloak's.
Decision 2 asks the probe first and keeps the code budget in the platform.

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
first-party enrolment screen behind it, a limit on code attempts that the platform
owns because Keycloak's counter cannot be relied on for it, and a recovery path
that is an audited administrator action and never a link.**

1. **TOTP only, one policy, held in Keycloak.** Authenticator apps with the
   standard parameters (6 digits, 30-second period, HMAC-SHA-1, look-ahead 1). The
   realm file declares the OTP policy explicitly instead of inheriting defaults. An
   account may hold up to two authenticators, so a second device is the recovery
   path that needs nobody else. The platform stores no secret, no recovery code and
   no copy of the factor.

2. **The code is a second step, signalled without an oracle, and its lockout is the
   platform's.** `POST …/auth/sessions` gains an optional `otp` field, forwarded to
   Keycloak as `totp`. After the existing per-address sign-in budget, the calls run
   in a fixed order:
   1. *Is the password right?* The platform asks a second, password-only Keycloak
      client, `horecaos-staff-password-check`. Its direct-grant flow has no OTP
      step. Its tokens are never released: the adapter returns a `PasswordVerified`
      value that carries the account's subject id (read from the answer, with the
      token dropped inside the adapter) and has no token field, so a bug cannot
      hand one out. If Keycloak refuses, the answer is exactly today's, the uniform
      "Invalid credentials" (or `ACCOUNT_ACTION_REQUIRED`, the one exception ADR
      0062 carves out). A wrong password or an unknown name stops here, and is one
      failure in Keycloak's counter, as it is today.
   2. *May a code be tried?* Only when `otp` was sent, and only for a verified
      password. The platform charges one attempt to that account's code budget
      (keyed by the subject id, on the ADR 0033 limiter, strict, so an unavailable
      limiter refuses). With the budget spent the answer is `429
      RATE_LIMIT_EXCEEDED` with `retryAfterSeconds`, and the code goes to Keycloak
      not at all, right or wrong. Four properties carry the weight. The charge is
      made before Keycloak is asked, so nothing after it can refund it. It is keyed
      by the account, not the address, so a rotating address does not refill it. It
      follows a verified password, so a wrong password or an unknown name never
      spends a budget and the 429 cannot tell accounts apart. And it is the
      platform's own counter, because Keycloak's is cleared by this very probe
      (Context). Every other endpoint that accepts a code for an existing factor
      (removing an authenticator, Specification) charges the same budget.
   3. *Sign in.* The platform calls `horecaos-staff-login` with the password and the
      code, if any. Issued: the session. Refused with no code sent: `MFA_REQUIRED`,
      and the page shows the code step with `q-otp-input`. Refused with a code sent:
      `MFA_CODE_INVALID`. A refusal that is `ACCOUNT_ACTION_REQUIRED` keeps its
      present answer. Both distinguishing answers are reachable only by someone who
      already knows the password.

   Cost: every sign-in is two Keycloak calls, and the first leaves a short-lived
   Keycloak session of the probe client that the adapter must end or let lapse (the
   checklist asks the spike to settle which). The probe's success zeroes
   Keycloak's failure count for the account, which is harmless for passwords (a
   guesser has no success to interleave) and is the reason Keycloak's lockout is
   not a control for codes.

3. **Enrolment is in the product and re-proves the password.** The enrolment screen
   asks for the current password again, shows the secret as a QR code
   (`q-qr-code` already exists) and as text, and takes the first code in
   `q-otp-input`. The platform generates 20 random bytes, returns them inside a
   sealed (authenticated encryption, key held as an ADR 0028 reference),
   10-minute, single-purpose token, so no table holds a pending secret,
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
   which a stolen first password can claim the factor. Enforcement for
   platform accounts ships in three phases behind one deploy setting: `OFF`,
   `PROMPT` (offer, do not require) and `REQUIRED`.

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
| A custom Keycloak authenticator or REST extension (distinguishable "OTP required", an enrolment endpoint) | The cleanest protocol: no second-client probe, no second Keycloak call per sign-in, and Keycloak's own counter would then count code failures properly. Also a Java extension one person must build, version and re-test on every Keycloak upgrade | The probe in Decision 2 proves fragile in operation |
| Rely on Keycloak's brute-force detector for the code step | Not possible while a password-only probe exists: its success clears the count. Reproduced: fifteen wrong codes, each followed by a probe, leave no failure and no lock; probing only when no code was sent is beaten by one code-less request between guesses | Keycloak offers a password check that leaves the detector alone, or the extension above is built |
| Ask the password-only client after Keycloak refuses (this record's first draft) | One Keycloak call on the happy path, but a wrong password becomes two failures milliseconds apart, the quick-login check disables the account for 60 seconds on the first typo, and the probe's success still clears the count | Never |
| Switch the quick-login check off in the realm so the double failure is harmless | Weakens the realm against concurrent guessing to hide an artefact of our own call pattern, and does nothing for the cleared count | Never |
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
  be a bypass. It is contained by what the adapter can return (a subject id and
  nothing that signs anything), not by vigilance, and it needs its own test that no
  token escapes.
- Every sign-in is two Keycloak calls and leaves a short-lived probe session behind.
  Staff sign in a handful of times a day, so the load is nothing; the session has
  to be ended or left to lapse on purpose (checklist).
- The limit on code attempts is the platform's, not Keycloak's, and it is the only
  brute-force control the second factor has. It sits behind the ADR 0033 port, so it
  is per replica and forgotten on restart until a shared limiter replaces it
  (roughly N times the budget for N replicas, which ADR 0033 accepts). Its metric
  has an alert, and the exit criteria test it.
- A budget keyed by the account means someone who holds a password can spend it and
  make the real owner wait for the hour to refill. That needs the password, and
  Keycloak's own lockout has the same property.
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
              | 429 RATE_LIMIT_EXCEEDED (the code budget, only after a verified password)
              | 403 MFA_ENROLMENT_REQUIRED { enrolmentTicket, expiresAt } | 403 ACCOUNT_ACTION_REQUIRED
POST …/auth/mfa/enrolments                 bearer or enrolmentTicket, password -> { sealedSecret, otpauthUri, secret, expiresAt }
POST …/auth/mfa/enrolments/confirm         { sealedSecret, code, password, label } -> 204 (+ session when begun from a ticket)
DELETE …/auth/mfa/authenticators/{id}      password + a valid code from another authenticator
                                           (charges the same code budget as sign-in)
GET  …/staff/{personId}/mfa                iam.staff.mfa.read -> { enrolled, authenticators[{id,label,createdAt}], requirement }
POST …/staff/{personId}/mfa/resets         iam.staff.mfa.reset, If-Match, Idempotency-Key, reason
```

Sign-in and the enrolment endpoints are unauthenticated or ticket-authenticated by
design, in the same `permitAll` category as the six ADR 0062 paths, each added to
`SecurityConfiguration` with its reason. All are rate limited per ADR 0033. The sign-in
budget of five a minute per address and username is unchanged and stays first. The
code budget is a second, separate limit keyed by the Keycloak subject id (burst 5,
five more an hour), charged after the password is verified and before any code
reaches Keycloak, by sign-in and by the authenticator-removal endpoint alike. The reset is a mutating endpoint and declares its capability
at the scope of the person's tenant, or platform scope for a platform account.

### Configuration

```text
horecaos.iam.mfa.enforcement            OFF | PROMPT | REQUIRED     platform accounts; deploy setting
horecaos.iam.mfa.code-budget            burst 5, refill 5 per hour, per account       deploy setting
iam.staff_mfa_requirement               OFF | SENSITIVE_ROLES | ALL_STAFF   tenant ConfigurationKey, ADR 0030
```

`PlatformRole` gains a code-owned flag for the sensitive roles. Neither setting
names a person.

### Keycloak

```text
horecaos-staff-login            unchanged: direct grant, default flow, conditional OTP
horecaos-staff-password-check   new confidential client; direct grant only; a flow with
                                username and password validation and no OTP; no scopes,
                                no offline_access; a one-second access token; asked
                                FIRST, on every sign-in
realm: OTP policy declared (totp, 6, 30, HmacSHA1, look-ahead 1);
       failureFactor 8 and the quick-login check (1000 ms / 60 s) unchanged
```

### Observability and audit

Counters `horecaos.auth.staff.mfa{step,outcome}` with bounded labels
(`challenge`, `budget`, `confirm`, `reset`; `ok`, `invalid`, `required`,
`exhausted`). A rise in `budget`/`exhausted` is the alert: somebody who holds a
password is guessing codes. Audit facts
`iam.staff.mfa.enrolled`, `.authenticator_added`, `.authenticator_removed`,
`.reset` through `ChangeDocuments`, carrying the subject's reference, the actor and
the reason — never a secret, a code or a label.

### Testing

- The direct-grant client sends `totp` when given a code. The password-only client
  is asked first: a wrong password or an unknown name answers the uniform failure,
  and the login client is never called. A missing code on an enrolled account
  yields `MFA_REQUIRED` only after that client confirms the password (against the
  live dev realm and against the fake).
- **The code lockout holds when Keycloak's counter does not.** With the right
  password, the sixth code-bearing sign-in within the hour answers 429 and no
  request carrying that code reaches Keycloak, although every earlier attempt was
  followed by a successful probe. The right code is refused with the same 429 while
  the budget is spent, and works again once it refills (a controlled clock, not a
  sleep). Rotating the source address does not refill it, and signing in by email
  instead of user name draws on the same account budget. A wrong password and an
  unknown name never spend a budget and answer exactly as before. Removing an
  authenticator spends the same budget. Seen failing first against a build that has
  only the probe.
- **The Keycloak facts the design rests on stay true.** An integration test, or
  `infra/keycloak/spikes/mfa-lockout-probe.py` in the release checklist, asserts on
  the pinned image that a successful password-only grant clears the failure count,
  that a wrong password asked of the probe first is one failure with no quick-login
  lock, and that a wrong code is counted. A Keycloak upgrade that changes any of
  them reopens this record.
- The password-only adapter's return type has no token (only a subject id); a test
  asserts it by reflection so a later edit cannot add one quietly.
- Enrolment: a good code registers and a bad code leaves no credential behind; the
  sealed token expires, cannot be replayed, and is useless on another account.
- Enforcement: platform account with no factor gets `MFA_ENROLMENT_REQUIRED` and its
  token is revoked; a tenant account under `OFF` signs in unchanged.
- Recovery: a reset removes factors, ends sessions, audits, emails; a platform-scope
  reset waits for the second signature; the password reset cannot remove a factor.
- No code, secret or sealed token reaches a log appender.

## Rollout and rollback

Spike first (first open input). Then realm changes in a staging realm: the OTP
policy and the password-check client (the brute-force settings do not change). Then the backend and both
consoles with enforcement `OFF`: accounts can enrol and enrolled accounts are asked
for a code, and nothing else changes. Then `PROMPT` for platform accounts, then
`REQUIRED` with a `logoutEverywhere` for the affected subjects. Tenant setting last,
one tenant at a time. Rollback is the setting: `OFF` stops requiring; an enrolled
account still needs its code, because Keycloak enforces what is configured, and the
reset action is how that is undone for a person.

## Implementation checklist

- [x] The Keycloak spike; its result recorded on this ADR (the implementation note below).
- [x] Realm file: OTP policy, `horecaos-staff-password-check` client and flow; the
      live-realm runbook step (written; running it against the live realm waits for the owner).
- [x] `StaffDirectGrantClient` sends `totp`; the password-only adapter, asked first,
      returning a subject id and no token; the answers above in `StaffAuthService`;
      `ErrorCode` entries.
- [x] The code budget on the ADR 0033 limiter, keyed by subject id, strict, charged
      after the probe and before the code is sent; the `budget` metric and its alert;
      the same charge in the authenticator-removal endpoint.
- [x] Decide how the probe's Keycloak session ends (a revoke, or idle expiry), in the
      same spike (a logout with the refresh token, best effort; the client's own 60-second idle
      timeout is the backstop).
- [x] `KeycloakStaffAccounts`: list, add and remove OTP credentials; required-action
      handling.
- [x] Enrolment endpoints and the sealed token; the confirm-by-grant proof and its
      compensation.
- [x] Requirement evaluation after password sign-in; the deploy setting and the
      tenant `ConfigurationKey`; role flag.
- [x] `iam.staff.mfa.read` and `iam.staff.mfa.reset` capabilities and bundles; the
      approval action for platform-scope resets; audit facts; emails in uz / ru / en.
- [x] Break-glass script and its runbook entry.
- [x] Sign-in second step, enrolment screen and authenticator list in both consoles
      on `q-otp-input`, `q-qr-code`; `q-otp-input` available to the control plane (the
      control plane has the second step and enrolment; its authenticator list waits for the
      screen that starts a platform reset, see the status line).
- [x] Staff list MFA column (spec §11.9) from the cached Keycloak read.
- [ ] Update `staff-and-access.md` §11.9 (done) and ADR 0062/0139 status notes (not done).
- [x] Tests listed under Testing, each seen failing first (eight mutations of the production code, each caught by the test written for it).

## Implementation note: the Keycloak spike (2026-10-07)

Run against a throwaway Keycloak 26.7.0 container with the realm file and the two scripts above.
The first open input, "can the platform hand Keycloak a secret and have a sign-in with a code
succeed", is **yes**, and Decision 3 is built as written; enrolment through `CONFIGURE_TOTP` was
not needed. What the spike settled, each now pinned by `StaffMfaKeycloakIntegrationTests` (which
runs only where a Keycloak is reachable and is skipped, not failed, elsewhere):

- *The secret.* The admin API stores an OTP credential whose `secretData.value` is Keycloak's own
  20-character string, and the HMAC key is that string's UTF-8 bytes; what an authenticator app
  wants is its Base32. The platform generates the 20 characters, shows their Base32 and an
  `otpauth://` URI, and keeps nothing of either past the ten-minute sealed token.
- *Two authenticators, delete by id.* Two OTP credentials on one account both verify; removing one
  by its credential id leaves the other signing in. A credential added through the admin API with
  a `CONFIGURE_TOTP` required action pending does not clear that action, so the add removes it in
  the same write, or the next password grant would be answered with "account not fully set up".
- *The probe.* A password-only grant by the second client succeeds without a code, clears
  Keycloak's failure count for the account (which is why the platform owns the code budget), and
  opens a session that is ended by a logout with the refresh token; the client's 60-second idle
  timeout covers a logout that fails. Its access token never leaves the adapter: only the subject
  id is read out.
- *A code is single-use* inside its 30-second step on the direct grant, as the earlier run found.
  The confirm-by-grant proof is therefore the first and only use of the code the person typed, and
  anything that signs in again straight after it (an integration test, a person who is quick) has
  to wait for the next step's code.

## Exit criteria

A platform administrator who has never enrolled signs in, is taken to enrolment,
scans the QR code, enters the first code and arrives signed in; signs out and back
in with a password and a code; and, with the phone lost, is reset by a second
administrator and re-enrols. A wrong password and an unknown username still answer
identically. Twenty wrong codes with the right password, from changing source
addresses, stop at the budget: Keycloak sees no more than five of them, the account
owner signs in again once the budget has refilled, and the alert has fired. No
authenticator secret, code or sealed token appears in any log, and the realm holds
the only copy of the factor.

## References

- ADR 0003, ADR 0025, ADR 0027, ADR 0028, ADR 0030, ADR 0033, ADR 0034, ADR 0050,
  ADR 0062 (direct grant, foreclosed WebAuthn), ADR 0079, ADR 0081, ADR 0097,
  ADR 0098, ADR 0116, ADR 0139 (the Keycloak boundary), ADR 0146
- `platform/docs/operations-gap-map.md` row `X.38`; `platform/docs/operations-spec/staff-and-access.md` §11.9
- `StaffDirectGrantClient`, `StaffAuthService`, `StaffSessionController`,
  `KeycloakStaffAccounts`, `SecurityConfiguration`, `RateLimiter` and
  `InProcessRateLimiter`; `platform/infra/keycloak/realm/horecaos-realm.json`,
  `platform/infra/keycloak/README.md`, `create-platform-admin.sh`
- `platform/infra/keycloak/spikes/mfa-lockout-probe.py`: the reproduction behind the
  Context section "What Keycloak's failure counter does"
- `frontend/operations/src/app/shared/ui/otp-input.ts`, `…/q-qr-code`, both consoles'
  `sign-in-page.ts`
- Keycloak community discussions of the direct grant and a missing `totp`
  (forum.keycloak.org, "Direct Grant prompt for OTP if configured" and "Conditional
  OTP for Direct Grant") and of creating an OTP credential through the admin API,
  as read on 2026-10-01; neither is verified against 26.7 here
