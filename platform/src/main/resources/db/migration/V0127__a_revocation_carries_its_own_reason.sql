-- staff-and-access.md §2 (operations IA §9.1): a suspended person's row shows
-- "the reason from iam.grants.reason of the revocation", and the caption text
-- is called out as the point of the row state, not a bare badge.
--
-- GrantManagementService.revoke(tenantId, grantId, revokerSubject, reason)
-- has taken a reason parameter since V0008, but had nowhere durable to put
-- it: the parameter only ever reached the GrantChanged event (and from there
-- audit.audit_events), while the grant row's own `reason` column kept
-- reporting why the grant was CREATED. A restored grant ("Вернуть доступ")
-- and a revoked row's caption both need the revocation's own reason and
-- actor, distinct from the original grant's.
--
-- Nullable and paired with `status` by a CHECK, the same
-- consumed/created-link pairing shape V0105's
-- ck_telegram_staff_link_code_consumed_pair already uses: an ACTIVE grant has
-- never been revoked and carries none of the three; a REVOKED grant always
-- carries all three, because GrantManagementService.revoke sets them in the
-- same UPDATE that flips the status.

ALTER TABLE iam.grants
    ADD COLUMN revoked_at timestamptz,
    ADD COLUMN revoked_by varchar(255),
    ADD COLUMN revoked_reason varchar(1000);

ALTER TABLE iam.grants
    ADD CONSTRAINT ck_grant_revocation_pair CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL AND revoked_by IS NULL AND revoked_reason IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_by IS NOT NULL AND revoked_reason IS NOT NULL)
    );

COMMENT ON COLUMN iam.grants.revoked_reason IS
    'Why this grant was taken away. Distinct from `reason`, which stays the reason it was granted in the first place.';
