-- ADR 0182 (D14): Web Push is a customer campaign channel, from the two storefronts, after an explicit
-- opt-in.
--
-- 1. notifications.push_subscriptions. The registry of a customer's browser subscriptions. A
--    subscription is a push service URL plus the browser's two keys (p256dh, auth). The URL is a
--    capability: whoever holds it and a VAPID key can push to that browser, and it names a device. So
--    all three are stored as ONE ADR 0029 ciphertext (FieldProtection, PERSONAL, bound to this row),
--    never in a column, a log, an event or a URL. endpoint_hash is the keyed lookup hash of the URL,
--    which is what lets one browser belong to one customer at a time without the URL being readable.
--
--    One ACTIVE subscription per customer per brand: a new one replaces the old one (ADR 0182 records
--    this as an accepted trade-off). A subscription that the push service reports gone, or that the
--    customer turns off, keeps its row for the audit and loses its ciphertext in the same statement
--    (ck_push_subscription_secret): the secret does not outlive its use.
--
-- 2. recipient_endpoints.push_subscription_id. The fifth destination an ADR 0020 endpoint can name, as
--    courier_id was the fourth (V0672). The endpoint is the subscription's id and nothing else; the
--    pipeline resolves the secret from the registry for the length of one send.
--
-- 3. notifications.push_click_tokens. A push notification carries an opaque token in its (encrypted)
--    payload, and the service worker posts it back when the customer taps the notification. That tap
--    is the only read signal Web Push offers. Like the unsubscribe token (V0671) it is 192 random bits
--    stored only as a SHA-256, and it names the notification it was minted for.
--
-- 4. template_versions.cover_asset_id / target_path. What a push adds to a wording: a 3:1 cover image
--    (an ADR 0010 media asset id, checked at authoring) and the storefront path a tap opens.

CREATE TABLE notifications.push_subscriptions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,

    -- FieldProtection.serialize() of {"endpoint","p256dh","auth"}; null once the subscription is over.
    secret_ciphertext text,
    -- FieldProtection.lookupHash of the endpoint URL (domain notifications.push.endpoint).
    endpoint_hash varchar(128) NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    -- Why a subscription ended, as a bounded code: never a push service's text, never a URL.
    ended_reason varchar(40),
    -- The browser's own expirationTime, when it gave one.
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    last_accepted_at timestamptz,
    ended_at timestamptz,

    CONSTRAINT uq_push_subscription_identity UNIQUE (id, tenant_id),
    CONSTRAINT fk_push_subscription_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT fk_push_subscription_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT ck_push_subscription_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'REVOKED')),
    -- A live subscription holds its secret and an ended one does not. Stated as an equality so that
    -- neither a live row without a secret nor an ended row that kept one can exist.
    CONSTRAINT ck_push_subscription_secret CHECK ((status = 'ACTIVE') = (secret_ciphertext IS NOT NULL)),
    CONSTRAINT ck_push_subscription_end CHECK ((status = 'ACTIVE') = (ended_at IS NULL))
);

-- One browser belongs to one customer at a time, and one customer has one live subscription per brand.
CREATE UNIQUE INDEX ux_push_subscription_endpoint
    ON notifications.push_subscriptions (tenant_id, endpoint_hash)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX ux_push_subscription_account
    ON notifications.push_subscriptions (tenant_id, brand_id, customer_account_id)
    WHERE status = 'ACTIVE';

COMMENT ON TABLE notifications.push_subscriptions IS
    'ADR 0182. A customer''s Web Push subscription, from the storefront''s own service worker after an explicit opt-in. The push service URL and the browser''s keys are one ADR 0029 ciphertext; an ended subscription keeps no secret.';
COMMENT ON COLUMN notifications.push_subscriptions.secret_ciphertext IS
    'FieldProtection ciphertext of {endpoint, p256dh, auth}. The endpoint is a capability that names a device: it is never logged, never in an event, a URL or an error.';

GRANT SELECT, INSERT, UPDATE ON notifications.push_subscriptions TO horecaos_application;

-- ---------------------------------------------------------- the endpoint a subscription stands for

ALTER TABLE notifications.recipient_endpoints ADD COLUMN push_subscription_id uuid;

-- Exactly one destination, now five-way, stated as V0100 and V0672 stated theirs.
ALTER TABLE notifications.recipient_endpoints DROP CONSTRAINT ck_endpoint_destination;
ALTER TABLE notifications.recipient_endpoints
    ADD CONSTRAINT ck_endpoint_destination CHECK (
        (CASE WHEN contact_point_id IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN operations_endpoint_reference IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN provider_binding_id IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN courier_id IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN push_subscription_id IS NOT NULL THEN 1 ELSE 0 END) = 1);

-- A push subscription is always a customer's own.
ALTER TABLE notifications.recipient_endpoints DROP CONSTRAINT ck_endpoint_owner;
ALTER TABLE notifications.recipient_endpoints
    ADD CONSTRAINT ck_endpoint_owner CHECK (
        (contact_point_id IS NOT NULL AND customer_account_id IS NOT NULL)
        OR (operations_endpoint_reference IS NOT NULL AND customer_account_id IS NULL)
        OR (provider_binding_id IS NOT NULL)
        OR (courier_id IS NOT NULL AND customer_account_id IS NULL)
        OR (push_subscription_id IS NOT NULL AND customer_account_id IS NOT NULL));

ALTER TABLE notifications.recipient_endpoints
    ADD CONSTRAINT fk_endpoint_push_subscription FOREIGN KEY (push_subscription_id, tenant_id)
        REFERENCES notifications.push_subscriptions (id, tenant_id);

-- One endpoint per subscription, mirroring ux_endpoint_contact_point and ux_endpoint_courier.
CREATE UNIQUE INDEX ux_endpoint_push_subscription
    ON notifications.recipient_endpoints (tenant_id, push_subscription_id)
    WHERE push_subscription_id IS NOT NULL;

COMMENT ON COLUMN notifications.recipient_endpoints.push_subscription_id IS
    'ADR 0182. A customer''s Web Push subscription as a destination: the id only. The push service URL and keys stay in notifications.push_subscriptions, encrypted, and are read for the length of one send.';

-- ------------------------------------------------------------------------ the click that is a read

CREATE TABLE notifications.push_click_tokens (
    token_hash varchar(64) PRIMARY KEY,
    tenant_id uuid NOT NULL,
    notification_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,

    CONSTRAINT fk_push_click_token_notification FOREIGN KEY (notification_id, tenant_id)
        REFERENCES notifications.notifications (id, tenant_id),
    CONSTRAINT ck_push_click_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_push_click_token_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_push_click_token_notification
    ON notifications.push_click_tokens (tenant_id, notification_id);

CREATE INDEX ix_push_click_token_expiry
    ON notifications.push_click_tokens (expires_at);

COMMENT ON TABLE notifications.push_click_tokens IS
    'ADR 0182. The SHA-256 of the opaque token inside a push notification''s encrypted payload, and the notification it was minted for. The service worker posts the token when the customer taps the notification; that tap is the read. Holds no token and no subscription.';

GRANT SELECT, INSERT, DELETE ON notifications.push_click_tokens TO horecaos_application;

-- ------------------------------------------------------------------ what a push wording adds

ALTER TABLE notifications.template_versions
    ADD COLUMN cover_asset_id uuid,
    ADD COLUMN target_path varchar(200),
    -- A path on the storefront's own origin: a leading slash and no second one, so it cannot be read
    -- as a protocol-relative URL to somebody else's site.
    ADD CONSTRAINT ck_template_version_target_path CHECK (
        target_path IS NULL OR target_path ~ '^/([^/\s\\].*)?$');

COMMENT ON COLUMN notifications.template_versions.cover_asset_id IS
    'ADR 0182. A push wording''s 3:1 cover, an ADR 0010 media asset id checked at authoring. Null on every other channel and on a push without one.';
COMMENT ON COLUMN notifications.template_versions.target_path IS
    'ADR 0182. The storefront path a tap on a push notification opens, relative to the storefront''s own origin. Never an absolute URL.';
