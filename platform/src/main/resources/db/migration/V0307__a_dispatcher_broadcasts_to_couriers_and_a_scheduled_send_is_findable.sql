-- T18: two gaps in the Campaigns row (operations §6.4/6.4b).
--
-- First, a lookup a scheduler needs and did not have. `marketing.campaigns`
-- has carried `scheduled_at` since V0043 -- the column exists precisely
-- because ADR 0044 always declared `SCHEDULED` in the campaign state machine
-- -- but nothing has ever written it, so nothing has ever needed to find a
-- campaign whose scheduled moment has arrived. `CampaignScheduledSendScheduler`
-- is the first caller; this partial index is its own `ix_campaigns_sending`
-- (V0112), scoped to the sibling status.
--
-- Second, `marketing.courier_broadcasts`: the shape row `6.4b` says a
-- campaign has no model for, because a courier is not a customer. A courier
-- has no marketing consent purpose, no `customer_account_id` a campaign
-- recipient row could reference (`marketing.campaign_recipients` FKs that
-- column to the customer schema), and is reached tenant-wide
-- (`fulfillment.couriers` carries no brand) rather than through a brand's own
-- audience. Building this as a customer campaign would mean either lying
-- about consent or widening `campaign_recipients`' FK to accept two
-- unrelated identity spaces. A dispatcher's own operational blast -- a shift
-- change, a weather closure, a route closure -- is also a simpler act than a
-- revenue campaign: no four-eyes approval, no cost estimate against a price
-- book, no long-lived audience definition. It gets its own small table
-- instead, authored and sent by whoever already holds the brand's own
-- courier-dispatch grant (`COURIER_DISPATCHER`, ADR 0025), and it still
-- refuses to send silently against an unwired channel -- see `isWired` on
-- `CampaignMessagePort`, which this reuses rather than duplicates.
--
-- Attribution counts (`recipient_count`) are read live from the couriers a
-- send actually resolved, at send time, never summed from a second ledger --
-- the same reasoning `JdbcCampaignStore.claimBatch`'s own doc gives for why a
-- reservation is a conditional UPDATE and not a sum of sent rows.

CREATE INDEX ix_campaigns_scheduled
    ON marketing.campaigns (id, tenant_id)
    WHERE status = 'SCHEDULED';

COMMENT ON INDEX marketing.ix_campaigns_scheduled IS
    'CampaignScheduledSendScheduler''s own worklist: SCHEDULED campaigns whose scheduled_at has arrived. Same partial-index shape as ix_campaigns_sending (V0112).';

-- --------------------------------------------------------- courier broadcasts

CREATE TABLE marketing.courier_broadcasts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    -- Fixed at SMS today, per the IA row's own bullet ("SMS ... couriers as
    -- separate audience"). A column rather than a hard-coded constant only so
    -- a later push/Telegram channel for the same audience is an added CHECK
    -- value, not a new table.
    channel varchar(16) NOT NULL DEFAULT 'SMS',

    target_kind varchar(16) NOT NULL,
    target_group_id uuid,

    message varchar(480) NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    recipient_count integer NOT NULL DEFAULT 0,
    refusal_reason varchar(200),

    created_by uuid NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz,

    CONSTRAINT uq_courier_broadcast_identity UNIQUE (id, tenant_id),
    CONSTRAINT fk_courier_broadcast_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    -- fulfillment.courier_groups is tenant-wide, not brand-scoped (couriers
    -- are engaged per tenant, per V0040's own comment on courier_engagements)
    -- -- a brand's dispatcher may target any of the tenant's groups, the same
    -- reach COURIER_DISPATCHER already has over fulfillment.courier_groups.
    CONSTRAINT fk_courier_broadcast_group FOREIGN KEY (target_group_id, tenant_id)
        REFERENCES fulfillment.courier_groups (id, tenant_id),

    CONSTRAINT ck_courier_broadcast_channel CHECK (channel = 'SMS'),
    CONSTRAINT ck_courier_broadcast_target_kind CHECK (
        target_kind IN ('ALL_ACTIVE', 'GROUP')),
    CONSTRAINT ck_courier_broadcast_target_pair CHECK (
        (target_kind = 'GROUP') = (target_group_id IS NOT NULL)),
    CONSTRAINT ck_courier_broadcast_message CHECK (length(btrim(message)) > 0),
    CONSTRAINT ck_courier_broadcast_status CHECK (
        status IN ('DRAFT', 'SENT', 'FAILED')),
    CONSTRAINT ck_courier_broadcast_recipient_count CHECK (recipient_count >= 0),
    CONSTRAINT ck_courier_broadcast_sent_pair CHECK (
        (status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT ck_courier_broadcast_failed_reason CHECK (
        (status = 'FAILED') = (refusal_reason IS NOT NULL))
);

CREATE INDEX ix_courier_broadcast_brand
    ON marketing.courier_broadcasts (tenant_id, brand_id, created_at DESC);

COMMENT ON TABLE marketing.courier_broadcasts IS
    'Operations 6.4b. A dispatcher''s own operational SMS blast to couriers -- shift change, weather, route closure -- never a customer campaign: no consent purpose, no four-eyes approval, and a target that is a courier group or "every active courier", never an ADR 0044 audience.';
COMMENT ON COLUMN marketing.courier_broadcasts.recipient_count IS
    'Set once, at send, from the couriers the target actually resolved to at that moment -- never incremented per-message, because there is no per-recipient receipt row the way a customer campaign keeps one.';
COMMENT ON COLUMN marketing.courier_broadcasts.refusal_reason IS
    'Why a send attempt failed -- today always "no SMS delivery path is wired", since CampaignMessagePort.isWired("SMS") is false in this build. Present so a dispatcher who tried to send sees why nothing happened, the same honesty isWired brings to the campaign create form.';

GRANT SELECT, INSERT, UPDATE ON marketing.courier_broadcasts TO horecaos_application;
