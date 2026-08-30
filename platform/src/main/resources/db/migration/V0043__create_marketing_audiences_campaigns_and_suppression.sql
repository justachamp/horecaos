-- ADR 0044: the campaign side of messaging — who is targeted, on what basis, at
-- what cost, and why somebody was left out.
--
-- ADR 0020 owns delivery and already names MARKETING as a class. Nothing owned
-- the other half: the audience, the campaign, the approval, the ceiling, and the
-- evidence that a send was lawful. This migration builds that half and nothing
-- else. Not one statement here writes to `notifications`, `customer`, or
-- `pricing`; the marketing module holds no phone number, no email address, no
-- push token, and no chat id, because ADR 0020 owns the endpoint and a second
-- copy would double the erasure surface for a fact this module never needs.
--
-- ---------------------------------------------------------------------------
-- Why there is a projection at all
-- ---------------------------------------------------------------------------
--
-- The RFM filters a marketer expects — days since last order, order count, total
-- spend, average check — are aggregates over every order a customer ever placed.
-- Evaluating them live, for every customer in a tenant, each time a slider moves,
-- is a scan of `ordering.orders` on the same database that is taking orders
-- during a dinner rush. `marketing.customer_metrics` is that scan, done once and
-- kept, and it is the only thing an audience predicate is allowed to read.
--
-- ---------------------------------------------------------------------------
-- What is deliberately not created here
-- ---------------------------------------------------------------------------
--
-- ADR 0044 also specifies merchandising slots, attribution links, referral edges,
-- reviews, review tags, trigger firings, and coded benefit grants on
-- `pricing.benefit_grants`. None of them is created below, for the reason V0031
-- and V0039 both wrote down: a table whose producer does not exist is not a head
-- start, it is an empty table that reads to the next author as though the
-- projection is broken. Each of those tables arrives with the code that fills it.
-- The coded grant additionally cannot be built here at all — `pricing` has no
-- `benefit_grants` table in this database yet, and inventing one from the
-- marketing side would be this module minting the benefit that ADR 0044 is
-- explicit it may only reference.

CREATE SCHEMA marketing;

COMMENT ON SCHEMA marketing IS
    'ADR 0044. Audiences, campaigns, suppression, and the per-customer metric projection they are evaluated against. Owns no contact value and sends no message: every outbound message is an ADR 0020 intent of class MARKETING.';


-- ---------------------------------------------------------------------------
-- 1. Engagement policy: quiet hours, the frequency cap, and the segment price
-- ---------------------------------------------------------------------------
--
-- ADR 0044 sets provisional values that legal and product will confirm, and is
-- explicit that they are enforced from day one rather than left blank, because an
-- unset cap is an infinite cap and the first production send would run without
-- one. The platform defaults live in application configuration; this table holds
-- only a tenant's override of them.
--
-- The CHECKs below bound every column to the provisional platform default in the
-- tightening direction. That duplicates a number that also exists in
-- configuration, and it is duplicated on purpose: the service rejects a loosening
-- override with an explanation a marketer can read, and the constraint is the
-- backstop for anything that reaches this table another way. Both numbers also
-- protect the sending reputation of an aggregator identity HorecaOS shares across
-- tenants, so one tenant loosening the cap degrades delivery for every other
-- tenant on the same sender — which is why "the tenant's own customer
-- relationship" is not a sufficient argument for letting them.
--
-- If counsel changes a value, this file does not change: a migration adds the new
-- bound. A forward-only history of what the limit was on any given day is worth
-- more than an UPDATE that erases it.
CREATE TABLE marketing.engagement_policies (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- The cap is per brand, not per tenant. Two brands under one tenant are two
    -- businesses to the customer, and a tenant-wide cap would let one brand's
    -- campaign silence another's.
    brand_id uuid NOT NULL,

    -- Null means "no override at this level"; the platform default applies.
    quiet_hours_start time,
    quiet_hours_end time,

    -- Uzbekistan is UTC+5 with no daylight saving, so a brand timezone is a fixed
    -- offset and a scheduled send does not shift twice a year. Stored anyway
    -- rather than assumed, because the boundary is meaningless without it.
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Tashkent',

    marketing_messages_per_7d integer,
    marketing_messages_per_30d integer,

    -- Integer minor units. For UZS a minor unit is a whole som, so this is a som
    -- price and never a hundredth of one; a formatter that asks ISO 4217 for the
    -- decimal places of UZS divides by 100 and shows a customer the wrong price.
    sms_price_per_segment_minor bigint,
    currency char(3),

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_engagement_policy_brand UNIQUE (tenant_id, brand_id),
    CONSTRAINT fk_engagement_policy_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),

    -- Pair completeness stated as an equality of nullity. The disjunctive form
    -- `(a IS NULL AND b IS NULL) OR (...)` leaves a three-valued-logic hole that
    -- passes when exactly one side is NULL.
    CONSTRAINT ck_engagement_quiet_hours_pair CHECK (
        (quiet_hours_start IS NULL) = (quiet_hours_end IS NULL)
    ),
    CONSTRAINT ck_engagement_price_pair CHECK (
        (sms_price_per_segment_minor IS NULL) = (currency IS NULL)
    ),
    CONSTRAINT ck_engagement_price_nonnegative CHECK (
        sms_price_per_segment_minor IS NULL OR sms_price_per_segment_minor >= 0
    ),

    -- Tighten only. The open window is 10:00 to 21:00, so an override may start
    -- its quiet period no later than 21:00 and end it no earlier than 10:00.
    CONSTRAINT ck_engagement_quiet_hours_tighten_only CHECK (
        quiet_hours_start IS NULL
        OR (quiet_hours_start <= TIME '21:00' AND quiet_hours_end >= TIME '10:00')
    ),
    CONSTRAINT ck_engagement_cap_tighten_only CHECK (
        (marketing_messages_per_7d IS NULL
            OR (marketing_messages_per_7d >= 0 AND marketing_messages_per_7d <= 3))
        AND (marketing_messages_per_30d IS NULL
            OR (marketing_messages_per_30d >= 0 AND marketing_messages_per_30d <= 8))
    )
);

COMMENT ON TABLE marketing.engagement_policies IS
    'ADR 0044. A brand''s override of the platform quiet hours and frequency cap. Overrides may tighten and never loosen: both numbers protect a sending reputation shared across tenants.';
COMMENT ON COLUMN marketing.engagement_policies.quiet_hours_start IS
    'ADR 0044. Start of the closed window in the brand timezone. Platform default 21:00. A message becoming eligible inside the window is held to the next open boundary, never dropped.';
COMMENT ON COLUMN marketing.engagement_policies.marketing_messages_per_7d IS
    'ADR 0044. Counted across all channels together, per customer per brand. The customer experiences one brand rather than three transports, and three SMS plus three pushes plus three Telegram messages in a week is nine interruptions.';
COMMENT ON COLUMN marketing.engagement_policies.sms_price_per_segment_minor IS
    'ADR 0044. Integer minor units per SMS segment, not per message: Latin copy encodes GSM-7 at 153 characters per concatenated segment and Cyrillic falls to UCS-2 at 67, so one body is two segments in uz-Latn and three in ru.';


-- ---------------------------------------------------------------------------
-- 2. The customer metric projection
-- ---------------------------------------------------------------------------
--
-- One row per brand profile. Eventually consistent with a stated staleness
-- budget; an audience needing transactional freshness is a trigger, not a
-- campaign.
--
-- No contact value, no date of birth, no free text. `birth_month_day` is a
-- derived selector rather than a copy of a birth date: duplicating the full date
-- would double the erasure surface for a fact nobody needs, and a birthday
-- campaign only ever asks "is it today".
CREATE TABLE marketing.customer_metrics (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,

    order_count integer NOT NULL DEFAULT 0,
    completed_order_count integer NOT NULL DEFAULT 0,
    cancelled_order_count integer NOT NULL DEFAULT 0,

    -- Both are carried, rather than one signed number, because ADR 0043's
    -- registry has not settled the signed treatment of cancelled and refunded
    -- orders. A registry revision restates this projection; it does not reshape
    -- it.
    gross_spend_minor bigint NOT NULL DEFAULT 0,
    net_spend_minor bigint NOT NULL DEFAULT 0,
    average_check_minor bigint NOT NULL DEFAULT 0,

    first_order_at timestamptz,
    last_order_at timestamptz,

    -- Stored rather than computed at query time so an index can serve a recency
    -- band. Recomputed by the sweep, which is why a stale value is drift the
    -- sweep reports rather than a wrong answer nobody can see.
    days_since_last_order integer,

    registered_at timestamptz NOT NULL,

    acquisition_channel varchar(32),
    acquisition_link_id uuid,
    preferred_locale varchar(16),

    -- Month and day only, as text 'MM-DD'. Not a date: a date needs a year, and
    -- the year is the part that identifies.
    birth_month_day char(5),

    last_marketing_message_at timestamptz,

    -- A cached copy of the rolling counts, refreshed by the sweep and used for
    -- estimation only. The authority is marketing.marketing_sends, which the
    -- per-recipient check reads at send time: a cached counter is right for
    -- showing an approver a number and wrong for deciding whether one more
    -- message is lawful.
    marketing_messages_7d integer NOT NULL DEFAULT 0,
    marketing_messages_30d integer NOT NULL DEFAULT 0,

    -- Which ADR 0043 definitions produced these numbers. Without it, "average
    -- check" in an audience and "average check" on the dashboard are two
    -- implementations that will disagree, and a merchant noticing that costs more
    -- credibility than the feature earns.
    metric_definition_version integer NOT NULL,

    -- The last order fact folded in. A campaign records the watermark it
    -- evaluated against, so "why did she not get it" is answerable afterwards.
    watermark_event_at timestamptz,

    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_customer_metrics PRIMARY KEY (tenant_id, brand_id, customer_account_id),
    CONSTRAINT fk_customer_metrics_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT fk_customer_metrics_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT ck_customer_metrics_locale CHECK (
        preferred_locale IS NULL OR preferred_locale IN ('ru', 'uz-Latn', 'en')
    ),
    CONSTRAINT ck_customer_metrics_birth_month_day CHECK (
        birth_month_day IS NULL OR birth_month_day ~ '^(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$'
    ),
    CONSTRAINT ck_customer_metrics_counts CHECK (
        order_count >= 0 AND completed_order_count >= 0 AND cancelled_order_count >= 0
        AND completed_order_count + cancelled_order_count <= order_count
    ),
    CONSTRAINT ck_customer_metrics_spend CHECK (
        gross_spend_minor >= 0 AND average_check_minor >= 0
    ),
    -- An account with no orders has neither boundary; one with orders has both.
    CONSTRAINT ck_customer_metrics_order_window CHECK (
        (first_order_at IS NULL) = (last_order_at IS NULL)
    ),
    CONSTRAINT ck_customer_metrics_recency CHECK (
        (last_order_at IS NULL) = (days_since_last_order IS NULL)
    )
);

COMMENT ON TABLE marketing.customer_metrics IS
    'ADR 0044. One row per brand profile, maintained by a recompute sweep from ordering as source. The only table an audience predicate may read.';
COMMENT ON COLUMN marketing.customer_metrics.birth_month_day IS
    'ADR 0044 and ADR 0029. A derived selector as MM-DD, never a copy of the date of birth. The full date stays a PERSONAL encrypted field on the account, and the year is the part that identifies.';
COMMENT ON COLUMN marketing.customer_metrics.metric_definition_version IS
    'ADR 0043. The metric registry version these numbers were computed under, so a slider and a dashboard cannot silently mean two different things.';
COMMENT ON COLUMN marketing.customer_metrics.marketing_messages_7d IS
    'ADR 0044. A cached rolling count for estimation. Authoritative capping reads marketing.marketing_sends at send time.';

-- Serves the recency, order-count, and spend bands. Every audience query carries
-- the tenant and the brand as its leading predicate, so they lead the index.
CREATE INDEX ix_customer_metrics_recency
    ON marketing.customer_metrics (tenant_id, brand_id, days_since_last_order);
CREATE INDEX ix_customer_metrics_orders
    ON marketing.customer_metrics (tenant_id, brand_id, completed_order_count);
CREATE INDEX ix_customer_metrics_spend
    ON marketing.customer_metrics (tenant_id, brand_id, net_spend_minor);
-- A birthday campaign is a daily equality on five characters. ADR 0044 accepts
-- that an encrypted date of birth cannot be indexed; the derived selector can.
CREATE INDEX ix_customer_metrics_birthday
    ON marketing.customer_metrics (tenant_id, brand_id, birth_month_day)
    WHERE birth_month_day IS NOT NULL;


-- Drift is reported, never silently corrected. A projection that quietly rewrites
-- itself to match a recomputation hides the bug that made it disagree, and the
-- first person to notice is a merchant comparing a campaign against a report.
CREATE TABLE marketing.metric_drift_observations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,
    observed_at timestamptz NOT NULL DEFAULT now(),
    metric_name varchar(48) NOT NULL,

    -- Both sides as text, because the metrics compared are a mix of counts,
    -- minor-unit amounts, and timestamps, and a numeric column here would force
    -- a lossy cast on the one that matters.
    projected_value varchar(64),
    recomputed_value varchar(64),

    metric_definition_version integer NOT NULL,

    CONSTRAINT fk_metric_drift_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT ck_metric_drift_differs CHECK (
        projected_value IS DISTINCT FROM recomputed_value
    )
);

COMMENT ON TABLE marketing.metric_drift_observations IS
    'ADR 0044. What the nightly sweep found and did not fix. A row here is a bug report about the projection, not a correction of it.';

CREATE INDEX ix_metric_drift_recent
    ON marketing.metric_drift_observations (tenant_id, brand_id, observed_at DESC);


-- ---------------------------------------------------------------------------
-- 3. Audiences and the closed predicate catalogue
-- ---------------------------------------------------------------------------
--
-- Predicates are a closed catalogue, not SQL. ADR 0018's argument against
-- scriptable price rules applies, plus one more: a marketer with arbitrary query
-- access over the customer tables has arbitrary read of the tenant's base. The
-- catalogue is small on purpose and extending it is a schema and code change.
CREATE TABLE marketing.audiences (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    name varchar(120) NOT NULL,
    description varchar(500),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',

    -- Incremented whenever a predicate set changes. A snapshot records the
    -- version it evaluated, so a dispute about who was targeted is answered
    -- against the definition of the day rather than today's.
    definition_version integer NOT NULL DEFAULT 1,

    created_by uuid NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_audience_name UNIQUE (tenant_id, brand_id, name),
    CONSTRAINT uq_audience_identity UNIQUE (id, tenant_id),
    CONSTRAINT fk_audience_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT ck_audience_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

COMMENT ON TABLE marketing.audiences IS
    'ADR 0044. A saved, typed, versioned predicate set. Evaluated inside the platform and nowhere else: no definition, snapshot, or member list is transmitted to an advertising platform, a data broker, or an analytics vendor.';


CREATE TABLE marketing.audience_predicates (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    audience_id uuid NOT NULL,
    definition_version integer NOT NULL,
    sequence integer NOT NULL,

    predicate_type varchar(32) NOT NULL,
    operator varchar(16) NOT NULL,

    -- One typed slot per shape, all nullable, with the type deciding which pair
    -- is populated. A single jsonb `value` was considered and rejected: it would
    -- put the shape validation entirely in application code, and the CHECKs below
    -- are what stop a malformed predicate from being stored at all.
    numeric_low bigint,
    numeric_high bigint,
    date_low date,
    date_high date,
    text_values text[],
    uuid_value uuid,

    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_audience_predicate_sequence
        UNIQUE (audience_id, definition_version, sequence),
    CONSTRAINT fk_audience_predicate_audience FOREIGN KEY (audience_id, tenant_id)
        REFERENCES marketing.audiences (id, tenant_id),

    -- The catalogue. A predicate may reference the projection's columns, the
    -- account's lifecycle state, consent and suppression facts, and membership of
    -- another audience. It may not reference free text of any kind, a raw date of
    -- birth, any contact value, payment instrument data, ADR 0043 behavioural
    -- telemetry, or the content of the legacy search_histories table. A predicate
    -- over what somebody searched for is a behavioural profile, and this
    -- catalogue is deliberately not one.
    CONSTRAINT ck_audience_predicate_type CHECK (predicate_type IN (
        'RECENCY_DAYS', 'ORDER_COUNT', 'COMPLETED_ORDER_COUNT', 'NET_SPEND_MINOR',
        'AVERAGE_CHECK_MINOR', 'ACQUISITION_CHANNEL', 'REGISTERED_BETWEEN',
        'BIRTHDAY_WITHIN_DAYS', 'PREFERRED_LOCALE', 'AUDIENCE_MEMBERSHIP')),
    CONSTRAINT ck_audience_predicate_operator CHECK (operator IN (
        'AT_LEAST', 'AT_MOST', 'BETWEEN', 'IN', 'NOT_IN')),

    -- Pair completeness, stated as an equality of nullity so a half-filled range
    -- cannot be stored. The disjunctive form leaves a hole that passes when
    -- exactly one bound is NULL.
    CONSTRAINT ck_audience_predicate_date_range CHECK (
        (date_low IS NULL) = (date_high IS NULL)
        AND (date_low IS NULL OR date_low <= date_high)
    ),
    CONSTRAINT ck_audience_predicate_numeric_range CHECK (
        operator <> 'BETWEEN' OR numeric_low IS NULL
            OR (numeric_high IS NOT NULL AND numeric_low <= numeric_high)
    ),
    CONSTRAINT ck_audience_predicate_membership CHECK (
        (predicate_type = 'AUDIENCE_MEMBERSHIP') = (uuid_value IS NOT NULL)
    ),
    -- Each type's shape, in one place. A predicate whose operator does not fit
    -- its type, or whose value slot is empty, is not a predicate that means
    -- something odd — it is one the evaluator cannot translate into SQL at all,
    -- and the storage layer is where that has to be refused.
    CONSTRAINT ck_audience_predicate_shape CHECK (
        CASE predicate_type
            WHEN 'REGISTERED_BETWEEN'
                THEN operator = 'BETWEEN' AND date_low IS NOT NULL
            WHEN 'ACQUISITION_CHANNEL'
                THEN operator IN ('IN', 'NOT_IN') AND text_values IS NOT NULL
            WHEN 'PREFERRED_LOCALE'
                THEN operator IN ('IN', 'NOT_IN') AND text_values IS NOT NULL
            WHEN 'AUDIENCE_MEMBERSHIP'
                THEN operator IN ('IN', 'NOT_IN')
            ELSE operator IN ('AT_LEAST', 'AT_MOST', 'BETWEEN')
                AND numeric_low IS NOT NULL
        END
    ),
    CONSTRAINT ck_audience_predicate_text CHECK (
        text_values IS NULL OR array_length(text_values, 1) BETWEEN 1 AND 64
    )
);

COMMENT ON COLUMN marketing.audience_predicates.uuid_value IS
    'ADR 0044. The referenced audience, for AUDIENCE_MEMBERSHIP. Resolved against that audience''s latest completed snapshot rather than re-evaluated, which makes a cycle unrepresentable rather than merely unlikely.';


-- ---------------------------------------------------------------------------
-- 4. Suppression
-- ---------------------------------------------------------------------------
--
-- Suppression is a different fact from consent, and one customer can carry both.
-- Consent is legal permission; suppression is a deliverability or abuse fact.
-- Suppression outranks consent: a positive consent decision does not overcome a
-- hard bounce, and it must not overcome a complaint.
CREATE TABLE marketing.suppressions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- Null suppresses across every brand of the tenant. A customer who
    -- complained to a regulator did not complain about one brand's newsletter.
    brand_id uuid,

    customer_account_id uuid NOT NULL,

    -- Null suppresses every channel. A stated channel narrows it to one
    -- transport, which is what a bounced number means and a complaint does not.
    channel varchar(24),

    reason varchar(24) NOT NULL,

    -- Who applied it, and why in their own words. A suppression is a new way to
    -- wrongly silence a real customer, so the row has to say who did it.
    applied_by uuid,
    applied_by_type varchar(16) NOT NULL,
    stated_reason varchar(500),

    applied_at timestamptz NOT NULL DEFAULT now(),

    -- UNSUBSCRIBE never expires on its own: it records a person's refusal, and
    -- deleting it re-enables what they refused. HARD_BOUNCE and INVALID_NUMBER
    -- expire after twelve months, because numbers are reassigned in this market
    -- and a permanent block on a recycled number silences a different, willing
    -- person.
    expires_at timestamptz,

    -- Removing a suppression is capability-gated and audited: a marketer must not
    -- be able to clear their own bounce list to inflate reach. The row is closed
    -- rather than deleted, so the removal is itself evidence.
    lifted_at timestamptz,
    lifted_by uuid,
    lift_reason varchar(500),

    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_suppression_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT fk_suppression_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT ck_suppression_reason CHECK (reason IN (
        'UNSUBSCRIBE', 'HARD_BOUNCE', 'INVALID_NUMBER', 'COMPLAINT',
        'OPERATOR_BLOCK', 'PLATFORM_BLOCK')),
    CONSTRAINT ck_suppression_channel CHECK (
        channel IS NULL OR channel IN ('SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP')
    ),
    CONSTRAINT ck_suppression_actor_type CHECK (
        applied_by_type IN ('CUSTOMER', 'OPERATOR', 'PROVIDER', 'CONTROL_PLANE')
    ),
    -- A person acting needs an identity; a provider bounce and a customer's own
    -- unsubscribe link do not carry one.
    CONSTRAINT ck_suppression_operator_identified CHECK (
        applied_by_type <> 'OPERATOR' OR applied_by IS NOT NULL
    ),
    -- PLATFORM_BLOCK is settable only by the control plane. It is how HorecaOS stops
    -- a tenant messaging someone who complained to a regulator, and a tenant
    -- operator who could set it could also lift it.
    CONSTRAINT ck_suppression_platform_block_actor CHECK (
        reason <> 'PLATFORM_BLOCK' OR applied_by_type = 'CONTROL_PLANE'
    ),
    -- A lift is a fact with an actor and a reason, or it is not a lift.
    CONSTRAINT ck_suppression_lift_complete CHECK (
        (lifted_at IS NULL) = (lifted_by IS NULL)
        AND (lifted_at IS NULL) = (lift_reason IS NULL)
    )
);

COMMENT ON TABLE marketing.suppressions IS
    'ADR 0044. A deliverability or abuse fact, distinct from ADR 0015 consent and outranking it. Rows are lifted rather than deleted, because a removal is exactly the action somebody would want to hide.';

-- The send-time lookup: every active suppression for one account. Partial on
-- lifted_at so the index holds only rows that can still refuse a message.
CREATE INDEX ix_suppression_active
    ON marketing.suppressions (tenant_id, customer_account_id, brand_id, channel)
    WHERE lifted_at IS NULL;


-- ---------------------------------------------------------------------------
-- 5. The marketing send ledger
-- ---------------------------------------------------------------------------
--
-- The frequency cap is counted across all channels together, per customer per
-- brand, over a rolling window. A rolling window cannot be maintained by an
-- incrementing counter — nothing decrements it as the window slides — so the
-- authority is this ledger and the projection's counters are a cached read of it.
--
-- A trigger firing counts towards the cap; a transactional message does not. A
-- transactional message is not the customer's to refuse, and counting it would
-- let a busy ordering week suppress the marketing the customer did consent to.
-- Nothing transactional is ever written here.
CREATE TABLE marketing.marketing_sends (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,
    channel varchar(24) NOT NULL,

    source_type varchar(16) NOT NULL,
    source_id uuid NOT NULL,

    -- The ADR 0020 message this ledger row corresponds to. Nullable because the
    -- ledger row is written in the same transaction that reserves the recipient,
    -- before the intent exists.
    notification_id uuid,

    sent_at timestamptz NOT NULL,

    CONSTRAINT fk_marketing_send_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT fk_marketing_send_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT ck_marketing_send_channel CHECK (
        channel IN ('SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP')
    ),
    CONSTRAINT ck_marketing_send_source CHECK (source_type IN ('CAMPAIGN', 'TRIGGER')),
    -- One ledger row per source per customer. A replayed batch therefore cannot
    -- inflate somebody's usage against the cap and silence them for a week.
    CONSTRAINT uq_marketing_send_source UNIQUE (source_id, customer_account_id)
);

COMMENT ON TABLE marketing.marketing_sends IS
    'ADR 0044. The authority for the cross-channel frequency cap. One SMS, one push, and one Telegram message are three rows against the same customer, because the customer experiences one brand rather than three transports.';

CREATE INDEX ix_marketing_send_window
    ON marketing.marketing_sends (tenant_id, brand_id, customer_account_id, sent_at DESC);


-- ---------------------------------------------------------------------------
-- 6. Snapshots
-- ---------------------------------------------------------------------------
--
-- A send targets a materialised snapshot rather than a re-evaluated predicate.
-- Re-evaluating at send is cheaper and was rejected: the audience then changes
-- between approval and send, finance signs off on twelve thousand recipients and
-- thirty-eight thousand receive it because an import ran overnight, and cost
-- approval over a moving set is meaningless.
CREATE TABLE marketing.audience_snapshots (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    audience_id uuid NOT NULL,
    definition_version integer NOT NULL,

    -- Which channel and purpose the consent and suppression subtractions were
    -- evaluated for. A snapshot is not channel-neutral: the same predicate set
    -- yields a different reach for SMS and for push, and storing one number
    -- without the channel is how an approver is shown a reach that was never
    -- true for the send they approved.
    channel varchar(24) NOT NULL,
    consent_purpose varchar(64) NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'BUILDING',

    candidate_count integer NOT NULL DEFAULT 0,
    member_count integer NOT NULL DEFAULT 0,

    -- The projection watermark this evaluation ran against, so "why did she not
    -- get it" can be answered with what was known at the time.
    metric_watermark_at timestamptz,
    metric_definition_version integer NOT NULL,

    built_by uuid NOT NULL,
    built_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,

    -- ADR 0044 retention: member rows are deleted 24 months after the send and
    -- the header survives with its counts. Recorded on the row rather than
    -- derived, so a retention job does not have to reimplement the schedule.
    members_purged_at timestamptz,

    CONSTRAINT uq_audience_snapshot_identity UNIQUE (id, tenant_id),
    CONSTRAINT fk_audience_snapshot_audience FOREIGN KEY (audience_id, tenant_id)
        REFERENCES marketing.audiences (id, tenant_id),
    CONSTRAINT fk_audience_snapshot_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT ck_audience_snapshot_status CHECK (
        status IN ('BUILDING', 'READY', 'FAILED')
    ),
    CONSTRAINT ck_audience_snapshot_channel CHECK (
        channel IN ('SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP')
    ),
    CONSTRAINT ck_audience_snapshot_counts CHECK (
        candidate_count >= 0 AND member_count >= 0 AND member_count <= candidate_count
    ),
    CONSTRAINT ck_audience_snapshot_completion CHECK (
        (status = 'BUILDING') = (completed_at IS NULL)
    )
);

COMMENT ON TABLE marketing.audience_snapshots IS
    'ADR 0044. An immutable evaluation of one audience for one channel and purpose at one moment. What makes a send costable before it starts and explainable afterwards.';


-- Every candidate the predicates matched, whether or not they survived the
-- subtractions, with the reason when they did not.
--
-- Storing the excluded candidates costs rows. Not storing them was the
-- alternative, and it makes "why did this customer not get it" unanswerable for
-- exactly the people who need it answered: somebody excluded at snapshot build
-- never becomes a campaign recipient, so there would be no row about them
-- anywhere. The reasons are the evidence that a send was lawful.
CREATE TABLE marketing.audience_snapshot_members (
    snapshot_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,

    inclusion_status varchar(16) NOT NULL,
    exclusion_reason varchar(32),

    -- The member's metrics as evaluated, so a report can say what a recipient
    -- looked like when they were chosen rather than what they look like now.
    locale_at_evaluation varchar(16),
    completed_order_count_at_evaluation integer,
    net_spend_minor_at_evaluation bigint,
    days_since_last_order_at_evaluation integer,

    CONSTRAINT pk_audience_snapshot_member PRIMARY KEY (snapshot_id, customer_account_id),
    CONSTRAINT fk_audience_snapshot_member_snapshot FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES marketing.audience_snapshots (id, tenant_id),
    CONSTRAINT fk_audience_snapshot_member_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT ck_audience_snapshot_member_status CHECK (
        inclusion_status IN ('INCLUDED', 'EXCLUDED')
    ),
    -- Exactly the excluded rows carry a reason, and every one of them does.
    CONSTRAINT ck_audience_snapshot_member_reason CHECK (
        (inclusion_status = 'EXCLUDED') = (exclusion_reason IS NOT NULL)
    ),
    CONSTRAINT ck_audience_snapshot_member_exclusion_reason CHECK (
        exclusion_reason IS NULL OR exclusion_reason IN (
            'ACCOUNT_NOT_ACTIVE', 'CONSENT_WITHHELD', 'SUPPRESSED',
            'FREQUENCY_CAP_REACHED', 'NO_VERIFIED_ENDPOINT')
    )
);

COMMENT ON COLUMN marketing.audience_snapshot_members.exclusion_reason IS
    'ADR 0044. The five subtractions, in the order they are applied: lifecycle, consent, suppression, frequency cap, endpoint. Absence of a consent decision is CONSENT_WITHHELD, because absence is not consent.';

CREATE INDEX ix_audience_snapshot_member_included
    ON marketing.audience_snapshot_members (snapshot_id)
    WHERE inclusion_status = 'INCLUDED';


-- ---------------------------------------------------------------------------
-- 7. Campaigns
-- ---------------------------------------------------------------------------
CREATE TABLE marketing.campaigns (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    name varchar(120) NOT NULL,

    channel varchar(24) NOT NULL,
    consent_purpose varchar(64) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'DRAFT',

    audience_id uuid NOT NULL,
    audience_snapshot_id uuid,

    -- The ADR 0020 template this campaign sends. Marketing never holds a rendered
    -- body: the wording, its versions, and its translations are the notifications
    -- module's, and a copy here would be a second thing to translate and activate.
    template_key varchar(64) NOT NULL,
    template_version integer,

    scheduled_at timestamptz,
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Tashkent',

    -- Never optional, on any channel. A runaway push campaign costs nothing in
    -- cash and everything in uninstalls.
    recipient_cap integer NOT NULL,
    estimated_recipients integer,

    -- A range, because a personalised name changes the rendered length per
    -- recipient and a single number would be a guess presented as a fact.
    estimated_cost_low_minor bigint,
    estimated_cost_high_minor bigint,

    -- Optional on a channel with no marginal money. Enforced by decrementing a
    -- reservation under a conditional update as batches are claimed, never by
    -- summing sent rows: summing is how two workers both conclude there is budget
    -- left and both spend it.
    cost_ceiling_minor bigint,
    reserved_cost_minor bigint NOT NULL DEFAULT 0,
    spent_cost_minor bigint NOT NULL DEFAULT 0,
    reserved_recipients integer NOT NULL DEFAULT 0,
    currency char(3),

    -- ADR 0018 and ADR 0046. A reference and never a definition: pricing decides
    -- what a grant is worth and whether it may combine with anything else, and a
    -- marketer cannot raise a discount or mint points from the campaign editor.
    -- There is no column here in which to state a discount, and that absence is
    -- the enforcement.
    benefit_offer_id uuid,
    loyalty_accrual_rule_id uuid,

    created_by uuid NOT NULL,
    approved_by uuid,
    approval_id uuid,
    approved_at timestamptz,

    halted_reason varchar(500),
    started_at timestamptz,
    completed_at timestamptz,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_campaign_identity UNIQUE (id, tenant_id),
    CONSTRAINT fk_campaign_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_campaign_audience FOREIGN KEY (audience_id, tenant_id)
        REFERENCES marketing.audiences (id, tenant_id),
    CONSTRAINT fk_campaign_snapshot FOREIGN KEY (audience_snapshot_id, tenant_id)
        REFERENCES marketing.audience_snapshots (id, tenant_id),
    CONSTRAINT ck_campaign_status CHECK (status IN (
        'DRAFT', 'IN_REVIEW', 'APPROVED', 'SCHEDULED', 'SENDING', 'PAUSED',
        'SENT', 'PARTIALLY_SENT', 'HALTED_BUDGET', 'HALTED_OPERATOR', 'CANCELLED')),
    CONSTRAINT ck_campaign_channel CHECK (
        channel IN ('SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP')
    ),
    CONSTRAINT ck_campaign_recipient_cap CHECK (recipient_cap > 0),
    CONSTRAINT ck_campaign_money_nonnegative CHECK (
        reserved_cost_minor >= 0 AND spent_cost_minor >= 0 AND reserved_recipients >= 0
        AND (cost_ceiling_minor IS NULL OR cost_ceiling_minor >= 0)
    ),
    -- A reservation that has already passed the ceiling means the conditional
    -- update was bypassed, which is the one failure the ceiling exists to prevent.
    CONSTRAINT ck_campaign_reservation_within_ceiling CHECK (
        cost_ceiling_minor IS NULL OR reserved_cost_minor <= cost_ceiling_minor
    ),
    CONSTRAINT ck_campaign_reservation_within_cap CHECK (
        reserved_recipients <= recipient_cap
    ),
    CONSTRAINT ck_campaign_currency_pair CHECK (
        (cost_ceiling_minor IS NULL) OR (currency IS NOT NULL)
    ),
    CONSTRAINT ck_campaign_estimate_range CHECK (
        (estimated_cost_low_minor IS NULL) = (estimated_cost_high_minor IS NULL)
        AND (estimated_cost_low_minor IS NULL
             OR estimated_cost_low_minor <= estimated_cost_high_minor)
    ),
    -- Approval is a signature with a time and an ADR 0027 request behind it, or
    -- it is nothing.
    CONSTRAINT ck_campaign_approval_complete CHECK (
        (approved_by IS NULL) = (approved_at IS NULL)
    ),
    -- Four-eyes. The approver must not be the author: the failure being prevented
    -- is a marketer testing a template and sending forty thousand real SMS, and
    -- there is no undo for an SMS.
    CONSTRAINT ck_campaign_four_eyes CHECK (
        approved_by IS NULL OR approved_by <> created_by
    ),
    -- Nothing may send without a snapshot. Sending against a live re-evaluation
    -- is exactly the moving set the approval was meant to fix.
    CONSTRAINT ck_campaign_snapshot_before_send CHECK (
        status NOT IN ('SENDING', 'PAUSED', 'SENT', 'PARTIALLY_SENT',
                       'HALTED_BUDGET', 'HALTED_OPERATOR')
        OR audience_snapshot_id IS NOT NULL
    ),
    CONSTRAINT ck_campaign_halt_reason CHECK (
        status NOT IN ('HALTED_BUDGET', 'HALTED_OPERATOR') OR halted_reason IS NOT NULL
    )
);

COMMENT ON TABLE marketing.campaigns IS
    'ADR 0044. A campaign has an author, an approver, a channel, a cost ceiling, a recipient cap, and a per-recipient receipt. It cannot send without approval, it stops at the ceiling, and its recipient rows carry the notification id.';
COMMENT ON COLUMN marketing.campaigns.benefit_offer_id IS
    'ADR 0018. A reference to an offer pricing owns. There is deliberately no discount type, value, minimum basket, or stacking rule on this table: a campaign references a benefit and never defines one.';
COMMENT ON COLUMN marketing.campaigns.reserved_cost_minor IS
    'ADR 0044. Reserved as batches are claimed, under a conditional update against the ceiling. Not derived from sent rows: two workers summing the same rows both conclude there is budget left.';


-- Batch claims. Expansion is batched and idempotent on
-- (campaign_id, snapshot_id, batch_sequence): a replayed batch finds its row
-- already here, claims no reservation, and produces no second message.
CREATE TABLE marketing.campaign_batches (
    campaign_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    batch_sequence integer NOT NULL,

    claimed_recipients integer NOT NULL,
    reserved_cost_minor bigint NOT NULL DEFAULT 0,
    claimed_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_campaign_batch PRIMARY KEY (campaign_id, snapshot_id, batch_sequence),
    CONSTRAINT fk_campaign_batch_campaign FOREIGN KEY (campaign_id, tenant_id)
        REFERENCES marketing.campaigns (id, tenant_id),
    CONSTRAINT fk_campaign_batch_snapshot FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES marketing.audience_snapshots (id, tenant_id),
    CONSTRAINT ck_campaign_batch_counts CHECK (
        claimed_recipients >= 0 AND reserved_cost_minor >= 0
    )
);


CREATE TABLE marketing.campaign_recipients (
    campaign_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,
    sequence integer NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'PENDING',

    -- The ADR 0020 message. Null on a recipient who was refused at send, which is
    -- the whole point of keeping the row.
    notification_id uuid,

    benefit_grant_id uuid,

    -- Why this customer did not get it, decided at send rather than at snapshot
    -- build. The unsubscribe that arrived between approval and send wins, and
    -- this column is where it says so.
    refusal_reason varchar(32),
    refusal_detail varchar(500),

    -- A message eligible inside quiet hours is held to the next open boundary,
    -- not dropped. Dropping loses the send silently and a marketer reading a
    -- delivered count cannot tell a quiet-hour hold from a suppression.
    deferred_until timestamptz,

    -- A denormalised copy of the ADR 0020 outcome. ADR 0020 stays the source of
    -- truth; the column exists so a campaign report does not fan out into a
    -- hundred thousand cross-module lookups.
    terminal_status varchar(24),
    terminal_at timestamptz,

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_campaign_recipient PRIMARY KEY (campaign_id, customer_account_id),
    CONSTRAINT fk_campaign_recipient_campaign FOREIGN KEY (campaign_id, tenant_id)
        REFERENCES marketing.campaigns (id, tenant_id),
    CONSTRAINT fk_campaign_recipient_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT ck_campaign_recipient_status CHECK (
        status IN ('PENDING', 'QUEUED', 'DEFERRED', 'REFUSED')
    ),
    CONSTRAINT ck_campaign_recipient_refusal CHECK (
        (status = 'REFUSED') = (refusal_reason IS NOT NULL)
    ),
    CONSTRAINT ck_campaign_recipient_refusal_reason CHECK (
        refusal_reason IS NULL OR refusal_reason IN (
            'ACCOUNT_NOT_ACTIVE', 'CONSENT_WITHHELD', 'SUPPRESSED',
            'FREQUENCY_CAP_REACHED', 'NO_VERIFIED_ENDPOINT', 'CAMPAIGN_HALTED')
    ),
    CONSTRAINT ck_campaign_recipient_deferral CHECK (
        (status = 'DEFERRED') = (deferred_until IS NOT NULL)
    ),
    CONSTRAINT ck_campaign_recipient_queued CHECK (
        status <> 'QUEUED' OR notification_id IS NOT NULL
    ),
    CONSTRAINT ck_campaign_recipient_terminal CHECK (
        (terminal_status IS NULL) = (terminal_at IS NULL)
    ),
    CONSTRAINT uq_campaign_recipient_sequence UNIQUE (campaign_id, sequence)
);

COMMENT ON TABLE marketing.campaign_recipients IS
    'ADR 0044. The per-recipient receipt. Carries the consent decision and suppression reason that prove lawfulness and holds no contact value by construction, so it is a pseudonymous reference rather than a growing store of personal data. Retained for the life of the campaign record.';
COMMENT ON COLUMN marketing.campaign_recipients.refusal_reason IS
    'ADR 0044. The second evaluation. A message failing the check is recorded as refused with its reason rather than silently dropped, because a tenant must be able to answer why this customer did not get it.';

CREATE INDEX ix_campaign_recipient_status
    ON marketing.campaign_recipients (campaign_id, status);


-- ---------------------------------------------------------------------------
-- 8. Grants
-- ---------------------------------------------------------------------------
--
-- DELETE is granted on exactly two tables, and both for the same stated reason.
-- Snapshot membership is deleted at 24 months by the retention job while the
-- header, its counts, and the per-recipient reasons survive; drift observations
-- are pruned once the projection bug they describe has been fixed. Everything
-- else here is evidence that a send was lawful, and evidence that the
-- application can delete is evidence somebody can quietly remove.
--
-- No DELETE on marketing.suppressions in particular: a suppression records a
-- person's refusal, and deleting it re-enables what they refused. A lift is an
-- UPDATE that leaves the row and names who lifted it.
GRANT USAGE ON SCHEMA marketing TO horecaos_application;

GRANT SELECT, INSERT, UPDATE ON marketing.engagement_policies TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON marketing.customer_metrics TO horecaos_application;
GRANT SELECT, INSERT, DELETE ON marketing.metric_drift_observations TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON marketing.audiences TO horecaos_application;
GRANT SELECT, INSERT, DELETE ON marketing.audience_predicates TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON marketing.suppressions TO horecaos_application;
GRANT SELECT, INSERT ON marketing.marketing_sends TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON marketing.audience_snapshots TO horecaos_application;
GRANT SELECT, INSERT, DELETE ON marketing.audience_snapshot_members TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON marketing.campaigns TO horecaos_application;
GRANT SELECT, INSERT ON marketing.campaign_batches TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON marketing.campaign_recipients TO horecaos_application;
