-- ADR 0059 stage 4: the Bot API gives roughly 30 messages per second per bot,
-- shared with every other message that bot sends — order alerts, flow replies,
-- the operator inbox. A campaign must pace itself well below that ceiling and
-- leave headroom for the traffic that was there first, and it must do so across
-- every application node and every campaign running against the same bot at
-- once, not just within the one it is currently expanding.
--
-- `campaign_pace_cursors` is that shared state: one row per (tenant, brand,
-- channel) — a bot, in ADR 0058's bot-per-brand topology — holding the next
-- instant a campaign message on that bot may be scheduled for. `CampaignPacer`
-- reserves a slot with a single `INSERT ... ON CONFLICT DO UPDATE ...
-- RETURNING`, so two workers on two nodes racing to schedule the next message
-- for the same brand still hand out two different, correctly-spaced slots
-- rather than the same one twice — the row lock the upsert takes is the
-- serialisation point, the same pattern `marketing.campaigns.claimBatch`
-- already uses for its own reservation.
--
-- This is deliberately not a token-bucket-in-memory limiter. Every application
-- node claims from the same `notifications.notifications` queue
-- (`NotificationWorker`'s own doc comment: "safe to run on every node"), so a
-- per-process limiter would let N nodes each believe they hold the whole
-- budget. Pacing lives here, in the one place every node already agrees on the
-- truth.
CREATE TABLE notifications.campaign_pace_cursors (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    channel varchar(24) NOT NULL,

    -- The earliest instant the next campaign message on this bot may be
    -- scheduled for. Always at least one pacing interval ahead of the last
    -- message actually reserved — see CampaignPacer for why the value stored
    -- here is one interval past the slot most recently handed out.
    next_slot_at timestamptz NOT NULL,

    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_campaign_pace_cursor PRIMARY KEY (tenant_id, brand_id, channel),
    CONSTRAINT fk_campaign_pace_cursor_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT ck_campaign_pace_cursor_channel CHECK (channel IN ('SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP', 'TELEGRAM'))
);

COMMENT ON TABLE notifications.campaign_pace_cursors IS
    'ADR 0059 stage 4. The next available send slot per (tenant, brand, channel) bot, shared across every node so a campaign paces itself under the configured per-bot rate regardless of which node or which of the tenant''s campaigns is scheduling the next message.';

GRANT SELECT, INSERT, UPDATE ON notifications.campaign_pace_cursors TO horecaos_application;
