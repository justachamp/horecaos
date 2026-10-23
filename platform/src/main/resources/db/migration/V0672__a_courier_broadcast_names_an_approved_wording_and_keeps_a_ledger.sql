-- ADR 0182 (D15): a courier broadcast is operational messaging from an approved wording, and it
-- keeps a ledger of who was addressed.
--
-- V0307 gave marketing.courier_broadcasts a free-text message (varchar 480), and
-- CourierBroadcastService#send called recordSent(recipientCount) after a wiring check and enqueued
-- nothing. Two things were wrong with that beyond the missing number to send to.
--
--   1. ADR 0091 holds the VAS gateway as moderating: a wording it has not approved is withheld.
--      Free text typed into a form cannot have been approved in advance. So a broadcast now names
--      an ADR 0020 SMS template of the new class COURIER_DISPATCH that the tenant authored and the
--      gateway approved, and supplies the values of the variables it declares. Short values only:
--      the wording is what the gateway approves, and the values are the dispatcher's own.
--      A broadcast drafted before this migration keeps its message, which is why `message` becomes
--      nullable and exactly one of the two is present.
--
--   2. A count is not a ledger. recipient_count says how many couriers a send resolved to and cannot
--      say which of them was messaged, which had nothing to be messaged on, or what became of each
--      message. marketing.courier_broadcast_recipients is one row per targeted courier. It holds
--      what marketing knows -- the courier's non-personal reference, the ADR 0020 intent that was
--      created for them, or that they had no number -- and never what a gateway said about it:
--      that is read from the notification's evidence when asked, the rule ADR 0146 states for a
--      campaign's recipients, so the two can never drift apart.
--
-- The ADR 0020 pipeline learns a recipient that is a courier and not a customer:
--
--   * a recipient endpoint can be a courier (courier_id). It carries the courier's id and nothing
--     else; the number is read from the identity provider for the length of one send (ADR 0029),
--     exactly as a customer's number is revealed from its contact point per send;
--   * a notification can be of the class COURIER_DISPATCH: an operational message to a courier,
--     with no data subject in ADR 0015's sense, so no consent, no preference and no quiet hours.
--
-- Nothing here touches marketing.campaign_recipients, which stays keyed by a customer account: the
-- V0307 comment that rejects widening it stands, and ADR 0182 answers ADR 0112's open input
-- "couriers as an audience" the same way.

-- ------------------------------------------------------------------- the broadcast's wording

ALTER TABLE marketing.courier_broadcasts
    ADD COLUMN template_key varchar(120),
    ADD COLUMN variables jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE marketing.courier_broadcasts ALTER COLUMN message DROP NOT NULL;

ALTER TABLE marketing.courier_broadcasts
    ADD CONSTRAINT ck_courier_broadcast_wording CHECK ((message IS NULL) <> (template_key IS NULL)),
    ADD CONSTRAINT ck_courier_broadcast_variables CHECK (jsonb_typeof(variables) = 'object');

COMMENT ON COLUMN marketing.courier_broadcasts.template_key IS
    'ADR 0182. The key of an active SMS template of class COURIER_DISPATCH. Exactly one of message and template_key is set: a broadcast drafted before V0672 keeps its free text, a new one names a wording the gateway has approved.';
COMMENT ON COLUMN marketing.courier_broadcasts.variables IS
    'ADR 0182. The values the dispatcher gave the template''s variables: short strings, checked against what the template declares. Operational text; a person''s name or number does not belong in one.';

-- ------------------------------------------------------------------------ the recipient ledger

CREATE TABLE marketing.courier_broadcast_recipients (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    broadcast_id uuid NOT NULL,
    courier_id uuid NOT NULL,

    -- fulfillment.couriers.display_reference (ADR 0029): "K-014", a handle a screen may show
    -- without revealing who the courier is. Copied at send so the ledger reads the same after the
    -- courier is archived.
    courier_reference varchar(32) NOT NULL,

    -- QUEUED: an ADR 0020 intent was created and its evidence says the rest.
    -- NO_CONTACT: the identity provider holds no usable number for the courier, so nothing was
    --             created. The dispatcher fixes the courier's profile and drafts again.
    state varchar(16) NOT NULL,
    notification_id uuid,

    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_courier_broadcast_recipient UNIQUE (tenant_id, broadcast_id, courier_id),
    CONSTRAINT fk_courier_broadcast_recipient_broadcast FOREIGN KEY (broadcast_id, tenant_id)
        REFERENCES marketing.courier_broadcasts (id, tenant_id),
    CONSTRAINT fk_courier_broadcast_recipient_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id),
    CONSTRAINT ck_courier_broadcast_recipient_state CHECK (state IN ('QUEUED', 'NO_CONTACT')),
    CONSTRAINT ck_courier_broadcast_recipient_notification CHECK ((state = 'QUEUED') = (notification_id IS NOT NULL))
);

CREATE INDEX ix_courier_broadcast_recipient_broadcast
    ON marketing.courier_broadcast_recipients (tenant_id, broadcast_id, courier_reference);

COMMENT ON TABLE marketing.courier_broadcast_recipients IS
    'ADR 0182. One row per courier a broadcast targeted: who was addressed (an ADR 0020 intent) and who had no number. Holds no number and no delivery state -- the state of a message is read from its notification''s evidence when asked.';

GRANT SELECT, INSERT ON marketing.courier_broadcast_recipients TO horecaos_application;

-- -------------------------------------------------------------- the pipeline learns a courier

ALTER TABLE notifications.recipient_endpoints ADD COLUMN courier_id uuid;

ALTER TABLE notifications.recipient_endpoints DROP CONSTRAINT ck_endpoint_type;
ALTER TABLE notifications.recipient_endpoints
    ADD CONSTRAINT ck_endpoint_type CHECK (
        endpoint_type IN ('PHONE', 'EMAIL', 'PUSH_TOKEN', 'OPERATIONS_ROUTE', 'PROVIDER_BINDING', 'COURIER'));

-- Exactly one destination, now four-way, stated as V0100 stated three.
ALTER TABLE notifications.recipient_endpoints DROP CONSTRAINT ck_endpoint_destination;
ALTER TABLE notifications.recipient_endpoints
    ADD CONSTRAINT ck_endpoint_destination CHECK (
        (CASE WHEN contact_point_id IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN operations_endpoint_reference IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN provider_binding_id IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN courier_id IS NOT NULL THEN 1 ELSE 0 END) = 1);

-- A courier is never a customer: V0107's three clauses, and a fourth that says so.
ALTER TABLE notifications.recipient_endpoints DROP CONSTRAINT ck_endpoint_owner;
ALTER TABLE notifications.recipient_endpoints
    ADD CONSTRAINT ck_endpoint_owner CHECK (
        (contact_point_id IS NOT NULL AND customer_account_id IS NOT NULL)
        OR (operations_endpoint_reference IS NOT NULL AND customer_account_id IS NULL)
        OR (provider_binding_id IS NOT NULL)
        OR (courier_id IS NOT NULL AND customer_account_id IS NULL));

ALTER TABLE notifications.recipient_endpoints
    ADD CONSTRAINT fk_endpoint_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id);

-- One endpoint per courier, mirroring ux_endpoint_contact_point and ux_endpoint_provider_binding:
-- two for one courier would send the same broadcast twice and the duplicate would look like a
-- gateway fault.
CREATE UNIQUE INDEX ux_endpoint_courier
    ON notifications.recipient_endpoints (tenant_id, courier_id)
    WHERE courier_id IS NOT NULL;

COMMENT ON COLUMN notifications.recipient_endpoints.courier_id IS
    'ADR 0182. A courier as a destination: the id only. The number is read from the identity provider for the length of one send and kept nowhere on this platform, the same discipline contact_point_id follows for a customer.';

-- A notification can be an operational message to a courier.
ALTER TABLE notifications.notifications DROP CONSTRAINT ck_notification_class;
ALTER TABLE notifications.notifications
    ADD CONSTRAINT ck_notification_class CHECK (
        notification_class IN ('TRANSACTIONAL_REQUIRED', 'TRANSACTIONAL_OPTIONAL',
                               'MARKETING', 'SECURITY', 'OPERATIONS_ALERT', 'COURIER_DISPATCH'));

ALTER TABLE notifications.templates DROP CONSTRAINT ck_template_class;
ALTER TABLE notifications.templates
    ADD CONSTRAINT ck_template_class CHECK (
        notification_class IN ('TRANSACTIONAL_REQUIRED', 'TRANSACTIONAL_OPTIONAL',
                               'MARKETING', 'SECURITY', 'OPERATIONS_ALERT', 'COURIER_DISPATCH'));
