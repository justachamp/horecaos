-- ADR 0010 and ADR 0029: the one media reference V0065's sweep did not reach.
--
-- `fulfillment.courier_engagements.evidence_media_id` has referenced
-- `media.assets (asset_id)` since V0040 — a single column, sitting directly
-- beside `fk_engagement_courier (courier_id, tenant_id)`, which is composite.
-- V0058 added `uq_media_assets_tenant_scoped (asset_id, tenant_id)` and stated
-- the reason in its own header: media.assets is keyed on asset_id alone, and a
-- single-column reference lets one tenant's row point at another tenant's asset.
-- V0065 moved `catalog.media_relations` and `media.derivative_jobs` onto that
-- key. This column was missed, and it is the worst one to miss.
--
-- What it points at is the scan of a courier's self-employment registration
-- certificate: a named person's tax document, ADR 0029 personal data, stored
-- with PRIVATE visibility. Two things follow from the missing tenant column, and
-- both were reproduced against a real database before this migration was written:
--
--   1. Tenant B can durably store a pointer to tenant A's private asset, and
--      that foreign identifier is then copied into B's own audit evidence field.
--   2. Because the constraint accepts exactly the ids that exist somewhere on
--      the platform and rejects every other uuid, the verify endpoint answers
--      "does this media asset id exist anywhere on HorecaOS" for any uuid a caller
--      cares to submit. A foreign key is a fine integrity rule and a terrible
--      place to learn that an identifier is real.
--
-- The identical attach against `catalog.media_relations` and
-- `media.derivative_jobs` is already refused by PostgreSQL. This makes the third
-- reference behave like the other two.
--
-- The Java side is fixed separately and is the part a caller sees:
-- CourierEngagementService resolves the asset in the caller's own tenant through
-- MediaAvailability and refuses with one answer that does not distinguish "not
-- yours" from "does not exist". This constraint is what holds when that check is
-- bypassed — a background job, a repair script, a future second write path.

-- ---------------------------------------------------------------------------
-- 1. Evidence before enforcement
-- ---------------------------------------------------------------------------

-- An engagement whose evidence pointer cannot satisfy the composite key cannot
-- be deleted: it is a live labour arrangement, and somebody is paid against it.
-- So the pointer is moved rather than the row, and moved rather than dropped —
-- V0065's rule, and ADR 0024's. "Which asset did this tenant believe it had
-- sighted" is the only question a compliance review can ask afterwards, and a
-- silently nulled column cannot answer it.
--
-- Deliberately not a media table: this is the courier module's record of a
-- reference it once held, and it names no asset it is entitled to read.
CREATE TABLE fulfillment.courier_engagement_evidence_orphans (
    tenant_id uuid NOT NULL,
    engagement_id uuid NOT NULL,
    evidence_media_id uuid NOT NULL,
    quarantine_reason varchar(64) NOT NULL,
    quarantined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, engagement_id, evidence_media_id)
);

COMMENT ON TABLE fulfillment.courier_engagement_evidence_orphans IS
    'Evidence media references removed from fulfillment.courier_engagements by V0069 because no media asset of that tenant matched. Retained as evidence of what was once attached; never read by the application, and never resolved to an object.';

GRANT SELECT, INSERT ON fulfillment.courier_engagement_evidence_orphans TO horecaos_application;

-- Move first, constrain second. An ALTER that fails on real data is not a
-- migration, and "there is probably nothing in that column" is not a plan. On
-- the development database this moves nothing; on a database that has been
-- verifying couriers it may move a row, and the row survives either way.
--
-- Copy, then clear — two statements rather than V0065's single
-- `WITH ... RETURNING`. V0065 could use one because it deleted the row and a
-- DELETE returns what it removed. This clears a column, and an UPDATE's
-- RETURNING gives the new values, so the same shape would faithfully record a
-- NULL as the evidence somebody once attached. Both statements run inside the
-- migration's transaction and share one predicate, so no row can be recorded and
-- kept, or cleared and lost.
--
-- Nulling the column is safe against every CHECK on the table:
-- ck_engagement_active_is_verified names the verification instant, the protected
-- reference and the two dates, and not this column. Evidence is optional on
-- purpose — a manual attestation is a person's sworn sighting, and ADR 0042 does
-- not require them to have scanned anything.
INSERT INTO fulfillment.courier_engagement_evidence_orphans (
    tenant_id, engagement_id, evidence_media_id, quarantine_reason)
SELECT engagement.tenant_id, engagement.id, engagement.evidence_media_id,
       'NO_MEDIA_ASSET_IN_TENANT'
FROM fulfillment.courier_engagements AS engagement
WHERE engagement.evidence_media_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM media.assets AS asset
      WHERE asset.asset_id = engagement.evidence_media_id
        AND asset.tenant_id = engagement.tenant_id);

UPDATE fulfillment.courier_engagements AS engagement
   SET evidence_media_id = NULL,
       updated_at = now()
 WHERE engagement.evidence_media_id IS NOT NULL
   AND NOT EXISTS (
       SELECT 1
       FROM media.assets AS asset
       WHERE asset.asset_id = engagement.evidence_media_id
         AND asset.tenant_id = engagement.tenant_id);

-- ---------------------------------------------------------------------------
-- 2. The constraint V0058 prepared the target for
-- ---------------------------------------------------------------------------

-- Dropping and re-adding is the only way to change a foreign key's column list,
-- and it is what this is: the same reference, now naming the tenant that both
-- rows already carry.
ALTER TABLE fulfillment.courier_engagements
    DROP CONSTRAINT fk_engagement_evidence;

-- Composite, against uq_media_assets_tenant_scoped (asset_id, tenant_id) — the
-- two columns, in that order, and nothing else, which is what a foreign key
-- requires of the unique constraint it references.
--
-- evidence_media_id stays nullable and the default MATCH SIMPLE is what makes
-- that work: with any referenced column NULL the constraint is not checked, so
-- an engagement with no scan is still accepted, while a non-null pointer is
-- always checked against both columns because tenant_id is NOT NULL.
--
-- No ON DELETE clause, so NO ACTION stands, matching V0065: an asset a live
-- engagement cites cannot be deleted out from under it, and media deletion is a
-- status transition on the asset rather than a row removal.
--
-- No supporting index on the referencing side, for the same reason
-- catalog.media_relations has none: the index a foreign key wants here would
-- serve deletes and key updates of media.assets, and there are none — asset_id
-- is immutable and deletion is a status change.
ALTER TABLE fulfillment.courier_engagements
    ADD CONSTRAINT fk_engagement_evidence FOREIGN KEY (evidence_media_id, tenant_id)
        REFERENCES media.assets (asset_id, tenant_id);

COMMENT ON COLUMN fulfillment.courier_engagements.evidence_media_id IS
    'ADR 0010 media asset id for the sighted registration certificate, PRIVATE and ADR 0029 personal data. Scoped to this engagement''s tenant by fk_engagement_evidence since V0069; before that a single-column reference let one tenant cite another tenant''s asset.';
