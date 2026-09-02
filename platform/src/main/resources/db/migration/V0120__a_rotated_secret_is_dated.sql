-- ADR 0065: the tenant self-service Integrations screen shows "last rotated"
-- for both a provider installation and a merchant binding. Neither table
-- carried the fact; updated_at is not a substitute, because it moves on any
-- edit, not only a secret rotation. Nullable and unbackfilled: null means
-- "never rotated through this platform", which is exactly true for every
-- existing row.

ALTER TABLE integration.installations
    ADD COLUMN last_secret_rotated_at timestamptz;

COMMENT ON COLUMN integration.installations.last_secret_rotated_at IS
    'ADR 0065. Set by both secret-rotation endpoints; null means never rotated through the platform.';

ALTER TABLE payments.merchant_bindings
    ADD COLUMN last_secret_rotated_at timestamptz;

COMMENT ON COLUMN payments.merchant_bindings.last_secret_rotated_at IS
    'ADR 0065. Set by the door-based rotation endpoint; null means never rotated through the platform.';
