-- ADR 0140 (status line "a customer-facing promotion title per locale"), gap-map row 6.1 (batch 21, wave
-- w6-promotions-reviews-approvals).
--
-- A promotion's `name` is the operator's own handle for a list and a report (the console says so), and its `code` is
-- the operator's stable key; neither is wording a storefront may print, which is why both storefronts showed only
-- the kind of benefit behind a price (a discount, a typed code, a delivery offer, a surcharge). A marketer who wants
-- the customer to read «Скидка на ланч» instead of «скидка» authors a title per locale, in the per-locale table
-- shape every other localized row uses (V0016, V0244, V0430, V0539).
--
-- Optional and null-safe: a promotion with no title for the locale shown is described exactly as before. A title is
-- part of the definition, so the immutable definition version records it (PromotionDefinition#canonical) and an
-- edit to it takes the same path as an edit to the rule: the promotion goes back to DRAFT. The locale is a
-- well-formed BCP 47 tag, not the closed platform triple; which locales a brand may author is its supported set
-- (tenant.brand_locales, V0242), enforced by the console and the validator.

CREATE TABLE pricing.promotion_titles (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    promotion_id uuid NOT NULL,
    locale varchar(16) NOT NULL,
    title varchar(120) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (promotion_id, locale),
    CONSTRAINT ck_promotion_title_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_promotion_title_text CHECK (length(btrim(title)) > 0),
    -- uq_promotion_identity (V0093) is (id, tenant_id, brand_id): a foreign key must match a unique constraint on
    -- exactly its own columns, so the brand is part of this reference.
    CONSTRAINT fk_promotion_title_promotion FOREIGN KEY (promotion_id, tenant_id, brand_id)
        REFERENCES pricing.promotions (id, tenant_id, brand_id) ON DELETE CASCADE
);

CREATE INDEX ix_promotion_titles_tenant ON pricing.promotion_titles (tenant_id, brand_id, promotion_id);

COMMENT ON TABLE pricing.promotion_titles IS
    'ADR 0140. The wording a storefront may print for a promotion, per locale. Optional: a promotion with none for the locale shown is described by the kind of benefit it gave. The operator''s own handle stays pricing.promotions.name.';

GRANT SELECT, INSERT, UPDATE, DELETE ON pricing.promotion_titles TO horecaos_application;
