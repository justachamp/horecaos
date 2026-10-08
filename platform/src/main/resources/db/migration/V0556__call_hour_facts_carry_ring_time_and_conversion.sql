-- ADR 0175 (row 7.5b): the two telephony KPIs that V0149 had no column for.
--
--   * Answer speed ("ring time"): seconds from a call's OFFERED event to its
--     first ANSWERED event, where a call is one (installation_id,
--     provider_call_id) inside a tenant.
--   * Call-to-order conversion: the share of answered calls for which at least
--     one order exists whose write-once source_call_id is the call's OFFERED
--     event id (V0148).
--
-- Both are written by DayCloseService.close in the same transaction as every
-- other fact and are rebuilt by clearDay like every other row; there is no
-- read-time join and no parallel store (ADR 0043, ADR 0064). Counts and sums
-- are stored, ratios are computed where they are read, because a ratio cannot
-- be summed across hours, operators and days. The columns are all nullable:
-- NULL means "this row was closed before the measure existed and has not been
-- rebuilt", which the console shows as "not measured" and never as zero.
-- V0149 is not edited; its table-level grants already cover added columns.
ALTER TABLE reporting.fact_call_hour
    ADD COLUMN answered_call_count integer,
    ADD COLUMN ring_seconds_total  bigint,
    ADD COLUMN ring_sample_count   integer,
    ADD COLUMN ring_seconds_max    integer,
    ADD COLUMN converted_count     integer;

ALTER TABLE reporting.fact_call_hour ADD CONSTRAINT ck_fact_call_hour_kpi CHECK (
    ((answered_call_count IS NULL) = (ring_seconds_total IS NULL))
    AND ((answered_call_count IS NULL) = (ring_sample_count IS NULL))
    AND ((answered_call_count IS NULL) = (converted_count IS NULL))
    AND (answered_call_count IS NULL OR (
            answered_call_count >= 0
        AND ring_seconds_total >= 0
        AND ring_sample_count BETWEEN 0 AND answered_call_count
        AND converted_count BETWEEN 0 AND answered_call_count
        AND ((ring_sample_count > 0) = (ring_seconds_max IS NOT NULL))
        AND (ring_seconds_max IS NULL OR ring_seconds_max >= 0))));

COMMENT ON COLUMN reporting.fact_call_hour.answered_call_count IS
    'ADR 0175: distinct calls first answered in this hour by this (resolved) operator. NULL = closed before the measure existed.';
COMMENT ON COLUMN reporting.fact_call_hour.ring_seconds_total IS
    'ADR 0175: sum of whole seconds from OFFERED to the first ANSWERED, over the sampled calls only.';
COMMENT ON COLUMN reporting.fact_call_hour.ring_sample_count IS
    'ADR 0175: calls with a sane OFFERED -> first ANSWERED pair (interval between zero and voice.ring_time_ceiling_seconds).';
COMMENT ON COLUMN reporting.fact_call_hour.ring_seconds_max IS
    'ADR 0175: the longest sampled interval; NULL exactly when ring_sample_count is zero.';
COMMENT ON COLUMN reporting.fact_call_hour.converted_count IS
    'ADR 0175: answered calls with at least one order, in any status, whose source_call_id is the call''s OFFERED event.';

-- ADR 0175 correction of a label, not of a number: this column has always held the
-- call's length (first event to ENDED, ring and queue time inside it), because
-- VoiceEventInboundPort.ingest measures an ENDED event against the earlier of the
-- call's OFFERED and ANSWERED events. Renaming a stored column and its API field is
-- a definition change ADR 0043 reserves for a new version, so the name and the
-- values stay and this comment says what they are.
COMMENT ON COLUMN reporting.fact_call_hour.talk_duration_seconds IS
    'ADR 0175: call length, not talk time -- seconds from the call''s first event to its ENDED event, ring and queue time included. The name is historic; a true talk time (end minus answer) waits for a provider that reports it.';

-- The conversion lookup: "is there an order whose source_call_id is this offered event".
-- Phone orders are a minority, so the partial index is tiny.
CREATE INDEX ix_orders_source_call
    ON ordering.orders (tenant_id, source_call_id) WHERE source_call_id IS NOT NULL;
