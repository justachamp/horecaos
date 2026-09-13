-- ADR 0043's own physical model for reporting.fact_order lists
-- `operator_principal_id null` — V0031 did not implement it, so /statistics/staff
-- has had nothing to join to (T12).
--
-- ordering.orders already carries who touched an order (V0029's
-- created_by_actor_type/id and accepted_by_actor_type/id); the close job simply
-- never copied either into the fact. This migration adds the column the close
-- job now fills; the write side lives in Java
-- (reporting.application.OperatorAttribution, called from DayCloseService).
--
-- Nullable and typed as varchar(255), matching every other "who did this"
-- column reporting already carries (voice.call_events.operator_principal_id,
-- V0146/V0149) rather than uuid: a Keycloak subject is a string here, not a
-- foreign key, and the value is not always a subject at all — see below.
--
-- No `operator_principal_kind` column. An order with no human actor at either
-- end (a storefront or bot checkout nobody on staff touched) is credited to a
-- pseudo-operator named after its own channel — "channel:BOT", "channel:WEBSITE"
-- — so the bot and the website compare against people on the same leaderboard
-- rather than disappearing from it. The "channel:" prefix is the only "kind" a
-- reader needs: it is self-describing, so a second column would carry no
-- information a `LIKE 'channel:%'` cannot already answer. See
-- OperatorAttribution's own doc for the precedence between created_by and
-- accepted_by, and for why a bare staff subject renders labelled rather than as
-- an unexplained UUID until the staff-identity ADR lands.
--
-- Historical rows already closed are left null by this ALTER: fact_order is
-- derived and rebuildable (its own table comment says so), so the honest fix
-- for old days is a recut, not a backfill migration guessing at attribution
-- this column never recorded.
ALTER TABLE reporting.fact_order
    ADD COLUMN operator_principal_id varchar(255);

COMMENT ON COLUMN reporting.fact_order.operator_principal_id IS
    'ADR 0043. Who took the order: a staff subject id when created_by/accepted_by named a USER, otherwise "channel:<channel_code>" as a pseudo-operator. Null on a row closed before this migration and never recut.';

-- Every reporting query carries the tenant predicate; this index leads with it
-- like every other fact_order index in V0031. Partial, because the leaderboard
-- and the operator-product read both filter operator_principal_id IS NOT NULL
-- (an order the close job could not attribute to anyone contributes nothing to
-- either) and the pilot's null rows (pre-migration history) are not worth
-- carrying in the index.
CREATE INDEX ix_fact_order_operator_day
    ON reporting.fact_order (tenant_id, business_date, operator_principal_id)
    WHERE operator_principal_id IS NOT NULL;

-- No GRANT block: fact_order already has one from V0031, and a GRANT is
-- per-table, not per-column.
