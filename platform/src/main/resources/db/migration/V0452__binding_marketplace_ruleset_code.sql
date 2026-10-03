-- ADR 0138: which marketplace ruleset a binding is previewed against.
--
-- The projection preview runs the ordinary publication validation and then, for
-- a binding that names a ruleset, that ruleset's partner-specific checks. The
-- column is nullable and a binding that names nothing is previewed against the
-- universal rules alone.
--
-- The closed set of codes is code-owned (MarketplaceRulesets) and EMPTY today:
-- the per-marketplace content rules for Yandex Eda, Uzum Tezkor, Wolt and
-- Express24 are an open input of ADR 0138 that the record deliberately leaves to
-- product and partnerships. So no value can be written yet and nothing does; the
-- column exists so that onboarding the first real marketplace is a ruleset added
-- in code, with no schema change. The format check keeps a typed-in code from
-- being something a Java enum constant could not be.
--
-- integration.bindings is already granted to the application role (V0035); a new
-- column needs no grant of its own.

ALTER TABLE integration.bindings
    ADD COLUMN marketplace_ruleset_code varchar(48);

ALTER TABLE integration.bindings
    ADD CONSTRAINT ck_binding_marketplace_ruleset_code CHECK (
        marketplace_ruleset_code IS NULL OR marketplace_ruleset_code ~ '^[A-Z][A-Z0-9_]*$');

COMMENT ON COLUMN integration.bindings.marketplace_ruleset_code IS
    'ADR 0138. Selects the partner-specific preview checks for a MARKETPLACE binding. Null = universal rules only. The closed set is owned by code (catalog MarketplaceRulesets) and is empty until a per-marketplace ruleset is authored.';
