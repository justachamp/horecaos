-- ADR 0159: service recovery and feedback settings (gap-map rows 5.4 and 5.5).
--
-- ADR 0071 gave a customer one immutable 1-5 rating of their own completed order, and nothing
-- around it. This adds the four things the owner's acceptance of ADR 0159 put around that rating,
-- all in the existing leaf `reviews` schema and none of them touching an order, a payment or a
-- courier:
--
--   1. a tag library the tenant authors, and the tags a review carries (which part went wrong);
--   2. a publication overlay, so a comment can be shown on a brand's public page, hidden by a
--      moderator or withdrawn by its author, without `order_reviews` ever being updated;
--   3. a service-recovery case about exactly one review, with an owner, two clocks, an append-only
--      timeline and the compensation the operator linked to it;
--   4. the single exception to `order_reviews`' "no UPDATE": a data subject's erasure may set the
--      comment to null, and nothing else may change, enforced by a trigger.
--
-- Every table carries tenant_id; every unique and foreign key includes it; every table is granted
-- to horecaos_application below (V0035's lesson); none holds a name, phone, address or code word.
-- Free text (a case note) is envelope-encrypted PERSONAL, exactly as the review comment is.
-- Isolation is application-enforced, as it is for V0168; the RLS backstop (V0161) is attached when
-- the production-hardening phase reaches this schema (ADR 0056).

-- ---------------------------------------------------------------------------- the tag library

CREATE TABLE reviews.feedback_tags (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- Stable, machine-readable, authored once. Immutable once a review carries the tag (the
    -- trigger below), so a report's "food_cold" means the same thing in March and in October.
    code varchar(48) NOT NULL,

    -- Code-owned vocabularies: adding a category is a release, not a tenant action (ADR 0159,
    -- Decision 1). COURIER is counted per location for reporting and nothing else: no automatic
    -- effect on dispatch, pay or availability, and no courier-facing view (open input 8, ADR 0042).
    category varchar(16) NOT NULL,
    sentiment varchar(8) NOT NULL,

    -- A tenant-owned PUBLIC media asset (ADR 0010), 3:2, checked by the service through the media
    -- port. Two-column reference on purpose: a single-column one lets one tenant's tag point at
    -- another tenant's object (V0069).
    icon_asset_id uuid,

    sort_order integer NOT NULL DEFAULT 0,

    -- A tag is retired, never deleted: a retired tag is no longer offered and stays on every
    -- review that carries it.
    status varchar(8) NOT NULL DEFAULT 'ACTIVE',

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by_subject varchar(255) NOT NULL,
    updated_by_subject varchar(255) NOT NULL,

    CONSTRAINT fk_feedback_tag_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT uq_feedback_tag_identity UNIQUE (tenant_id, id),
    CONSTRAINT uq_feedback_tag_code UNIQUE (tenant_id, code),
    CONSTRAINT fk_feedback_tag_icon FOREIGN KEY (icon_asset_id, tenant_id)
        REFERENCES media.assets (asset_id, tenant_id),
    CONSTRAINT ck_feedback_tag_code CHECK (code ~ '^[a-z][a-z0-9_]{1,47}$'),
    CONSTRAINT ck_feedback_tag_category CHECK (
        category IN ('FOOD', 'SERVICE', 'COURIER', 'DELIVERY_TIME', 'OTHER')),
    CONSTRAINT ck_feedback_tag_sentiment CHECK (sentiment IN ('POSITIVE', 'NEGATIVE')),
    CONSTRAINT ck_feedback_tag_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_feedback_tag_version CHECK (version >= 1)
);

CREATE INDEX ix_feedback_tags_offer
    ON reviews.feedback_tags (tenant_id, status, sentiment, sort_order, code);

COMMENT ON TABLE reviews.feedback_tags IS
    'ADR 0159. The tenant''s one-tap feedback tag library. Retired, never deleted. code, category and sentiment are frozen once a review carries the tag.';

-- The per-locale labels: the V0430 shape. The locale is a well-formed BCP 47 tag, not a closed
-- triple, so a fourth language needs no migration (ADR 0149).
CREATE TABLE reviews.feedback_tag_translations (
    tenant_id uuid NOT NULL,
    tag_id uuid NOT NULL,
    locale varchar(16) NOT NULL,
    label varchar(40) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tag_id, locale),
    CONSTRAINT ck_feedback_tag_translation_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_feedback_tag_translation_label CHECK (length(btrim(label)) > 0),
    CONSTRAINT fk_feedback_tag_translation_tag FOREIGN KEY (tag_id, tenant_id)
        REFERENCES reviews.feedback_tags (id, tenant_id)
);

CREATE INDEX ix_feedback_tag_translations_tenant ON reviews.feedback_tag_translations (tenant_id, tag_id);

-- Insert only, like the review: the tags a review carries never change.
CREATE TABLE reviews.order_review_tags (
    tenant_id uuid NOT NULL,
    review_id uuid NOT NULL,
    tag_id uuid NOT NULL,

    PRIMARY KEY (review_id, tag_id),
    CONSTRAINT fk_order_review_tag_review FOREIGN KEY (tenant_id, review_id)
        REFERENCES reviews.order_reviews (tenant_id, id),
    CONSTRAINT fk_order_review_tag_tag FOREIGN KEY (tenant_id, tag_id)
        REFERENCES reviews.feedback_tags (tenant_id, id)
);

-- "How many reviews carry this tag" is the question the frozen-once-used trigger and the tag
-- reports both ask.
CREATE INDEX ix_order_review_tags_tag ON reviews.order_review_tags (tenant_id, tag_id);

-- A tag's code, category and sentiment are what a report groups by. Changing one after a review
-- has carried the tag would silently re-file history, so the database refuses it even if a
-- service forgets to.
CREATE FUNCTION reviews.feedback_tag_frozen_once_used() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.code IS DISTINCT FROM OLD.code
            OR NEW.category IS DISTINCT FROM OLD.category
            OR NEW.sentiment IS DISTINCT FROM OLD.sentiment)
       AND EXISTS (SELECT 1 FROM reviews.order_review_tags used
                    WHERE used.tenant_id = OLD.tenant_id AND used.tag_id = OLD.id) THEN
        RAISE EXCEPTION 'A feedback tag that a review carries cannot change its code, category or sentiment'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_feedback_tag_frozen_once_used
    BEFORE UPDATE ON reviews.feedback_tags
    FOR EACH ROW EXECUTE FUNCTION reviews.feedback_tag_frozen_once_used();

-- --------------------------------------------------------------------- the publication overlay

-- One row per review; order_reviews stays untouched (ADR 0071's immutability). public_consent is
-- the immutable snapshot of what was ticked at submission and is never flipped: the present state
-- of a comment is comment_state together with withdrawal_decision_id.
CREATE TABLE reviews.review_publications (
    tenant_id uuid NOT NULL,
    review_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,

    public_consent boolean NOT NULL,
    -- ADR 0015: the GRANTED decision, when ticked, and the WITHDRAWN decision, set once.
    consent_decision_id uuid,
    withdrawal_decision_id uuid,

    comment_state varchar(10) NOT NULL,
    state_reason_code varchar(32),
    decided_by_actor_type varchar(8),
    decided_by_subject varchar(64),
    decided_at timestamptz,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, review_id),
    CONSTRAINT fk_review_publication_review FOREIGN KEY (tenant_id, review_id)
        REFERENCES reviews.order_reviews (tenant_id, id),
    CONSTRAINT fk_review_publication_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_review_publication_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT ck_review_publication_state CHECK (
        comment_state IN ('NOT_SHARED', 'PENDING', 'PUBLISHED', 'HIDDEN')),
    CONSTRAINT ck_review_publication_actor CHECK (
        decided_by_actor_type IS NULL OR decided_by_actor_type IN ('STAFF', 'CUSTOMER', 'SYSTEM')),
    CONSTRAINT ck_review_publication_consent CHECK (comment_state = 'NOT_SHARED' OR public_consent),
    CONSTRAINT ck_review_publication_withdrawal CHECK (
        withdrawal_decision_id IS NULL OR comment_state = 'HIDDEN'),
    CONSTRAINT ck_review_publication_version CHECK (version >= 1)
);

-- The moderation queue: one brand's comments by state, most recently touched first.
CREATE INDEX ix_review_publications_queue
    ON reviews.review_publications (tenant_id, brand_id, comment_state, updated_at DESC);

-- The public list: one brand's published comments, newest review first.
CREATE INDEX ix_review_publications_public
    ON reviews.review_publications (tenant_id, brand_id, location_id, review_id DESC)
    WHERE comment_state = 'PUBLISHED';

COMMENT ON TABLE reviews.review_publications IS
    'ADR 0159. Whether a review''s comment may be shown on the brand''s public page. The overlay of order_reviews, which stays untouched. A moderator hides words and never numbers: nothing here changes a rating or the average.';

-- Reviews placed before this migration carry no consent, so none of their comments can ever be
-- shown publicly (ADR 0159, accepted trade-off); they still get their overlay row so every read can
-- join it.
INSERT INTO reviews.review_publications
    (tenant_id, review_id, brand_id, location_id, public_consent, comment_state, created_at, updated_at)
SELECT tenant_id, id, brand_id, location_id, false, 'NOT_SHARED', submitted_at, submitted_at
  FROM reviews.order_reviews;

-- ------------------------------------------------------------------------------- the case

CREATE TABLE reviews.recovery_cases (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    location_id uuid NOT NULL,
    order_id uuid NOT NULL,
    review_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,

    source varchar(8) NOT NULL,
    state varchar(18) NOT NULL DEFAULT 'NEW',
    outcome_code varchar(24),

    owner_staff_member_id uuid,
    opened_by_staff_member_id uuid,

    -- The reviews.recovery policy version the clocks were computed from, so a later edit of the
    -- policy never makes an old case late (ADR 0159, Decision 10).
    policy_version integer NOT NULL,
    first_response_due_at timestamptz NOT NULL,
    resolution_due_at timestamptz NOT NULL,

    first_response_at timestamptz,
    closed_at timestamptz,
    first_response_breached_at timestamptz,
    resolution_breached_at timestamptz,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_recovery_case_identity UNIQUE (tenant_id, id),
    -- A case is about exactly one review; a returning complaint is a new review, not a new state.
    CONSTRAINT uq_recovery_case_review UNIQUE (tenant_id, review_id),
    CONSTRAINT fk_recovery_case_review FOREIGN KEY (tenant_id, review_id)
        REFERENCES reviews.order_reviews (tenant_id, id),
    CONSTRAINT fk_recovery_case_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT fk_recovery_case_location FOREIGN KEY (tenant_id, brand_id, location_id)
        REFERENCES tenant.locations (tenant_id, brand_id, id),
    CONSTRAINT fk_recovery_case_customer FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT fk_recovery_case_owner FOREIGN KEY (owner_staff_member_id, tenant_id)
        REFERENCES iam.staff_members (id, tenant_id),
    CONSTRAINT fk_recovery_case_opener FOREIGN KEY (opened_by_staff_member_id, tenant_id)
        REFERENCES iam.staff_members (id, tenant_id),
    CONSTRAINT ck_recovery_case_source CHECK (source IN ('AUTO', 'MANUAL')),
    CONSTRAINT ck_recovery_case_state CHECK (state IN ('NEW', 'IN_PROGRESS', 'RESOLVED', 'CLOSED_UNRESOLVED')),
    CONSTRAINT ck_recovery_case_outcome CHECK (
        (state IN ('NEW', 'IN_PROGRESS') AND outcome_code IS NULL)
        OR (state = 'RESOLVED' AND outcome_code IN (
            'REMEDY_GRANTED', 'OFFLINE_GESTURE', 'EXPLAINED_NO_REMEDY', 'NO_REMEDY_NEEDED'))
        OR (state = 'CLOSED_UNRESOLVED' AND outcome_code IN (
            'CUSTOMER_UNREACHABLE', 'CUSTOMER_DECLINED', 'COMPLAINT_NOT_UPHELD', 'NOT_ACTIONABLE'))),
    CONSTRAINT ck_recovery_case_owner CHECK (state <> 'IN_PROGRESS' OR owner_staff_member_id IS NOT NULL),
    CONSTRAINT ck_recovery_case_closed CHECK (
        (closed_at IS NOT NULL) = (state IN ('RESOLVED', 'CLOSED_UNRESOLVED'))),
    CONSTRAINT ck_recovery_case_clock CHECK (resolution_due_at >= first_response_due_at),
    -- A breach is the worker's mark on an OPEN case, so a breach mark never falls after the close
    -- (a case closed in the gap before the worker's next pass is simply not counted).
    CONSTRAINT ck_recovery_case_first_response_breach CHECK (
        first_response_breached_at IS NULL OR closed_at IS NULL OR first_response_breached_at <= closed_at),
    CONSTRAINT ck_recovery_case_resolution_breach CHECK (
        resolution_breached_at IS NULL OR closed_at IS NULL OR resolution_breached_at <= closed_at),
    CONSTRAINT ck_recovery_case_version CHECK (version >= 1)
);

CREATE INDEX ix_recovery_cases_brand
    ON reviews.recovery_cases (tenant_id, brand_id, state, resolution_due_at);
CREATE INDEX ix_recovery_cases_location ON reviews.recovery_cases (tenant_id, location_id, state);
CREATE INDEX ix_recovery_cases_owner
    ON reviews.recovery_cases (tenant_id, owner_staff_member_id, state) WHERE owner_staff_member_id IS NOT NULL;

-- The two sweepers each select only what they could still mark, so neither ever scans a closed case.
CREATE INDEX ix_recovery_cases_first_response_sweep
    ON reviews.recovery_cases (first_response_due_at)
    WHERE closed_at IS NULL AND first_response_at IS NULL AND first_response_breached_at IS NULL;
CREATE INDEX ix_recovery_cases_resolution_sweep
    ON reviews.recovery_cases (resolution_due_at)
    WHERE closed_at IS NULL AND resolution_breached_at IS NULL;

COMMENT ON TABLE reviews.recovery_cases IS
    'ADR 0159. A service-recovery worklist item about exactly one review: an owner, two clocks snapshotted at opening, an outcome from a closed list. Links to compensation and pays nothing.';

-- The handling history, insert only. A note is free text and therefore PERSONAL, envelope-encrypted;
-- the one UPDATE this table grants is the erasure participant nulling it.
CREATE TABLE reviews.recovery_case_events (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    case_id uuid NOT NULL,
    kind varchar(24) NOT NULL,
    from_state varchar(18),
    to_state varchar(18),
    detail_code varchar(24),
    actor_type varchar(8) NOT NULL,
    actor_staff_member_id uuid,
    note_protected text,
    occurred_at timestamptz NOT NULL,

    CONSTRAINT fk_recovery_case_event_case FOREIGN KEY (tenant_id, case_id)
        REFERENCES reviews.recovery_cases (tenant_id, id),
    CONSTRAINT fk_recovery_case_event_actor FOREIGN KEY (actor_staff_member_id, tenant_id)
        REFERENCES iam.staff_members (id, tenant_id),
    CONSTRAINT ck_recovery_case_event_kind CHECK (kind IN (
        'OPENED', 'ASSIGNED', 'STARTED', 'RELEASED', 'NOTE', 'COMPENSATION_ATTACHED',
        'FIRST_RESPONSE_BREACHED', 'RESOLUTION_BREACHED', 'RESOLVED', 'CLOSED_UNRESOLVED')),
    CONSTRAINT ck_recovery_case_event_actor CHECK (actor_type IN ('STAFF', 'SYSTEM'))
);

CREATE INDEX ix_recovery_case_events_case ON reviews.recovery_case_events (tenant_id, case_id, occurred_at, id);

-- The compensation an operator linked to a case. The case pays nothing: the remedy was made through
-- ADR 0048's console or the grant through ADR 0044's mint, and this is only the link. The amount and
-- currency are copied from the directory at attach time and never re-read, so reviews never reads
-- payments again and the reporting fact reads no payments table.
CREATE TABLE reviews.recovery_case_compensations (
    tenant_id uuid NOT NULL,
    case_id uuid NOT NULL,
    kind varchar(14) NOT NULL,
    ref_id uuid NOT NULL,
    order_id uuid NOT NULL,
    attached_by_staff_member_id uuid,
    attached_at timestamptz NOT NULL,
    amount_minor bigint,
    currency char(3),

    PRIMARY KEY (case_id, kind, ref_id),
    -- A compensation belongs to at most one case.
    CONSTRAINT uq_recovery_case_compensation_once UNIQUE (tenant_id, kind, ref_id),
    CONSTRAINT fk_recovery_case_compensation_case FOREIGN KEY (tenant_id, case_id)
        REFERENCES reviews.recovery_cases (tenant_id, id),
    CONSTRAINT fk_recovery_case_compensation_actor FOREIGN KEY (attached_by_staff_member_id, tenant_id)
        REFERENCES iam.staff_members (id, tenant_id),
    CONSTRAINT ck_recovery_case_compensation_kind CHECK (kind IN ('ORDER_REMEDY', 'BENEFIT_GRANT')),
    CONSTRAINT ck_recovery_case_compensation_money CHECK ((amount_minor IS NULL) = (currency IS NULL)),
    CONSTRAINT ck_recovery_case_compensation_amount CHECK (amount_minor IS NULL OR amount_minor >= 0),
    CONSTRAINT ck_recovery_case_compensation_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    -- A benefit grant is a count, not money.
    CONSTRAINT ck_recovery_case_compensation_grant CHECK (kind = 'ORDER_REMEDY' OR amount_minor IS NULL)
);

-- ------------------------------------------------------ order_reviews: the one erasure exception

-- ADR 0071: "no UPDATE statement exists anywhere in the reviews module". ADR 0159 reopens that for
-- one column and one purpose: a data subject's erasure nulls the comment. The column grant is the
-- first fence and this trigger the second -- it refuses every change except comment_protected going
-- to null, whoever holds a broader grant, and compares the whole row so a column added later is
-- covered without anyone remembering to edit this.
CREATE FUNCTION reviews.order_reviews_only_the_comment_may_be_erased() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.comment_protected IS NOT NULL
       OR (to_jsonb(NEW) - 'comment_protected') IS DISTINCT FROM (to_jsonb(OLD) - 'comment_protected') THEN
        RAISE EXCEPTION 'A review is immutable: only its comment may be erased, by setting it to null'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_order_reviews_only_the_comment_may_be_erased
    BEFORE UPDATE ON reviews.order_reviews
    FOR EACH ROW EXECUTE FUNCTION reviews.order_reviews_only_the_comment_may_be_erased();

COMMENT ON TABLE reviews.order_reviews IS
    'ADR 0071, amended by ADR 0159. Immutable once written, with one exception: an erasure request may set comment_protected to null (a trigger refuses any other UPDATE). The moderation and publication state lives in review_publications, never here.';

-- --------------------------------------------------------------------------- the consent purpose

-- ADR 0015's purpose registry (V0289) is seeded for a tenant only on its first read, so a tenant
-- that already has a registry would never learn of a purpose added to the code-owned defaults.
-- This inserts review.public_display for every tenant that has one; a tenant without a registry
-- gets it with the rest of the defaults (ConsentTypeService.DEFAULTS). One sentence in ru, uz-Latn
-- and en saying the words may appear on this restaurant's page without the customer's name; policy
-- version 1 (ADR 0159, open input 2 -- the wording is product's and legal's to confirm).
INSERT INTO customer.consent_types
    (id, tenant_id, code, label_ru, label_uz, label_en, description, channel_specific, policy_version,
     active, created_by, updated_by)
SELECT uuidv7(), existing.tenant_id, 'review.public_display',
       'Показать мой отзыв на странице ресторана без моего имени',
       'Sharhimni restoran sahifasida ismimsiz ko''rsatish',
       'Show my review on the restaurant''s page without my name',
       'The customer agrees that the words of one review may appear on the restaurant''s public page, '
           || 'without their name, number or order.',
       false, '1', true, 'system:migration:V0543', 'system:migration:V0543'
  FROM (SELECT DISTINCT tenant_id FROM customer.consent_types) existing
ON CONFLICT (tenant_id, code) DO NOTHING;

-- ------------------------------------------------------------------------------------- grants

GRANT SELECT, INSERT, UPDATE ON reviews.feedback_tags TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON reviews.feedback_tag_translations TO horecaos_application;
GRANT SELECT, INSERT ON reviews.order_review_tags TO horecaos_application;

-- The publication row is mutable by design, but never its consent snapshot: public_consent is
-- deliberately absent from the column grant.
GRANT SELECT, INSERT ON reviews.review_publications TO horecaos_application;
GRANT UPDATE (comment_state, state_reason_code, decided_by_actor_type, decided_by_subject, decided_at,
              withdrawal_decision_id, version, updated_at)
    ON reviews.review_publications TO horecaos_application;

GRANT SELECT, INSERT, UPDATE ON reviews.recovery_cases TO horecaos_application;

GRANT SELECT, INSERT ON reviews.recovery_case_events TO horecaos_application;
GRANT UPDATE (note_protected) ON reviews.recovery_case_events TO horecaos_application;

GRANT SELECT, INSERT ON reviews.recovery_case_compensations TO horecaos_application;

-- The erasure exception on order_reviews, narrowed to the one column (the trigger narrows it again).
GRANT UPDATE (comment_protected) ON reviews.order_reviews TO horecaos_application;
