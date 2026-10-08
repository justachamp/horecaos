-- ADR 0112: a campaign may be a scenario, offers become a versioned entity that
-- references the catalogue, and a tenant may tighten who is contacted when.
--
-- Three things, none of which moves ownership. marketing still decides who and
-- when; pricing still decides what a benefit is worth; loyalty still mints points.
-- An offer holds a reference to one of them and never a discount.
--
-- ------------------------------------------------------------------ campaigns
--
-- `kind` is BROADCAST (every campaign that exists) or SCENARIO (per-guest steps,
-- V0496). Both share this table's approval, cost-ceiling and channel machinery, so a
-- scenario is approved by the same second signature and halts at the same ceiling.
-- `control_group_percent` is nullable on purpose and meaningful only for a scenario:
-- null runs the scenario against its full audience with no measurement baseline, and
-- ADR 0112's open input on a mandatory floor is closed on "none" (a five-table
-- restaurant sending to forty guests cannot spare a withheld group).
-- `supersedes_campaign_id` is how an edited scenario is a new version: a published
-- version is immutable, editing makes a new DRAFT that points at it and needs a fresh
-- approval.

ALTER TABLE marketing.campaigns
    ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'BROADCAST',
    ADD COLUMN control_group_percent smallint,
    ADD COLUMN supersedes_campaign_id uuid;

ALTER TABLE marketing.campaigns
    ADD CONSTRAINT ck_campaign_kind CHECK (kind IN ('BROADCAST', 'SCENARIO')),
    ADD CONSTRAINT ck_campaign_control_group CHECK (
        control_group_percent IS NULL
        OR (kind = 'SCENARIO' AND control_group_percent BETWEEN 0 AND 100)
    ),
    ADD CONSTRAINT ck_campaign_supersedes CHECK (
        supersedes_campaign_id IS NULL OR kind = 'SCENARIO'
    ),
    ADD CONSTRAINT fk_campaign_supersedes FOREIGN KEY (supersedes_campaign_id, tenant_id)
        REFERENCES marketing.campaigns (id, tenant_id);

COMMENT ON COLUMN marketing.campaigns.kind IS
    'ADR 0112. BROADCAST is the one-off send to a snapshot; SCENARIO is a per-guest sequence of steps evaluated against each guest, never against the whole audience at once.';
COMMENT ON COLUMN marketing.campaigns.control_group_percent IS
    'ADR 0112. The share of the snapshot withheld from every step, fixed at scenario start and never resampled. Null means no control group and so no measurement baseline.';

-- --------------------------------------------------------------------- offers
--
-- A versioned reference to something pricing or loyalty already owns. Exactly one of
-- pricing_promotion_id and loyalty_accrual_rule_id is set, stated as inequality of
-- nullness (the disjunctive form admits a row with neither set when one side is
-- unknown). There is no column here in which to state a discount, and that absence is
-- the enforcement: a marketer selects an offer and cannot invent one.
--
-- One row per VERSION. A scenario step references a specific row, so editing an offer
-- must not change what an approved scenario means: a change is a new row in the same
-- lineage, and at most one version of a lineage is PUBLISHED at a time. A published
-- row is never updated except to be superseded or retired.

CREATE TABLE marketing.offers (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    lineage_id uuid NOT NULL,
    version_number integer NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',

    display_name varchar(120) NOT NULL,

    pricing_promotion_id uuid,
    loyalty_accrual_rule_id uuid,

    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    audience_id uuid,

    allowed_channels varchar(24)[] NOT NULL,
    template_key varchar(64) NOT NULL,
    template_version integer,

    -- Backs the storefront's existing OfferItem/CustomerUiOffer wire type
    -- (home.types.ts): one OfferItem is one presented offer joined to these two.
    banner_image_reference varchar(255),

    created_by uuid NOT NULL,
    published_by uuid,
    published_at timestamptz,

    -- Optimistic locking for ADR 0031's If-Match, distinct from version_number, which
    -- names the lineage version and changes only by creating a new row.
    row_version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_offer_identity UNIQUE (id, tenant_id),
    CONSTRAINT uq_offer_version UNIQUE (tenant_id, lineage_id, version_number),
    CONSTRAINT fk_offer_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_offer_audience FOREIGN KEY (audience_id, tenant_id)
        REFERENCES marketing.audiences (id, tenant_id),
    CONSTRAINT ck_offer_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED', 'RETIRED')),
    CONSTRAINT ck_offer_version CHECK (version_number >= 1 AND row_version >= 1),
    CONSTRAINT ck_offer_exactly_one_reference CHECK (
        (pricing_promotion_id IS NOT NULL) <> (loyalty_accrual_rule_id IS NOT NULL)
    ),
    CONSTRAINT ck_offer_validity CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_offer_channels CHECK (
        cardinality(allowed_channels) > 0
        AND allowed_channels <@ ARRAY['SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP', 'IN_APP', 'CALL_CENTRE']::varchar[]
    ),
    -- Published and superseded versions were signed off by somebody; a draft was not;
    -- a retired one may be either, since an abandoned draft can be retired unpublished.
    CONSTRAINT ck_offer_publication CHECK (
        (published_by IS NULL) = (published_at IS NULL)
        AND (
            (status = 'DRAFT' AND published_at IS NULL)
            OR (status IN ('PUBLISHED', 'SUPERSEDED') AND published_at IS NOT NULL)
            OR status = 'RETIRED'
        )
    )
);

CREATE UNIQUE INDEX ux_offer_one_published_version
    ON marketing.offers (tenant_id, lineage_id)
    WHERE status = 'PUBLISHED';

CREATE INDEX ix_offers_brand ON marketing.offers (tenant_id, brand_id, created_at DESC);

COMMENT ON TABLE marketing.offers IS
    'ADR 0112. A versioned reference to exactly one pricing promotion or loyalty accrual rule, with a validity window, allowed channels and a template. Marketing selects and never authors a discount.';

-- ------------------------------------------------------ contact policy overrides
--
-- A tenant may TIGHTEN the platform quiet hours and frequency cap for one
-- (channel, campaign purpose, period), never loosen them. The bounds are the ADR 0044
-- platform defaults, and the CHECKs state them in the tightening direction, exactly as
-- ck_engagement_* does on marketing.engagement_policies: the service refuses a
-- loosening with an explanation a marketer can read, and the constraint is the
-- backstop for anything that reaches this table another way. They duplicate a number
-- that also exists in EngagementPolicy, on purpose, for the reason V0043 gives.
--
--   caps         <= 3 over a day, a calendar week or a rolling 7 days; <= 8 over a rolling 30 days
--   quiet hours  start no later than 21:00, end no earlier than 10:00

CREATE TABLE marketing.contact_policy_overrides (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    channel varchar(24) NOT NULL,
    campaign_purpose varchar(64) NOT NULL,
    period_kind varchar(16) NOT NULL,

    cap_count integer,
    quiet_hours_start time,
    quiet_hours_end time,

    stated_reason varchar(500) NOT NULL,
    updated_by uuid NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, brand_id, channel, campaign_purpose, period_kind),
    CONSTRAINT fk_contact_policy_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT ck_contact_policy_channel CHECK (
        channel IN ('SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP', 'IN_APP', 'CALL_CENTRE')
    ),
    CONSTRAINT ck_contact_policy_period CHECK (
        period_kind IN ('DAILY', 'WEEKLY', 'ROLLING_7D', 'ROLLING_30D')
    ),
    CONSTRAINT ck_contact_policy_quiet_pair CHECK (
        (quiet_hours_start IS NULL) = (quiet_hours_end IS NULL)
    ),
    CONSTRAINT ck_contact_policy_says_something CHECK (
        cap_count IS NOT NULL OR quiet_hours_start IS NOT NULL
    ),
    CONSTRAINT ck_contact_policy_cap_tighten_only CHECK (
        cap_count IS NULL
        OR (cap_count >= 0 AND cap_count <= CASE WHEN period_kind = 'ROLLING_30D' THEN 8 ELSE 3 END)
    ),
    CONSTRAINT ck_contact_policy_quiet_tighten_only CHECK (
        quiet_hours_start IS NULL
        OR (quiet_hours_start <= TIME '21:00' AND quiet_hours_end >= TIME '10:00')
    ),
    CONSTRAINT ck_contact_policy_version CHECK (version >= 1)
);

COMMENT ON TABLE marketing.contact_policy_overrides IS
    'ADR 0112. A tenant''s tighter cap or wider quiet-hour exclusion for one channel, campaign purpose and period. Tighten-only, enforced here as well as in the service, because these numbers protect a sending reputation shared across tenants.';

GRANT SELECT, INSERT, UPDATE ON marketing.offers TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON marketing.contact_policy_overrides TO horecaos_application;
