-- Settings 10.12 (operations-gap-map): a brand's regional display formats.
-- The console formatted every amount and phone one way for every brand -- the
-- currency unit after a total, groups of three separated by a no-break space,
-- a phone as the platform sent it -- and nothing let a brand say otherwise.
--
-- Three plain columns on the brand, next to contact_phone/telegram_handle
-- (V0241): they are how this brand's operators read money and phone numbers,
-- the same for every branch, not a versioned or localized concept. The
-- timezone is not here: it is already a tenant fact (tenant.tenants.
-- default_timezone) and each branch's own (tenant.locations.timezone).
--
-- The defaults reproduce what the console did before, so an existing brand
-- sees no change until someone edits it. The phone pattern is NULL for "show
-- the number as it arrives" for the same reason.
ALTER TABLE tenant.brands
    ADD COLUMN money_symbol_placement varchar(8) NOT NULL DEFAULT 'AFTER',
    ADD COLUMN money_grouping varchar(8) NOT NULL DEFAULT 'SPACE',
    ADD COLUMN phone_display_pattern varchar(32);

COMMENT ON COLUMN tenant.brands.money_symbol_placement IS
    'Where the currency unit is written on a total in the operator console: BEFORE the amount or AFTER it. Rows carry the bare number either way.';
COMMENT ON COLUMN tenant.brands.money_grouping IS
    'How the operator console groups the thousands of an amount: SPACE (no-break space), COMMA, DOT or NONE. The decimal separator follows so the two never collide.';
COMMENT ON COLUMN tenant.brands.phone_display_pattern IS
    'How the operator console writes a phone number, one # per digit and the +, spaces, brackets, dashes and dots kept as written, for example +### ## ### ## ##. NULL shows the number as it arrives.';

ALTER TABLE tenant.brands
    ADD CONSTRAINT ck_brands_money_symbol_placement CHECK (
        money_symbol_placement IN ('BEFORE', 'AFTER')
    );

ALTER TABLE tenant.brands
    ADD CONSTRAINT ck_brands_money_grouping CHECK (
        money_grouping IN ('SPACE', 'COMMA', 'DOT', 'NONE')
    );

-- Only what a phone pattern is made of, and between 7 and 15 digit slots (E.164's own range).
ALTER TABLE tenant.brands
    ADD CONSTRAINT ck_brands_phone_display_pattern CHECK (
        phone_display_pattern IS NULL OR (
            phone_display_pattern ~ '^[+#() .-]{1,32}$'
            AND length(regexp_replace(phone_display_pattern, '[^#]', '', 'g')) BETWEEN 7 AND 15
        )
    );
