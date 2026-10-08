# Bringing the staff member record level with Keycloak (ADR 0139)

**Last executed:** never. The reconciler, its gauges and the retention sweeper are
exercised by `StaffMemberReconciliationTests`
(`platform/src/test/java/uz/horecaos/platform/iam/application/staff/`) against a
real PostgreSQL and a fake identity provider. No real Keycloak has been read by this
job yet, locally or on pre-prod, so step 2's "unanswered" and "no account" counts
have never been seen against a real realm. Update this line with the date, the host
and what it found the first time it is run.

**Is there a way back?** Yes for everything here. The backfill only inserts rows
into `iam.staff_members` and never changes Keycloak. A row it should not have made
is removed with the statement in the last section; nothing else in the platform has
started to depend on it until the callers have been moved (see the rollout order in
ADR 0139).

## What this is, in one paragraph

Before ADR 0139 a staff member's name lived only in Keycloak. The tenant now keeps
its own record (`iam.staff_members`, one row per tenant and Keycloak subject), and a
row is created where the platform first learns the name: at invitation, and when an
owner accepts. An account that **predates** the record has a name only in Keycloak,
and `StaffMemberReconciler` copies it across, once, per subject. It runs on a timer
in the worker (every five minutes, one hundred subjects per pass), is safe to run
twice, and says how far it has got in `horecaos.iam.staff.unbacked_active`. Until
that number is zero everywhere, the directory falls back to Keycloak's name for a
subject with no row; at zero, the fallback can be deleted.

## Before you start

On the host, as root, in the compose directory (the same two shortcuts
[deploy.md](deploy.md) uses):

```bash
cd /opt/horecaos/horecaos-platform
alias qc='docker compose -f compose.production.yaml --env-file /etc/horecaos/production.env'
```

## 1. How many active staff subjects have no row

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -At -F',' <<'SQL'
SELECT g.tenant_id, count(DISTINCT g.principal_subject) AS unbacked
  FROM iam.grants g
  JOIN iam.roles r ON r.id = g.role_id
 WHERE g.tenant_id IS NOT NULL AND g.status = 'ACTIVE' AND r.status = 'ACTIVE'
   AND g.valid_from <= now() AND (g.valid_until IS NULL OR g.valid_until > now())
   AND r.code NOT IN ('kitchen-device', 'kitchen-vdu-device', 'support-session-view', 'support-session-assist')
   AND NOT EXISTS (
       SELECT 1 FROM iam.staff_members m
        WHERE m.tenant_id = g.tenant_id AND m.principal_subject = g.principal_subject)
 GROUP BY g.tenant_id
 ORDER BY unbacked DESC;
SQL
```

**Check:** one row per tenant that still has subjects without a record, with its
count. No rows means the backfill is complete for every tenant: go to step 4. The
output has tenant ids and counts only, no names.

## 2. Let it run, and read what it says

The reconciler logs one line per pass that did anything, with counts and no person:

```bash
qc logs --since 30m platform 2>&1 | grep -a "Staff member reconciliation"
```

**Check:** a line such as
`Staff member reconciliation: 12 created, 0 promoted, 1 without an account, 0 unanswered (left for retry), 1 still unbacked`.

- **`created`** rows were inserted: `ACTIVE` when the Keycloak account has a password,
  `PENDING` when it does not. An account with no name gets a row with null names and
  is shown by its reference (`S-0007`) until someone fills it in.
- **`without an account`**: Keycloak has no account under that subject. The job
  leaves it alone, on purpose: inventing a person from a grant would put a name in
  the record that nobody typed. A grant for a subject Keycloak does not know is a
  finding for the grants screen, not for this job.
- **`unanswered`**: Keycloak could not be asked this time. The next pass retries;
  if the count does not fall, Keycloak is the problem, not this job (see
  [container-crash-loop.md](container-crash-loop.md) and check the identity
  provider).
- **`still unbacked`** is the same number as the gauge.

The job is off with `HORECAOS_IAM_STAFF_MEMBERS_RECONCILE_ENABLED=false`, and it does
not run in a process with `HORECAOS_RUNTIME_ROLE=app`.

## 3. The gauges

```bash
qc exec -T platform curl -s localhost:8080/actuator/prometheus 2>/dev/null \
  | grep -a '^horecaos_iam_staff'
```

**Check:**

- `horecaos_iam_staff_unbacked_active` falls to `0` and stays there.
- `horecaos_iam_staff_members{status="ACTIVE"}` etc. are the counts by status.
- `horecaos_iam_staff_ended_with_access` is `0`. A non-zero value means someone's
  employment was ended and at least one revoke then failed: open that person on the
  People screen (they carry `accessDrift`) and end their employment again with the
  current version; it revokes what is left.

If the metrics endpoint is not reachable from inside the container, read the same
figures from the database with the query in step 1 and
`SELECT employment_status, count(*) FROM iam.staff_members GROUP BY 1;`.

## 4. Retiring the Keycloak fallback

Do this only when step 1 returns no rows **on every environment** and the gauge has
read `0` for a full day. Until then `StaffDirectoryService` answers a subject with no
row from `StaffAccounts#displayName`. At that point the fallback method, the
`hasEverHeldStaffJob` guard and this runbook's step 2 can be deleted in one change.
Rolling back after that is a forward migration that stops reading the table, not an
edit to an applied one.

## 5. The retention sweeper stays report-only

`StaffMemberRetentionSweeper` counts the ended employees whose personal data is past
retention (ADR 0029's provisional default: twenty-four months after `employed_until`;
legal has not confirmed the labour-law period) and logs the count. It writes nothing
until someone has read a sample and set
`HORECAOS_IAM_STAFF_MEMBERS_RETENTION_MODE=ENFORCE`:

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -At -F',' <<'SQL'
SELECT m.tenant_id, m.display_reference, m.employed_until
  FROM iam.staff_members m
 WHERE m.employment_status = 'ENDED'
   AND m.employed_until < (current_date - interval '24 months')
   AND (m.protected_first_name IS NOT NULL OR m.protected_last_name IS NOT NULL
        OR m.protected_phone IS NOT NULL OR m.protected_employee_number IS NOT NULL
        OR m.photo_asset_id IS NOT NULL)
 ORDER BY m.employed_until
 LIMIT 20;
SQL
```

**Check:** the rows are former employees who really left more than two years ago,
named only by reference and date. Enforcing overwrites, in place and without a way
back: names, phone and employee number are nulled, the photo reference is dropped
and the emergency contacts are deleted. The reference and the status history remain,
so the audit log still says "former staff S-0142". There is no undo; the only
protection is this sample.

The photo asset itself is not deleted by the sweeper (the media module has no delete
port for it yet); it becomes unreferenced and is left to the media lifecycle.

## Removing a row that should not exist

A subject was given a record in the wrong tenant (a grant that should not have been
there). If nothing has attributed anything to the row yet:

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos <<'SQL'
-- replace the two values with the tenant id and the S-number from step 1's follow-up
DELETE FROM iam.staff_emergency_contacts WHERE staff_member_id IN (
    SELECT id FROM iam.staff_members WHERE tenant_id = '00000000-0000-0000-0000-000000000000' AND display_reference = 'S-0000');
DELETE FROM iam.staff_members WHERE tenant_id = '00000000-0000-0000-0000-000000000000' AND display_reference = 'S-0000';
SQL
```

**Check:** `DELETE 0` then `DELETE 1`. The migrator role owns the table; the
application role has no `DELETE` on it, on purpose. If an audit fact or a POS mapping
already refers to the member, do not delete: anonymise it instead (ended members
only) or leave it.
