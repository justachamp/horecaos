-- ADR 0058 (rollout stage 1): TELEGRAM joins the ADR 0020 channel vocabulary,
-- and the recipient_endpoints table gains the binding-shaped variant its
-- comment already anticipated ("an alert destination that is not a customer
-- contact point").
--
-- Same append-only pattern V0038 used to add MARKETPLACE to
-- ck_installation_category and ck_provider_environment_category: DROP the
-- constraint, ADD it back with the value included. Nothing already stored
-- changes; the four places below all reject 'TELEGRAM' today and accept it
-- after this migration.

ALTER TABLE notifications.notification_preferences DROP CONSTRAINT ck_preference_channel;
ALTER TABLE notifications.notification_preferences
    ADD CONSTRAINT ck_preference_channel CHECK (
        channel IN ('SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP', 'TELEGRAM'));

ALTER TABLE notifications.templates DROP CONSTRAINT ck_template_channel;
ALTER TABLE notifications.templates
    ADD CONSTRAINT ck_template_channel CHECK (
        channel IN ('SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP', 'TELEGRAM'));

ALTER TABLE notifications.notifications DROP CONSTRAINT ck_notification_channel;
ALTER TABLE notifications.notifications
    ADD CONSTRAINT ck_notification_channel CHECK (
        channel IN ('SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP', 'TELEGRAM'));

ALTER TABLE notifications.delivery_attempts DROP CONSTRAINT ck_attempt_channel;
ALTER TABLE notifications.delivery_attempts
    ADD CONSTRAINT ck_attempt_channel CHECK (
        channel IN ('SMS', 'EMAIL', 'PUSH', 'MESSAGING_APP', 'TELEGRAM'));

-- --------------------------------------------------------- binding-shaped endpoint

-- notifications.recipient_endpoints already distinguishes a customer contact
-- point from a bare operations config string. A chat binding is neither: it is
-- an ADR 0026 object with its own lifecycle (activation, suspension,
-- retirement), not a value ADR 0015 owns and not free-form configuration. The
-- send path resolves it directly (see NotificationDispatchService), so the
-- column carries the binding id and nothing about the chat itself — exactly
-- the same "reference, never a value" discipline contact_point_id already
-- follows.
ALTER TABLE notifications.recipient_endpoints ADD COLUMN provider_binding_id uuid;

ALTER TABLE notifications.recipient_endpoints DROP CONSTRAINT ck_endpoint_type;
ALTER TABLE notifications.recipient_endpoints
    ADD CONSTRAINT ck_endpoint_type CHECK (
        endpoint_type IN ('PHONE', 'EMAIL', 'PUSH_TOKEN', 'OPERATIONS_ROUTE', 'PROVIDER_BINDING'));

-- Exactly one destination, now three-way. Counting non-nulls rather than
-- chaining <> the way the two-way version did: <> only ever proves "not both",
-- and a third column needs "exactly one", which a boolean count states
-- directly and a chain of inequalities does not generalise to.
ALTER TABLE notifications.recipient_endpoints DROP CONSTRAINT ck_endpoint_destination;
ALTER TABLE notifications.recipient_endpoints
    ADD CONSTRAINT ck_endpoint_destination CHECK (
        (CASE WHEN contact_point_id IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN operations_endpoint_reference IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN provider_binding_id IS NOT NULL THEN 1 ELSE 0 END) = 1);

-- customer_account_id is null for every operations destination, including a
-- binding-shaped one. This is a stage-1 fact, not a permanent one: ADR 0058's
-- customer 1:1 linking (out of scope this slice) will need its own
-- provider_binding_id endpoint that DOES carry a customer_account_id, which is
-- a future DROP/ADD of this same constraint once that stage's trigger exists
-- to write such a row — not before, for the reason ck_telegram_binding_audience
-- above gives.
ALTER TABLE notifications.recipient_endpoints DROP CONSTRAINT ck_endpoint_owner;
ALTER TABLE notifications.recipient_endpoints
    ADD CONSTRAINT ck_endpoint_owner CHECK (
        (customer_account_id IS NULL) = (
            operations_endpoint_reference IS NOT NULL OR provider_binding_id IS NOT NULL));

ALTER TABLE notifications.recipient_endpoints
    ADD CONSTRAINT fk_endpoint_provider_binding FOREIGN KEY (tenant_id, provider_binding_id)
        REFERENCES integration.bindings (tenant_id, id);

-- One endpoint per binding, mirroring ux_endpoint_contact_point: two endpoint
-- rows for the same chat would let the same event fan out to it twice.
CREATE UNIQUE INDEX ux_endpoint_provider_binding
    ON notifications.recipient_endpoints (tenant_id, provider_binding_id)
    WHERE provider_binding_id IS NOT NULL;
