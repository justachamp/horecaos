-- ADR 0091: an SMS template version a gateway has to approve waits for that
-- approval before anything is sent with it.
--
-- Some SMS gateways refuse any text their operator has not approved. A version
-- HorecaOS staff mark as awaiting the provider is withheld from sending until
-- they record the provider's answer; every version starts as not needing one,
-- so nothing already sending changes.

ALTER TABLE notifications.template_versions
    ADD COLUMN provider_review varchar(16) NOT NULL DEFAULT 'NOT_REQUIRED',
    ADD COLUMN provider_review_reference varchar(200),
    ADD COLUMN provider_review_note varchar(1000),
    ADD COLUMN provider_review_updated_by varchar(255),
    ADD COLUMN provider_review_updated_at timestamptz;

ALTER TABLE notifications.template_versions
    ADD CONSTRAINT ck_template_version_provider_review CHECK (
        provider_review IN ('NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED')
    ),
    ADD CONSTRAINT ck_template_version_provider_review_attributed CHECK (
        provider_review = 'NOT_REQUIRED'
        OR (provider_review_updated_by IS NOT NULL AND provider_review_updated_at IS NOT NULL)
    );

CREATE INDEX ix_template_versions_provider_review_pending
    ON notifications.template_versions (provider_review_updated_at)
    WHERE provider_review = 'PENDING';

COMMENT ON COLUMN notifications.template_versions.provider_review IS
    'ADR 0091. Whether the SMS gateway has approved this wording: NOT_REQUIRED sends as before, PENDING and REJECTED are withheld from sending, APPROVED sends.';
