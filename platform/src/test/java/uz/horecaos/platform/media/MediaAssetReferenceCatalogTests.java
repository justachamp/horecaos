package uz.horecaos.platform.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Every column in the database that holds a media asset id is accounted for (ADR 0157, Media and video).
 *
 * <p>The content module releases a superseded revision's pictures by asking the media module to let go
 * of them, and the media module lets go only if every module that stores an asset id says it no longer
 * uses it ({@code MediaUsageProbe}). That is only as safe as the list of modules that were asked. A new
 * table with a {@code logo_asset_id} that nobody told the probes about would have its picture deleted
 * from under a live page, and nothing would fail until a customer saw a broken image.
 *
 * <p>So this test reads the database's own catalogue for every column that references
 * {@code media.assets} or is named like an asset reference, and requires each to be either covered by a
 * probe, named here with the probe that covers it, or <em>excluded for a stated reason</em>. Adding such
 * a column without adding a line here fails the build; the line is where the author is made to decide.
 *
 * <p>The exclusions are all private assets. {@code MediaRelease} reaches only a {@code PUBLIC} asset
 * owned at {@code BRAND} scope (the kind a creative or a poster is), so a courier's evidence photo, a
 * staff member's portrait or a compliance document can never be released through it, whatever id it is
 * handed, and needs no probe.
 */
class MediaAssetReferenceCatalogTests {

    /** Columns that hold an asset id of a PUBLIC brand asset, and the module whose probe answers for them. */
    private static final Map<String, String> COVERED_BY_A_PROBE = Map.of(
            "catalog.media_relations.media_asset_id", "catalog",
            "catalog.channel_media_overrides.media_asset_id", "catalog",
            "tenant.brand_media.media_asset_id", "tenancy",
            "tenant.channel_presentation.og_image_asset_id", "tenancy",
            "support.social_links.media_asset_id", "helpcenter",
            "content.revision_media.media_asset_id", "content");

    /**
     * Columns that can only hold a private asset, with the reason. The release guard is in the SQL
     * ({@code visibility = 'PUBLIC' AND owner_scope = 'BRAND'}).
     */
    private static final Map<String, String> EXCLUDED = new TreeMap<>();

    static {
        EXCLUDED.put(
                "fulfillment.courier_engagements.evidence_media_id",
                "a courier's evidence photo: private, tenant-owned (ADR 0029)");
        EXCLUDED.put(
                "fulfillment.courier_engagement_evidence_orphans.evidence_media_id",
                "quarantined evidence of a relation to an asset that did not exist; never shown");
        EXCLUDED.put("fulfillment.couriers.photo_media_id", "a courier's own photograph: private, tenant-owned");
        EXCLUDED.put("iam.staff_members.photo_asset_id", "a staff member's portrait: private, tenant-owned (ADR 0029)");
        EXCLUDED.put(
                "catalog.media_relation_orphans.media_asset_id",
                "quarantined relation to an asset that did not exist in the tenant; evidence, never read by the storefront");
    }

    private static TestDatabase.Handle db;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("every column that holds a media asset id is covered by a usage probe or excluded for a stated reason")
    void everyAssetReferenceIsAccountedFor() {
        Set<String> found = new TreeSet<>(
                JdbcClient.create(db.dataSource()).sql("""
                        SELECT n.nspname || '.' || c.relname || '.' || a.attname
                        FROM pg_attribute a
                        JOIN pg_class c ON c.oid = a.attrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE a.attnum > 0 AND NOT a.attisdropped
                          AND c.relkind IN ('r', 'p') AND NOT c.relispartition
                          AND n.nspname NOT IN ('media', 'pg_catalog', 'information_schema', 'public')
                          AND a.atttypid = 'uuid'::regtype
                          AND (a.attname ~ '(asset_id|media_id)$'
                               OR EXISTS (
                                   SELECT 1 FROM pg_constraint k
                                   WHERE k.conrelid = c.oid AND k.contype = 'f'
                                     AND k.confrelid = 'media.assets'::regclass
                                     AND a.attnum = ANY (k.conkey)
                                     AND a.attname <> 'tenant_id'))
                        """).query(String.class).list());

        Set<String> accountedFor = new TreeSet<>(COVERED_BY_A_PROBE.keySet());
        accountedFor.addAll(EXCLUDED.keySet());

        assertThat(found)
                .as("a column that holds an asset id and is neither covered by a MediaUsageProbe nor excluded here")
                .containsExactlyInAnyOrderElementsOf(accountedFor);
    }
}
