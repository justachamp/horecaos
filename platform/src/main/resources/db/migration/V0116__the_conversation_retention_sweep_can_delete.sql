-- ADR 0059 / ADR 0029: conversations.conversations.retention_months has
-- existed since V0108 and nothing enforced it (recorded there as a named
-- ADR 0029 gap). This is the sweep's own schema: indexes so a cross-tenant
-- scan does not degrade to a sequential scan as history accumulates, and the
-- DELETE grants nobody asked for at V0108/V0109 because there was no deleter
-- yet.
--
-- V0115 is this same change set's own campaign-resume column, already
-- landed; no sibling worktree's db/migration/ goes past V0114 as of this
-- writing.

-- ---------------------------------------------------------------------------
-- 1. Finding expired messages without scanning every tenant's history
-- ---------------------------------------------------------------------------
--
-- ix_conversation_message_conversation (V0108) is keyed
-- (tenant_id, conversation_id, occurred_at) — exactly right for "this
-- conversation's messages", exactly wrong for "the oldest messages on the
-- whole platform", which is what a cross-tenant sweep (the same shape
-- ApprovalDeadlineWarningSweeper and CampaignExpansionScheduler already use)
-- asks every tick.
CREATE INDEX ix_conversation_message_occurred_at
    ON conversations.conversation_messages (occurred_at);

COMMENT ON INDEX conversations.ix_conversation_message_occurred_at IS
    'ADR 0029/0059. ConversationRetentionSweeper''s own oldest-first scan.';

-- ---------------------------------------------------------------------------
-- 2. Finding closed conversations whose history might have fully aged out
-- ---------------------------------------------------------------------------
--
-- Partial on state = 'CLOSED', the same shape ix_conversations_sending and
-- ix_flow_run_resume_due already use for their own sweeps: CLOSED is a
-- minority of all conversations for as long as most chats stay open or
-- handed to an operator, so the index stays small regardless of a tenant's
-- total history.
CREATE INDEX ix_conversation_closed_updated_at
    ON conversations.conversations (updated_at)
    WHERE state = 'CLOSED';

COMMENT ON INDEX conversations.ix_conversation_closed_updated_at IS
    'ADR 0029/0059. ConversationRetentionSweeper''s own scan for a CLOSED conversation whose own retention window has passed, before it checks whether any message still survives.';

-- ---------------------------------------------------------------------------
-- 3. The sweep's own DELETE grants
-- ---------------------------------------------------------------------------
--
-- V0108/V0109 granted SELECT/INSERT(/UPDATE) only, because nothing deleted a
-- row in either table yet. Restated as the full set each table needs from
-- here on, per this migration file's own predecessors' warning about a bare
-- GRANT not covering a table created after it.
GRANT DELETE ON conversations.conversation_messages TO horecaos_application;
GRANT DELETE ON conversations.conversations TO horecaos_application;
GRANT DELETE ON conversations.flow_runs TO horecaos_application;
