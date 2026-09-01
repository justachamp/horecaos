-- ADR 0059 stage 1: the conversations module — flow documents, conversations,
-- flow runs, message history — plus two small Telegram-side gaps this stage
-- needs to answer honestly rather than guess around.
--
-- First gap: ADR 0058 decided bot-per-brand topology but left it "not yet a
-- schema fact" — installations remain tenant-scoped only. A bare /start from a
-- chat that has never linked to anything has no other way to name which
-- brand's welcome flow should answer it, so this migration adds the column
-- ADR 0058 already named as missing. It stays nullable and additive: every
-- existing installation keeps working exactly as before, and a chat that has
-- already bound (group or customer) still resolves its brand from that
-- binding first — this column is only the fallback for a chat with no
-- binding yet.
--
-- Second gap: TelegramUpdateHandler's path has never deduplicated inbound
-- update_id, relying on Telegram rarely redelivering. ADR 0032's at-least-once
-- discipline says that is not a dedup strategy, and the conversations engine
-- makes the gap concrete — a redelivered /start must not restart or re-answer
-- a flow. The table belongs in integration, the schema this update ever
-- touches, not in conversations.

ALTER TABLE integration.installations
    ADD COLUMN brand_id uuid;

ALTER TABLE integration.installations
    ADD CONSTRAINT fk_installation_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id);

COMMENT ON COLUMN integration.installations.brand_id IS
    'ADR 0058''s decided-but-not-yet-schema bot-per-brand fact, added by ADR '
    '0059 stage 1 to resolve which brand''s flow answers a chat with no '
    'binding yet. Null for every installation that predates a brand bot '
    '(operations groups, the platform digest binding); set when a Telegram '
    'installation is provisioned as one brand''s own bot.';

CREATE TABLE integration.telegram_processed_updates (
    tenant_id uuid NOT NULL,
    -- Deliberately not a foreign key to integration.installations. update_id
    -- is per-bot and monotonic, so (installation_id, update_id) is the
    -- natural dedup key regardless — but an installation row here would tie
    -- dedup to installation lifecycle for no correctness benefit (a
    -- retired/replaced installation's old update_ids must still be
    -- recognised as seen), and the webhook controller's own secret-token
    -- check is what already establishes trust in installation_id before
    -- this table is ever touched, the same trust TelegramUpdateHandler's own
    -- WebhookInstallation carries with no referential proof of its own.
    installation_id uuid NOT NULL,
    update_id bigint NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    -- tenant_id in the key itself, not just a column beside it: (installation_id,
    -- update_id) alone is a composite key of caller-supplied values with no
    -- foreign key on either column (see the comment above), which
    -- TenantScopedReferenceCatalogTests specifically checks for as the shape a
    -- foreign-key sweep cannot see on its own.
    CONSTRAINT pk_telegram_processed_update PRIMARY KEY (tenant_id, installation_id, update_id),
    CONSTRAINT fk_telegram_processed_update_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id)
);

COMMENT ON TABLE integration.telegram_processed_updates IS
    'ADR 0032 at-least-once dedup for TelegramUpdateHandler''s entry point '
    '(webhook and local long-polling both call it): a redelivered update_id '
    'from the same installation is recorded once and every later delivery is '
    'dropped before any handler runs. Unbounded for now — dev-scale, per ADR '
    '0059 stage 1''s own named pre-work; a retention sweep is future work, '
    'not a stage-1 blocker.';

-- Retained indefinitely for now (see comment above); an index on processed_at
-- is cheap insurance for whenever that sweep is written.
CREATE INDEX ix_telegram_processed_update_processed_at
    ON integration.telegram_processed_updates (processed_at);

GRANT SELECT, INSERT ON integration.telegram_processed_updates TO horecaos_application;

-- ------------------------------------------------------------- conversations

CREATE SCHEMA IF NOT EXISTS conversations;
GRANT USAGE ON SCHEMA conversations TO horecaos_application;

-- A per-brand, versioned YAML flow document (ADR 0059: "declarative, versioned,
-- per-brand YAML documents... authored as configuration through the
-- control-plane"). Never edited in place: authoring always inserts the next
-- version and, on activation, flips is_active over inside one transaction, so
-- a flow run already mid-execution keeps reading the version it started on
-- via flow_runs.flow_document_id rather than "whatever is active now".
CREATE TABLE conversations.flow_documents (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    -- The stable name a document is authored under across versions, e.g.
    -- "welcome-series". Distinct from a state id: a flow_key identifies the
    -- flow, not a point inside it.
    flow_key varchar(64) NOT NULL,
    version integer NOT NULL,

    -- The authored document, verbatim. Configuration, not personal data — see
    -- the module's PII posture note on conversation_messages below for the
    -- contrast.
    document_yaml text NOT NULL,

    is_active boolean NOT NULL DEFAULT false,
    description varchar(500),
    authored_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_flow_document_identity UNIQUE (tenant_id, id),
    CONSTRAINT fk_flow_document_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT uq_flow_document_version UNIQUE (tenant_id, brand_id, flow_key, version),
    CONSTRAINT ck_flow_document_version CHECK (version > 0),
    CONSTRAINT ck_flow_document_key CHECK (flow_key ~ '^[a-z0-9][a-z0-9-]{0,62}[a-z0-9]$')
);

-- Exactly one active version per (tenant, brand, flow_key) — a partial unique
-- index rather than a boolean-and-service-layer check, per AGENTS.md's own
-- "prefer a constraint over a service-layer check".
CREATE UNIQUE INDEX uq_flow_document_one_active
    ON conversations.flow_documents (tenant_id, brand_id, flow_key)
    WHERE is_active;

CREATE INDEX ix_flow_document_tenant ON conversations.flow_documents (tenant_id);

COMMENT ON TABLE conversations.flow_documents IS
    'ADR 0059: the YAML authoring surface. No visual builder, ever — the '
    'owner''s decision, recorded in the ADR, not an implementation gap.';

-- One row per (brand-scoped) channel identity, linked to a customer account
-- when ADR 0058''s handshake has resolved one. A chat with no linked customer
-- is still a conversation (ADR 0059) — customer_account_id is nullable and
-- stays that way until a handshake, or a flow-captured contact, creates the
-- link.
CREATE TABLE conversations.conversations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    -- Which installation (bot) this conversation talks through — needed to
    -- resolve the outbound send path (base URL, secret reference) without a
    -- second round trip through the chat's binding, which may not exist yet.
    installation_id uuid NOT NULL,

    channel varchar(24) NOT NULL,
    channel_chat_id bigint NOT NULL,

    customer_account_id uuid,

    -- IDLE: exists, no flow currently running (before the first /start, or
    -- after a run completes). FLOW_ACTIVE: an ACTIVE conversations.flow_runs
    -- row exists. HANDED_TO_OPERATOR: the operator-handoff block parked it —
    -- stage 2's inbox is what returns it, not built here, but the state and a
    -- way to list conversations in it both need to exist now so stage 2 has
    -- something to build on. CLOSED: reserved for the future retention/
    -- erasure sweep ADR 0029 does not have machinery for yet.
    state varchar(24) NOT NULL DEFAULT 'IDLE',

    -- ADR 0059's stated PII posture: "Default retention is 12 months,
    -- tenant-adjustable downward." Recorded here; enforcement (a sweep that
    -- actually erases past this window) is a named ADR 0029 gap, not built by
    -- this stage.
    retention_months integer NOT NULL DEFAULT 12,

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,

    CONSTRAINT uq_conversation_identity UNIQUE (tenant_id, id),
    CONSTRAINT fk_conversation_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_conversation_installation FOREIGN KEY (tenant_id, installation_id)
        REFERENCES integration.installations (tenant_id, id),
    CONSTRAINT fk_conversation_customer FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT uq_conversation_chat UNIQUE (tenant_id, brand_id, channel, channel_chat_id),
    CONSTRAINT ck_conversation_channel CHECK (channel IN ('TELEGRAM')),
    CONSTRAINT ck_conversation_state CHECK (state IN ('IDLE', 'FLOW_ACTIVE', 'HANDED_TO_OPERATOR', 'CLOSED')),
    CONSTRAINT ck_conversation_retention CHECK (retention_months > 0)
);

CREATE INDEX ix_conversation_tenant ON conversations.conversations (tenant_id);
CREATE INDEX ix_conversation_customer ON conversations.conversations (tenant_id, customer_account_id)
    WHERE customer_account_id IS NOT NULL;
CREATE INDEX ix_conversation_handed_to_operator ON conversations.conversations (tenant_id, brand_id)
    WHERE state = 'HANDED_TO_OPERATOR';

COMMENT ON TABLE conversations.conversations IS
    'ADR 0059: a channel identity, optionally linked to customer.customer_accounts. '
    'Channel-neutral by column shape (channel + channel_chat_id), Telegram-only by '
    'CHECK constraint until a second adapter ships.';

-- A conversation's position inside one flow document: which state it is at,
-- what it has captured so far, and (for a delay block) when it is next due.
CREATE TABLE conversations.flow_runs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    flow_document_id uuid NOT NULL,

    -- Snapshotted at run creation so a document re-authored mid-run does not
    -- change the meaning of a state this run is already sitting in — the same
    -- discipline ApprovalPolicyService's own versioning uses.
    flow_version integer NOT NULL,

    current_state_id varchar(64) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'ACTIVE',

    -- One encrypted JSON object, e.g. {"feedback": "..."}. ADR 0059: "captured
    -- inputs... are envelope-encrypted." A single blob rather than one row per
    -- field: stage 1's welcome series captures exactly one field, and a
    -- per-field table is exactly the kind of "infrastructure for hypothetical
    -- scale" AGENTS.md tells us not to build ahead of a second caller.
    captured_fields_protected text,

    -- Armed by a delay block; the resume sweeper claims rows where this is
    -- due (ADR 0059: "a delay block genuinely needs a resume").
    resume_due_at timestamptz,

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,

    CONSTRAINT fk_flow_run_conversation FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES conversations.conversations (tenant_id, id),
    CONSTRAINT fk_flow_run_document FOREIGN KEY (tenant_id, flow_document_id)
        REFERENCES conversations.flow_documents (tenant_id, id),
    CONSTRAINT ck_flow_run_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'HANDED_TO_OPERATOR', 'ABANDONED'))
);

-- At most one ACTIVE run per conversation, database-enforced rather than
-- trusted to the service layer.
CREATE UNIQUE INDEX uq_flow_run_one_active
    ON conversations.flow_runs (tenant_id, conversation_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_flow_run_resume_due ON conversations.flow_runs (resume_due_at)
    WHERE status = 'ACTIVE' AND resume_due_at IS NOT NULL;

-- Message history. ADR 0059's PII posture, applied literally: "free-text
-- message bodies... are envelope-encrypted; channel ids, block ids, and
-- timing metadata are not." body_protected holds every message uniformly —
-- both directions — because an outbound message can itself echo a captured
-- field back to the customer (a thank-you that names what they typed), so
-- there is no outbound-is-always-safe shortcut to take here.
CREATE TABLE conversations.conversation_messages (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    direction varchar(8) NOT NULL,

    -- The block that produced (OUTBOUND) or consumed (INBOUND) this message.
    -- Not encrypted — a state machine position, not a fact about the person.
    block_id varchar(64),

    body_protected text NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_conversation_message_conversation FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES conversations.conversations (tenant_id, id),
    CONSTRAINT ck_conversation_message_direction CHECK (direction IN ('INBOUND', 'OUTBOUND'))
);

CREATE INDEX ix_conversation_message_conversation
    ON conversations.conversation_messages (tenant_id, conversation_id, occurred_at);

GRANT SELECT, INSERT, UPDATE ON conversations.flow_documents TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON conversations.conversations TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON conversations.flow_runs TO horecaos_application;
GRANT SELECT, INSERT ON conversations.conversation_messages TO horecaos_application;
