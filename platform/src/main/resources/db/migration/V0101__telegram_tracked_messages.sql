-- ADR 0058 (rollout stage 1): the edit-vs-send lifecycle needs somewhere to
-- remember which Telegram message a chat is currently showing for a given
-- subject, so a later update can edit it in place instead of always sending a
-- new line into the chat.
--
-- Keyed by (binding, subject, template_key) rather than by subject alone:
-- two different concerns about the same order — its confirmation-or-rejection
-- line versus, in a later slice, its dispatch line — must never edit each
-- other's message. template_key is the stand-in for "concern" this slice
-- already has on hand; a later slice that wants a coarser or finer grouping
-- changes what is passed in, not this table.
CREATE TABLE integration.telegram_tracked_messages (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    binding_id uuid NOT NULL,

    -- Mirrors notifications.notifications' own subject pair, so a support
    -- question ("what does the chat show for order X") is answerable without
    -- inventing a second vocabulary for "what this message is about".
    subject_type varchar(32) NOT NULL,
    subject_id uuid NOT NULL,
    template_key varchar(64) NOT NULL,

    telegram_message_id bigint NOT NULL,
    content_hash varchar(64) NOT NULL,

    sent_at timestamptz NOT NULL,
    last_edited_at timestamptz,
    -- Telegram's edit window is ~48 hours from send; stored explicitly rather
    -- than recomputed from sent_at everywhere it is checked, so the constant
    -- lives in one place (TelegramMessageTracker) and a test can assert
    -- against the stored value instead of reimplementing the arithmetic.
    edit_window_expires_at timestamptz NOT NULL,

    -- Set once this row stops being "the current message for this subject":
    -- the edit window closed, or a send failed for a reason that means the
    -- message itself is gone (deleted, unchanged-content refused, etc.) and a
    -- new one replaced it. The old row is kept rather than deleted — it is
    -- evidence of what the chat actually showed and when.
    superseded_at timestamptz,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_telegram_tracked_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.telegram_bindings (tenant_id, binding_id),
    CONSTRAINT ck_telegram_tracked_edit_pair CHECK (
        last_edited_at IS NULL OR last_edited_at >= sent_at),
    CONSTRAINT ck_telegram_tracked_version CHECK (version >= 1)
);

-- The lookup the adapter actually does before every send: "is there a live
-- tracked message for this exact concern in this chat". Partial on
-- superseded_at so history never has to be filtered out by the query that
-- matters at send time.
CREATE UNIQUE INDEX ux_telegram_tracked_current
    ON integration.telegram_tracked_messages (tenant_id, binding_id, subject_type, subject_id, template_key)
    WHERE superseded_at IS NULL;

COMMENT ON TABLE integration.telegram_tracked_messages IS
    'ADR 0058: the live-state message per (chat, subject, concern), for edit-in-place-or-send-new.';

GRANT SELECT, INSERT, UPDATE ON integration.telegram_tracked_messages TO horecaos_application;
