-- ADR 0069 (operations batch 19, wave w9): the grounded assistant's own tables.
--
-- Three tables, all append-only by grant (SELECT, INSERT and nothing else):
--
--   knowledge_entries          the identity of one tenant-authored answer: its
--                              scope (tenant, brand or location) and its locale.
--                              Neither ever changes; a different scope or locale
--                              is a different entry.
--   knowledge_entry_versions   what the entry said, version by version. Never
--                              edited in place -- "someone was answered with
--                              specific words at a specific time" (ADR 0069,
--                              the posture ADR 0068 gives terms and ADR 0020
--                              gives notification templates). Retiring an entry
--                              is a new version whose status is RETIRED, so the
--                              history of what a customer might have been told
--                              is never lost to a delete.
--   turns                      one row per assistant turn: what class of question
--                              it was, what was retrieved, what was cited, what
--                              the model cost, and how it ended. It is both the
--                              spend ledger the per-tenant ceiling reads and the
--                              provenance "why did it say that" is answered from.
--                              It holds NO customer message text and NO reply
--                              text: the words live, envelope-encrypted, in
--                              conversations.conversation_messages, and a turn
--                              is found from a message through the message's
--                              assistant_turn_id (V0512).
--
-- Tenant scope: every row carries a non-null tenant_id, and every key and
-- foreign key includes it (CLAUDE.md). The knowledge versions' foreign key names
-- (tenant_id, entry_id), not entry_id alone, so a version can never point at
-- another tenant's entry.
--
-- turns.conversation_id is deliberately NOT a foreign key. The conversations
-- schema belongs to another module and ADR 0069's own rule is that the assistant
-- reaches other modules "only through their api ports -- never across schemas".

CREATE SCHEMA IF NOT EXISTS assistant;
GRANT USAGE ON SCHEMA assistant TO horecaos_application;

CREATE TABLE assistant.knowledge_entries (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    scope_type varchar(8) NOT NULL,
    brand_id uuid,
    location_id uuid,
    locale varchar(8) NOT NULL,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_knowledge_entry_identity UNIQUE (tenant_id, id),
    CONSTRAINT fk_knowledge_entry_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT fk_knowledge_entry_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_knowledge_entry_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT ck_knowledge_entry_scope CHECK (
        (scope_type = 'TENANT' AND brand_id IS NULL AND location_id IS NULL)
        OR (scope_type = 'BRAND' AND brand_id IS NOT NULL AND location_id IS NULL)
        OR (scope_type = 'LOCATION' AND brand_id IS NOT NULL AND location_id IS NOT NULL)
    ),
    CONSTRAINT ck_knowledge_entry_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$')
);

CREATE INDEX ix_knowledge_entry_scope
    ON assistant.knowledge_entries (tenant_id, scope_type, brand_id, location_id);

COMMENT ON TABLE assistant.knowledge_entries IS
    'ADR 0069: a tenant-owned answer the assistant may retrieve. Identity only -- scope and locale, '
    'both immutable. What it says is in knowledge_entry_versions. Entry bodies are tenant business '
    'content, not personal data.';

CREATE TABLE assistant.knowledge_entry_versions (
    tenant_id uuid NOT NULL,
    entry_id uuid NOT NULL,
    version integer NOT NULL,
    status varchar(10) NOT NULL,
    question_form varchar(300) NOT NULL,
    answer_body varchar(2000) NOT NULL,
    authored_by varchar(255) NOT NULL,
    reason varchar(1000) NOT NULL,
    published_at timestamptz NOT NULL,

    CONSTRAINT pk_knowledge_entry_version PRIMARY KEY (tenant_id, entry_id, version),
    CONSTRAINT fk_knowledge_entry_version_entry FOREIGN KEY (tenant_id, entry_id)
        REFERENCES assistant.knowledge_entries (tenant_id, id),
    CONSTRAINT ck_knowledge_entry_version_number CHECK (version > 0),
    CONSTRAINT ck_knowledge_entry_version_status CHECK (status IN ('PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_knowledge_entry_version_text CHECK (
        btrim(question_form) <> '' AND btrim(answer_body) <> ''
    )
);

COMMENT ON TABLE assistant.knowledge_entry_versions IS
    'ADR 0069: append-only. The current version of an entry is its highest; an entry whose highest '
    'version is RETIRED is never retrieved. Two operators publishing at once collide on the primary '
    'key, which is the optimistic-concurrency check (ADR 0031) and not an accident.';

GRANT SELECT, INSERT ON assistant.knowledge_entries TO horecaos_application;
GRANT SELECT, INSERT ON assistant.knowledge_entry_versions TO horecaos_application;

CREATE TABLE assistant.turns (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    locale varchar(8) NOT NULL,

    -- The retrieval kinds the question was classified into, comma separated:
    -- PRICE, AVAILABILITY, BRANCHES, HOURS, COVERAGE, ORDER_STATUS, KNOWLEDGE.
    question_kinds varchar(200) NOT NULL,

    outcome varchar(10) NOT NULL,
    refusal_reason varchar(32),

    provider_type varchar(32),
    model_id varchar(64),
    input_tokens bigint NOT NULL DEFAULT 0,
    output_tokens bigint NOT NULL DEFAULT 0,

    -- What the call cost, in millionths of a US dollar (a "micro" unit rather than
    -- a cent, because one turn costs a fraction of a cent and integer cents would
    -- round every turn to zero). Integer arithmetic throughout; never a double.
    cost_usd_micros bigint NOT NULL DEFAULT 0,
    latency_ms integer NOT NULL DEFAULT 0,
    served_from_cache boolean NOT NULL DEFAULT false,

    -- Provenance, never content: the ids and amounts of the platform facts that
    -- were retrieved, which of them the reply cited, and which knowledge entry
    -- versions were among them.
    facts jsonb NOT NULL DEFAULT '[]'::jsonb,
    cited_fact_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    knowledge_versions jsonb NOT NULL DEFAULT '[]'::jsonb,

    -- ADR 0029's per-tenant keyed hash of the customer account -- the only
    -- identifier of the customer the model is ever given. Null for a chat that
    -- has never linked to an account.
    customer_pseudonym varchar(128),

    CONSTRAINT uq_assistant_turn_identity UNIQUE (tenant_id, id),
    CONSTRAINT fk_assistant_turn_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT fk_assistant_turn_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT ck_assistant_turn_outcome CHECK (outcome IN ('ANSWERED', 'REFUSED', 'ESCALATED', 'DECLINED')),
    CONSTRAINT ck_assistant_turn_refusal CHECK (
        (outcome IN ('REFUSED', 'DECLINED')) = (refusal_reason IS NOT NULL)
        OR outcome = 'ESCALATED'
    ),
    CONSTRAINT ck_assistant_turn_amounts CHECK (
        input_tokens >= 0 AND output_tokens >= 0 AND cost_usd_micros >= 0 AND latency_ms >= 0
    )
);

-- The spend ceiling sums a tenant's month; the turn cap counts one conversation's
-- day. Both are range reads on exactly these keys.
CREATE INDEX ix_assistant_turn_tenant_time ON assistant.turns (tenant_id, occurred_at);
CREATE INDEX ix_assistant_turn_conversation ON assistant.turns (tenant_id, conversation_id, occurred_at);

COMMENT ON TABLE assistant.turns IS
    'ADR 0069: the assistant''s spend ledger and its provenance. Append-only. No message text of any '
    'kind -- the customer''s words and the reply live encrypted in conversations.conversation_messages.';
COMMENT ON COLUMN assistant.turns.cost_usd_micros IS
    'Millionths of a US dollar. 1 USD = 1000000. The per-tenant monthly ceiling is configured in US '
    'cents and compared as cents * 10000.';

GRANT SELECT, INSERT ON assistant.turns TO horecaos_application;
