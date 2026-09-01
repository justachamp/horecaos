-- ADR 0044 / ADR 0059 stage 4: the broadcast path. `notifications` now implements
-- `marketing.api.CampaignMessagePort` for the TELEGRAM channel, so a campaign can
-- finally expand into messages that actually go out. Two facts this record adds
-- did not exist before there was a send path to describe: how long a send is
-- expected to take, and how many recipients blocked the bot.
--
-- V0110 is reserved (piiaudit worktree) and V0111 is reserved (sendpulse
-- worktree) as of this migration's authoring; neither collides with this one.

-- ---------------------------------------------------------------------------
-- 1. The estimated delivery window
-- ---------------------------------------------------------------------------
--
-- ADR 0044 already reports a cost range at estimate time; this mirrors that
-- shape for time instead of money. `estimated_delivery_seconds` is
-- `audience size / the channel's configured pacing rate`, computed once at
-- `CampaignService.prepare()` the same moment the cost estimate is, and it is a
-- planning number rather than a promise — quiet-hours deferral and the
-- block-rate guard can both make the real send take longer.
ALTER TABLE marketing.campaigns
    ADD COLUMN estimated_delivery_seconds bigint;

ALTER TABLE marketing.campaigns
    ADD CONSTRAINT ck_campaign_estimated_delivery_seconds CHECK (
        estimated_delivery_seconds IS NULL OR estimated_delivery_seconds >= 0
    );

COMMENT ON COLUMN marketing.campaigns.estimated_delivery_seconds IS
    'ADR 0059 stage 4. Audience size divided by the channel''s configured campaign pacing rate, recorded at estimate time. Null when the channel reports no pacing ceiling (nothing paces SMS/EMAIL/PUSH sends today).';

-- ---------------------------------------------------------------------------
-- 2. The block-rate guard
-- ---------------------------------------------------------------------------
--
-- Telegram's own anti-spam enforcement acts on how often a bot's messages get
-- blocked, regardless of what this platform's consent records say (ADR 0059:
-- "the guard protects the bot"). `blocked_count` is incremented once per
-- recipient whose send retired their Telegram binding — the same signal
-- `CustomerProviderBindingSyncService` already treats as consent revocation —
-- and `CampaignFeedbackService` compares it against the recipients actually
-- queued for send (`marketing.campaign_recipients`, no new counter needed
-- there) to decide whether to pause. PAUSED already exists in
-- `ck_campaign_status` (V0043): SENDING -> PAUSED is a real transition in
-- `CampaignStatus`, and this column is the only schema this guard needs.
ALTER TABLE marketing.campaigns
    ADD COLUMN blocked_count integer NOT NULL DEFAULT 0;

ALTER TABLE marketing.campaigns
    ADD CONSTRAINT ck_campaign_blocked_count CHECK (blocked_count >= 0);

COMMENT ON COLUMN marketing.campaigns.blocked_count IS
    'ADR 0059 stage 4. How many recipients'' Telegram bindings retired while this campaign was sending to them. Compared against queued recipients by CampaignFeedbackService to decide whether to pause.';

-- A campaign whose channel does not carry marginal cost (PUSH, MESSAGING_APP)
-- may still need to be paused for blocks, which is a state-machine transition,
-- not a money one — no new CHECK needed there. `halted_reason` (V0043) already
-- accepts a reason on any transition; it is reused rather than duplicated for
-- the pause reason, the same way HALTED_BUDGET and HALTED_OPERATOR share it.

-- ---------------------------------------------------------------------------
-- 3. Finding a SENDING campaign without scanning every campaign row
-- ---------------------------------------------------------------------------
--
-- CampaignExpansionScheduler's sweep is cross-tenant by design, the same way
-- ApprovalDeadlineWarningSweeper's own scan is: infrastructure walking a
-- partial index, not a tenant-scoped business read. SENDING is a small,
-- transient fraction of all campaign rows, so the partial index stays small
-- regardless of how many campaigns a tenant has sent and finished.
CREATE INDEX ix_campaigns_sending ON marketing.campaigns (id, tenant_id) WHERE status = 'SENDING';

COMMENT ON INDEX marketing.ix_campaigns_sending IS
    'ADR 0059 stage 4. CampaignExpansionScheduler''s cross-tenant sweep for campaigns to keep expanding.';
