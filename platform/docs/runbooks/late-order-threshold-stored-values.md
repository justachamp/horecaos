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

**Two places hold a number, and the second one wins.** The saved number can sit at any scope the
setting has: the whole platform, a company (tenant), a brand or a branch, the narrowest one
covering an order governing it. But a lateness **document** (`ordering.lateness`, authored on the
«When an order counts as late» card under Settings → Order policy → Timing and SLA) can also sit
at any of those scopes, and it carries a fallback **of its own per mode** (delivery, pickup,
dine-in). Where the document in
force for an order carries one for the order's mode, that number wins and the saved one changes
nothing for it. Every document saved before ADR 0150 carries a fallback for every mode, so a
tenant that ever edited its lateness thresholds is largely shielded from the new meaning, at the
scope of its document and beneath it; a document that leaves a mode blank takes the saved number
for that mode. Step 1 lists both kinds of row, at every scope; step 2 resolves each open order
through both, so the counts are what the board will do and not what the saved number alone would.

## Before you start

On the host, as root, in the compose directory (the same two shortcuts
[deploy.md](deploy.md) uses):

```bash
cd /opt/horecaos/horecaos-platform
alias qc='docker compose -f compose.production.yaml --env-file /etc/horecaos/production.env'
```

## 1. Query: who holds an explicit value, or a document of their own, at any scope

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -At -F',' <<'SQL'
SELECT 'SCALAR' AS source,
       COALESCE(t.slug, '(every tenant)') AS tenant,
       v.scope_type, v.brand_id, v.location_id,
       CASE WHEN v.is_explicit_null THEN 'inherit' ELSE v.integer_value::text || ' min' END AS value,
       v.version, to_char(v.updated_at, 'YYYY-MM-DD') AS saved_on
  FROM tenant.configuration_values v
  LEFT JOIN tenant.tenants t ON t.id = v.tenant_id
 WHERE v.key_code = 'ordering.late_order_threshold_minutes'
UNION ALL
SELECT 'DOCUMENT',
       COALESCE(t.slug, '(every tenant)'),
       c.scope_type, c.brand_id, c.location_id,
       'delivery=' || COALESCE(p.document -> 'delivery' ->> 'noPromiseFallbackSeconds', 'blank')
         || ' pickup=' || COALESCE(p.document -> 'pickup' ->> 'noPromiseFallbackSeconds', 'blank')
         || ' dine_in=' || COALESCE(p.document -> 'dineIn' ->> 'noPromiseFallbackSeconds', 'blank'),
       p.version, to_char(c.activated_at, 'YYYY-MM-DD')
  FROM tenant.policy_current c
  JOIN tenant.policies p ON p.id = c.policy_id AND p.status = 'ACTIVE'
  LEFT JOIN tenant.tenants t ON t.id = c.tenant_id
 WHERE c.key_code = 'ordering.lateness'
 ORDER BY 2, 1, 3, 8;
SQL
```

**Check:** one row per tenant, brand or branch that ever saved the number (`SCALAR`: the
number in minutes, `inherit` for an explicit revert that means nothing was chosen, the row's
version and the day it was saved), and one per scope that holds an authored lateness document
(`DOCUMENT`: its no-promise fallback in **seconds** for each mode, or `blank` where the mode
leaves it to the saved number). A `PLATFORM` scope row says `(every tenant)`: it governs every
tenant that has nothing narrower, and it is the row to read first, because it reaches tenants
that never touched the setting. **No `SCALAR` rows means no tenant has anything to be told:
stop here**, the reader changes nothing for anyone. A `DOCUMENT` row on its own changes
nothing either; it matters only where it sits beside a `SCALAR` row, and step 2 says where. The
output has tenant slugs, scope ids, numbers and dates only, no names (ADR 0029).

## 2. Report: what each saved number would do to the orders open right now

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -At -F',' <<'SQL'
WITH open_unpromised AS (
    SELECT o.id, o.tenant_id, o.brand_id, o.location_id, o.created_at,
           CASE o.fulfillment_mode WHEN 'DINE_IN' THEN 'dineIn'
                                   WHEN 'PICKUP' THEN 'pickup'
                                   ELSE 'delivery' END AS mode_key
      FROM ordering.orders o
     WHERE o.promised_at IS NULL
       AND o.status NOT IN ('PAYMENT_FAILED', 'REJECTED', 'EXPIRED', 'COMPLETED', 'CANCELLED')
), scalar AS (
    -- the narrowest saved number that covers the order; an explicit revert is "not chosen here"
    SELECT DISTINCT ON (u.id)
           u.id, v.scope_type, v.brand_id, v.location_id, v.integer_value AS minutes
      FROM open_unpromised u
      JOIN tenant.configuration_values v
        ON v.key_code = 'ordering.late_order_threshold_minutes'
       AND NOT v.is_explicit_null AND v.integer_value IS NOT NULL
       AND (v.scope_type = 'PLATFORM'
            OR (v.scope_type = 'TENANT' AND v.tenant_id = u.tenant_id)
            OR (v.scope_type = 'BRAND' AND v.tenant_id = u.tenant_id AND v.brand_id = u.brand_id)
            OR (v.scope_type = 'LOCATION' AND v.tenant_id = u.tenant_id
                AND v.brand_id = u.brand_id AND v.location_id = u.location_id))
     ORDER BY u.id, CASE v.scope_type WHEN 'LOCATION' THEN 1 WHEN 'BRAND' THEN 2
                                      WHEN 'TENANT' THEN 3 ELSE 4 END
), document AS (
    -- the authored lateness document in force for the order: the narrowest scope that has one
    SELECT DISTINCT ON (u.id) u.id, p.document
      FROM open_unpromised u
      JOIN tenant.policy_current c
        ON c.key_code = 'ordering.lateness'
       AND (c.scope_type = 'PLATFORM'
            OR (c.scope_type = 'TENANT' AND c.tenant_id = u.tenant_id)
            OR (c.scope_type = 'BRAND' AND c.tenant_id = u.tenant_id AND c.brand_id = u.brand_id)
            OR (c.scope_type = 'LOCATION' AND c.tenant_id = u.tenant_id
                AND c.brand_id = u.brand_id AND c.location_id = u.location_id))
      JOIN tenant.policies p ON p.id = c.policy_id AND p.status = 'ACTIVE'
     ORDER BY u.id, CASE c.scope_type WHEN 'LOCATION' THEN 1 WHEN 'BRAND' THEN 2
                                      WHEN 'TENANT' THEN 3 ELSE 4 END
), governed AS (
    SELECT u.created_at, u.tenant_id, s.scope_type, s.brand_id, s.location_id, s.minutes,
           (d.document -> u.mode_key ->> 'noPromiseFallbackSeconds')::int AS document_seconds
      FROM open_unpromised u
      JOIN scalar s ON s.id = u.id
      LEFT JOIN document d ON d.id = u.id
), compared AS (
    SELECT g.*,
           -- what the order is held to today: its document's own number, else the 45 minutes
           COALESCE(g.document_seconds, 2700) AS before_seconds,
           -- and from the release: the document's own number, else the saved one (when usable), else 45
           COALESCE(g.document_seconds,
                    CASE WHEN g.minutes BETWEEN 1 AND 600 THEN g.minutes * 60 END,
                    2700) AS after_seconds
      FROM governed g
)
SELECT t.slug AS tenant, c.scope_type AS number_sits_at, c.brand_id, c.location_id, c.minutes,
       count(*) AS open_unpromised_orders,
       count(*) FILTER (WHERE c.document_seconds IS NOT NULL) AS held_back_by_a_document,
       count(*) FILTER (
           WHERE c.created_at < now() - c.after_seconds * interval '1 second'
             AND c.created_at >= now() - c.before_seconds * interval '1 second') AS would_turn_late_now,
       count(*) FILTER (
           WHERE c.created_at < now() - c.before_seconds * interval '1 second'
             AND c.created_at >= now() - c.after_seconds * interval '1 second') AS would_stop_being_late_now
  FROM compared c
  JOIN tenant.tenants t ON t.id = c.tenant_id
 GROUP BY t.slug, c.scope_type, c.brand_id, c.location_id, c.minutes
 ORDER BY t.slug, c.scope_type, c.minutes;
SQL
```

**Check:** a row for each saved number (that is not `inherit`) that governs at least one open
order with no promised time; a number that governs none has no row here, and step 1 still lists
it. `number_sits_at` is the scope the number was saved at and `open_unpromised_orders` counts the
orders **it** governs, that is, the ones no narrower saved number covers, so a branch with its
own number is counted under its own row and not again under the company's. For each of those
orders the query takes the authored lateness document in force (the narrowest scope that has
one) and the order's own mode (delivery, pickup, dine-in):

- `held_back_by_a_document` counts the orders whose document carries a fallback of its own for
  their mode. **The saved number changes nothing for them**, whatever it is: they are late at the
  document's number before the release and after it. Read this first: when it equals
  `open_unpromised_orders`, the saved number is inert for that tenant today.
- `would_turn_late_now` are the orders the board will start colouring as late the moment the
  release is deployed (the number that governs them is under what they are held to now), and
  `would_stop_being_late_now` the ones it will stop colouring (the number is over it). A number
  of exactly 45, or an order held back by a document, turns nothing.

An unusable saved number (under a minute, or over ten hours) is ignored by the reader and by this
query alike, and the order stays at forty-five minutes.

## 3. Apply: nothing to run, and the one choice a tenant has

The new meaning is applied by the release, not by a command: do **not** edit
`tenant.configuration_values` or `tenant.policies` by hand. For each tenant step 2 listed, tell the
tenant owner (by the channel you already use with them; the message needs no order data and no
names):

> The setting «Заказ опаздывает с» is now «Заказ без обещанного времени опаздывает через». It
> used to change nothing; from the next release an order that arrives without a promised time
> (for example an aggregator order) is marked late that many minutes after it was created. Your
> saved value is N minutes, saved at <company / brand / branch>. Orders that carry a promised time
> are unaffected. If you would rather keep forty-five minutes, set the value back to 45 in
> Settings → Order policy → Timing and SLA.

**If step 1 also listed a `DOCUMENT` row at that tenant, brand or branch, or step 2 showed
`held_back_by_a_document` above zero, add what applies to it, because the first message would
promise something the document prevents:**

> Your lateness thresholds (Settings → Order policy → Timing and SLA, the lateness card) already
> give <delivery / pickup / dine-in> orders at <company / brand / branch> their own number of
> minutes for an order without a promised time. That number wins there, so the value above does
> not change those orders. To have the value above apply, clear that field on the lateness card
> (a blank means "use the number above") and publish it.

Say it for **each scope that has a document**, not once for the tenant: a branch's document
shields that branch alone, and its siblings take the saved number. A `PLATFORM` scope row from
step 1 is not a tenant's to answer; it is operations': read what it holds before the release and
decide whether it is meant to reach every tenant that never set the value.

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
