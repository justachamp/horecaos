-- ADR 0058 stage 2: supervisor digests, and the control-plane audience the
-- stage-1 CHECK constraints did not yet admit.
--
-- Two independent widenings, both append-only per Flyway discipline and both
-- honest about what they do and do not change:
--
-- 1. `telegram_binding_events.event_class` grows five values for the digest
--    cadences ADR 0058 names: three for the tenant operations audience
--    (15-minute live counts, half-day and day-close ADR 0043 summaries) and two
--    for the platform audience (half-day and day platform totals).
-- 2. `telegram_bindings.audience` and `telegram_pending_links.audience` grow
--    from the single literal 'OPERATIONS' to an explicit two-value list
--    including 'PLATFORM'. This does not change what a binding row is: a
--    PLATFORM-audience binding is still created under one `integration.bindings`
--    row and one tenant_id, because ADR 0026 bindings are tenant-scoped
--    end to end and re-deriving that model for one control-plane audience is a
--    larger, separately-decided change. What PLATFORM means in practice is that
--    the row lives under the platform's own operating tenant and its digest
--    content is queried across every tenant rather than filtered to its own —
--    a scheduling and query-scope decision, not a schema one.
ALTER TABLE integration.telegram_binding_events
    DROP CONSTRAINT ck_telegram_binding_event_class;

ALTER TABLE integration.telegram_binding_events
    ADD CONSTRAINT ck_telegram_binding_event_class CHECK (event_class IN (
        'ORDER_CONFIRMED', 'ORDER_REJECTED', 'ORDER_APPROVAL_DEADLINE_WARNING',
        'DIGEST_15M', 'DIGEST_HALF_DAY', 'DIGEST_DAY_CLOSE',
        'PLATFORM_DIGEST_HALF_DAY', 'PLATFORM_DIGEST_DAY_CLOSE'
    ));

ALTER TABLE integration.telegram_bindings
    DROP CONSTRAINT ck_telegram_binding_audience;

ALTER TABLE integration.telegram_bindings
    ADD CONSTRAINT ck_telegram_binding_audience CHECK (audience IN ('OPERATIONS', 'PLATFORM'));

ALTER TABLE integration.telegram_pending_links
    DROP CONSTRAINT ck_telegram_pending_link_audience;

ALTER TABLE integration.telegram_pending_links
    ADD CONSTRAINT ck_telegram_pending_link_audience CHECK (audience IN ('OPERATIONS', 'PLATFORM'));
