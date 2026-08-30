-- ADR 0026: append-only capability evidence for provider categories whose
-- adapters cannot safely send a generic authenticated probe. POS retains its
-- specialised live-discovery table from V0036; this table records the shared
-- secret-reference preflight and adapter-declaration evidence for all others.
CREATE TABLE integration.provider_capability_probes (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    installation_id uuid NOT NULL,
    capability_code varchar(64) NOT NULL,
    probe_status varchar(16) NOT NULL,
    evidence varchar(1000) NOT NULL,
    adapter_version varchar(64) NOT NULL,
    probed_at timestamptz NOT NULL,

    CONSTRAINT fk_provider_capability_probe_installation
        FOREIGN KEY (tenant_id, installation_id)
        REFERENCES integration.installations (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_provider_capability_probe_status
        CHECK (probe_status IN ('SUPPORTED', 'UNSUPPORTED', 'UNVERIFIABLE'))
);

CREATE INDEX ix_provider_capability_probe_latest
    ON integration.provider_capability_probes
        (tenant_id, installation_id, capability_code, probed_at DESC);

COMMENT ON TABLE integration.provider_capability_probes IS
    'ADR 0026 append-only non-POS provider preflight evidence. It records a resolvable secret reference and an adapter-declared capability, never a credential or provider response body.';

GRANT SELECT, INSERT ON integration.provider_capability_probes TO horecaos_application;
