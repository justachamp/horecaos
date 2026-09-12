-- ADR 0106 (wave P35), gap-map row X.14: a provider secret's last-used watermark.
--
-- After rotating a Click or Payme secret an operator gets no evidence of when
-- it was last used at all -- no column, no field, no endpoint anywhere. This
-- adds the column; ProviderCapabilityReconciliationService stamps it in the
-- same UPDATE it already issues, exactly when the preflight secret-resolve
-- succeeds (see ADR 0106's Specification for what this does and does not
-- prove: it is evidence the secret still resolves, not evidence of a live
-- provider call that actually left the platform).
ALTER TABLE integration.installations
    ADD COLUMN secret_last_used_at timestamptz;

COMMENT ON COLUMN integration.installations.secret_last_used_at IS
    'ADR 0106. Stamped by ProviderCapabilityReconciliationService when the installation''s secret reference last resolved successfully during a capability-reconciliation preflight. Not proof of a live provider call.';
