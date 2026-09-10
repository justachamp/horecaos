-- ADR 0086: the control plane's webhook log lists payment providers' calls
-- across every tenant, newest first. The one existing index leads with the
-- tenant and the payment attempt, which a platform-wide "what arrived last"
-- cannot use.
CREATE INDEX ix_provider_callback_received_platform ON payments.provider_callbacks (received_at DESC);
