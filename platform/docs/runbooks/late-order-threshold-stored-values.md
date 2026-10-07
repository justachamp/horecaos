# Telling tenants what their saved «late after» number now means (ADR 0150)

**Last executed:** never. The two queries below are run against a real PostgreSQL by
`LateOrderThresholdRunbookTests`
(`platform/src/test/java/uz/horecaos/platform/ordering/`), which parses this very file
and executes the SQL between each `<<'SQL'` and its closing `SQL`; no real host, pre-prod
included, has been asked yet. Update this line with the date, the host and what it found
the first time it is run, and run it **before** the release that carries ADR 0150's reader
is deployed.

**Is there a way back?** Yes, and it is the point of step 3. The reader is the only change
in behaviour; reverting that release returns the setting to doing nothing. A tenant that
does not want the new meaning does not need a rollback: it sets the number back to 45, or
reverts it to the inherited value, and its unpromised orders go late at forty-five minutes,
as they always did.

## What this is, in one paragraph

`ordering.late_order_threshold_minutes` («Заказ опаздывает с» until ADR 0150, now «Заказ без
обещанного времени опаздывает через») could be edited while it did nothing. A tenant that
saved a number expected the Delever meaning. From the release that carries the reader, the
number is the tenant-wide default for the no-promise fallback of the `ordering.lateness`
document: **an order with no promised time** (every marketplace order, and a native order no
preparation band covered) counts as late that many minutes after it was **created**. An order
with a promise is unaffected, and acceptance never starts or shortens the clock. A tenant that
never set the value is unchanged: 45 minutes from creation is the platform default. So only a
tenant with an **explicit** stored value can see a difference, and this runbook finds them,
shows what each would see, and says what to do.

## Before you start

On the host, as root, in the compose directory (the same two shortcuts
[deploy.md](deploy.md) uses):

```bash
cd /opt/horecaos/horecaos-platform
alias qc='docker compose -f compose.production.yaml --env-file /etc/horecaos/production.env'
```

## 1. Query: who holds an explicit value

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -At -F',' <<'SQL'
SELECT t.slug AS tenant, v.scope_type, v.brand_id, v.location_id,
       CASE WHEN v.is_explicit_null THEN 'inherit' ELSE v.integer_value::text END AS minutes,
       v.version, to_char(v.updated_at, 'YYYY-MM-DD') AS saved_on
  FROM tenant.configuration_values v
  JOIN tenant.tenants t ON t.id = v.tenant_id
 WHERE v.key_code = 'ordering.late_order_threshold_minutes'
 ORDER BY t.slug, v.scope_type, v.updated_at;
SQL
```

**Check:** one row per tenant, brand or branch that ever saved the number, with the number
(`inherit` is an explicit revert and means nothing was chosen), the row's version and the day
it was saved. **No rows means no tenant has anything to be told: stop here**, the reader
changes nothing for anyone. The output has tenant slugs, scope ids, numbers and dates only,
no names (ADR 0029).

## 2. Report: what each saved number would do to the orders open right now

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -At -F',' <<'SQL'
WITH saved AS (
    SELECT v.tenant_id, v.scope_type, v.brand_id, v.location_id, v.integer_value AS minutes
      FROM tenant.configuration_values v
     WHERE v.key_code = 'ordering.late_order_threshold_minutes'
       AND NOT v.is_explicit_null AND v.integer_value IS NOT NULL
)
SELECT t.slug AS tenant, s.scope_type, s.minutes,
       count(o.id) AS open_unpromised_orders,
       count(o.id) FILTER (
           WHERE o.created_at < now() - s.minutes * interval '1 minute'
             AND o.created_at >= now() - interval '45 minutes') AS would_turn_late_now,
       count(o.id) FILTER (
           WHERE o.created_at < now() - interval '45 minutes'
             AND o.created_at >= now() - s.minutes * interval '1 minute') AS would_stop_being_late_now
  FROM saved s
  JOIN tenant.tenants t ON t.id = s.tenant_id
  LEFT JOIN ordering.orders o
         ON o.tenant_id = s.tenant_id
        AND o.promised_at IS NULL
        AND o.status NOT IN ('PAYMENT_FAILED', 'REJECTED', 'EXPIRED', 'COMPLETED', 'CANCELLED')
        AND (s.scope_type = 'TENANT'
             OR (s.scope_type = 'BRAND' AND o.brand_id = s.brand_id)
             OR (s.scope_type = 'LOCATION' AND o.location_id = s.location_id))
 GROUP BY t.slug, s.scope_type, s.brand_id, s.location_id, s.minutes
 ORDER BY t.slug, s.scope_type, s.minutes;
SQL
```

**Check:** a row for each saved number that is not `inherit`. `open_unpromised_orders` counts
orders with no promised time that are not finished; `would_turn_late_now` are the ones the
board will start colouring as late the moment the release is deployed (the number is under 45),
`would_stop_being_late_now` the ones it will stop colouring (the number is over 45). A number of
exactly 45 turns nothing. Read the counts of a company-wide row against the narrower rows of the
same tenant: an order is governed by the narrowest value that covers it, so a branch with its own
number is counted under the company's row as well.

## 3. Apply: nothing to run, and the one choice a tenant has

The new meaning is applied by the release, not by a command: do **not** edit
`tenant.configuration_values` by hand. For each tenant step 2 listed, tell the tenant owner (by
the channel you already use with them; the message needs no order data and no names):

> The setting «Заказ опаздывает с» is now «Заказ без обещанного времени опаздывает через». It
> used to change nothing; from the next release an order that arrives without a promised time
> (for example an aggregator order) is marked late that many minutes after it was created. Your
> saved value is N minutes. Orders that carry a promised time are unaffected. If you would rather
> keep forty-five minutes, set the value back to 45 in Settings → Order policy → Timing and SLA.

A tenant that wants the old (forty-five minute) behaviour sets the number back to 45, or reverts
it to the inherited value, in the console. Where many tenants must be moved in one sitting, the
same write the console makes is `POST
/api/v1/operations/tenants/{tenantId}/configuration/keys/ordering.late_order_threshold_minutes/values`
with the scope, `integerValue` and a reason, an `Idempotency-Key`, and the row's version from
step 1 as `expectedVersion` ([deploy.md](deploy.md) shows how the `ops` container makes
authenticated calls).

**Check:** run step 1 again after the tenant has moved: its row carries the new number and a
higher version. A tenant that chose to keep its number needs nothing further.

## Why a runbook and not a migration

A Flyway migration cannot tell anyone anything, and rewriting a tenant's saved value for it
would replace a number the tenant typed with a number somebody guessed. The reader's behaviour is
fully determined by the stored rows, so the honest preparation is to read them first and say what
will happen.
