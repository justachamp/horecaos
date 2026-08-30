-- Close the two privilege gaps that only became visible once anything actually
-- connected as `qoida_application`.
--
-- Both were written correctly in SQL and verified by nothing. The application ran
-- as the superuser that owns the database on every laptop and in every test, so a
-- missing GRANT and a function that cannot do what it was granted for both looked
-- like working code for sixty-one migrations. They are found here because the
-- local stack and the probe suite now connect as the role production has always
-- used, not because anything about the intent changed.
--
-- Neither statement below widens the role beyond what the application already
-- does. There is no new table, no new column, and no new capability — only the
-- privilege the existing code path has been exercising against a connection that
-- did not need it.


-- ---------------------------------------------------------------------------
-- 1. ADR 0029 erasure on the marketing projection
-- ---------------------------------------------------------------------------
--
-- V0043 granted SELECT, INSERT and UPDATE on marketing.customer_metrics and
-- stopped there, which is the right instinct for a projection: it is rebuilt from
-- facts, so nothing should be deleting from it.
--
-- Except one thing must. JdbcCustomerMetricStore.erase drops the customer's row
-- on an ADR 0029 erasure request — the row is a per-customer projection and it
-- identifies somebody, so it goes; the campaign and spend aggregates around it
-- deliberately stay, because an aggregate that no longer identifies anyone is not
-- personal data and reversing a finance number to honour a privacy request is a
-- different kind of wrong.
--
-- Under the owner connection that DELETE succeeded and nobody noticed the missing
-- grant. Under qoida_app it is `permission denied for table customer_metrics`,
-- raised inside an erasure request, which is the single worst place on this
-- platform to discover a privilege gap: the request is legally timed, the failure
-- looks like a bug in the erasure path rather than a grant, and the operator's
-- only evidence is that the customer is still there.
GRANT DELETE ON marketing.customer_metrics TO qoida_application;


-- ---------------------------------------------------------------------------
-- 2. ADR 0043 fact partitions the application is supposed to be able to create
-- ---------------------------------------------------------------------------
--
-- reporting.ensure_fact_partition exists precisely so that the application can
-- add next month's fact partition without holding DDL rights: it validates the
-- table name against a fixed list, creates the partition, and grants it to
-- qoida_application and qoida_reporting_read. V0031 granted EXECUTE on it to
-- qoida_application, which says plainly who was meant to call it.
--
-- It was declared SECURITY INVOKER, so the CREATE TABLE inside it runs with the
-- caller's privileges. The caller is the application. The application owns
-- nothing and cannot create a partition of a table it does not own, so every call
-- from ReportingPartitionManager would fail — the grant of EXECUTE bought the
-- right to invoke a function that could not do its job. The owner connection hid
-- that completely, because as the owner the body succeeded on its own account and
-- the security mode never mattered.
--
-- SECURITY DEFINER is what the function was already documented to be. The two
-- risks that come with it are handled rather than accepted:
--
--   * search_path is pinned, so a caller cannot bend an unqualified name inside
--     the body to something of their own. Everything the body touches is either
--     schema-qualified already or lives in pg_catalog.
--   * EXECUTE is revoked from PUBLIC and re-granted by name. A SECURITY DEFINER
--     function callable by PUBLIC is a privilege escalation waiting for a role
--     nobody thought about, and the default on a new function is exactly that.
--
-- The function keeps its own guard: it refuses any table outside fact_order,
-- fact_order_line and fact_refund, so the widest thing a caller can do with it is
-- create a partition of a reporting fact table that ADR 0043 already says it
-- writes to.
ALTER FUNCTION reporting.ensure_fact_partition(text, date)
    SECURITY DEFINER
    SET search_path = pg_catalog;

REVOKE EXECUTE ON FUNCTION reporting.ensure_fact_partition(text, date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION reporting.ensure_fact_partition(text, date) TO qoida_application;

COMMENT ON FUNCTION reporting.ensure_fact_partition(text, date) IS
    'ADR 0043 partition upkeep. Idempotent, and refuses any table it does not own so a typo cannot partition something else. SECURITY DEFINER (V0070) because the application role holds no DDL rights and this function is the only way it is meant to add a partition; search_path is pinned and EXECUTE is granted by name, never to PUBLIC.';
