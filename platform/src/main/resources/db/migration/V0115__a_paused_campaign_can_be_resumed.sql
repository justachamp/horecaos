-- ADR 0044 / ADR 0059 stage 4: wave 12 gave the block-rate guard a pause
-- (SENDING -> PAUSED, V0112) but no way back. This adds the one column a
-- resume needs to report an honest cost: how many messages the pause itself
-- suppressed, not the campaign's whole lifetime.
--
-- V0116 is reserved (this same change set, conversation retention) as of
-- this migration's authoring; no sibling worktree's db/migration/ goes past
-- V0114 as of this writing.

-- ---------------------------------------------------------------------------
-- 1. When the campaign was last paused
-- ---------------------------------------------------------------------------
--
-- Not reused from `updated_at`: that column is touched by several unrelated
-- writers (recordSpend has no status guard today), so it cannot be trusted as
-- "since this pause" without auditing every writer's behaviour under a race.
-- A dedicated, narrowly-written column is the honest boundary for the one
-- thing that reads it — CampaignService#resume, counting how many of this
-- campaign's messages notifications.notifications suppressed with
-- CAMPAIGN_NOT_SENDING at or after this instant.
ALTER TABLE marketing.campaigns
    ADD COLUMN paused_at timestamptz;

COMMENT ON COLUMN marketing.campaigns.paused_at IS
    'ADR 0044/0059 stage 4. Set by the block-rate guard''s own pause (CampaignFeedbackService), cleared on resume or on any terminal halt. The lower bound CampaignService#resume counts CAMPAIGN_NOT_SENDING suppressions from, so a second pause-and-resume cycle does not report the first pause''s cost twice.';
