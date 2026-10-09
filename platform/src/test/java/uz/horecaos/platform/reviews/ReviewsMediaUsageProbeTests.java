package uz.horecaos.platform.reviews;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.ordering.OrderBoardFixtures;
import uz.horecaos.platform.reviews.infrastructure.persistence.ReviewsMediaUsageProbe;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The reviews module tells the media module which icons it still shows (ADR 0157, ADR 0159): a content
 * revision's pictures are released only when every module that stores an asset id says it is done with it,
 * and a feedback tag's icon is one of those ids.
 */
class ReviewsMediaUsageProbeTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-9000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-9000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9f10-9000-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9f10-9000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9f10-9000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f9f10-9000-7000-8000-0000000000c2");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private static JdbcClient jdbc;

    private ReviewsMediaUsageProbe probe;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        db = TestDatabase.migrated();
        jdbc = JdbcClient.create(db.dataSource());
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE TABLE reviews.order_reviews CASCADE").update();
        jdbc.sql("TRUNCATE TABLE reviews.feedback_tags CASCADE").update();
        OrderBoardFixtures fixtures = new OrderBoardFixtures(jdbc);
        fixtures.clean();
        fixtures.tenant(TENANT, "reviews-probe", BRAND, LOCATION);
        fixtures.tenant(OTHER_TENANT, "reviews-probe-other", OTHER_BRAND, OTHER_LOCATION);
        probe = new ReviewsMediaUsageProbe(jdbc);
    }

    @Test
    @DisplayName("an icon a tag names is in use, a retired tag's included, and one nobody names is not")
    void answersForTheIconsOfTheTenantsTags() {
        UUID used = asset(TENANT);
        UUID retiredOnly = asset(TENANT);
        UUID unused = asset(TENANT);
        tag(TENANT, "food_cold", used, "ACTIVE");
        tag(TENANT, "food_late", retiredOnly, "RETIRED");

        assertThat(probe.inUse(TENANT, List.of(used, retiredOnly, unused)))
                .containsExactlyInAnyOrder(used, retiredOnly);
    }

    @Test
    @DisplayName("another tenant's tags answer nothing for this tenant, and an empty question answers nothing")
    void isScopedToTheAskingTenant() {
        UUID icon = asset(OTHER_TENANT);
        tag(OTHER_TENANT, "food_cold", icon, "ACTIVE");

        assertThat(probe.inUse(TENANT, List.of(icon))).isEmpty();
        assertThat(probe.inUse(TENANT, List.of())).isEmpty();
        assertThat(probe.module()).isEqualTo("reviews");
    }

    private static UUID asset(UUID tenantId) {
        UUID assetId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO media.assets (asset_id, tenant_id, owner_scope, owner_id, bucket,
                    object_key, visibility, status, declared_content_type, declared_size_bytes,
                    verified_content_type, verified_size_bytes, verified_checksum_sha256)
                VALUES (:assetId, :tenantId, 'TENANT', :tenantId, 'horecaos-media',
                    :objectKey, 'PRIVATE', 'AVAILABLE', 'image/jpeg', 1024,
                    'image/jpeg', 1024, repeat('0', 64))
                """)
                .param("assetId", assetId)
                .param("tenantId", tenantId)
                .param("objectKey", tenantId + "/tenant/" + tenantId + "/" + assetId)
                .update();
        return assetId;
    }

    private static void tag(UUID tenantId, String code, UUID icon, String status) {
        jdbc.sql("""
                INSERT INTO reviews.feedback_tags
                    (id, tenant_id, code, category, sentiment, icon_asset_id, status, created_by_subject, updated_by_subject)
                VALUES (:id, :t, :code, 'FOOD', 'NEGATIVE', :icon, :status, 'test', 'test')
                """)
                .param("id", UUID.randomUUID())
                .param("t", tenantId)
                .param("code", code)
                .param("icon", icon)
                .param("status", status)
                .update();
    }
}
