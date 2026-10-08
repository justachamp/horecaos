-- ADR 0044 / ADR 0112: a quiet window that "tightens" must still be the evening and the morning.
--
-- ck_contact_policy_quiet_tighten_only (V0495) and ck_engagement_quiet_hours_tighten_only
-- (V0043) state the rule as two bounds: the window starts no later than 21:00 and ends no
-- earlier than 10:00. Those bounds describe a tighter window only when the window wraps
-- midnight. 05:00 to 11:00 satisfies both and closes just those six hours, which leaves
-- 21:00 to 05:00 open: the "tightening" opened the evening and the night.
--
-- The closed window is the evening and the morning, so it starts after it ends. Stating
-- that here closes the hole for any writer, not only for the service that already refuses
-- it (ContactPolicyService, EngagementPolicy#tightenedBy).
--
-- NOT VALID: a row written before this migration is not re-checked, so the migration
-- cannot fail on data it did not create, and no row is rewritten. Every new row, and every
-- update of an old one, is checked. The runtime does not trust the old rows either: it asks
-- each closed window on its own whether it holds a moment (ContactPolicyService#quietWindows),
-- so a pre-existing malformed window can add closed time and can never remove any.

ALTER TABLE marketing.contact_policy_overrides
    ADD CONSTRAINT ck_contact_policy_quiet_wraps_midnight CHECK (
        quiet_hours_start IS NULL OR quiet_hours_start > quiet_hours_end
    ) NOT VALID;

ALTER TABLE marketing.engagement_policies
    ADD CONSTRAINT ck_engagement_quiet_hours_wraps_midnight CHECK (
        quiet_hours_start IS NULL OR quiet_hours_start > quiet_hours_end
    ) NOT VALID;

COMMENT ON CONSTRAINT ck_contact_policy_quiet_wraps_midnight ON marketing.contact_policy_overrides IS
    'V0580. A quiet window is the evening and the morning, so it starts after it ends. Without this, 05:00 to 11:00 passed the two tighten-only bounds and opened the evening. Not validated against rows older than the constraint.';
COMMENT ON CONSTRAINT ck_engagement_quiet_hours_wraps_midnight ON marketing.engagement_policies IS
    'V0580. The same rule for a brand''s own quiet hours. Not validated against rows older than the constraint.';
