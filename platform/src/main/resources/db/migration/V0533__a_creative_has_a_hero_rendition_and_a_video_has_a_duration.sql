-- ADR 0157, Release 1 and Release 3: the two small changes content makes to `media`.
--
-- 1. HERO. A 3:1 banner on a desktop channel needs more than DETAIL's 800 px, so the closed
--    rendition set gains one member, HERO (w1600). It is rendered ONLY for an asset allocated
--    with the CREATIVE rendition profile, which the console's content upload sets: the
--    derivative loop renders the three standard variants for every asset, plus HERO for a
--    creative, so a catalogue photograph or a staff photo on the one 8 vCPU machine (ADR 0073)
--    never costs a fourth JPEG. An asset with no HERO (everything uploaded before this
--    migration, or not uploaded for content) is not an error: the read model lists the
--    renditions that exist and a client falls back to the largest.
--
--    The new constraint is added NOT VALID, validated, and only then does the old one go:
--    there is never a moment when `media.derivatives` has no variant check, and no moment when
--    a HERO row is refused.
--
-- 2. rendition_profile on media.assets: null (the standard set) or CREATIVE.
--
-- 3. duration_ms on media.assets (Release 3): the playing time of a verified MP4, read by the
--    bounded VideoProbe from `mvhd`. Null for every image.
--
-- Rollback (ADR 0157, Rollout): the previous image lists derivatives with
-- DerivativeVariant.valueOf, which throws on a HERO row, so a rollback first runs
--     DELETE FROM media.derivatives WHERE variant = 'HERO';
-- (the rows are re-renderable, and only creatives have any). The widened constraint and the new
-- columns stay: they are additive.

ALTER TABLE media.derivatives
    ADD CONSTRAINT ck_media_derivative_variant_v2
        CHECK (variant IN ('THUMBNAIL', 'CARD', 'DETAIL', 'HERO')) NOT VALID;
ALTER TABLE media.derivatives VALIDATE CONSTRAINT ck_media_derivative_variant_v2;
ALTER TABLE media.derivatives DROP CONSTRAINT ck_media_derivative_variant;
ALTER TABLE media.derivatives
    RENAME CONSTRAINT ck_media_derivative_variant_v2 TO ck_media_derivative_variant;

ALTER TABLE media.assets
    ADD COLUMN rendition_profile varchar(16),
    ADD COLUMN duration_ms integer,
    ADD CONSTRAINT ck_media_asset_rendition_profile CHECK (
        rendition_profile IS NULL OR rendition_profile = 'CREATIVE'),
    ADD CONSTRAINT ck_media_asset_duration CHECK (duration_ms IS NULL OR duration_ms > 0);

COMMENT ON COLUMN media.assets.rendition_profile IS
    'ADR 0157: null renders the three standard renditions; CREATIVE additionally renders HERO (w1600). Set at allocation by the console''s content upload, never by a customer.';
COMMENT ON COLUMN media.assets.duration_ms IS
    'ADR 0157 Release 3: the verified playing time of an MP4, from its own mvhd box. Null for an image.';
