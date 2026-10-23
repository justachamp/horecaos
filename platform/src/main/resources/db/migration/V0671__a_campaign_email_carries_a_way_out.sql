-- ADR 0182 (D13): a tenant's campaign email, sent through the platform's one mail relay, carries a
-- one-click way out, and the consent purpose it is sent under says that it covers email.
--
-- 1. notifications.unsubscribe_tokens. A campaign email's List-Unsubscribe header and footer link
--    both carry an opaque token. The token is 192 random bits, shown once in the message and never
--    stored: this table holds its SHA-256, so a reader of the table cannot unsubscribe anyone.
--    It names the notification it was minted for, and through it the customer, the brand and the
--    consent purpose the message was sent under; the unsubscribe page reads nothing else.
--
--    Deliberately no foreign key to customer.customer_accounts. An erased account must not be
--    blocked by a row whose only job is to withdraw a consent, and a token whose account has gone
--    withdraws nothing (the consent store refuses a withdrawal of what is not granted).
--
-- 2. The platform's own mail relay is a provider type with no ADR 0026 binding behind it: it is the
--    deployment's account, identical for every tenant. V0026 required an attempt's binding and
--    provider type to be both present or both absent (ck_attempt_binding_pair). The truth it was
--    protecting is "a binding names its provider", and that survives; the converse does not. An
--    attempt made through the relay records provider_type = PLATFORM_SMTP and no binding.
--    The daily cap counts a tenant's attempts on a channel since midnight UTC, so the table gets
--    the index that count needs.
--
-- 3. The shipped description of the MARKETING_PROMOTIONS consent purpose names SMS, Telegram and
--    push and not email (ConsentTypeService.DEFAULTS, seeded per tenant at first read). A purpose a
--    customer is asked to accept has to say what it covers, so the rows that still carry exactly
--    the shipped text are brought in line; a description a tenant has written itself is left alone.

CREATE TABLE notifications.unsubscribe_tokens (
    token_hash varchar(64) PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,
    notification_id uuid NOT NULL,
    channel varchar(16) NOT NULL DEFAULT 'EMAIL',
    consent_purpose varchar(64) NOT NULL,
    -- The language of the message, so the page a customer lands on speaks it.
    locale varchar(16),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,

    CONSTRAINT fk_unsubscribe_token_notification FOREIGN KEY (notification_id, tenant_id)
        REFERENCES notifications.notifications (id, tenant_id),
    CONSTRAINT fk_unsubscribe_token_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT ck_unsubscribe_token_channel CHECK (channel = 'EMAIL'),
    CONSTRAINT ck_unsubscribe_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_unsubscribe_token_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_unsubscribe_token_notification
    ON notifications.unsubscribe_tokens (tenant_id, notification_id);

CREATE INDEX ix_unsubscribe_token_expiry
    ON notifications.unsubscribe_tokens (expires_at);

COMMENT ON TABLE notifications.unsubscribe_tokens IS
    'ADR 0182. The SHA-256 of the opaque token in a campaign email''s List-Unsubscribe header and footer link, and what it withdraws: the consent purpose, on the EMAIL channel, for the customer the notification was sent to. Holds no address and no token. A token is valid until expires_at and may be used more than once: unsubscribing twice is the same act.';
COMMENT ON COLUMN notifications.unsubscribe_tokens.token_hash IS
    'SHA-256 (hex) of a 192-bit random token. The token itself is in the sent message and nowhere on the platform.';

GRANT SELECT, INSERT, DELETE ON notifications.unsubscribe_tokens TO horecaos_application;

-- ------------------------------------------------------- an attempt through the platform's relay

ALTER TABLE notifications.delivery_attempts DROP CONSTRAINT ck_attempt_binding_pair;
ALTER TABLE notifications.delivery_attempts
    ADD CONSTRAINT ck_attempt_binding_pair CHECK (provider_binding_id IS NULL OR provider_type IS NOT NULL);

CREATE INDEX ix_attempt_tenant_channel_requested
    ON notifications.delivery_attempts (tenant_id, channel, requested_at);

-- ------------------------------------------------------------------ consent purpose wording

UPDATE customer.consent_types
   SET description = 'Promotional messages and campaigns sent by SMS, email, Telegram, or push.',
       updated_at = now()
 WHERE code = 'MARKETING_PROMOTIONS'
   AND description = 'Promotional messages and campaigns sent by SMS, Telegram, or push.';
