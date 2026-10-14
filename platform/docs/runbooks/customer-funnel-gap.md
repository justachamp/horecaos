# The customer funnel is missing days

**A morning-digest alert.** Two alerts link here: **a closed business day holds orders and has no customer-funnel row**
(`HorecaosFunnelDayWithoutRow`), and **days past the first week with a basket or checkout count that was never recorded are
growing** (`HorecaosFunnelUnrecordedDaysGrowing`). **Last executed:** never — this is a draft.

**What was lost, and why it matters now.** The funnel's basket and checkout counts are read from `ordering.carts` and
`ordering.checkout_attempts` when a business day closes (ADR 0167, Decision 3), and `CartRetentionSweeper` deletes a cart 90
days after it was last touched (ADR 0092). A day the close skipped can be re-captured while its carts still exist, and not
after. Nothing here is an order: no customer waited, no money moved, and the registrations and orders counts for the same day
are still derivable from the tables that keep them. So this is the morning digest and not a page; the clock it runs against is
measured in weeks.

**The way back, before the first command.** Every step below only *adds* counts. A capture never lets a missing count
overwrite one that was recorded (ADR 0167, Decision 3), so re-running one cannot make a day worse.

## 1. Which tenant, and which days?

Both gauges are deliberately free of a tenant (ADR 0023). The platform's log names it:

```bash
docker logs platform-app 2>&1 | grep 'Customer funnel: .* closed business days hold orders and have no funnel row'
```

Then, as the migrator role, list the days:

```sql
SELECT o.tenant_id, o.business_date, count(*) AS orders
  FROM reporting.fact_order o
  LEFT JOIN reporting.fact_funnel_day f
    ON f.tenant_id = o.tenant_id AND f.business_date = o.business_date
 WHERE f.business_date IS NULL
   AND o.business_date < current_date - 7
 GROUP BY 1, 2
 ORDER BY 2;
```

**Check:** the dates are older than a week. A younger one is a close that has not reached it yet, and the alert would not
have fired for it.

## 2. Why did the close skip it?

```bash
docker logs platform-app 2>&1 | grep -E 'dayclose|DayClose' | grep <tenant-id>
```

Look at `horecaos.reporting.dayclose.runs` in Prometheus (`kind="close"`, `outcome`): a stopped heartbeat is the close job
itself being down (`horecaos.reporting.dayclose.enabled=false`, or the replica holding the claim died). Fix that first;
re-capturing days while the job is down just waits for the next failure.

## 3. Re-capture the days

The funnel backfill walks every already-closed day of a tenant from the facts still held, once, and marks the tenant done in
`reporting.funnel_backfill_state`. To walk it again, remove that one row; the scheduler picks the tenant up within the hour
(`horecaos.reporting.funnel.backfill-interval`, default `PT1H`):

```sql
DELETE FROM reporting.funnel_backfill_state WHERE tenant_id = '<tenant-id>';
```

**Check:** within the hour the log says `Customer funnel backfill for tenant <id>: N closed business days walked`, the query in
step 1 returns nothing for that tenant, and the next probe run (hourly) drops `days_without_row` to zero. The alert then
resolves by itself.

## 4. Days whose carts are already gone

A day older than the cart retention window gets its basket and checkout counts as «not recorded» (`NULL`), by design, and the
funnel screen shows a gap and never a zero. Those days are counted by `unrecorded_days` and are not repairable. They are an
alert only while the number *grows*, because a plateau is history the funnel was never able to hold (a tenant that went live
before the funnel existed), and a rise means days are being lost now. If it is rising, step 2 is the cause.

## If it keeps happening

The close is failing for the same reason every night. That is `docs/runbooks/outbox-not-draining.md`-class trouble only if
orders stop flowing; otherwise open an incident against the reporting module with the step 2 log lines attached. Do not
extend `ordering.cart_retention_days` to buy time: that keeps customers' carts longer than ADR 0092 decided to hold them.
