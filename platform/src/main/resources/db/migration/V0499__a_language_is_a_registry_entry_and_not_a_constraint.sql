-- ADR 0149: one place in code says which languages exist (PlatformLocales), so the schema stops
-- saying it a second, third and fourteenth time.
--
-- Fourteen CHECK constraints each spelled the whole set of languages: nine as ('ru', 'uz-Latn',
-- 'en') and five as a set with a bare 'uz'. Every one of them would have to change, in one
-- migration under time pressure, the day a fourth language is activated, and a bare 'uz' in five of
-- them said "Uzbek" without saying which of its two scripts. This migration does the work once:
--
--   1. Each of the fourteen is dropped and re-added as V0430's BCP 47 shape check
--      (^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$), with the IS NULL OR form where the original had one.
--      Which tags are valid is decided where it is decided -- the registry for the platform's own
--      vocabularies, a brand's supported set (tenant.brand_locales, V0242) for tenant content --
--      and the schema only refuses what is not a language tag at all. A new language therefore
--      needs no migration.
--
--   2. A bare 'uz' is rewritten to 'uz-Latn' in the six columns that held it -- the owner invitation,
--      its event log, the password reset, the staff invitation, iam.staff_members.ui_locale and
--      customer.customer_accounts.preferred_locale -- BEFORE the constraint that would refuse the
--      new spelling is recreated, and in the same statement order the old constraint is dropped
--      first, because 'uz-Latn' is not in ('uz', 'ru', 'en'). The customer column was free text
--      (V0017), which the storefront's language screen filled with the id of the language it
--      offers; the marketing copy of it (V0043) never admitted 'uz', so a customer who picked
--      Uzbek handed the metrics refresh a value its target refused.
--
--   3. The five varchar(8) columns among them are widened to the varchar(16) every other locale
--      column uses (the customer's already is): a shape check that admits kaa-Latn-KZ is no use
--      over a column that cannot hold it. Widening a varchar is a catalog change, not a rewrite.
--
-- What this deliberately does NOT touch, and the migration test names each so that a later reader
-- does not "fix" them:
--   * catalog.translations and catalog.publication_items hold the catalog's own Uzbek code 'uz',
--     which the registry names as uz-Latn's catalogCode. A published snapshot is written once and
--     identified by a hash over its keys (V0016 revokes UPDATE and DELETE on it); rewriting the
--     keys would change the hash of every live menu and every one a rollback can return to.
--   * iam.staff_members.spoken_languages holds ISO 639 language codes, where a person who speaks
--     Uzbek speaks it in either script and 'uz' is correct.
--
-- Locking: each ALTER TABLE below holds ACCESS EXCLUSIVE until this migration commits, and
-- re-adding a CHECK scans its table. The largest, notifications.notifications, is also the one
-- written to continuously, so this runs in a quiet window and is rehearsed on a restored copy
-- first (ADR 0149, "Rollout and rollback"). The migration is not reversible in place; its forward
-- rollback is the re-add of the closed CHECKs.

-- ---------------------------------------------------------------------------------------------
-- The six columns that held a bare 'uz'. Drop the closed constraint, widen, rewrite, re-add.

-- tenant.owner_invitations (V0210, ADR 0097)
ALTER TABLE tenant.owner_invitations DROP CONSTRAINT ck_owner_invitation_locale;
ALTER TABLE tenant.owner_invitations ALTER COLUMN locale TYPE varchar(16);
UPDATE tenant.owner_invitations SET locale = 'uz-Latn' WHERE locale = 'uz';
ALTER TABLE tenant.owner_invitations
    ADD CONSTRAINT ck_owner_invitation_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$');

-- tenant.owner_invitation_events (V0215): the history of an invitation. It is appended to and never
-- edited by the application; this is the one correction made to it, and it changes the spelling of
-- a language, not a fact about what happened.
ALTER TABLE tenant.owner_invitation_events DROP CONSTRAINT ck_owner_invitation_event_locale;
ALTER TABLE tenant.owner_invitation_events ALTER COLUMN locale TYPE varchar(16);
UPDATE tenant.owner_invitation_events SET locale = 'uz-Latn' WHERE locale = 'uz';
ALTER TABLE tenant.owner_invitation_events
    ADD CONSTRAINT ck_owner_invitation_event_locale
    CHECK (locale IS NULL OR locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$');

-- iam.password_resets (V0213)
ALTER TABLE iam.password_resets DROP CONSTRAINT ck_password_reset_locale;
ALTER TABLE iam.password_resets ALTER COLUMN locale TYPE varchar(16);
UPDATE iam.password_resets SET locale = 'uz-Latn' WHERE locale = 'uz';
ALTER TABLE iam.password_resets
    ADD CONSTRAINT ck_password_reset_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$');

-- tenant.staff_invitations (V0313)
ALTER TABLE tenant.staff_invitations DROP CONSTRAINT ck_staff_invitation_locale;
ALTER TABLE tenant.staff_invitations ALTER COLUMN locale TYPE varchar(16);
UPDATE tenant.staff_invitations SET locale = 'uz-Latn' WHERE locale = 'uz';
ALTER TABLE tenant.staff_invitations
    ADD CONSTRAINT ck_staff_invitation_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$');

-- iam.staff_members.ui_locale (V0453): the staff member's interface language.
ALTER TABLE iam.staff_members DROP CONSTRAINT ck_staff_member_locale;
ALTER TABLE iam.staff_members ALTER COLUMN ui_locale TYPE varchar(16);
UPDATE iam.staff_members SET ui_locale = 'uz-Latn' WHERE ui_locale = 'uz';
ALTER TABLE iam.staff_members
    ADD CONSTRAINT ck_staff_member_locale CHECK (ui_locale IS NULL OR ui_locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$');

-- customer.customer_accounts.preferred_locale (V0017): free text, no constraint, already varchar(16).
-- Only the rewrite; the column stays free text because a customer's preference is theirs to state
-- and the notification path already falls back for a language it cannot send in.
UPDATE customer.customer_accounts SET preferred_locale = 'uz-Latn' WHERE preferred_locale = 'uz';

-- ---------------------------------------------------------------------------------------------
-- The nine that already said 'uz-Latn': only the closed list goes.

-- notifications.template_versions (V0026)
ALTER TABLE notifications.template_versions DROP CONSTRAINT ck_template_version_locale;
ALTER TABLE notifications.template_versions
    ADD CONSTRAINT ck_template_version_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$');

-- notifications.notifications (V0026)
ALTER TABLE notifications.notifications DROP CONSTRAINT ck_notification_locale;
ALTER TABLE notifications.notifications
    ADD CONSTRAINT ck_notification_locale CHECK (locale IS NULL OR locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$');

-- ordering.order_outcome_reason_texts (V0029)
ALTER TABLE ordering.order_outcome_reason_texts DROP CONSTRAINT ck_outcome_reason_text_locale;
ALTER TABLE ordering.order_outcome_reason_texts
    ADD CONSTRAINT ck_outcome_reason_text_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$');

-- marketing.customer_metrics.preferred_locale (V0043): the marketing copy of the customer's language,
-- which after the rewrite above and the profile service reading the registry agrees with its source.
ALTER TABLE marketing.customer_metrics DROP CONSTRAINT ck_customer_metrics_locale;
ALTER TABLE marketing.customer_metrics
    ADD CONSTRAINT ck_customer_metrics_locale
    CHECK (preferred_locale IS NULL OR preferred_locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$');

-- ordering.order_reject_reason_texts (V0119)
ALTER TABLE ordering.order_reject_reason_texts DROP CONSTRAINT ck_order_reject_reason_text_locale;
ALTER TABLE ordering.order_reject_reason_texts
    ADD CONSTRAINT ck_order_reject_reason_text_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$');

-- legal.terms_version_contents (V0160)
ALTER TABLE legal.terms_version_contents DROP CONSTRAINT ck_terms_content_locale;
ALTER TABLE legal.terms_version_contents
    ADD CONSTRAINT ck_terms_content_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$');

-- payments.payment_method_translations (V0244)
ALTER TABLE payments.payment_method_translations DROP CONSTRAINT ck_payment_method_translation_locale;
ALTER TABLE payments.payment_method_translations
    ADD CONSTRAINT ck_payment_method_translation_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$');

-- tenant.channel_page_contents (V0404)
ALTER TABLE tenant.channel_page_contents DROP CONSTRAINT ck_channel_page_content_locale;
ALTER TABLE tenant.channel_page_contents
    ADD CONSTRAINT ck_channel_page_content_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$');

-- ordering.branch_override_reason_texts (V0423)
ALTER TABLE ordering.branch_override_reason_texts DROP CONSTRAINT ck_branch_override_reason_text_locale;
ALTER TABLE ordering.branch_override_reason_texts
    ADD CONSTRAINT ck_branch_override_reason_text_locale CHECK (locale ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$');
