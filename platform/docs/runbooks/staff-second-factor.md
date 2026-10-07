# Staff second factor (ADR 0148)

Authenticator-app codes (TOTP) for staff sign-in. Keycloak holds and verifies the
factor; the platform generates the secret once, at enrolment, hands it over and
forgets it. This runbook is the rollout, the one alert, and the night nobody can
reset a phone.

## Rollout, in this order

Nothing below changes a person's sign-in until the last two steps. Every step can
be undone by the one before it.

1. **The realm.** The OTP policy and the password-only client. Locally `make up`
   runs `infra/keycloak/create-staff-password-check-client.sh`. On the real realm
   `deploy/keycloak-stage2.sh` does the same three things (`stage2-inner.sh`, the
   block "Staff second factor") and its rotation loop then replaces the client's
   secret in OpenBao (`identity_admin/keycloak/staff-password-check-secret`).
   **Do this before the backend is deployed**: the platform asks that client first
   on every sign-in, and a realm without it answers every sign-in with a server
   error, not "wrong password".
2. **The sealing key.** `horecaos/<environment>/data_encryption/platform/mfa-enrolment-sealing-key`
   in OpenBao, a long random value (`openssl rand -base64 48`). It seals the secret
   of an enrolment in progress for ten minutes. Rotating it ends enrolments in
   progress and nothing else.
3. **The backend, enforcement `OFF`.** `horecaos.iam.mfa.enforcement` unset means
   `OFF`. People can enrol from «Мой профиль»; an enrolled account is asked for its
   code, because Keycloak enforces what is configured. Nothing else changes.
4. **`PROMPT` for platform accounts.** `HORECAOS_IAM_MFA_ENFORCEMENT=PROMPT`. A
   platform account with no factor signs in and is offered enrolment.
5. **`REQUIRED` for platform accounts.** `HORECAOS_IAM_MFA_ENFORCEMENT=REQUIRED`.
   An account holding a platform-scope grant with no factor is refused with
   `MFA_ENROLMENT_REQUIRED` and taken to enrolment. **Switching it on does not
   reach sessions already open**: end them with the break-glass below's
   `logoutEverywhere` half, or let the refresh tokens run out, or no existing
   session is protected.
6. **Tenants, one at a time.** A tenant owner sets «Вход в два шага» (ADR 0030 key
   `iam.staff_mfa_requirement`: `OFF`, `SENSITIVE_ROLES`, `ALL_STAFF`) on the
   security settings card. The same caveat: open sessions of the affected accounts
   stay open until they end.

**Rollback is the setting.** `OFF` stops requiring. An enrolled account still needs
its code, because Keycloak enforces what is configured; a person's factor is removed
by the reset, never by the setting.

## The alert

`HorecaosStaffMfaCodeBudgetExhausted` fires when `horecaos_auth_staff_mfa_total{step="budget",outcome="exhausted"}`
rises. Somebody who holds a password has spent an account's five code attempts in
the hour. The 429 they got is working as designed; what you decide is whether it
is the owner fumbling a new phone (it clears in about twelve minutes per attempt)
or a guesser (the password is compromised: reset it from the product, then the
factor).

What to check: who the account is (the metric names none: ask the audit trail for
sign-in activity of staff in the hour), whether the owner is reachable, and whether
the alert repeats after the password reset.

## Reset, the ordinary way

From the product, by another administrator: the person card's Безопасность tab,
«Сбросить второй фактор», with a reason. It removes every authenticator, ends the
sessions, writes an audit fact and emails the person. A platform account's reset
waits for a second administrator's signature on the approvals queue; the first
repeats the call after approval. A tenant owner's is done by platform support inside
a support session.

## Break-glass: no administrator can reset

The last platform administrator lost their phone. On the host, with the temporary
Keycloak administrator of `deploy/keycloak-stage2.sh`:

```bash
read -rsp 'Keycloak admin password: ' HORECAOS_KEYCLOAK_ADMIN_PASSWORD; echo
export HORECAOS_KEYCLOAK_ADMIN_PASSWORD
HORECAOS_KEYCLOAK_URL=http://keycloak:8080 infra/keycloak/remove-staff-second-factor.sh <user name or subject id>
unset HORECAOS_KEYCLOAK_ADMIN_PASSWORD
```

**Check:** it prints how many authenticators it removed. The person then signs in
with their password, is refused with `MFA_ENROLMENT_REQUIRED`, and enrols again.

**What it cannot do:** the platform's audit trail does not see it. Write down who
ran it, for whom, why and when, in the operator log, and tell the person. There is
no way back from it except enrolling again.

## Keycloak facts the design rests on

`infra/keycloak/spikes/mfa-lockout-probe.py` reproduces them on a throwaway
Keycloak and exits non-zero when one stops holding. Run it, and the integration
test `StaffMfaKeycloakIntegrationTests` (see its header), whenever the pinned
Keycloak image changes: a successful password-only grant clears the failure count;
a wrong password asked of the password-only client first is one failure with no
lock; a wrong code is counted; a code is single use. A change in any of them
reopens ADR 0148.
