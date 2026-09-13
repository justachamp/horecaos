-- ADR 0012/0026, gap-map row 10.8b: the tenant-facing mapping API (list by
-- binding and entity type, optionally filtered by status; the unmapped-
-- external anti-join) has no index to serve it. uq_mapping_external and
-- uq_mapping_horecaos (V0013) exist to enforce uniqueness, not to serve this
-- read pattern, and neither carries status.
CREATE INDEX ix_mapping_binding_type_status
    ON integration.provider_entity_mappings (tenant_id, binding_id, entity_type, status, external_entity_id);

COMMENT ON INDEX integration.ix_mapping_binding_type_status IS
    'Serves the tenant-facing mapping list (by binding and entity type, optionally filtered by status) and the unmapped-external anti-join. Gap-map row 10.8b / wave P24.';
