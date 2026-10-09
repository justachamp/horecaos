-- ADR 0069 (operations batch 19, wave w9): where the assistant meets two other
-- modules' tables.
--
-- 1. A third author in a conversation. ADR 0069: "a handoff is a change of
--    author and not a change of system." conversations.conversation_messages
--    already tells the customer (INBOUND), the flow engine (OUTBOUND) and a
--    staff member (OPERATOR) apart; the assistant is a fourth. The column is
--    varchar(8) and "ASSISTANT" is nine characters, so it is widened first.
--    assistant_turn_id names the assistant.turns row that produced the message
--    -- an opaque id, not a foreign key, because the two tables belong to two
--    modules and a message must be able to outlive the retention of a turn.
--
-- 2. A provider category for the model provider. ADR 0069: "Adapters live behind
--    ADR 0026 installations of a new ASSISTANT category." The CHECK constraints
--    on integration.installations and integration.provider_environments name
--    every legal category. V0145 and V0250 each restated the whole list by hand,
--    which is correct for one migration and a hazard for two written at once in
--    sibling worktrees: whichever applies second silently removes the category
--    the first added. This one reads the constraint as it stands and adds
--    ASSISTANT to whatever it already allows, so it commutes with any other
--    migration that adds a category, in either order.
--
-- 3. The approved endpoint (ADR 0026: "Endpoints come from an approved provider
--    environment catalogue, never from tenant-supplied configuration").

ALTER TABLE conversations.conversation_messages
    ALTER COLUMN direction TYPE varchar(16);

ALTER TABLE conversations.conversation_messages
    DROP CONSTRAINT ck_conversation_message_direction,
    ADD CONSTRAINT ck_conversation_message_direction
        CHECK (direction IN ('INBOUND', 'OUTBOUND', 'OPERATOR', 'ASSISTANT')),
    ADD COLUMN assistant_turn_id uuid,
    ADD CONSTRAINT ck_conversation_message_assistant_turn CHECK (
        (direction = 'ASSISTANT') = (assistant_turn_id IS NOT NULL)
    );

-- ck_conversation_message_actor (V0109) already says "OPERATOR has a principal,
-- everything else has none", which an ASSISTANT message satisfies as it stands:
-- the assistant has no staff principal, and giving it a fake one would put a
-- name in the audit trail of someone who never acted.

COMMENT ON COLUMN conversations.conversation_messages.assistant_turn_id IS
    'ADR 0069: the assistant.turns row that produced an ASSISTANT-direction message. Not a foreign '
    'key -- another module''s table. Set exactly when direction is ASSISTANT.';

DO $$
DECLARE
    target record;
    allowed text;
BEGIN
    FOR target IN
        SELECT * FROM (VALUES
            ('integration.provider_environments', 'ck_provider_environment_category'),
            ('integration.installations', 'ck_installation_category')
        ) AS t(table_name, constraint_name)
    LOOP
        SELECT string_agg(quote_literal(category), ', ' ORDER BY category)
          INTO allowed
          FROM (
              SELECT DISTINCT hit[1] AS category
                FROM pg_constraint c,
                     LATERAL regexp_matches(pg_get_constraintdef(c.oid), '''([A-Z_]+)''', 'g') AS hit
               WHERE c.conrelid = target.table_name::regclass
                 AND c.conname = target.constraint_name
              UNION
              SELECT 'ASSISTANT'
          ) categories;

        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', target.table_name, target.constraint_name);
        EXECUTE format(
            'ALTER TABLE %s ADD CONSTRAINT %I CHECK (provider_category IN (%s))',
            target.table_name, target.constraint_name, allowed);
    END LOOP;
END
$$;

INSERT INTO integration.provider_environments
    (code, provider_category, provider_type, base_url, is_production, egress_allowlist, notes)
VALUES
    ('anthropic-production', 'ASSISTANT', 'ANTHROPIC',
     'https://api.anthropic.com', true, 'api.anthropic.com',
     'ADR 0069: the Anthropic Messages API, the first ASSISTANT adapter. No trailing slash: the adapter appends /v1/messages. The model id is configuration (horecaos.assistant.provider.model-id), never a column of this table, so a model swap is a deployment setting and not a migration.')
ON CONFLICT (code) DO NOTHING;
