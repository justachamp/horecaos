# Staff sessions: the list, signing out everywhere (ADR 0176)

**Last executed:** never. `StaffSessionsEndpointTests`, `KeycloakStaffSessionsTests` and the
probe `infra/keycloak/spikes/staff-sessions-probe.py` (run once against Keycloak 26.7 on the
laptop, result in its header) exercise this against a stateful fake and a throwaway Keycloak.
No real realm has served the session list yet. Update this line with the date, the host and
what it found.

**Is there a way back?** Yes. The feature adds a table (`iam.staff_sign_ins`) and reads; it
changes no grant. Unsetting `HORECAOS_KEYCLOAK_STAFF_LOGIN_CLIENT_UUID` turns the list back to
"the platform's own record only"; nothing else depends on it.

## What it is

«Мой профиль» → «Сеансы» lists the signed-in person's sessions: a device label out of three
small closed sets (browser, system, form) and the times. «Выйти на всех устройствах» ends them
all, each one audited. Keycloak decides whether a session is alive; the platform's record
(`iam.staff_sign_ins`) says what it was. **No address, header, position or device identifier is
kept**, and a canary test fails the build if one is.

## Setting it up on a deployment

1. Run `infra/keycloak/create-staff-login-client.sh` (it is idempotent). The last line it prints
   is the one to set:

   ```
   HORECAOS_KEYCLOAK_STAFF_LOGIN_CLIENT_UUID=<the client's internal id>
   ```

   The platform lists sessions through the offline-session endpoint, which takes the client's
   **internal id**, not `horecaos-staff-login`. Reading it would need `view-clients`, which the
   provisioning credential deliberately lacks, so it is configuration.
2. Put the line in `/etc/horecaos/production.env` (both compose files pass it through) and
   restart the platform.

**Check:** a person's «Мой профиль» → «Сеансы» shows their devices and "Keycloak: available".
With the variable unset the card still renders, lists what the platform recorded, and says it
could not ask the sign-in service.

## When «Выйти на всех устройствах» fails

The act answers `IDENTITY_PROVIDER_UNAVAILABLE` when Keycloak could not be reached. **Nothing
changed**: no session was ended and no audit fact says one was. The person tries again. If it
keeps failing, Keycloak is the problem (see its container logs and the realm's health), not this
feature. The metric is `horecaos_iam_staff_session_operations_total{operation,outcome}`; its
labels name no one.

After a **password reset** (ADR 0098) the sessions are ended too, and the history is marked
`PASSWORD_RESET` only after Keycloak confirmed the logout. A reset whose logout failed leaves the
history open, and the reset is reported as incomplete.

## Retention

Rows are operational history, not evidence: kept `iam.staff_sign_in_retention_days` (ADR 0030,
default 180, tenant-settable between 30 and 730) and swept in batches. What is audited is the act
that ends sessions, never the sign-in.

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -At -F',' <<'SQL'
SELECT tenant_id, count(*) AS rows, count(*) FILTER (WHERE ended_at IS NULL) AS open,
       min(signed_in_at) AS oldest
  FROM iam.staff_sign_ins
 GROUP BY tenant_id ORDER BY rows DESC LIMIT 20;
SQL
```

**Check:** counts and times only; the table has no column that could hold a person's device
detail beyond the three labels.

## Who sees what

Nobody sees anyone else's sessions: not an owner, not support. A manager cannot list or end a
colleague's sessions; the only remedy for a lost phone is the colleague's own «Выйти везде» or a
password reset. That is by design (ADR 0176), not a gap.

The session facts are written as platform-scope SECURITY facts, so a tenant's activity log may
not show them; they are in the audit trail itself.
