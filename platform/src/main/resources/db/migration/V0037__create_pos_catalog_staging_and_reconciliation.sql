-- ADR 0012: POS catalog synchronization staging and reconciliation.
--
-- Every import lands here before anything of Qoida's is touched. The catalog
-- tables in V0016 are never written from a provider response; a run stages a
-- normalized snapshot, a deterministic engine compares it, and applying the
-- comparison is a separate command a person authorises.
--
-- ---------------------------------------------------------------------------
-- What Clopos forces this design to add
-- ---------------------------------------------------------------------------
--
-- ADR 0012 already assumed a full-snapshot import, which is fortunate, because
-- Clopos removes the alternative: GET /products takes no date range, no
-- updated_at filter, no cursor and no sort, and there is no ETag, no
-- If-Modified-Since, no change feed and no webhook. Change detection is a full
-- re-read and a client-side diff, and that is not a first release simplification
-- that a later one improves.
--
-- The consequence ADR 0012 did *not* assume is the one that matters. Offset
-- pagination over a table somebody is editing can skip rows: insert a product
-- while we are reading page two and page three shifts, and one product is never
-- read at all. A product we failed to read is indistinguishable, in the staged
-- snapshot, from a product that was deleted — so a pagination race presents to
-- the difference engine as a removal.
--
-- integration.pos_absence_observations below is the answer. An entity missing
-- from one run is an observation, not a removal. A REMOVAL_SIGNAL difference is
-- only produced once the same entity has been absent from two consecutive runs
-- of the same binding, which costs one extra catalog read and converts a class
-- of phantom removals into nothing at all. That matters more than it sounds:
-- ADR 0012 already refuses to physically delete on a removal signal, so the
-- blast radius of a phantom was only ever a review-queue item — but a review
-- queue full of phantom removals is how an operator learns to approve the queue
-- without reading it, and then a real removal goes through the same way.
--
-- ---------------------------------------------------------------------------
-- Identity, and the naming trap
-- ---------------------------------------------------------------------------
--
-- Clopos's stable identifier is an integer id per resource, scoped to the brand,
-- and there is nothing else. No SKU, no external code, no slug, no stable
-- secondary key. Names, full names, prices, cost prices, statuses, category and
-- station assignments are all editable in the back office, and barcode is
-- deprecated and not guaranteed to be populated. Categories additionally carry
-- _lft and _rgt nested-set columns that renumber on every tree edit and are not
-- identifiers at all. So external_entity_id below is that integer as text, and
-- ADR 0012's rule against guessing a mapping from mutable names has no softer
-- reading available.
--
-- Two Clopos words differ by three characters and mean different things. A
-- `modification` is a variant — size, colour — carrying the full product schema
-- with its own price and stock, and when a product has them the parent is not
-- sellable. A `modificator` is a modifier option, "extra cheese", and attaches
-- only to a DISH. Neither word appears in this schema: the staging tables are
-- named for what the things are, and the normalizer is where the provider's
-- vocabulary stops.
--
-- ---------------------------------------------------------------------------
-- What is deliberately not created here
-- ---------------------------------------------------------------------------
--
-- No staged price-list resolution. Clopos's own index describes a price list as
-- applicable to specific venues or sales channels, and no field in either the
-- PriceList or the Price schema expresses that application — no venue_id, no
-- sale_type_id, no channel, and no endpoint that resolves the effective price of
-- a product at a venue for a sale type. Prices are staged as evidence for review
-- because ADR 0012 makes Qoida authoritative for customer-facing price anyway;
-- building a price-list-to-venue resolution on a guess would put a wrong number
-- in front of a customer with a confident provenance attached to it.
--
-- No staged units. Every Clopos product carries unit_id, an integer, and there
-- is no units endpoint anywhere in the API. A product is "three of unit 1" and
-- nothing can say what unit 1 is. The raw payload keeps the integer so the
-- evidence survives; a unit column would imply a resolution that does not exist.

-- ---------------------------------------------------------------------------
-- Schedules
-- ---------------------------------------------------------------------------
--
-- A durable PostgreSQL timer, not Kafka. Kafka carries the resulting command;
-- a topic is not a clock, and a retained message is not a schedule.
CREATE TABLE integration.pos_sync_schedules (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    binding_id uuid NOT NULL,

    -- The branch's own zone, not the platform's. A restaurant's catalog run at
    -- "four in the morning" means four in the morning where the restaurant is,
    -- and a UTC schedule drifts an hour twice a year against the only clock
    -- anybody there reads.
    timezone varchar(64) NOT NULL,
    local_time time NOT NULL,

    enabled boolean NOT NULL DEFAULT false,
    next_run_at timestamptz,
    last_run_at timestamptz,

    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_pos_schedule_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id) ON DELETE CASCADE,
    -- One schedule per binding. Two would produce two runs racing to stage the
    -- same snapshot under different policy versions.
    CONSTRAINT uq_pos_schedule_binding UNIQUE (tenant_id, binding_id),
    CONSTRAINT ck_pos_schedule_version CHECK (version >= 0),
    -- An enabled schedule with no next run is a timer that will never fire, and
    -- it looks identical on a screen to one that will.
    CONSTRAINT ck_pos_schedule_armed CHECK (NOT enabled OR next_run_at IS NOT NULL)
);

CREATE INDEX ix_pos_schedule_due
    ON integration.pos_sync_schedules (next_run_at)
    WHERE enabled;

COMMENT ON TABLE integration.pos_sync_schedules IS
    'ADR 0012. The daily catalog run. The stop list is a separate, far faster feed and is deliberately not scheduled here.';

COMMENT ON COLUMN integration.pos_sync_schedules.timezone IS
    'IANA zone of the bound location. The run time is local because that is the only clock the restaurant reads.';

-- ---------------------------------------------------------------------------
-- Runs
-- ---------------------------------------------------------------------------

CREATE TABLE integration.pos_sync_runs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    binding_id uuid NOT NULL,

    trigger_type varchar(16) NOT NULL,
    status varchar(24) NOT NULL,
    dry_run boolean NOT NULL DEFAULT true,

    -- Snapshotted so a resume cannot reinterpret data staged by earlier code
    -- under new rules. A run that restarts under a different adapter version is
    -- a new run, not a continuation.
    adapter_version varchar(64) NOT NULL,
    field_policy_version integer NOT NULL,

    started_at timestamptz NOT NULL,
    fetched_at timestamptz,
    normalized_at timestamptz,
    compared_at timestamptz,
    applied_at timestamptz,
    completed_at timestamptz,

    -- Where a resume picks up. Free-form because what a checkpoint means differs
    -- by stage, and a typed column would have to be the union of all of them.
    checkpoint jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- For Clopos this is a page number, and it is honest about what it is worth:
    -- a page number is a position in a list that may have shifted underneath it.
    source_cursor varchar(255),

    -- ADR 0010. Large raw snapshots go to S3; the key is here.
    raw_object_key varchar(512),

    received_count integer NOT NULL DEFAULT 0,
    valid_count integer NOT NULL DEFAULT 0,
    invalid_count integer NOT NULL DEFAULT 0,
    addition_count integer NOT NULL DEFAULT 0,
    change_count integer NOT NULL DEFAULT 0,
    removal_count integer NOT NULL DEFAULT 0,
    conflict_count integer NOT NULL DEFAULT 0,

    -- How many pages were read, and whether the walk was stable. A read that
    -- paged by an ascending id is not exposed to the insert race; one that paged
    -- by offset is, and the difference decides how much a single run's absence
    -- is worth as evidence.
    page_count integer NOT NULL DEFAULT 0,
    walk_kind varchar(16) NOT NULL DEFAULT 'OFFSET',

    last_error_code varchar(128),
    last_error varchar(1000),

    version bigint NOT NULL DEFAULT 0,

    CONSTRAINT fk_pos_run_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id),
    CONSTRAINT uq_pos_run_identity UNIQUE (id, tenant_id),
    CONSTRAINT ck_pos_run_trigger CHECK (trigger_type IN ('SCHEDULED', 'MANUAL', 'RESUMED')),
    CONSTRAINT ck_pos_run_status CHECK (status IN (
        'REQUESTED', 'FETCHING', 'STAGED', 'VALIDATING', 'COMPARING',
        'REVIEW_REQUIRED', 'APPLYING', 'RECONCILING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_pos_run_walk CHECK (walk_kind IN ('OFFSET', 'KEYSET')),
    CONSTRAINT ck_pos_run_counts CHECK (
        received_count >= 0 AND valid_count >= 0 AND invalid_count >= 0
        AND addition_count >= 0 AND change_count >= 0 AND removal_count >= 0
        AND conflict_count >= 0 AND page_count >= 0),
    CONSTRAINT ck_pos_run_checkpoint CHECK (jsonb_typeof(checkpoint) = 'object'),
    CONSTRAINT ck_pos_run_version CHECK (version >= 0),
    -- A dry run performs every step except target mutation, so it can never have
    -- applied anything. Without this the distinction survives only in whichever
    -- code path happened to be taken.
    CONSTRAINT ck_pos_run_dry_run_applies_nothing CHECK (NOT dry_run OR applied_at IS NULL)
);

CREATE INDEX ix_pos_run_binding
    ON integration.pos_sync_runs (tenant_id, binding_id, started_at DESC);

COMMENT ON TABLE integration.pos_sync_runs IS
    'ADR 0012. One import. Adapter and policy versions are snapshotted so a resume cannot reinterpret earlier data under later code.';

COMMENT ON COLUMN integration.pos_sync_runs.walk_kind IS
    'OFFSET pagination can skip rows when the provider''s catalog is edited mid-read, and a skipped row looks exactly like a deleted one. KEYSET cannot. The removal quorum reads this.';

COMMENT ON COLUMN integration.pos_sync_runs.source_cursor IS
    'For an offset walk this is a page number, which is a position in a list that may have moved. It is enough to resume and not enough to prove the walk was complete.';

-- ---------------------------------------------------------------------------
-- Staging
-- ---------------------------------------------------------------------------
--
-- Core comparable fields are typed columns, because the difference engine
-- compares them and a comparison over JSONB is a comparison over whatever the
-- provider happened to send. raw_payload keeps the original for diagnosis and
-- for the fields no canonical model has a place for.

CREATE TABLE integration.pos_staged_categories (
    run_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    external_entity_id varchar(64) NOT NULL,
    external_parent_id varchar(64),

    name varchar(255) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    depth integer,

    raw_payload jsonb NOT NULL,

    CONSTRAINT pk_pos_staged_category PRIMARY KEY (run_id, external_entity_id),
    CONSTRAINT fk_pos_staged_category_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES integration.pos_sync_runs (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_pos_staged_category_payload CHECK (jsonb_typeof(raw_payload) = 'object')
);

COMMENT ON COLUMN integration.pos_staged_categories.external_parent_id IS
    'The provider''s parent id. Clopos also returns _lft and _rgt nested-set columns; they renumber on every tree edit and are read as nothing.';

CREATE TABLE integration.pos_staged_products (
    run_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    external_entity_id varchar(64) NOT NULL,

    name varchar(255) NOT NULL,
    external_category_id varchar(64),

    -- The provider's own type, normalized. Only sellable kinds reach comparison:
    -- Clopos returns INGREDIENT and PREPARATION rows from /products alongside
    -- real menu items, and staging them as products would have the difference
    -- engine proposing tomatoes as draft customer-facing products. They are
    -- staged anyway, marked, and excluded from comparison, so that the evidence
    -- of what the provider sent survives.
    source_kind varchar(24) NOT NULL,
    comparable boolean NOT NULL,

    -- True when the provider says the parent is a shell whose variants carry the
    -- price. On Clopos a product with variants is not itself sellable and its
    -- own price is documented as possibly zero and not inherited at sale time,
    -- so treating the parent as priceable would publish a free dish.
    parent_only boolean NOT NULL DEFAULT false,

    -- Whole minor units, never a double. Clopos sends JSON numbers with no
    -- currency field anywhere in its API, so the adapter parses the raw text as
    -- a decimal and the currency is asserted from installation configuration.
    -- For UZS a minor unit is a whole som.
    price_minor bigint,
    currency char(3),

    active boolean NOT NULL DEFAULT true,
    hidden boolean NOT NULL DEFAULT false,

    -- The provider's tax classification field, kept unread. On Clopos this is
    -- gov_code: nullable, no format, no example, no validation, and the only
    -- classification field in the API. ADR 0038 needs MXIK; whether this holds
    -- one is unknown, and an Azerbaijani code would look exactly as convincing.
    government_code varchar(64),

    raw_payload jsonb NOT NULL,

    CONSTRAINT pk_pos_staged_product PRIMARY KEY (run_id, external_entity_id),
    CONSTRAINT fk_pos_staged_product_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES integration.pos_sync_runs (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_pos_staged_product_kind CHECK (source_kind IN (
        'GOODS', 'DISH', 'TIMER', 'PREPARATION', 'INGREDIENT', 'VARIANT', 'UNKNOWN')),
    CONSTRAINT ck_pos_staged_product_price CHECK (price_minor IS NULL OR price_minor >= 0),
    -- A price without a currency is a number nobody can add up, and a currency
    -- without a price is a claim about nothing.
    CONSTRAINT ck_pos_staged_product_money CHECK ((price_minor IS NULL) = (currency IS NULL)),
    CONSTRAINT ck_pos_staged_product_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_pos_staged_product_payload CHECK (jsonb_typeof(raw_payload) = 'object')
);

CREATE INDEX ix_pos_staged_product_comparable
    ON integration.pos_staged_products (run_id) WHERE comparable;

COMMENT ON TABLE integration.pos_staged_products IS
    'ADR 0012. Normalized provider products for one run. Inventory-only kinds are staged and marked rather than dropped, so the evidence of what arrived survives the filter.';

COMMENT ON COLUMN integration.pos_staged_products.comparable IS
    'False for kinds that are not menu items — ingredients, preparations, time-billed rentals. They are evidence, not candidates for a customer-facing draft.';

COMMENT ON COLUMN integration.pos_staged_products.parent_only IS
    'True when the provider sells only this product''s variants. The parent''s own price is not inherited at sale time and may be zero.';

COMMENT ON COLUMN integration.pos_staged_products.government_code IS
    'The provider''s tax classification string, unparsed and unvalidated. ADR 0038 may not treat this as an MXIK until somebody has established what it holds for an Uzbek brand.';

CREATE TABLE integration.pos_staged_variants (
    run_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    external_entity_id varchar(64) NOT NULL,
    external_product_id varchar(64) NOT NULL,

    name varchar(255) NOT NULL,
    price_minor bigint,
    currency char(3),
    active boolean NOT NULL DEFAULT true,

    -- The provider's unit identifier, kept as sent. On Clopos there is no units
    -- endpoint, so this integer resolves to nothing; a unit_code column here
    -- would be a translation nobody can perform.
    external_unit_reference varchar(64),

    raw_payload jsonb NOT NULL,

    CONSTRAINT pk_pos_staged_variant PRIMARY KEY (run_id, external_entity_id),
    CONSTRAINT fk_pos_staged_variant_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES integration.pos_sync_runs (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_pos_staged_variant_price CHECK (price_minor IS NULL OR price_minor >= 0),
    CONSTRAINT ck_pos_staged_variant_money CHECK ((price_minor IS NULL) = (currency IS NULL)),
    CONSTRAINT ck_pos_staged_variant_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_pos_staged_variant_payload CHECK (jsonb_typeof(raw_payload) = 'object')
);

CREATE INDEX ix_pos_staged_variant_parent
    ON integration.pos_staged_variants (run_id, external_product_id);

COMMENT ON COLUMN integration.pos_staged_variants.external_unit_reference IS
    'The provider''s unit id, unresolved. Clopos publishes no endpoint that maps it to a name or a measure, so this is the whole of what is knowable.';

CREATE TABLE integration.pos_staged_modifier_groups (
    run_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    external_entity_id varchar(64) NOT NULL,
    external_product_id varchar(64) NOT NULL,

    name varchar(255) NOT NULL,
    minimum_selections integer NOT NULL DEFAULT 0,
    maximum_selections integer NOT NULL DEFAULT 1,
    required boolean NOT NULL DEFAULT false,

    raw_payload jsonb NOT NULL,

    CONSTRAINT pk_pos_staged_modifier_group PRIMARY KEY (run_id, external_entity_id),
    CONSTRAINT fk_pos_staged_modifier_group_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES integration.pos_sync_runs (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_pos_staged_modifier_group_range CHECK (
        minimum_selections >= 0 AND maximum_selections >= 1
        AND minimum_selections <= maximum_selections),
    CONSTRAINT ck_pos_staged_modifier_group_payload CHECK (jsonb_typeof(raw_payload) = 'object')
);

CREATE TABLE integration.pos_staged_modifiers (
    run_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    external_entity_id varchar(64) NOT NULL,
    external_group_id varchar(64) NOT NULL,

    name varchar(255) NOT NULL,
    price_minor bigint,
    currency char(3),
    active boolean NOT NULL DEFAULT true,

    raw_payload jsonb NOT NULL,

    CONSTRAINT pk_pos_staged_modifier PRIMARY KEY (run_id, external_entity_id),
    CONSTRAINT fk_pos_staged_modifier_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES integration.pos_sync_runs (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_pos_staged_modifier_price CHECK (price_minor IS NULL OR price_minor >= 0),
    CONSTRAINT ck_pos_staged_modifier_money CHECK ((price_minor IS NULL) = (currency IS NULL)),
    CONSTRAINT ck_pos_staged_modifier_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_pos_staged_modifier_payload CHECK (jsonb_typeof(raw_payload) = 'object')
);

CREATE INDEX ix_pos_staged_modifier_group
    ON integration.pos_staged_modifiers (run_id, external_group_id);

-- Availability is staged per run for the reviewed catalog path, and read far
-- more often than that on its own feed. The stop list is the only endpoint in
-- the Clopos API carrying a per-row change timestamp, and it happens to cover
-- the fastest-moving data — a dish that ran out ten minutes ago is a customer
-- ordering something that does not exist. Collapsing it into a daily run makes
-- it useless, so the run stages it for comparison while the live feed reads it
-- every half minute and writes availability directly.
CREATE TABLE integration.pos_staged_availability (
    run_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    external_entity_id varchar(64) NOT NULL,

    -- Null means the provider named no limit for this entity. On Clopos absence
    -- from the stop list means unconstrained, not unavailable, and inverting
    -- that reading empties the entire menu.
    stock_limit numeric(18, 3),
    observed_at timestamptz,

    raw_payload jsonb NOT NULL,

    CONSTRAINT pk_pos_staged_availability PRIMARY KEY (run_id, external_entity_id),
    CONSTRAINT fk_pos_staged_availability_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES integration.pos_sync_runs (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_pos_staged_availability_limit CHECK (stock_limit IS NULL OR stock_limit >= 0),
    CONSTRAINT ck_pos_staged_availability_payload CHECK (jsonb_typeof(raw_payload) = 'object')
);

COMMENT ON COLUMN integration.pos_staged_availability.stock_limit IS
    'Zero means out of stock. Null means the provider named no limit, which is unconstrained and emphatically not unavailable.';

-- ---------------------------------------------------------------------------
-- The removal quorum
-- ---------------------------------------------------------------------------
--
-- The table that stops a pagination race becoming a menu removal. One entity per
-- binding, carrying how many consecutive runs have now failed to see it. Cleared
-- the moment it reappears, because a gap in a sequence is not a sequence.
CREATE TABLE integration.pos_absence_observations (
    tenant_id uuid NOT NULL,
    binding_id uuid NOT NULL,
    entity_type varchar(32) NOT NULL,
    external_entity_id varchar(64) NOT NULL,

    consecutive_absent_runs integer NOT NULL DEFAULT 1,
    first_absent_run_id uuid NOT NULL,
    first_absent_at timestamptz NOT NULL,
    last_absent_run_id uuid NOT NULL,
    last_absent_at timestamptz NOT NULL,

    -- Whether every run in the streak walked the provider's list in a way that
    -- cannot skip rows. An offset walk cannot prove absence on its own; two
    -- agreeing offset walks can, because the same row being skipped twice by
    -- independent races is a coincidence rather than a pattern.
    all_walks_stable boolean NOT NULL DEFAULT false,

    CONSTRAINT pk_pos_absence_observation
        PRIMARY KEY (binding_id, entity_type, external_entity_id),
    CONSTRAINT fk_pos_absence_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES integration.bindings (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_pos_absence_runs CHECK (consecutive_absent_runs >= 1),
    CONSTRAINT ck_pos_absence_entity_type CHECK (entity_type IN (
        'PRODUCT', 'VARIANT', 'CATEGORY', 'MODIFIER_GROUP', 'MODIFIER'))
);

COMMENT ON TABLE integration.pos_absence_observations IS
    'ADR 0012. An entity missing from one run is an observation. A REMOVAL_SIGNAL needs two consecutive runs, because offset pagination over a catalog being edited skips rows and a skipped row is indistinguishable from a deleted one.';

COMMENT ON COLUMN integration.pos_absence_observations.consecutive_absent_runs IS
    'Reset to zero rows on reappearance rather than decremented. A streak with a hole in it is not a streak.';

-- ---------------------------------------------------------------------------
-- Differences, conflicts, apply items
-- ---------------------------------------------------------------------------

CREATE TABLE integration.pos_sync_differences (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,

    entity_type varchar(32) NOT NULL,
    external_entity_id varchar(64) NOT NULL,
    qoida_entity_id uuid,

    category varchar(32) NOT NULL,
    field_path varchar(128),

    -- Hashes rather than values wherever the value could be long or personal.
    -- The value columns exist because an operator cannot review a hash, and they
    -- are bounded for the same reason a raw payload does not go here.
    current_value varchar(1000),
    current_hash varchar(64),
    imported_value varchar(1000),
    imported_hash varchar(64),

    authority varchar(24) NOT NULL,
    severity varchar(16) NOT NULL,
    recommended_action varchar(32) NOT NULL,

    review_outcome varchar(24),
    reviewed_by varchar(255),
    reviewed_at timestamptz,
    review_note varchar(1000),

    CONSTRAINT fk_pos_difference_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES integration.pos_sync_runs (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_pos_difference_identity UNIQUE (id, tenant_id),
    -- Every stage is idempotent under (run, entity type, entity, field), which
    -- is what lets a comparison be re-run after an interruption without
    -- producing the same difference twice.
    -- NULLS NOT DISTINCT because field_path is null for whole-entity findings —
    -- an addition, a removal signal — and the default null semantics would treat
    -- every one of those as distinct from every other, which is exactly the
    -- duplication this constraint exists to prevent.
    CONSTRAINT uq_pos_difference_per_field
        UNIQUE NULLS NOT DISTINCT (run_id, entity_type, external_entity_id, field_path),
    CONSTRAINT ck_pos_difference_entity_type CHECK (entity_type IN (
        'PRODUCT', 'VARIANT', 'CATEGORY', 'MODIFIER_GROUP', 'MODIFIER', 'AVAILABILITY')),
    CONSTRAINT ck_pos_difference_category CHECK (category IN (
        'ADDITION', 'AUTHORIZED_CHANGE', 'PROTECTED_FIELD_CHANGE', 'REMOVAL_SIGNAL',
        'MAPPING_CONFLICT', 'INVALID_SOURCE', 'NO_CHANGE')),
    CONSTRAINT ck_pos_difference_authority CHECK (authority IN (
        'QOIDA', 'PROVIDER', 'MAPPING', 'REVIEWED_IMPORT')),
    CONSTRAINT ck_pos_difference_severity CHECK (severity IN ('INFO', 'WARNING', 'BLOCKING')),
    CONSTRAINT ck_pos_difference_action CHECK (recommended_action IN (
        'AUTO_APPLY', 'REVIEW', 'IGNORE', 'STOP')),
    CONSTRAINT ck_pos_difference_review_outcome CHECK (
        review_outcome IS NULL OR review_outcome IN ('APPROVED', 'REJECTED', 'DEFERRED')),
    -- A review is a decision with an author and a time. Any one of the three
    -- alone is a record that cannot be defended.
    CONSTRAINT ck_pos_difference_review_complete CHECK (
        (review_outcome IS NULL) = (reviewed_at IS NULL)
    ),
    CONSTRAINT ck_pos_difference_review_attributed CHECK (
        (review_outcome IS NULL) = (reviewed_by IS NULL)
    )
);

CREATE INDEX ix_pos_difference_run_category
    ON integration.pos_sync_differences (tenant_id, run_id, category, severity);

COMMENT ON TABLE integration.pos_sync_differences IS
    'ADR 0012. One row per entity and field the comparison had something to say about. Re-running a comparison over the same snapshots produces exactly these rows again.';

COMMENT ON COLUMN integration.pos_sync_differences.authority IS
    'Who owns this field under the run''s snapshotted policy. QOIDA fields never auto-apply, whatever the provider sent.';

CREATE TABLE integration.pos_sync_conflicts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,

    entity_type varchar(32) NOT NULL,
    external_entity_id varchar(64) NOT NULL,
    conflict_kind varchar(32) NOT NULL,
    detail varchar(1000) NOT NULL,

    -- The candidates the engine could not choose between. Present because
    -- "ambiguous" is not actionable and "ambiguous between these two" is.
    candidate_entity_ids varchar(1000),

    resolved_by varchar(255),
    resolved_at timestamptz,
    resolution varchar(32),

    CONSTRAINT fk_pos_conflict_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES integration.pos_sync_runs (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_pos_conflict_per_entity UNIQUE (run_id, entity_type, external_entity_id, conflict_kind),
    CONSTRAINT ck_pos_conflict_entity_type CHECK (entity_type IN (
        'PRODUCT', 'VARIANT', 'CATEGORY', 'MODIFIER_GROUP', 'MODIFIER', 'AVAILABILITY')),
    CONSTRAINT ck_pos_conflict_kind CHECK (conflict_kind IN (
        'DUPLICATE_EXTERNAL_ID', 'AMBIGUOUS_TARGET', 'MISSING_PARENT',
        'CROSS_BRAND_REFERENCE', 'UNREPRESENTABLE_STRUCTURE')),
    CONSTRAINT ck_pos_conflict_resolution CHECK (
        resolution IS NULL OR resolution IN ('MAPPED', 'IGNORED', 'ESCALATED')),
    CONSTRAINT ck_pos_conflict_resolution_complete CHECK (
        (resolution IS NULL) = (resolved_at IS NULL)
    ),
    CONSTRAINT ck_pos_conflict_resolution_attributed CHECK (
        (resolution IS NULL) = (resolved_by IS NULL)
    )
);

CREATE INDEX ix_pos_conflict_open
    ON integration.pos_sync_conflicts (tenant_id, run_id)
    WHERE resolution IS NULL;

COMMENT ON TABLE integration.pos_sync_conflicts IS
    'ADR 0012. Conflicts stop rather than resolve. UNREPRESENTABLE_STRUCTURE is the one Clopos produces most: modifiers attach only to a DISH, so a modifier on any other kind cannot be expressed and must surface rather than be silently dropped.';

CREATE TABLE integration.pos_sync_apply_items (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    difference_id uuid,

    -- Stable across retries of one logical apply. An apply that repeats under
    -- the same key is the same apply, which is what makes the whole stage
    -- resumable after a failure halfway through a large catalog.
    idempotency_key varchar(128) NOT NULL,

    action varchar(32) NOT NULL,
    target_type varchar(32) NOT NULL,
    target_id uuid,
    -- The version the comparison saw. If the target has moved since, the item
    -- returns to review instead of overwriting somebody's edit — the ADR 0012
    -- rule that keeps an apply from silently undoing an author's work.
    expected_target_version integer,

    status varchar(24) NOT NULL,
    applied_at timestamptz,
    failure_reason varchar(1000),

    CONSTRAINT fk_pos_apply_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES integration.pos_sync_runs (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_pos_apply_difference FOREIGN KEY (difference_id, tenant_id)
        REFERENCES integration.pos_sync_differences (id, tenant_id) ON DELETE SET NULL,
    CONSTRAINT uq_pos_apply_idempotency UNIQUE (run_id, idempotency_key),
    CONSTRAINT ck_pos_apply_action CHECK (action IN (
        'CREATE_DRAFT_PRODUCT', 'CREATE_DRAFT_VARIANT', 'CREATE_MAPPING',
        'UPDATE_MAPPING', 'RETIRE_MAPPING', 'UPDATE_OPERATIONAL_FIELD',
        'SUSPEND_OFFERING')),
    CONSTRAINT ck_pos_apply_target_type CHECK (target_type IN (
        'PRODUCT', 'VARIANT', 'CATEGORY', 'MODIFIER_GROUP', 'MODIFIER',
        'MAPPING', 'OFFERING')),
    CONSTRAINT ck_pos_apply_status CHECK (status IN (
        'PLANNED', 'APPLIED', 'SKIPPED', 'FAILED', 'RETURNED_TO_REVIEW')),
    CONSTRAINT ck_pos_apply_applied_at CHECK (
        (status = 'APPLIED') = (applied_at IS NOT NULL)
    )
);

CREATE INDEX ix_pos_apply_run_status
    ON integration.pos_sync_apply_items (tenant_id, run_id, status);

COMMENT ON TABLE integration.pos_sync_apply_items IS
    'ADR 0012. What an approved run will do, and what it did. Nothing here writes a customer-facing price, name, or availability; those stay Qoida''s under every policy version this table can carry.';

COMMENT ON COLUMN integration.pos_sync_apply_items.expected_target_version IS
    'The optimistic version the comparison read. A target that moved since goes back to review rather than being overwritten by a decision taken against an older row.';

GRANT USAGE ON SCHEMA integration TO qoida_application;

GRANT SELECT, INSERT, UPDATE ON integration.pos_sync_schedules TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON integration.pos_sync_runs TO qoida_application;
-- Staging is per-run working data with a retention policy, so DELETE is the
-- expiry path rather than an edit path.
GRANT SELECT, INSERT, DELETE ON integration.pos_staged_categories TO qoida_application;
GRANT SELECT, INSERT, DELETE ON integration.pos_staged_products TO qoida_application;
GRANT SELECT, INSERT, DELETE ON integration.pos_staged_variants TO qoida_application;
GRANT SELECT, INSERT, DELETE ON integration.pos_staged_modifier_groups TO qoida_application;
GRANT SELECT, INSERT, DELETE ON integration.pos_staged_modifiers TO qoida_application;
GRANT SELECT, INSERT, DELETE ON integration.pos_staged_availability TO qoida_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON integration.pos_absence_observations TO qoida_application;
-- No DELETE on a difference or a conflict. A reviewed decision is the evidence
-- that a menu changed for a reason, and the reason has to outlive the review.
GRANT SELECT, INSERT, UPDATE ON integration.pos_sync_differences TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON integration.pos_sync_conflicts TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON integration.pos_sync_apply_items TO qoida_application;
