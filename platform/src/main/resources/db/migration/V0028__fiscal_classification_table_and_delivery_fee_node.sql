-- ADR 0038: the fiscal classification table V0021 deferred, and the delivery
-- fee's first identity.
--
-- V0021 added `mxik_code` and `package_code` to catalog.products,
-- catalog.variants and catalog.modifier_options and said so plainly in its own
-- comment: "these columns are deliberately the smaller interim", while
-- "ADR 0038's accepted form is a separate catalog.fiscal_classifications table
-- keyed by (priceable_type, priceable_id)". Two conditions justified the
-- interim, and both have ended.
--
-- The first was that ADR 0038 was Proposed. It was accepted on 2026-08-22.
--
-- The second was that two columns were thought to be most of what a receipt
-- needs. Reading both provider contracts in full disproved that. Click's
-- `Items[]` and Payme's `detail.items[]` each require roughly seven per-line
-- fields, and V0021 covers two of them. Missing are the fiscal unit code
-- (Click `Units`, Payme `units`), the VAT rate as a whole percent
-- (`VATPercent`, `vat_percent`), a fiscal name capped at 63 characters that is
-- not the customer-facing name (Click `Name`), and the marking-code array
-- (Click `Labels`). None of those fits "add another column to three tables"
-- without the three tables disagreeing about what a classification is.
--
-- ---------------------------------------------------------------------------
-- Why a table and not three more columns per node
-- ---------------------------------------------------------------------------
--
-- Widening the columns was seriously considered, because it is the smaller
-- change and the interim already works. It was rejected for four reasons, in
-- descending order of how expensive each one is to discover later.
--
-- 1. The delivery fee has no row anywhere to widen. ADR 0018 already types a
--    priceable as VARIANT, MODIFIER_OPTION or FEE, and pricing.prices accepts
--    all three — but no table has ever held a FEE. The fee reaches a receipt as
--    an ordinary line and must carry its own ИКПУ, package code and VAT
--    percent, because Payme's `shipping` block accepts a title and a price and
--    nothing else. Columns on products, variants and modifier options can never
--    classify it. A table keyed by the priceable pair can, and this migration
--    gives the fee the identity it has been missing.
-- 2. Ten columns times three tables is thirty places for the same rule. The
--    "is this node classified" predicate would be written thirty times and
--    would drift, which is exactly how catalog.products.tax_category_code — one
--    nullable column that nothing reads — came to exist.
-- 3. Classification has provenance and the node does not. Who classified this,
--    when, from a manual entry or a POS import, is a fact about the
--    classification. Hanging it on the variant makes a variant's `updated_at`
--    move when nothing about the dish changed.
-- 4. Marking is a fact that payments must read on the checkout path. A marked
--    good cannot be fiscalized through Payme, which has no marking field at
--    all, so a cart containing one has to drop Payme from the offered payment
--    methods. That is one indexed read against one table, not a three-way union
--    over the authoring tables.
--
-- ---------------------------------------------------------------------------
-- Where this deviates from ADR 0038's sketch, and why
-- ---------------------------------------------------------------------------
--
-- The ADR sketches `mxik_code not null, package_code not null, unit_code
-- integer not null, fiscal_name varchar(63) not null`. They are nullable here.
--
-- The ADR's own rollout is the reason: stage 2 migrates the interim columns and
-- stage 3 enables the validator rules "per brand once its coverage report is
-- clean". A NOT NULL column cannot be enabled per brand. It is on for every
-- tenant the instant this migration runs, which would refuse the partially
-- filled rows V0021 has been collecting — an operator who entered an ИКПУ but
-- not yet a package code — and would refuse an operator saving half a
-- classification and coming back to it. Losing entered codes to make a column
-- stricter is the wrong trade.
--
-- So completeness is asserted by CatalogValidator, which reports exactly which
-- of the four fields a node is missing, as a warning today and as a blocker
-- when a brand's coverage is clean. When ADR 0038 stage 3 finishes for every
-- brand, tightening these four to NOT NULL is a one-line migration against data
-- already known to satisfy it.
--
-- Second deviation: the ADR calls the column `unit_code` and it is
-- `fiscal_unit_code` here. catalog.variants.unit_code already exists, is
-- varchar(16), and holds 'PIECE'. The fiscal unit code is an integer from the
-- tax authority's list. Two columns named unit_code in one schema meaning
-- different things in different types is the same class of trap as the legacy
-- `variants.package_id` — a bundle concept — being read as the fiscal
-- `package_code`, which the provider notes call out by name. The column is
-- renamed rather than the trap being documented.

-- ---------------------------------------------------------------------------
-- 1. The ИКПУ/MXIK reference list
-- ---------------------------------------------------------------------------
--
-- ADR 0038: a code is validated against the official list, never against a
-- hard-coded format, because the code's shape belongs to the tax authority and
-- asserting a length or a digit pattern it later changes is a migration nobody
-- planned for. V0021 reached the same conclusion and asserted no format; this
-- is where the alternative lands.
--
-- The table is created empty. The source of the list and its refresh cadence
-- are an open input on ADR 0038 (finance), so nothing imports it yet, and there
-- is deliberately no foreign key from a classification to it: an empty
-- reference behind a foreign key refuses every code, which would turn an
-- unfinished import into an inability to classify anything. The validator
-- reports an unrecognised code as a warning, and only once the reference has
-- rows — an empty reference means the import has not run, and reporting every
-- code in the catalog as unknown is noise rather than a finding.
CREATE TABLE catalog.mxik_reference (
    code varchar(32) PRIMARY KEY,
    -- The list is a tree; a parent lets an operator browse to a code rather
    -- than having to already know it, which is how a wrong code gets chosen.
    parent_code varchar(32),
    label_ru varchar(400) NOT NULL,
    label_uz varchar(400) NOT NULL,
    label_en varchar(400),
    -- What the list says this code is normally packaged and measured in. A
    -- suggestion offered to the operator, never a default written behind their
    -- back: the package code appears on a legal document, and a value nobody
    -- chose is a value nobody checked.
    default_package_codes text[] NOT NULL DEFAULT '{}',
    default_unit_codes integer[] NOT NULL DEFAULT '{}',
    -- A code can be withdrawn. A receipt issued last year under a code that has
    -- since been retired was still correct, so a retired code is dated rather
    -- than deleted.
    valid_from date NOT NULL,
    valid_until date,
    imported_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_mxik_reference_window CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT fk_mxik_reference_parent FOREIGN KEY (parent_code)
        REFERENCES catalog.mxik_reference (code)
);

COMMENT ON TABLE catalog.mxik_reference IS
    'ADR 0038. The official ИКПУ/MXIK list, imported. Empty until finance settles the source and cadence; a classification does not foreign-key to it, because an empty reference behind a foreign key would refuse every code.';

-- ---------------------------------------------------------------------------
-- 2. The delivery fee finally has a row
-- ---------------------------------------------------------------------------
--
-- ADR 0018 types a priceable as VARIANT, MODIFIER_OPTION or FEE and
-- pricing.prices carries all three, but nothing has ever created a FEE: the
-- delivery charge is assembled by the pricing engine under a synthetic line id
-- and resolved from an ADR 0037 tariff. That is fine for a number. It is not
-- fine for a receipt line, which needs an ИКПУ, a package code, a unit and a
-- VAT rate of its own — and it is why the provider notes record that the fee's
-- classification "has nowhere to carry a code today".
--
-- One row per brand per fee code is the whole of it. The row exists so that
-- something with a stable identity can be classified, offered to an operator in
-- a coverage report, and pointed at by ADR 0018's FEE priceable. It carries no
-- amount: the amount is ADR 0037's, resolved per order, and a second place for
-- a fee to have a price is a second answer to what the customer pays.
CREATE TABLE catalog.fees (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    -- DELIVERY today. A service charge and a packaging charge are the obvious
    -- next two and neither is invented here, because a fee nobody charges is a
    -- row that has to be classified before a brand can publish.
    code varchar(32) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_fee_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,31}$'),
    CONSTRAINT ck_fee_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT fk_fee_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT uq_fee_code UNIQUE (tenant_id, brand_id, code),
    -- The composite identity every catalog table carries, so a classification
    -- can foreign-key the fee together with its tenant and brand and a row
    -- cannot classify another brand's fee.
    CONSTRAINT uq_fee_identity UNIQUE (id, tenant_id, brand_id)
);

COMMENT ON TABLE catalog.fees IS
    'ADR 0038. The FEE priceable node ADR 0018 typed and nothing ever created. Exists so the delivery charge can be classified and reported on; carries no amount, which stays ADR 0037''s.';

-- Every existing brand gets its delivery fee node. Without the row the coverage
-- report cannot name the gap, and an operator cannot classify a thing that does
-- not exist — which is the state ADR 0038 describes as "the delivery fee cannot
-- be the line that quietly has no code".
INSERT INTO catalog.fees (id, tenant_id, brand_id, code)
SELECT gen_random_uuid(), b.tenant_id, b.id, 'DELIVERY'
FROM tenant.brands b;

-- ---------------------------------------------------------------------------
-- 3. Fiscal classification
-- ---------------------------------------------------------------------------
--
-- ADR 0038 keys this by (priceable_type, priceable_id). A bare polymorphic pair
-- cannot be foreign-keyed, so a classification could name a variant that never
-- existed or was archived years ago, and nothing would say so until a receipt
-- was built from it. Three typed, nullable node columns can be foreign-keyed —
-- with the tenant and brand in the key, so a classification cannot reach across
-- a brand boundary — and the ADR's pair is then derived from them rather than
-- stored beside them. Derived, the two can never disagree with the three; a
-- stored pair alongside three columns is one UPDATE away from claiming VARIANT
-- while pointing at a modifier option.
CREATE TABLE catalog.fiscal_classifications (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    variant_id uuid,
    modifier_option_id uuid,
    fee_id uuid,

    -- ADR 0018's exact vocabulary, so the delivery fee is classified by the
    -- same mechanism as a dish and a reader of this table does not have to
    -- learn a second set of names for the same three things.
    priceable_type varchar(16) GENERATED ALWAYS AS (
        CASE
            WHEN variant_id IS NOT NULL THEN 'VARIANT'
            WHEN modifier_option_id IS NOT NULL THEN 'MODIFIER_OPTION'
            WHEN fee_id IS NOT NULL THEN 'FEE'
        END
    ) STORED,
    priceable_id uuid GENERATED ALWAYS AS (
        coalesce(variant_id, modifier_option_id, fee_id)
    ) STORED,

    -- The four fields both providers need on every line. Nullable, for the
    -- reason set out at the top of this file: completeness is a validator rule
    -- that can be switched on per brand, and a NOT NULL column cannot be.
    mxik_code varchar(32),
    package_code varchar(32),
    fiscal_unit_code integer,
    fiscal_name varchar(63),

    -- Optional on both wires and cheap to carry. Click caps it at 13.
    barcode varchar(13),

    -- An ADR 0018 profile that overrides the entity/brand/tenant chain for this
    -- node — a zero-rated item in a brand that is otherwise standard-rated. The
    -- rate itself is deliberately not here. A second rate stored on a catalog
    -- node is how a receipt comes to disagree with the price the customer was
    -- shown, and ADR 0038 says so outright: VAT does not live in this table.
    tax_profile_id uuid,

    -- Marking, and the reason it is in the catalog rather than in an adapter.
    -- Click's Items[] carries a Labels array; Payme's detail.items[] has no
    -- marking field of any kind, so a marked good cannot be lawfully fiscalized
    -- through Payme. That makes marking a constraint on how the customer may
    -- pay, and payments has to be able to read it while a cart is still being
    -- priced. Stated here as a fact about the item; enforced there as a rule
    -- about the tender.
    --
    -- The default is false, which is the honest default and not the safe one: a
    -- marked SKU nobody flagged will be fiscalized through Payme unlawfully.
    -- Defaulting to true is worse — it would withdraw Payme from every cart in
    -- the system on the day this migration runs, over goods no tenant sells
    -- yet. ADR 0038 puts marked goods in rollout stage 6, against a real marked
    -- SKU, with the Payme exclusion in place before the first one is published.
    marking_required boolean NOT NULL DEFAULT false,
    marking_scheme varchar(16) NOT NULL DEFAULT 'NONE',

    -- Carried because receipts and aggregator feeds ask for them, not because
    -- anything here computes from them.
    excisable boolean NOT NULL DEFAULT false,
    alcohol_by_volume_bp integer,
    age_restriction_years smallint,

    source varchar(24) NOT NULL,
    classified_by uuid,
    classified_at timestamptz NOT NULL DEFAULT now(),

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    -- Exactly one node. Written with num_nonnulls rather than as a chain of
    -- OR'd null tests, because the chain has to enumerate three positives and
    -- three negatives and one of the six is always the one that gets forgotten.
    -- It is also what makes priceable_type total: with exactly one column
    -- populated the CASE above always has a branch, so the generated column is
    -- never null and the unique index below is never bypassed by a null.
    CONSTRAINT ck_fiscal_classification_one_node CHECK (
        num_nonnulls(variant_id, modifier_option_id, fee_id) = 1
    ),

    -- Blank is not a classification. An operator who tabs through the field
    -- submits an empty string, which satisfies "the column is set" while
    -- classifying nothing — and a coverage report built on IS NOT NULL would
    -- then call a brand complete while its receipts went out unclassified. The
    -- domain type normalises blank to null; this is the backstop for a POS
    -- import or a migration writing the column directly.
    CONSTRAINT ck_fiscal_classification_codes_not_blank CHECK (
        (mxik_code IS NULL OR length(btrim(mxik_code)) > 0)
        AND (package_code IS NULL OR length(btrim(package_code)) > 0)
        AND (fiscal_name IS NULL OR length(btrim(fiscal_name)) > 0)
        AND (barcode IS NULL OR length(btrim(barcode)) > 0)
    ),

    -- The unit code is an identifier from a list, so zero and negative numbers
    -- are not unit codes; they are an integer column that was never filled in
    -- and got a default from somewhere.
    CONSTRAINT ck_fiscal_classification_unit_code CHECK (
        fiscal_unit_code IS NULL OR fiscal_unit_code > 0
    ),

    -- A scheme and the requirement state the same fact twice, so they are tied
    -- as an equivalence rather than as two one-way rules: NONE means not
    -- marked, and any other scheme means marked. Half of this pair on its own
    -- is a marked good whose codes nobody will capture, or an unmarked good
    -- that blocks its own fiscal document waiting for codes that do not exist.
    CONSTRAINT ck_fiscal_classification_marking_scheme CHECK (
        marking_scheme IN ('NONE', 'DATA_MATRIX')
    ),
    CONSTRAINT ck_fiscal_classification_marking_agrees CHECK (
        (marking_scheme = 'NONE') = (NOT marking_required)
    ),

    CONSTRAINT ck_fiscal_classification_abv CHECK (
        alcohol_by_volume_bp IS NULL
        OR (alcohol_by_volume_bp >= 0 AND alcohol_by_volume_bp <= 10000)
    ),
    -- A restriction of zero years restricts nobody while reading as a
    -- restriction, which is worse than no value at all.
    CONSTRAINT ck_fiscal_classification_age CHECK (
        age_restriction_years IS NULL
        OR (age_restriction_years >= 1 AND age_restriction_years <= 120)
    ),

    CONSTRAINT ck_fiscal_classification_source CHECK (
        source IN (
            -- An operator entered it.
            'MANUAL',
            -- A bulk import of a tenant's own list, under ADR 0024.
            'IMPORT',
            -- Carried in from a POS catalog sync, under ADR 0012.
            'POS_SYNC',
            -- Migration-only: carried over from V0021's interim columns, which
            -- recorded no author and no timestamp. Never written by the
            -- application. Named rather than folded into MANUAL so a later
            -- provenance audit can find exactly these rows and ask a human to
            -- confirm them, in the same way V0021 named LEGACY_UNSOURCED.
            'INTERIM'
        )
    ),

    CONSTRAINT fk_fiscal_classification_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_fiscal_classification_variant FOREIGN KEY (variant_id, tenant_id, brand_id)
        REFERENCES catalog.variants (id, tenant_id, brand_id),
    CONSTRAINT fk_fiscal_classification_option FOREIGN KEY (modifier_option_id, tenant_id, brand_id)
        REFERENCES catalog.modifier_options (id, tenant_id, brand_id),
    CONSTRAINT fk_fiscal_classification_fee FOREIGN KEY (fee_id, tenant_id, brand_id)
        REFERENCES catalog.fees (id, tenant_id, brand_id)
);

-- ADR 0038's "unique (priceable_type, priceable_id)", over the derived pair.
-- Two classifications for one node would make the code on a receipt depend on
-- row order, which is the same failure mode ADR 0018 forbids for prices.
CREATE UNIQUE INDEX uq_fiscal_classification_node
    ON catalog.fiscal_classifications (priceable_type, priceable_id);

-- The coverage report: which nodes are still short of the four fields a
-- conformant provider line needs. Partial, because the complete rows are not
-- the ones anyone is looking for. This replaces V0021's two unclassified
-- indexes, which asked the narrower question of whether an ИКПУ was present.
CREATE INDEX ix_fiscal_classifications_incomplete
    ON catalog.fiscal_classifications (tenant_id, brand_id)
    WHERE mxik_code IS NULL
       OR package_code IS NULL
       OR fiscal_unit_code IS NULL
       OR fiscal_name IS NULL;

CREATE INDEX ix_fiscal_classifications_brand
    ON catalog.fiscal_classifications (tenant_id, brand_id);

-- Serves the checkout-path question payments asks of catalog: does this cart
-- contain anything marked. Partial, because the answer is no for every node of
-- every tenant until a marked SKU is published, and an index over all of them
-- would be read on every checkout to learn nothing.
CREATE INDEX ix_fiscal_classifications_marked
    ON catalog.fiscal_classifications (tenant_id, brand_id)
    WHERE marking_required;

COMMENT ON TABLE catalog.fiscal_classifications IS
    'ADR 0038. One row per ADR 0018 priceable node carrying everything a Click or Payme receipt line needs about it. Replaces V0021''s interim columns on products, variants and modifier options.';
COMMENT ON COLUMN catalog.fiscal_classifications.priceable_type IS
    'ADR 0018 vocabulary, derived from whichever node column is populated so it cannot disagree with it.';
COMMENT ON COLUMN catalog.fiscal_classifications.mxik_code IS
    'ADR 0038. ИКПУ/MXIK. Click SPIC, Payme code. No format is asserted: the shape belongs to the official reference list, and a pattern the tax authority later changes is a migration nobody planned.';
COMMENT ON COLUMN catalog.fiscal_classifications.package_code IS
    'ADR 0038. Код упаковки. Required by both providers. Unrelated to legacy variants.package_id and variants.is_package, which are a bundle concept; a migration that maps one onto the other produces a menu that reads as classified and is not.';
COMMENT ON COLUMN catalog.fiscal_classifications.fiscal_unit_code IS
    'ADR 0038. The numeric fiscal unit (Click Units, Payme units). Distinct from catalog.variants.unit_code, which is the varchar measurement unit a menu is authored in. Click compensates for the field being optional by demanding the unit inside Name, so a line cannot be built without one.';
COMMENT ON COLUMN catalog.fiscal_classifications.fiscal_name IS
    'ADR 0038. Click caps Name at 63 characters and wants the unit of measure inside it. A Cyrillic dish name routinely exceeds that, and truncating a customer-facing name at fiscalization time produces a receipt line nobody can reconcile against a menu.';
COMMENT ON COLUMN catalog.fiscal_classifications.marking_required IS
    'ADR 0038. Payme''s detail object has no marking field, so a marked good cannot be fiscalized through Payme and a cart containing one must not offer it. Stated by the catalog, read by payments, rather than buried in an adapter.';
COMMENT ON COLUMN catalog.fiscal_classifications.tax_profile_id IS
    'ADR 0018 profile overriding the entity/brand/tenant chain for this node. The rate itself is never stored here: two rates in two tables is how a receipt shows tax on a line the customer paid none on.';
COMMENT ON COLUMN catalog.fiscal_classifications.classified_by IS
    'Null on rows migrated from V0021''s interim columns, which recorded no author. A null here means the classification predates per-user attribution, not that nobody is responsible for it.';

-- ---------------------------------------------------------------------------
-- 4. The interim columns move in and go away
-- ---------------------------------------------------------------------------
--
-- Variants first, applying V0021's stated inheritance — "a product is not
-- itself priceable; this is the default its variants inherit when they carry no
-- code of their own" — at migration time, because the inheritance ends with the
-- columns. A product is not a receipt line, and resolving a code through a
-- parent at fiscalization means the resolver needs the product row: the
-- published snapshot does not have it, which the snapshot loader already had to
-- work around by resolving inheritance before writing the publication item.
-- Classifying the priceable node directly removes the step rather than moving
-- it somewhere else.
--
-- Only rows that have something to carry. A node with neither code produces no
-- row, and its absence is what the coverage report reads as unclassified.
INSERT INTO catalog.fiscal_classifications (
    id, tenant_id, brand_id, variant_id, mxik_code, package_code, source, classified_at)
SELECT
    gen_random_uuid(), v.tenant_id, v.brand_id, v.id,
    coalesce(v.mxik_code, p.mxik_code),
    coalesce(v.package_code, p.package_code),
    'INTERIM',
    now()
FROM catalog.variants v
JOIN catalog.products p ON p.id = v.product_id
WHERE coalesce(v.mxik_code, p.mxik_code) IS NOT NULL
   OR coalesce(v.package_code, p.package_code) IS NOT NULL;

INSERT INTO catalog.fiscal_classifications (
    id, tenant_id, brand_id, modifier_option_id, mxik_code, package_code, source, classified_at)
SELECT
    gen_random_uuid(), o.tenant_id, o.brand_id, o.id,
    o.mxik_code, o.package_code, 'INTERIM', now()
FROM catalog.modifier_options o
WHERE o.mxik_code IS NOT NULL OR o.package_code IS NOT NULL;

-- A modifier option that is itself a sellable variant was classified by the
-- link rather than in its own right, and the validator still resolves that way,
-- so nothing is copied across the link here. Copying would produce two rows for
-- one physical good that can be corrected independently and then disagree,
-- which is the specific outcome the linked-variant fallback exists to prevent.

DROP INDEX catalog.ix_variants_unclassified;
DROP INDEX catalog.ix_modifier_options_unclassified;

ALTER TABLE catalog.products
    DROP COLUMN mxik_code,
    DROP COLUMN package_code,
    -- ADR 0038 drops this in the same migration. It has been nullable and
    -- unread since V0016, and ADR 0038's reasoning is that a second unenforced
    -- classification column would be picked up by exactly one adapter and
    -- disagree with the real one forever. Nothing has ever written it, so there
    -- is nothing to carry over.
    DROP COLUMN tax_category_code;

ALTER TABLE catalog.variants
    DROP COLUMN mxik_code,
    DROP COLUMN package_code;

ALTER TABLE catalog.modifier_options
    DROP COLUMN mxik_code,
    DROP COLUMN package_code;

-- ---------------------------------------------------------------------------
-- 5. A VAT rate that cannot be expressed must be refused, not rounded
-- ---------------------------------------------------------------------------
--
-- ADR 0018 stores a tax rate as basis points, which is right: integers
-- throughout, so no rate is a float that rounds differently on two machines.
-- Both providers type the VAT rate on a receipt line as an integer *percent* —
-- Click `VATPercent`, Payme `vat_percent`. There is no representation of 12.5
-- percent on either wire.
--
-- So a profile whose rate is not a whole number of percent has no conformant
-- receipt. The two ways to be wrong about that are to round it, which puts a
-- misstated tax figure on a legal document, or to discover it in the adapter
-- on the checkout path, which fails an order that was already priced. This
-- constraint is the third: the rate is refused when it is configured, by the
-- database, in front of the person configuring it.
--
-- ADR 0038 requires exactly this ("`tax_profiles.rate_basis_points` must be
-- constrained to multiples of 100"). It is safe to add unconditionally: every
-- rate in the system today is 1200.
ALTER TABLE pricing.tax_profiles
    ADD CONSTRAINT ck_tax_rate_whole_percent CHECK (rate_basis_points % 100 = 0);

COMMENT ON CONSTRAINT ck_tax_rate_whole_percent ON pricing.tax_profiles IS
    'ADR 0038. Click VATPercent and Payme vat_percent are integer percents, so a rate that is not a multiple of 100 basis points cannot appear on a conformant receipt. Refused here rather than rounded onto a fiscal document or discovered by an adapter mid-checkout.';

-- The composite identity a classification's tax profile override foreign-keys
-- against, so an override cannot name another brand's profile. ADR 0038 says
-- the override is same-brand; without this the only enforceable key is the id
-- alone, which asserts the weaker half of the rule while reading like the
-- whole of it.
ALTER TABLE pricing.tax_profiles
    ADD CONSTRAINT uq_tax_profile_identity UNIQUE (id, tenant_id, brand_id);

ALTER TABLE catalog.fiscal_classifications
    ADD CONSTRAINT fk_fiscal_classification_tax_profile
    FOREIGN KEY (tax_profile_id, tenant_id, brand_id)
    REFERENCES pricing.tax_profiles (id, tenant_id, brand_id);

GRANT USAGE ON SCHEMA catalog TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.mxik_reference TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.fees TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.fiscal_classifications TO qoida_application;
