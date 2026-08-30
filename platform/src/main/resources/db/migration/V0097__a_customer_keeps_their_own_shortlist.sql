-- Favourites: the dishes a customer marked to find again.
--
-- The legacy backend served `/customers/favourites/` and the platform served
-- nothing, so the storefront kept the list in the browser -- which meant
-- favouriting on a phone was invisible on any other device and clearing site
-- data lost it. This is the row behind that heart.
--
-- A *product*, not a variant. The heart sits on a food card, and a food card is
-- a product; asking a customer to favourite "osh, large" when they meant "osh"
-- is a distinction the interface never offered them.
--
-- Brand-scoped, like everything else a customer owns here. A product id belongs
-- to exactly one brand already, so a wider scope would buy nothing and would
-- make the foreign key unenforceable.

CREATE TABLE customer.favourites (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    account_id uuid NOT NULL,
    product_id uuid NOT NULL,

    created_at timestamptz NOT NULL DEFAULT now(),

    -- The natural key is the whole row. Favouriting twice is not two facts, and
    -- a surrogate id would let it become two.
    PRIMARY KEY (tenant_id, brand_id, account_id, product_id),

    CONSTRAINT fk_favourite_account FOREIGN KEY (account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id) ON DELETE CASCADE,
    -- The product must be this brand's. Without the brand in the reference a
    -- customer could favourite another brand's dish by id and it would sit in
    -- their list forever, unresolvable against any menu they can see.
    CONSTRAINT fk_favourite_product FOREIGN KEY (product_id, tenant_id, brand_id)
        REFERENCES catalog.products (id, tenant_id, brand_id) ON DELETE CASCADE
);

-- The only read: one customer's list, newest first.
CREATE INDEX ix_favourites_by_account
    ON customer.favourites (tenant_id, brand_id, account_id, created_at DESC);

COMMENT ON TABLE customer.favourites IS
    'Products a customer marked. Not personal data in itself, but a behavioural '
    'record tied to an account: it is deleted with the account rather than kept.';

GRANT SELECT, INSERT, DELETE ON customer.favourites TO horecaos_application;
