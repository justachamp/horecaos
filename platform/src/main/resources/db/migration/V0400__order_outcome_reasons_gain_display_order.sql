-- Gap-map row 10.10a: cancellation and completion reasons could not be
-- reordered. The Settings screen and every reason-picking surface (cancel
-- dialogs, the storefront reason list where exposed) render
-- `JdbcOutcomeReasonStore.list`'s own `ORDER BY system_category,
-- internal_name` -- an alphabetic accident, not an operator's deliberate
-- ranking of which reasons their own dispatchers reach for first.
--
-- A plain integer column, the same shape `ordering.order_reject_reasons.
-- display_order` (V0119) already uses for its sibling table -- ties broken
-- by `internal_name` wherever this column is read, so two reasons that
-- happen to share a value (every row does, at first) still render in a
-- stable order rather than database-scan order.
--
-- Scoped per (tenant, kind): a tenant's cancellation-reason ranking and its
-- completion-reason ranking are two independent lists an operator reorders
-- separately, exactly the two lists `JdbcOutcomeReasonStore.list`'s own
-- `kind` parameter already separates.
--
-- Backfilled from the existing alphabetic order so a tenant that has never
-- touched this screen sees no visible reshuffle the moment this migration
-- runs -- only a starting point their next drag-reorder changes.
--
-- No new GRANT: this widens a table `V0029` already granted
-- (SELECT, INSERT, UPDATE, DELETE) to horecaos_application, and Postgres
-- GRANT is table-scoped, not per-column.
ALTER TABLE ordering.order_outcome_reasons
    ADD COLUMN display_order integer NOT NULL DEFAULT 0;

ALTER TABLE ordering.order_outcome_reasons
    ADD CONSTRAINT ck_outcome_reason_display_order CHECK (display_order >= 0);

COMMENT ON COLUMN ordering.order_outcome_reasons.display_order IS
    'Gap-map row 10.10a. Scoped per (tenant_id, kind); ties (including every row before its first reorder) break on internal_name wherever this is read. Reordered wholesale by OrderOutcomeReasonController.reorder, the same "the caller sends every id it means to keep" contract q-schedule-grid''s own whole-set writes use elsewhere in this codebase.';

WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY tenant_id, kind
               ORDER BY system_category, internal_name, id
           ) - 1 AS ordinal
    FROM ordering.order_outcome_reasons
)
UPDATE ordering.order_outcome_reasons AS reasons
SET display_order = ranked.ordinal
FROM ranked
WHERE reasons.id = ranked.id;
