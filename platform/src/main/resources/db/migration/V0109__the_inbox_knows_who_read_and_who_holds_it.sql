-- ADR 0059 stage 2: the operator inbox. Three small additive columns rather
-- than new tables — the inbox's whole job is reading and transitioning a
-- conversation that already exists, not modeling a new aggregate.
--
-- assigned_to: which operator currently holds this conversation. Nullable,
-- and folded into takeover/return/close rather than a standing assignment
-- workflow: takeover sets it to the acting operator, return-to-flow and close
-- both clear it, because neither state has an operator actively working it
-- afterward.
--
-- last_read_at / last_read_by: not a "seen" receipt for the customer's
-- benefit — this is the throttle on ADR 0027 audit noise for the inbox's
-- PII-read discipline. The detail read decrypts every message body on every
-- open (that decryption is the inbox's whole purpose, per the ADR), but this
-- module writes a conversation.history.read audit fact only the first time a
-- given operator (by these two columns together) opens a given
-- conversation's history, not on every polling refresh of an already-open
-- thread. A second operator's first open still writes its own fact: the
-- throttle is keyed on which operator is reading, not on whether the
-- conversation has ever been read by anyone.

ALTER TABLE conversations.conversations
    ADD COLUMN assigned_to varchar(255),
    ADD COLUMN last_read_at timestamptz,
    ADD COLUMN last_read_by varchar(255);

COMMENT ON COLUMN conversations.conversations.assigned_to IS
    'The Keycloak subject of the operator who last took this conversation over. '
    'Null when nobody currently holds it. Set by the inbox''s takeover action, '
    'cleared by return-to-flow and close.';

COMMENT ON COLUMN conversations.conversations.last_read_at IS
    'When last_read_by most recently opened this conversation''s decrypted '
    'history. Exists only to throttle the ADR 0027 audit fact to once per '
    'operator per conversation, not to drive any read-receipt UI.';

COMMENT ON COLUMN conversations.conversations.last_read_by IS
    'The Keycloak subject of the operator named by last_read_at. See that '
    'column''s comment.';

-- Every message needs a direction an operator's own reply can be told apart
-- from the flow engine's: OPERATOR joins INBOUND (the customer) and OUTBOUND
-- (the flow engine) as a third party to the conversation, so the inbox's
-- needs-reply computation ("the newest message is one nobody has answered
-- yet") and its history display ("who actually said this") can both tell all
-- three apart. actor_principal_id is the acting operator's Keycloak subject,
-- set only for OPERATOR rows — never for INBOUND or OUTBOUND, which have no
-- staff actor.
ALTER TABLE conversations.conversation_messages
    DROP CONSTRAINT ck_conversation_message_direction,
    ADD CONSTRAINT ck_conversation_message_direction CHECK (direction IN ('INBOUND', 'OUTBOUND', 'OPERATOR')),
    ADD COLUMN actor_principal_id varchar(255),
    ADD CONSTRAINT ck_conversation_message_actor CHECK (
        (direction = 'OPERATOR' AND actor_principal_id IS NOT NULL)
        OR (direction <> 'OPERATOR' AND actor_principal_id IS NULL)
    );

COMMENT ON COLUMN conversations.conversation_messages.actor_principal_id IS
    'The Keycloak subject who sent an OPERATOR-direction message. Not '
    'personal data about the customer (it is the reverse — who on staff '
    'answered), so it is stored in the clear like block_id, not encrypted '
    'like body_protected.';
