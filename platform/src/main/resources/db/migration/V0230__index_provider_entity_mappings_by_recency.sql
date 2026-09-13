-- ADR 0012/0026, gap-map row 10.8b: the installation-detail mapping tab
-- ("Соответствия") lists a binding's recent mapping activity across every
-- entity type, newest first. Nothing in V0013 leads with binding_id and
-- updated_at, so that read would otherwise be a sequential scan per binding.
CREATE INDEX ix_mapping_binding_recency
    ON integration.provider_entity_mappings (tenant_id, binding_id, updated_at DESC, id DESC);

COMMENT ON INDEX integration.ix_mapping_binding_recency IS
    'Serves the cursor-paginated recent-mapping-activity read across every entity type for one binding. Gap-map row 10.8b / wave P24.';
