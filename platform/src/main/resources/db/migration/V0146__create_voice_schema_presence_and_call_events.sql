-- ADR 0064: voice as a first-class channel — operator presence and the
-- normalized call-event ledger.
--
-- Two tables carry the two halves of the decision that are genuinely new
-- domain modelling (the ADR says so: no presence or telephony concept exists
-- anywhere in this codebase before this migration).
--
-- `operator_presence` is deliberately channel-neutral and deliberately not
-- telephony-private, per the ADR's own words: it is owned by the operations
-- surface, written by staff themselves, and must be readable by ADR 0059's
-- future inbox assignment without being rebuilt. There is nothing about a call
-- in this table.
--
-- `call_events` is the normalized vocabulary itself: one append-only row per
-- offered/answered/ended/missed/transferred event, correlated by
-- `provider_call_id`. It never carries a plaintext caller number — ADR 0029
-- classifies a caller's phone number as personal data, so it is envelope
-- encrypted exactly like `ordering.order_customer_snapshots.contact_encrypted`,
-- and it never appears on the `voice.events` Kafka topic at all (see
-- VoiceCallEventRecorded's payload, which carries only the resolved customer
-- account id).
--
-- `screen_pop_state` is the one mutable table here: whether the ringing call's
-- client card has been claimed by an operator yet. It is not folded into
-- `call_events`, which stays a pure fact ledger the reporting pipeline can
-- read without worrying that a row it already summed will change under it.
CREATE SCHEMA IF NOT EXISTS voice;

-- ------------------------------------------------------------ operator presence

CREATE TABLE voice.operator_presence (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant.tenants (id),
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,

    -- The Keycloak subject, not a staff/HR record id — the same
    -- varchar(255) principal-id shape this codebase already uses for a
    -- staff-initiated actor (e.g. V0099's requested_by_principal_id, V0111's
    -- imported_by_principal_id) rather than inventing a second identity
    -- column a "StaffMember" aggregate does not exist to fill.
    operator_principal_id varchar(255) NOT NULL,

    state varchar(16) NOT NULL,
    -- Required exactly on PAUSED. AuditFact separately requires a reason for
    -- every USER-actor fact, so this column is what makes the reason part of
    -- the queryable current state as well as the audit trail — a supervisor's
    -- roster view has no reason to open the audit log just to see why someone
    -- is away.
    reason varchar(255),

    changed_at timestamptz NOT NULL,
    version integer NOT NULL DEFAULT 1,

    CONSTRAINT fk_operator_presence_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_operator_presence_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    -- One current-state row per operator per location: a shift at a second
    -- branch is a second row, not a second state overwriting the first. A
    -- courier-shift-style "one open session" rule is not needed here because
    -- there is no separate open/close pair — OFFLINE already means closed.
    CONSTRAINT uq_operator_presence UNIQUE (tenant_id, location_id, operator_principal_id),
    CONSTRAINT ck_operator_presence_state CHECK (state IN ('ONLINE', 'PAUSED', 'WRAP_UP', 'OFFLINE')),
    CONSTRAINT ck_operator_presence_reason CHECK (state <> 'PAUSED' OR reason IS NOT NULL),
    CONSTRAINT ck_operator_presence_version CHECK (version >= 1)
);

CREATE INDEX ix_operator_presence_location_state ON voice.operator_presence (tenant_id, location_id, state);

COMMENT ON TABLE voice.operator_presence IS
    'ADR 0064: ONLINE/PAUSED/WRAP_UP/OFFLINE, channel-neutral, one row per operator per location.';

-- ---------------------------------------------------------------- call events

CREATE TABLE voice.call_events (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant.tenants (id),
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,

    -- The ADR 0026 installation and (when resolved) binding that produced this
    -- event. No FK to `integration.installations`/`bindings`: those tables are
    -- owned by a different module's schema and, per V0038's own precedent with
    -- `marketplace_binding_id`, a cross-schema FK from a fact ledger back to
    -- provider configuration is exactly the coupling that pattern avoids.
    installation_id uuid NOT NULL,
    binding_id uuid,

    -- Correlates every event of one call. Provider-scoped, never assumed
    -- globally unique on its own — always read together with tenant_id and
    -- installation_id, which is what `ix_call_events_provider_call` indexes.
    -- Not a uniqueness constraint: several rows legitimately share one
    -- provider_call_id, one per event_type in that call's lifecycle. The
    -- integration.voice_processed_events dedup table (V0147) is what refuses a
    -- retried delivery before it ever reaches this table.
    provider_call_id varchar(255) NOT NULL,

    event_type varchar(16) NOT NULL,
    direction varchar(16) NOT NULL DEFAULT 'INBOUND',
    line_did varchar(64),

    -- ADR 0029 envelope encryption, bound to this row exactly like
    -- `ordering.order_customer_snapshots.contact_encrypted`. Populated only on
    -- OFFERED, where the caller number is fresh from the provider event; other
    -- event types for the same call correlate by `provider_call_id` instead of
    -- repeating it. Kept for the day a supervisor investigation needs the full
    -- number under its own reveal-gated capability; nothing in this build
    -- reads it back (see caller_number_masked for what the screen-pop card
    -- actually displays).
    caller_number_encrypted text,

    -- Computed once, from the plaintext caller number ADR 0064's ingestion
    -- already holds in memory at OFFERED time, and stored unencrypted because
    -- a mask ("••••••••42") is not personal data — it is deliberately not
    -- reversible to the number it came from. This is what the screen-pop poll
    -- reads on every request: masking is not a reveal, so re-decrypting
    -- caller_number_encrypted on a 10-second poll cadence would either flood
    -- the ADR 0027 audit trail with a reveal fact every ten seconds for the
    -- length of every call, or (worse) silently stop being audited at all.
    caller_number_masked varchar(32),

    resolved_customer_account_id uuid,
    operator_principal_id varchar(255),

    -- ENDED only, computed against the OFFERED/ANSWERED row for the same
    -- `provider_call_id` at ingestion time. Not authoritative to the second —
    -- a convenience the reporting read also has to be able to derive from
    -- `occurred_at` alone if this is ever null.
    duration_seconds integer,

    transfer_target_line varchar(64),

    -- ADR 0064's exit criteria: "a missed call appears in the owner's stats
    -- with the operator roster of that moment." A snapshot taken at ingestion
    -- rather than a join against `operator_presence` at report time, because
    -- presence is a live table and a report run tomorrow must still show who
    -- was online when the call actually rang.
    online_operator_roster jsonb NOT NULL DEFAULT '[]'::jsonb,

    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_call_events_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_call_events_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT ck_call_events_type CHECK (
        event_type IN ('OFFERED', 'ANSWERED', 'ENDED', 'MISSED', 'TRANSFERRED')),
    CONSTRAINT ck_call_events_direction CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT ck_call_events_duration CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    CONSTRAINT ck_call_events_roster CHECK (jsonb_typeof(online_operator_roster) = 'array'),
    -- What voice.screen_pop_state's own composite FK references — a bare
    -- `id` foreign key would let one tenant's screen-pop row point at
    -- another tenant's call event, which the tenant-isolation lint refuses.
    CONSTRAINT uq_call_events_tenant_id UNIQUE (tenant_id, id)
);

-- The webhook/event-socket dedup table (integration.voice_processed_events,
-- V0147) is what actually prevents a duplicate row from a retried delivery;
-- this index is for the duration/roster lookups within one call, not a second
-- idempotency mechanism.
CREATE INDEX ix_call_events_provider_call ON voice.call_events (tenant_id, installation_id, provider_call_id);
CREATE INDEX ix_call_events_location_time ON voice.call_events (tenant_id, location_id, occurred_at);
CREATE INDEX ix_call_events_reporting_scan ON voice.call_events (tenant_id, occurred_at);

COMMENT ON TABLE voice.call_events IS
    'ADR 0064: the normalized call-event vocabulary. Append-only; never carries a plaintext caller number.';

-- ------------------------------------------------------------- screen-pop state

-- The one mutable table here. Keyed on the OFFERED event so a second operator
-- polling the same location sees the card disappear once the first one claims
-- it — a lightweight claim, not a hard lock, matching the ADR's own framing of
-- this as "the answering operator" rather than a queue assignment engine.
CREATE TABLE voice.screen_pop_state (
    offered_call_event_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    location_id uuid NOT NULL,
    acknowledged_by_principal_id varchar(255),
    acknowledged_at timestamptz,
    cleared_at timestamptz,

    -- Composite, against voice.call_events' own (tenant_id, id) unique
    -- constraint rather than a bare `id` FK, so a screen-pop row can never
    -- point at another tenant's call event.
    CONSTRAINT fk_screen_pop_call_event FOREIGN KEY (tenant_id, offered_call_event_id)
        REFERENCES voice.call_events (tenant_id, id),
    CONSTRAINT ck_screen_pop_ack_pairing CHECK (
        (acknowledged_by_principal_id IS NULL) = (acknowledged_at IS NULL))
);

CREATE INDEX ix_screen_pop_state_location_open
    ON voice.screen_pop_state (tenant_id, location_id)
    WHERE cleared_at IS NULL;

GRANT USAGE ON SCHEMA voice TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON voice.operator_presence TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON voice.call_events TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON voice.screen_pop_state TO horecaos_application;
