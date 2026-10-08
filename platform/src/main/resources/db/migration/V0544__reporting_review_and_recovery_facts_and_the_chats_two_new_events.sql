-- ADR 0159 / ADR 0043: reviews and service recovery become reporting facts, and an operations chat
-- may subscribe to the two case events.
--
-- Three facts, built at day close through `reviews.api.ReviewFactSource` and `RecoveryFactSource`
-- and never by a report reading `reviews` tables (ADR 0023). The customer is the ADR 0029 keyed
-- hash and never an account id, which is the honest limit ADR 0140 states for promotions too: a
-- manager cannot open the customer from a fact row. No fact carries a comment, a note, a tag's
-- label or a courier: `COURIER` is a tag category counted per location and nothing finer (ADR 0159,
-- open input 8).
--
-- All three are derived and rebuildable from the review and the case, so DELETE is granted: the day
-- close clears and rewrites them like every other fact.

-- One row per review. The average rating counts EVERY review of a brand -- consented or not,
-- hidden comment or not -- because a rating is not words (ADR 0159, Decision 5), so no column here
-- looks at the publication overlay.
CREATE TABLE reporting.fact_review (
    tenant_id uuid NOT NULL,
    review_id uuid NOT NULL,
    business_date date NOT NULL,
    boundary_version integer NOT NULL,
    metric_calculation_version integer NOT NULL,

    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    order_id uuid NOT NULL,
    customer_subject_hash varchar(128),

    rating smallint NOT NULL,
    has_comment boolean NOT NULL,
    tag_count integer NOT NULL DEFAULT 0,

    built_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_fact_review PRIMARY KEY (tenant_id, review_id),
    CONSTRAINT ck_fact_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_fact_review_tag_count CHECK (tag_count BETWEEN 0 AND 5)
);

CREATE INDEX ix_fact_review_brand ON reporting.fact_review (tenant_id, brand_id, business_date);
CREATE INDEX ix_fact_review_location ON reporting.fact_review (tenant_id, location_id, business_date);

COMMENT ON TABLE reporting.fact_review IS
    'ADR 0159, ADR 0043. Derived and rebuildable from reviews.order_reviews through ReviewFactSource -- see DayCloseService. One row per review; the average counts every review of the brand.';

-- One row per (review, tag): the "which part is getting worse" view is a count of these by category
-- and sentiment per location and day.
CREATE TABLE reporting.fact_review_tag (
    tenant_id uuid NOT NULL,
    review_id uuid NOT NULL,
    tag_id uuid NOT NULL,
    business_date date NOT NULL,
    boundary_version integer NOT NULL,
    metric_calculation_version integer NOT NULL,

    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    category varchar(16) NOT NULL,
    sentiment varchar(8) NOT NULL,

    built_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_fact_review_tag PRIMARY KEY (tenant_id, review_id, tag_id),
    CONSTRAINT ck_fact_review_tag_category CHECK (
        category IN ('FOOD', 'SERVICE', 'COURIER', 'DELIVERY_TIME', 'OTHER')),
    CONSTRAINT ck_fact_review_tag_sentiment CHECK (sentiment IN ('POSITIVE', 'NEGATIVE'))
);

CREATE INDEX ix_fact_review_tag_day ON reporting.fact_review_tag (tenant_id, brand_id, business_date, category, sentiment);

-- One row per case. A case counts as opened on its opening date and closed on its closing date, as
-- a refund lands on its own date, so a row carries both and is rewritten, not re-filed, when a
-- later close sees the case finish. remedy_money_minor is read from the amount and currency copied
-- onto the compensation link when it was attached; the source reads no payments table.
CREATE TABLE reporting.fact_recovery_case (
    tenant_id uuid NOT NULL,
    case_id uuid NOT NULL,
    opened_business_date date NOT NULL,
    closed_business_date date,
    boundary_version integer NOT NULL,
    metric_calculation_version integer NOT NULL,

    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    order_id uuid NOT NULL,
    review_id uuid NOT NULL,
    rating smallint NOT NULL,

    state varchar(18) NOT NULL,
    outcome_code varchar(24),
    seconds_to_first_response bigint,
    seconds_to_close bigint,
    first_response_breached boolean NOT NULL DEFAULT false,
    resolution_breached boolean NOT NULL DEFAULT false,

    compensation_count integer NOT NULL DEFAULT 0,
    remedy_money_minor bigint NOT NULL DEFAULT 0,
    currency char(3),

    built_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_fact_recovery_case PRIMARY KEY (tenant_id, case_id),
    CONSTRAINT ck_fact_recovery_case_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_fact_recovery_case_state CHECK (
        state IN ('NEW', 'IN_PROGRESS', 'RESOLVED', 'CLOSED_UNRESOLVED')),
    CONSTRAINT ck_fact_recovery_case_closed CHECK (
        (closed_business_date IS NOT NULL) = (state IN ('RESOLVED', 'CLOSED_UNRESOLVED'))),
    CONSTRAINT ck_fact_recovery_case_money CHECK (
        remedy_money_minor >= 0 AND (remedy_money_minor = 0 OR currency IS NOT NULL)),
    CONSTRAINT ck_fact_recovery_case_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_fact_recovery_case_compensation CHECK (compensation_count >= 0)
);

CREATE INDEX ix_fact_recovery_case_opened
    ON reporting.fact_recovery_case (tenant_id, brand_id, opened_business_date);
CREATE INDEX ix_fact_recovery_case_closed
    ON reporting.fact_recovery_case (tenant_id, brand_id, closed_business_date)
    WHERE closed_business_date IS NOT NULL;

COMMENT ON TABLE reporting.fact_recovery_case IS
    'ADR 0159, ADR 0043. Derived and rebuildable from reviews.recovery_cases through RecoveryFactSource. Opened on its opening date, closed on its closing date; remedy money is the amount copied onto the compensation link at attach, never re-read from payments.';

-- The average rating is a number of stars, which no unit the registry knew could say honestly. The
-- list is restated from the LIVE constraint (V0388's, which widened V0031's), never from an older
-- copy, with the one new value.
ALTER TABLE reporting.metric_definitions
    DROP CONSTRAINT ck_metric_definition_unit,
    ADD CONSTRAINT ck_metric_definition_unit CHECK (unit IN (
        'MONEY_SOM', 'COUNT', 'SECONDS', 'MINUTES', 'BASIS_POINTS', 'METERS', 'STARS'));

GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.fact_review TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.fact_review_tag TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON reporting.fact_recovery_case TO horecaos_application;

-- ADR 0058 / ADR 0159, Decision 10: an operations chat may hear that a case opened or breached a
-- clock. Off unless the policy's alertOnOpen / alertOnBreach is set -- the subscription alone does
-- nothing -- but a chat that wants it has to be able to subscribe.
--
-- Restated from the LIVE constraint (V0488's list, which widened V0114's), never from an older
-- copy: a bare DROP/ADD silently drops every value the previous migration knew about if the full
-- list is not carried forward.
ALTER TABLE integration.telegram_binding_events DROP CONSTRAINT ck_telegram_binding_event_class;
ALTER TABLE integration.telegram_binding_events
    ADD CONSTRAINT ck_telegram_binding_event_class CHECK (
        event_class IN (
            'ORDER_CONFIRMED', 'ORDER_REJECTED', 'ORDER_APPROVAL_DEADLINE_WARNING',
            'ORDER_AWAITING_APPROVAL',
            'DIGEST_15M', 'DIGEST_HALF_DAY', 'DIGEST_DAY_CLOSE',
            'PLATFORM_DIGEST_HALF_DAY', 'PLATFORM_DIGEST_DAY_CLOSE',
            'PAYMENT_ATTEMPT_FAILED', 'PAYMENT_ATTEMPT_NEEDS_OPERATOR',
            'FISCAL_DOCUMENT_BLOCKED',
            'ITEM_86D',
            'DEAD_LETTER_RECORDED', 'POS_EXPORT_AWAITING_OPERATOR',
            'CAMPAIGN_BLOCK_RATE_PAUSED',
            'MARKETPLACE_CHANNEL_STALE',
            'RECOVERY_CASE_OPENED', 'RECOVERY_CASE_SLA_BREACHED'));
