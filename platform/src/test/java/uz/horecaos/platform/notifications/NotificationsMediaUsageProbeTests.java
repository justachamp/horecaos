package uz.horecaos.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.NotificationsMediaUsageProbe;
import uz.horecaos.platform.ordering.OrderBoardFixtures;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The notifications module tells the media module which covers it still shows (ADR 0157, ADR 0182): a
 * content revision's pictures are released only when every module that stores an asset id says it is done
 * with it, and a push wording's cover is one of those ids.
 */
class NotificationsMediaUsageProbeTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-a000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-a000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9f10-a000-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9f10-a000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9f10-a000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f9f10-a000-7000-8000-0000000000c2");
    private static final Instant NOW = Instant.parse("2026-10-23T09:00:00Z");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private static JdbcClient jdbc;

    private NotificationsMediaUsageProbe probe;
    private JdbcTemplateStore templates;

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
        jdbc.sql("TRUNCATE TABLE notifications.template_versions, notifications.templates CASCADE")
                .update();
        OrderBoardFixtures fixtures = new OrderBoardFixtures(jdbc);
        fixtures.clean();
        fixtures.tenant(TENANT, "notify-probe", BRAND, LOCATION);
        fixtures.tenant(OTHER_TENANT, "notify-probe-other", OTHER_BRAND, OTHER_LOCATION);
        probe = new NotificationsMediaUsageProbe(jdbc);
        templates = new JdbcTemplateStore(jdbc);
    }

    @Test
    @DisplayName("a cover a push wording names is in use, a draft's included, and one nobody names is not")
    void answersForTheCoversOfTheTenantsWordings() {
        UUID used = asset(TENANT);
        UUID unused = asset(TENANT);
        wording(TENANT, "PROMO", used);

        assertThat(probe.inUse(TENANT, List.of(used, unused))).containsExactly(used);
    }

    @Test
    @DisplayName("another tenant's wordings answer nothing for this tenant, and an empty question answers nothing")
    void isScopedToTheAskingTenant() {
        UUID cover = asset(OTHER_TENANT);
        wording(OTHER_TENANT, "PROMO", cover);

        assertThat(probe.inUse(TENANT, List.of(cover))).isEmpty();
        assertThat(probe.inUse(TENANT, List.of())).isEmpty();
        assertThat(probe.module()).isEqualTo("notifications");
    }

    private void wording(UUID tenantId, String key, UUID cover) {
        UUID templateId = UUID.randomUUID();
        templates.insertTemplate(
                templateId, tenantId, null, key, "MARKETING", "PUSH", "MARKETING_PROMOTIONS", null, null, NOW);
        templates.insertVersion(
                UUID.randomUUID(),
                tenantId,
                templateId,
                1,
                "ru",
                "Aksiya",
                "Chegirma",
                "{}",
                "hash-" + key,
                false,
                false,
                cover,
                "/menu",
                NOW);
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
}
