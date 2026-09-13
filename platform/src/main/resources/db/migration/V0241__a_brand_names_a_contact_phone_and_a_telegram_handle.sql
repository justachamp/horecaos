-- Settings 10.1 (operations-gap-map P32): a brand's own profile is a read-out
-- of four of thirteen fields today. `tenant.brands` is code/slug/display_name
-- /status and nothing else -- renaming already works
-- (TenantControlPlaneService.reviseBrand, PUT .../brands/{brandId}), but a
-- brand cannot say how a customer reaches it.
--
-- Two plain columns, not a versioned or localized concept: a brand has
-- exactly one main phone and one Telegram handle regardless of which
-- storefront language is showing it, the same way `tenant.locations.
-- contact_phone` (V0023) is one column per branch. The per-language facts
-- (description, supported languages) are V0242's table instead.
ALTER TABLE tenant.brands
    ADD COLUMN contact_phone varchar(32),
    ADD COLUMN telegram_handle varchar(64);

COMMENT ON COLUMN tenant.brands.contact_phone IS
    'The brand''s own main phone, shown across every storefront regardless of branch. E.164, like tenant.locations.contact_phone.';
COMMENT ON COLUMN tenant.brands.telegram_handle IS
    'The brand''s public Telegram handle (without the leading @), for the storefront and receipts -- not the bot integration token (10.5), a different concept.';

ALTER TABLE tenant.brands
    ADD CONSTRAINT ck_brands_contact_phone CHECK (
        contact_phone IS NULL OR contact_phone ~ '^\+[1-9][0-9]{7,14}$'
    );

ALTER TABLE tenant.brands
    ADD CONSTRAINT ck_brands_telegram_handle CHECK (
        telegram_handle IS NULL OR telegram_handle ~ '^[A-Za-z][A-Za-z0-9_]{4,31}$'
    );
