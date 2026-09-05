-- ADR 0064: idempotent receipt of VOICE provider events.
--
-- Mirrors integration.telegram_processed_updates (V0099) exactly. A voice
-- provider event — a hosted PBX's webhook retry or an Asterisk AMI event
-- delivered twice across a reconnect — is deduplicated here before it ever
-- reaches voice.call_events, which is what lets that table stay a plain
-- append-only ledger with no ON CONFLICT logic of its own.
CREATE TABLE integration.voice_processed_events (
    tenant_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    -- The provider's own event id where one exists (a hosted PBX webhook's
    -- event id, or an Asterisk AMI "ActionID"/synthesized key); never the
    -- provider_call_id alone, since one call produces several distinct events.
    provider_event_id varchar(255) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, installation_id, provider_event_id)
);

GRANT SELECT, INSERT ON integration.voice_processed_events TO horecaos_application;
