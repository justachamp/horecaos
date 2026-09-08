package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.commercial.api.UsageMeter;
import uz.horecaos.platform.media.api.MediaAssetAvailable;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link MediaUsageMeterTrigger} is the caller {@link UsageMeter} was missing for
 * {@code media.storage_bytes_included} (ADR 0021): a real {@link
 * MediaAssetAvailable} fact, shaped the way {@code MediaAssetService} actually
 * publishes it, must meter the verified byte count exactly once.
 */
class MediaUsageMeterTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-07T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MediaUsageMeterTrigger trigger;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this integration test");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE commercial.usage_aggregates, commercial.usage_events")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'media-trigger-tenant', 'Media trigger tenant', 'Media trigger tenant', 'UZS',
                    'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();

        UsageMeter usage =
                CommercialDefaults.wire(jdbc, Clock.fixed(NOW, ZoneOffset.UTC)).usage();
        trigger = new MediaUsageMeterTrigger(usage);
    }

    @Test
    @DisplayName("an available asset meters its verified byte count")
    void anAvailableAssetMetersItsVerifiedByteCount() {
        trigger.onMediaAssetAvailable(mediaAssetAvailable(UUID.randomUUID(), 483_920L));

        assertThat(consumed()).isEqualTo(483_920L);
    }

    @Test
    @DisplayName("a redelivered fact does not meter twice")
    void aRedeliveredFactDoesNotDoubleCount() {
        MediaAssetAvailable event = mediaAssetAvailable(UUID.randomUUID(), 200_000L);

        trigger.onMediaAssetAvailable(event);
        trigger.onMediaAssetAvailable(event);

        assertThat(consumed())
                .as("at-least-once delivery is the contract every listener works under")
                .isEqualTo(200_000L);
    }

    @Test
    @DisplayName("two distinct assets meter their sum")
    void twoDistinctAssetsMeterTheirSum() {
        trigger.onMediaAssetAvailable(mediaAssetAvailable(UUID.randomUUID(), 100_000L));
        trigger.onMediaAssetAvailable(mediaAssetAvailable(UUID.randomUUID(), 250_000L));

        assertThat(consumed()).isEqualTo(350_000L);
    }

    private MediaAssetAvailable mediaAssetAvailable(UUID assetId, long sizeBytes) {
        return new MediaAssetAvailable(
                UUID.randomUUID(),
                TENANT,
                new MediaAssetId(assetId),
                NOW,
                "BRAND",
                BRAND,
                "PUBLIC",
                "image/jpeg",
                sizeBytes,
                640,
                480);
    }

    private long consumed() {
        return jdbc.sql("""
                SELECT COALESCE(SUM(consumed_quantity), 0) FROM commercial.usage_aggregates
                 WHERE tenant_id = :tenantId AND entitlement_key = 'media.storage_bytes_included'
                   AND period_key = 'LIFETIME'
                """).param("tenantId", TENANT).query(Long.class).single();
    }
}
