package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.catalog.api.ItemDisplayLookup;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcInventoryStopDigestStore;
import uz.horecaos.platform.support.RecordingOperationsAlertPort;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link InventoryStopDigestSweeper} — gap map row 2.5c. Proves the three
 * things the row's own text names: a burst of toggles coalesces into one
 * alert rather than one per item, the back-in-stock direction is announced
 * (never raised at all before this wave), and a claimed batch is marked
 * consumed so the next tick does not repeat it.
 */
class InventoryStopDigestSweeperTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcInventoryStopDigestStore digestQueue;
    private RecordingOperationsAlertPort alerts;
    private InventoryStopDigestSweeper sweeper;

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

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE notifications.inventory_stop_digest_entries CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenancy();

        digestQueue = new JdbcInventoryStopDigestStore(jdbc);
        alerts = new RecordingOperationsAlertPort();
        sweeper = new InventoryStopDigestSweeper(
                digestQueue, named(), alerts, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(30));
    }

    @Test
    void aBurstOfStopsCoalescesIntoOneAlert() {
        for (int i = 0; i < 5; i++) {
            digestQueue.enqueue(
                    TENANT, BRAND, LOCATION, UUID.randomUUID(), false, "SOLD_OUT", null, NOW.minusSeconds(30));
        }

        sweeper.flushDue();

        assertThat(alerts.calls()).hasSize(1);
        RecordingOperationsAlertPort.Call call = alerts.calls().get(0);
        assertThat(call.eventClass()).isEqualTo(InventoryStopDigestSweeper.DIGEST_EVENT_CLASS);
        assertThat(call.variables()).containsEntry("stoppedCount", "5").containsEntry("restoredCount", "0");
    }

    @Test
    void theBackInStockDirectionIsAnnounced() {
        UUID variant = UUID.randomUUID();
        digestQueue.enqueue(TENANT, BRAND, LOCATION, variant, true, "RESTOCKED", null, NOW.minusSeconds(10));

        sweeper.flushDue();

        assertThat(alerts.calls()).singleElement().satisfies(call -> {
            assertThat(call.variables()).containsEntry("restoredCount", "1");
            assertThat(call.variables().get("restoredItems")).contains("Lagman");
            assertThat(call.variables()).containsEntry("stoppedCount", "0");
        });
    }

    @Test
    void bothDirectionsInOneWindowAreGroupedSeparately() {
        digestQueue.enqueue(TENANT, BRAND, LOCATION, UUID.randomUUID(), false, "SOLD_OUT", null, NOW.minusSeconds(40));
        digestQueue.enqueue(TENANT, BRAND, LOCATION, UUID.randomUUID(), true, "RESTOCKED", null, NOW.minusSeconds(20));

        sweeper.flushDue();

        assertThat(alerts.calls()).singleElement().satisfies(call -> {
            assertThat(call.variables()).containsEntry("stoppedCount", "1");
            assertThat(call.variables()).containsEntry("restoredCount", "1");
        });
    }

    @Test
    void aClaimedBatchIsMarkedConsumedAndNeverRepeated() {
        digestQueue.enqueue(TENANT, BRAND, LOCATION, UUID.randomUUID(), false, "SOLD_OUT", null, NOW.minusSeconds(30));

        sweeper.flushDue();
        assertThat(digestQueue.pendingFor(TENANT, LOCATION)).isEmpty();

        // A second tick with nothing new queued raises nothing more.
        sweeper.flushDue();
        assertThat(alerts.calls()).hasSize(1);
    }

    @Test
    void aQuietLocationRaisesNothing() {
        sweeper.flushDue();

        assertThat(alerts.calls()).isEmpty();
    }

    private static ItemDisplayLookup named() {
        return new ItemDisplayLookup() {
            @Override
            public Optional<String> displayName(UUID tenantId, UUID variantId) {
                return Optional.of("Lagman");
            }

            @Override
            public Map<UUID, String> displayNames(UUID tenantId, Set<UUID> variantIds) {
                return variantIds.stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> "Lagman"));
            }
        };
    }

    private void insertTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'inventory-digest-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'inventory-digest-brand', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'inventory-digest-location', 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }
}
