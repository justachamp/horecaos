-- ADR 0071: order reviews. A customer rates their own COMPLETED order, once,
-- and the rating is never shown to anyone but the author and the tenant's own
-- staff. No moderation, no fact-layer entry, no per-product or per-courier
-- review — see the ADR for the alternatives this schema deliberately does not
-- become.

CREATE SCHEMA IF NOT EXISTS reviews;
GRANT USAGE ON SCHEMA reviews TO horecaos_application;

-- One row per order, ever. brand_id and customer_account_id are denormalized
-- from the order at submission time (reporting.fact_order's own discipline)
-- so every read this ADR needs — "this location's reviews", "this customer's
-- reviews left" — is a filter on this one table, never a join back into
-- ordering.
CREATE TABLE reviews.order_reviews (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    order_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,

    rating smallint NOT NULL,

    -- ADR 0029 PERSONAL: a customer's own words about a real visit, which can
    -- and will eventually name a server, a courier, or another guest. Stored
    -- exactly the way conversations.conversation_messages.body_protected is —
    -- FieldProtection.protect, DataClass.PERSONAL, a RecordRef binding the
    -- ciphertext to this row. Nullable: a rating with no comment is a
    -- complete review.
    comment_protected text,

    submitted_at timestamptz NOT NULL DEFAULT now(),

    -- Identity for anything that might one day reference a review by id.
    -- Nothing does yet (this module exposes no api package — see the ADR),
    -- but every other aggregate in this platform carries this and a review is
    -- not an exception.
    CONSTRAINT uq_order_review_identity UNIQUE (tenant_id, id),

    -- The whole of "once per order": database-enforced, not merely checked in
    -- the service layer, so a race between two requests for the same order
    -- cannot produce two rows no matter what the application code does.
    CONSTRAINT uq_order_review_one_per_order UNIQUE (tenant_id, order_id),

    CONSTRAINT fk_order_review_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    -- tenant.locations' own unique key is three columns (tenant_id, brand_id,
    -- id) — a two-column (tenant_id, location_id) reference would not satisfy
    -- it, the exact V0046 lesson AGENTS.md names.
    CONSTRAINT fk_order_review_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_order_review_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT fk_order_review_customer FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),

    CONSTRAINT ck_order_review_rating CHECK (rating BETWEEN 1 AND 5)
);

-- The operations Reviews screen's own query shape: one brand, optionally one
-- location, newest first.
CREATE INDEX ix_order_review_brand ON reviews.order_reviews (tenant_id, brand_id, submitted_at DESC);
CREATE INDEX ix_order_review_location ON reviews.order_reviews (tenant_id, location_id, submitted_at DESC);

-- Customer detail's (§5.2) "reviews left" read, and the storefront's own "my
-- reviews".
CREATE INDEX ix_order_review_customer ON reviews.order_reviews (tenant_id, customer_account_id, submitted_at DESC);

COMMENT ON TABLE reviews.order_reviews IS
    'ADR 0071. Immutable once written: no UPDATE statement exists anywhere in '
    'the reviews module, which is what "once per order, forever" means at the '
    'schema level and not only in prose.';

-- INSERT and SELECT only — there is no UPDATE or DELETE path anywhere in this
-- module by design (see the table comment above).
GRANT SELECT, INSERT ON reviews.order_reviews TO horecaos_application;
