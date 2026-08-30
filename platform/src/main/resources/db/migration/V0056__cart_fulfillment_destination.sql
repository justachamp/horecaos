-- ADR 0019's `ordering.cart_fulfillment`, arriving with the address capture it
-- exists to hold, exactly as V0022 lines 168-181 said it would.
--
-- V0022 left it out because "a table nobody writes is schema everybody has to
-- reason about, and worse, it reads as a capability that exists". The capability
-- now exists: CartService.setDestination writes this row, CheckoutService
-- snapshots it onto the order, and JdbcDeliveryOrderPort hands it to ADR 0014
-- sourcing. The requested-time column ADR 0019 also names is still absent, for
-- the same reason and by the same rule: scheduled orders are not built, so
-- nothing would write it.

-- The key the destination points at. A destination belongs to a DELIVERY cart and
-- to no other kind, and this is what lets the database say so: the FK below
-- carries the mode, so a collected cart cannot acquire a doorstep and a cart with
-- a doorstep cannot be turned into a collection. `id` is already the primary key,
-- so this index is a constraint rather than a new access path.
ALTER TABLE ordering.carts
    ADD CONSTRAINT uq_cart_identity_mode UNIQUE (id, tenant_id, fulfillment_mode);

CREATE TABLE ordering.cart_fulfillment (
    cart_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    -- Carried only so the foreign key can constrain it. Always 'DELIVERY'.
    fulfillment_mode varchar(16) NOT NULL,

    -- Provenance, and deliberately NOT a foreign key. The columns beside it are a
    -- copy taken when the customer chose the address, so archiving or editing
    -- customer.addresses afterwards must leave this cart deliverable and this id
    -- pointing at a row that is no longer offered. A foreign key would make an
    -- ordinary profile edit able to break a cart in flight.
    customer_address_id uuid,

    -- ADR 0029. The whole destination as one encrypted document, the same shape
    -- customer.addresses stores it in: no query needs a street name, and
    -- splitting it into columns would leak structure without buying anything.
    -- подъезд, этаж, квартира and ориентир are fields inside the document.
    address_encrypted text NOT NULL,
    delivery_instructions_encrypted text,
    -- The person the courier asks for and the number he rings. Often not the
    -- account holder, which is why they are captured rather than inferred.
    recipient_name_encrypted text,
    recipient_phone_encrypted text,

    -- In clear, for the reason V0017 gives about customer.addresses: a delivery
    -- cannot be routed without them, and a coordinate identifies a building
    -- rather than a person. NOT NULL because a destination that cannot be
    -- measured from the branch is not one ADR 0037 can price or ADR 0014 can
    -- source, and because ShipmentBookingPort.Waypoint takes primitive doubles —
    -- a landmark-only address admitted this far is a courier sent to 0,0.
    --
    -- The order's copy of the same destination puts the coordinate INSIDE the
    -- ciphertext instead, and the asymmetry is deliberate: a cart lives four
    -- hours, while an order lives for years and is crypto-shredded, and a clear
    -- coordinate would survive the shred still pointing at the building somebody
    -- lived in.
    latitude double precision NOT NULL,
    longitude double precision NOT NULL,

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_cart_fulfillment_is_delivery CHECK (fulfillment_mode = 'DELIVERY'),
    CONSTRAINT ck_cart_fulfillment_coordinates CHECK (
        latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180),
    CONSTRAINT fk_cart_fulfillment_cart FOREIGN KEY (cart_id, tenant_id, fulfillment_mode)
        REFERENCES ordering.carts (id, tenant_id, fulfillment_mode) ON DELETE CASCADE
);

COMMENT ON TABLE ordering.cart_fulfillment IS
    'ADR 0019 cart destination. A copy taken when the customer chose a saved address, never a reference: editing or archiving customer.addresses must not change where a cart in flight is going.';
COMMENT ON COLUMN ordering.cart_fulfillment.customer_address_id IS
    'ADR 0015 provenance only. No foreign key: the address may be archived while this cart is still deliverable.';
COMMENT ON COLUMN ordering.cart_fulfillment.latitude IS
    'ADR 0037/0014. Required: an address with no point cannot be priced, measured, or handed to a partner booking.';

-- No DELETE: a destination is replaced by upsert, and the row goes when the cart
-- does, through the referential action above rather than through a grant.
GRANT SELECT, INSERT, UPDATE ON ordering.cart_fulfillment TO qoida_application;
