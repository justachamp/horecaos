package uz.horecaos.platform.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.content.application.ContentAuthoringService;
import uz.horecaos.platform.content.application.ContentEnvironment;
import uz.horecaos.platform.content.application.ContentMetrics;
import uz.horecaos.platform.content.application.ContentRevisionMediaSweeper;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.media.api.MediaCreativeLookup;
import uz.horecaos.platform.media.api.MediaRelease;
import uz.horecaos.platform.media.api.MediaUsageProbe;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * What happens to a superseded revision's pictures (ADR 0157, Media and video): kept for the
 * retention window, then released -- unless something still uses them, and never touching anything
 * that is not a brand's own public creative.
 *
 * <p>The revisions are written as rows, with explicit publication instants, because the thing under
 * test is a query over {@code published_at} against a movable clock and the rows are exactly what the
 * publish service writes. The release itself goes through the real media module: the real
 * {@link MediaRelease}, the real probes of every module that stores an asset id, the real audit
 * recorder, over real PostgreSQL.
 */
@SpringBootTest
class ContentMediaRetentionTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-7000-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9b20-7000-7000-8000-0000000000c2");
    private static final UUID BRAND = UUID.fromString("018f9b20-7000-7000-8000-0000000000d1");
    private static final UUID FOREIGN_BRAND = UUID.fromString("018f9b20-7000-7000-8000-0000000000d3");

    private static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // The deletion worker would try to reach an object store this test does not have.
        registry.add("horecaos.media.deletion.enabled", () -> "false");
        registry.add("horecaos.content.media-sweep.enabled", () -> "true");
    }

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    @SuppressWarnings("NullAway")
    private ContentRevisionMediaSweeper sweeper;

    @Autowired
    @SuppressWarnings("NullAway")
    private MediaRelease release;

    @Autowired
    @SuppressWarnings("NullAway")
    private List<MediaUsageProbe> probes;

    @Autowired
    @SuppressWarnings("NullAway")
    private ContentAuthoringService authoring;

    @Autowired
    @SuppressWarnings("NullAway")
    private MovableClock clock;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcContentStore store;

    @Autowired
    @SuppressWarnings("NullAway")
    private MediaCreativeLookup mediaLookup;

    @Autowired
    @SuppressWarnings("NullAway")
    private ContentEnvironment environment;

    @Autowired
    @SuppressWarnings("NullAway")
    private ContentMetrics metrics;

    private ContentFixture fixture;

    @BeforeEach
    void reset() {
        clock.set(NOW);
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, catalog.catalogs,"
                        + " pricing.promotions, media.assets CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.policies, tenant.policy_current").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        fixture = new ContentFixture(jdbc);
        fixture.tenant(TENANT, "content-retention");
        fixture.tenant(OTHER_TENANT, "content-retention-other");
        fixture.brand(TENANT, BRAND, "MAIN");
        fixture.brand(OTHER_TENANT, FOREIGN_BRAND, "FOREIGN");
    }

    // ==================================================================== helpers

    private UUID asset() {
        return fixture.goodBanner(TENANT, BRAND);
    }

    /** A banner whose revisions were published on the given days before {@link #NOW}, each using the given assets. */
    private UUID banner(Map<Integer, List<UUID>> daysAgoToAssets) {
        return banner(TENANT, BRAND, daysAgoToAssets);
    }

    private UUID banner(UUID tenant, UUID brand, Map<Integer, List<UUID>> daysAgoToAssets) {
        UUID item = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO content.items (id, tenant_id, brand_id, kind, placement, internal_name, display_title,
                                           state, sort_key, version, created_by)
                VALUES (:id, :t, :b, 'BANNER', 'HOME_CAROUSEL', 'banner', '', 'DRAFT', 10, 1, 'test')
                """).param("id", item).param("t", tenant).param("b", brand).update();
        int number = 0;
        for (var entry : daysAgoToAssets.entrySet().stream()
                .sorted(Map.Entry.<Integer, List<UUID>>comparingByKey().reversed())
                .toList()) {
            number++;
            jdbc.sql("""
                    INSERT INTO content.item_revisions (item_id, revision_no, tenant_id, brand_id, document,
                                                        content_hash, published_by, published_at)
                    VALUES (:item, :n, :t, :b, '{}'::jsonb, :hash, 'test', :at)
                    """)
                    .param("item", item)
                    .param("n", number)
                    .param("t", tenant)
                    .param("b", brand)
                    .param("hash", String.format("%064x", number))
                    .param("at", java.sql.Timestamp.from(NOW.minus(Duration.ofDays(entry.getKey()))))
                    .update();
            for (UUID asset : entry.getValue()) {
                jdbc.sql("""
                        INSERT INTO content.revision_media (item_id, revision_no, tenant_id, media_asset_id, role, position)
                        VALUES (:item, :n, :t, :asset, 'PRIMARY', 0)
                        """)
                        .param("item", item)
                        .param("n", number)
                        .param("t", tenant)
                        .param("asset", asset)
                        .update();
            }
        }
        jdbc.sql("UPDATE content.items SET state = 'PUBLISHED', live_revision_no = :n WHERE id = :id")
                .param("n", number)
                .param("id", item)
                .update();
        return item;
    }

    private String status(UUID asset) {
        return jdbc.sql("SELECT status FROM media.assets WHERE asset_id = :id")
                .param("id", asset)
                .query(String.class)
                .single();
    }

    private long releaseAudits(UUID asset) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = 'media.asset.released'"
                        + " AND target_type = 'media_asset' AND target_id = :id")
                .param("id", asset)
                .query(Long.class)
                .single();
    }

    // ================================================================== the window

    @Test
    @DisplayName(
            "a superseded revision's media is released only once it has been superseded for the retention window; the live revision's is never touched")
    void theRetentionWindowDecides() {
        UUID first = asset();
        UUID second = asset();
        UUID live = asset();
        // Revision 1 was superseded by revision 2 ninety-five days ago (past the 90-day default);
        // revision 2 was superseded by revision 3 ten days ago (inside it); revision 3 is live.
        banner(Map.of(100, List.of(first), 95, List.of(second), 10, List.of(live)));

        var pass = sweeper.sweepOnce();

        assertThat(pass.released()).isEqualTo(1);
        assertThat(status(first)).isEqualTo("DELETION_REQUESTED");
        assertThat(status(second))
                .as("superseded ten days ago: inside the window")
                .isEqualTo("AVAILABLE");
        assertThat(status(live)).isEqualTo("AVAILABLE");
        assertThat(releaseAudits(first)).isEqualTo(1);

        // Time passes: the second is now past its window as well, and the live one still is not.
        clock.set(NOW.plus(Duration.ofDays(81)));
        assertThat(sweeper.sweepOnce().released()).isEqualTo(1);
        assertThat(status(second)).isEqualTo("DELETION_REQUESTED");
        assertThat(status(live)).as("a live revision's media is never released").isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName(
            "the window is a duration measured from the supersession: one day short of it releases nothing, the day it passes does")
    void theWindowIsADuration() {
        UUID old = asset();
        UUID live = asset();
        // Superseded 89 days ago.
        banner(Map.of(100, List.of(old), 89, List.of(live)));

        assertThat(sweeper.sweepOnce().released()).isZero();
        assertThat(status(old)).isEqualTo("AVAILABLE");

        clock.set(NOW.plus(Duration.ofDays(2)));
        assertThat(sweeper.sweepOnce().released()).isEqualTo(1);
        assertThat(status(old)).isEqualTo("DELETION_REQUESTED");
    }

    @Test
    @DisplayName("an asset any current revision still uses is not released, however old another use of it is")
    void aSharedAssetIsKept() {
        UUID shared = asset();
        UUID other = asset();
        // Used by revision 1 (long superseded) and by the live revision 2.
        banner(Map.of(200, List.of(shared), 150, List.of(shared, other)));

        sweeper.sweepOnce();

        assertThat(status(shared)).isEqualTo("AVAILABLE");
        assertThat(status(other)).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("a second pass over the same state releases nothing more")
    void aSweepIsIdempotent() {
        UUID old = asset();
        UUID live = asset();
        banner(Map.of(200, List.of(old), 150, List.of(live)));

        assertThat(sweeper.sweepOnce().released()).isEqualTo(1);
        var again = sweeper.sweepOnce();

        assertThat(again.released()).isZero();
        assertThat(again.skipped())
                .as("the released asset is seen and left alone")
                .isEqualTo(1);
        assertThat(releaseAudits(old)).isEqualTo(1);
    }

    // ======================================================= a pass is bounded, and fair

    private ContentRevisionMediaSweeper sweeperWithABudgetOf(int releases) {
        return new ContentRevisionMediaSweeper(store, mediaLookup, release, environment, metrics, clock, releases);
    }

    /** What a product still showing the asset does: the release is asked for, and some module says no. */
    private void heldBackByAProduct(UUID tenant, UUID brand, UUID asset) {
        jdbc.sql("""
                INSERT INTO catalog.media_relations (tenant_id, brand_id, entity_type, entity_id, media_asset_id, role)
                VALUES (:t, :b, 'PRODUCT', :entity, :asset, 'PRIMARY')
                """)
                .param("t", tenant)
                .param("b", brand)
                .param("entity", UUID.randomUUID())
                .param("asset", asset)
                .update();
    }

    @Test
    @DisplayName(
            "assets another module holds back are probed in turn and do not use up every pass: with a budget of two, the free asset behind two held-back ones is released by the second pass")
    void heldBackAssetsDoNotStarveTheOnesBehindThem() {
        ContentRevisionMediaSweeper small = sweeperWithABudgetOf(2);
        UUID live = asset();
        UUID heldOne = asset();
        UUID heldTwo = asset();
        UUID heldThree = asset();
        UUID free = asset();
        // Superseded 250, 240, 230 and 220 days ago: the three held-back assets are the oldest candidates.
        banner(Map.of(300, List.of(heldOne), 250, List.of(live)));
        banner(Map.of(290, List.of(heldTwo), 240, List.of(live)));
        banner(Map.of(280, List.of(heldThree), 230, List.of(live)));
        banner(Map.of(270, List.of(free), 220, List.of(live)));
        List.of(heldOne, heldTwo, heldThree).forEach(held -> heldBackByAProduct(TENANT, BRAND, held));

        var first = small.sweepOnce();
        assertThat(first.kept())
                .as("the first pass spends its budget on the two oldest")
                .isEqualTo(2);
        assertThat(status(free)).isEqualTo("AVAILABLE");

        var second = small.sweepOnce();

        assertThat(second.released())
                .as("the second pass goes on from where the first stopped, not back to the same two")
                .isEqualTo(1);
        assertThat(status(free)).isEqualTo("DELETION_REQUESTED");
        assertThat(List.of(status(heldOne), status(heldTwo), status(heldThree))).containsOnly("AVAILABLE");
    }

    @Test
    @DisplayName(
            "a tenant whose candidates fill the budget does not keep every other tenant waiting: the passes take the tenants in turn and each tenant goes on from where it stopped")
    void oneTenantDoesNotStarveTheOthers() {
        ContentRevisionMediaSweeper small = sweeperWithABudgetOf(2);
        UUID live = asset();
        UUID otherLive = fixture.goodBanner(OTHER_TENANT, FOREIGN_BRAND);
        UUID[] held = new UUID[4];
        UUID[] free = new UUID[2];
        for (int i = 0; i < 2; i++) {
            held[i] = asset();
            held[2 + i] = fixture.goodBanner(OTHER_TENANT, FOREIGN_BRAND);
            heldBackByAProduct(TENANT, BRAND, held[i]);
            heldBackByAProduct(OTHER_TENANT, FOREIGN_BRAND, held[2 + i]);
            banner(TENANT, BRAND, Map.of(300 - i, List.of(held[i]), 250 - i, List.of(live)));
            banner(OTHER_TENANT, FOREIGN_BRAND, Map.of(300 - i, List.of(held[2 + i]), 250 - i, List.of(otherLive)));
        }
        free[0] = asset();
        free[1] = fixture.goodBanner(OTHER_TENANT, FOREIGN_BRAND);
        banner(TENANT, BRAND, Map.of(270, List.of(free[0]), 220, List.of(live)));
        banner(OTHER_TENANT, FOREIGN_BRAND, Map.of(270, List.of(free[1]), 220, List.of(otherLive)));

        for (int pass = 0; pass < 3; pass++) {
            small.sweepOnce();
        }

        assertThat(status(free[0]))
                .as("the first tenant's free asset, behind two held back")
                .isEqualTo("DELETION_REQUESTED");
        assertThat(status(free[1]))
                .as("the second tenant's, which waited its turn")
                .isEqualTo("DELETION_REQUESTED");
        assertThat(java.util.Arrays.stream(held).map(this::status)).containsOnly("AVAILABLE");
    }

    // ============================================================ who else uses it

    @Test
    @DisplayName(
            "an asset a product, a brand logo, a share image, a help-centre icon or a draft still uses is kept, and let go when they stop")
    void everyModuleThatStoresAnAssetIdHoldsItBack() {
        UUID onAProduct = asset();
        UUID asLogo = asset();
        UUID asShareImage = asset();
        UUID inDraft = asset();
        UUID free = asset();
        UUID live = asset();
        UUID itemWithDraft = banner(Map.of(200, List.of(inDraft), 150, List.of(live)));
        banner(Map.of(200, List.of(onAProduct, asLogo, asShareImage, free), 150, List.of(live)));
        jdbc.sql("""
                INSERT INTO catalog.media_relations (tenant_id, brand_id, entity_type, entity_id, media_asset_id, role)
                VALUES (:t, :b, 'PRODUCT', :entity, :asset, 'PRIMARY')
                """)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("entity", UUID.randomUUID())
                .param("asset", onAProduct)
                .update();
        jdbc.sql(
                        "INSERT INTO tenant.brand_media (tenant_id, brand_id, role, media_asset_id) VALUES (:t, :b, 'LOGO', :a)")
                .param("t", TENANT)
                .param("b", BRAND)
                .param("a", asLogo)
                .update();
        UUID channel = fixture.channel(TENANT, ContentFixture.STOREFRONT, "WEB");
        jdbc.sql(
                        "INSERT INTO tenant.channel_presentation (tenant_id, channel_id, og_image_asset_id) VALUES (:t, :c, :a)")
                .param("t", TENANT)
                .param("c", channel)
                .param("a", asShareImage)
                .update();
        jdbc.sql("""
                INSERT INTO content.item_drafts (item_id, tenant_id, brand_id, document, updated_by)
                VALUES (:item, :t, :b, CAST(:doc AS jsonb), 'test')
                """)
                .param("item", itemWithDraft)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("doc", "{\"media\":{\"primaryAssetId\":\"" + inDraft + "\"}}")
                .update();

        var pass = sweeper.sweepOnce();

        assertThat(status(onAProduct)).as("a product still shows it").isEqualTo("AVAILABLE");
        assertThat(status(asLogo)).as("it is the brand's logo").isEqualTo("AVAILABLE");
        assertThat(status(asShareImage)).as("it is a channel's share image").isEqualTo("AVAILABLE");
        assertThat(status(inDraft)).as("a draft names it").isEqualTo("AVAILABLE");
        assertThat(status(free)).as("nothing uses it").isEqualTo("DELETION_REQUESTED");
        assertThat(pass.kept()).isEqualTo(4);
        assertThat(pass.released()).isEqualTo(1);

        // The product is deleted from the catalogue and the draft discarded: now they go.
        jdbc.sql("DELETE FROM catalog.media_relations WHERE media_asset_id = :a")
                .param("a", onAProduct)
                .update();
        jdbc.sql("DELETE FROM content.item_drafts WHERE item_id = :i")
                .param("i", itemWithDraft)
                .update();
        sweeper.sweepOnce();
        assertThat(status(onAProduct)).isEqualTo("DELETION_REQUESTED");
        assertThat(status(inDraft)).isEqualTo("DELETION_REQUESTED");
        assertThat(status(asLogo)).isEqualTo("AVAILABLE");
        assertThat(status(asShareImage)).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("the help-centre icon holds an asset back too")
    void aSocialLinkIconHoldsAnAssetBack() {
        UUID icon = asset();
        UUID live = asset();
        banner(Map.of(200, List.of(icon), 150, List.of(live)));
        jdbc.sql("""
                INSERT INTO support.social_links (id, tenant_id, brand_id, platform, url, media_asset_id)
                VALUES (:id, :t, :b, 'INSTAGRAM', 'https://instagram.com/x', :a)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("a", icon)
                .update();

        sweeper.sweepOnce();

        assertThat(status(icon)).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName(
            "every module that stores an asset id has registered a usage probe: a release with a probe missing would be a release that cannot see")
    void everyOwnerOfAnAssetReferenceHasAProbe() {
        assertThat(probes.stream().map(MediaUsageProbe::module).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("content", "catalog", "tenancy", "helpcenter", "reviews", "notifications");
    }

    // ========================================================= what cannot be released

    @Test
    @DisplayName(
            "the release port reaches only a brand's public asset in its own tenant: a private asset, a tenant-owned file and another tenant's id are all refused")
    void theReleasePortIsNarrow() {
        UUID creative = asset();
        UUID privateOne = fixture.image(TENANT, BRAND, 100, 100, "PRIVATE", "AVAILABLE", Map.of());
        UUID tenantOwned = asset();
        jdbc.sql("UPDATE media.assets SET owner_scope = 'TENANT', owner_id = :t WHERE asset_id = :a")
                .param("t", TENANT)
                .param("a", tenantOwned)
                .update();
        UUID foreign = fixture.image(OTHER_TENANT, FOREIGN_BRAND, 100, 100, "PUBLIC", "AVAILABLE", Map.of());

        assertThat(release.release(TENANT, privateOne, "x")).isEqualTo(MediaRelease.Outcome.NOT_RELEASABLE);
        assertThat(release.release(TENANT, tenantOwned, "x")).isEqualTo(MediaRelease.Outcome.NOT_RELEASABLE);
        assertThat(release.release(TENANT, foreign, "x")).isEqualTo(MediaRelease.Outcome.NOT_RELEASABLE);
        assertThat(release.release(TENANT, UUID.randomUUID(), "x")).isEqualTo(MediaRelease.Outcome.NOT_RELEASABLE);
        assertThat(List.of(status(privateOne), status(tenantOwned), status(foreign)))
                .containsOnly("AVAILABLE");

        assertThat(release.release(TENANT, creative, "x")).isEqualTo(MediaRelease.Outcome.RELEASED);
        assertThat(release.release(TENANT, creative, "x"))
                .as("already on its way out")
                .isEqualTo(MediaRelease.Outcome.NOT_RELEASABLE);
    }

    // ============================================================== after the release

    @Test
    @DisplayName(
            "republishing a revision whose media was released is refused by name, and one whose media is intact is not")
    void aReleasedRevisionCannotBeRepublished() {
        UUID gone = asset();
        UUID kept = asset();
        UUID live = asset();
        UUID item = banner(Map.of(200, List.of(gone), 150, List.of(kept), 10, List.of(live)));
        sweeper.sweepOnce();
        assertThat(status(gone)).isEqualTo("DELETION_REQUESTED");

        assertThatThrownBy(() -> authoring.cloneRevisionIntoDraft(TENANT, BRAND, "tester", item, 1, 1))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(
                                        Objects.toString(refusal.properties().get("errors")))
                                .contains("CONTENT_REVISION_MEDIA_RELEASED"));
        assertThat(authoring
                        .cloneRevisionIntoDraft(TENANT, BRAND, "tester", item, 2, 1)
                        .draft())
                .as("revision 2's picture still exists")
                .isNotNull();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIssuer {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    /** The clock the whole context runs on, which a test moves across the retention window. */
    static final class MovableClock extends Clock {
        private volatile Instant now = NOW;

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
