package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.inventory.api.ItemAvailabilityChanged;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcInventoryStopDigestStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcInventoryStopDigestStore.PendingEntry;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link InventoryOperationsAlertTrigger} — wave P16: it now only enqueues to
 * {@link JdbcInventoryStopDigestStore}, on both directions of the toggle
 * (before this wave, {@code available -> true} raised nothing at all and the
 * other direction fanned out immediately, one message per item). {@link
 * InventoryStopDigestSweeperTests} covers what actually turns a queued batch
 * into one grouped alert.
 */
class InventoryOperationsAlertTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID VARIANT = UUID.randomUUID();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private InventoryOperationsAlertTrigger trigger;
    private JdbcInventoryStopDigestStore digestQueue;

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
        trigger = new InventoryOperationsAlertTrigger(digestQueue);
    }

    @Test
    void goingUnavailableIsQueued() {
        trigger.onAvailabilityChanged(new ItemAvailabilityChanged(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, VARIANT, false, "SOLD_OUT", Instant.now()));

        List<PendingEntry> pending = digestQueue.pendingFor(TENANT, LOCATION);
        assertThat(pending).singleElement().satisfies(entry -> {
            assertThat(entry.variantId()).isEqualTo(VARIANT);
            assertThat(entry.available()).isFalse();
            assertThat(entry.reasonCode()).isEqualTo("SOLD_OUT");
        });
    }

    @Test
    void comingBackAvailableIsAlsoQueued() {
        // The behaviour this wave adds: before P16, this direction raised
        // nothing at all, so a branch was never told when a dish came back.
        trigger.onAvailabilityChanged(new ItemAvailabilityChanged(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, VARIANT, true, "RESTOCKED", Instant.now()));

        List<PendingEntry> pending = digestQueue.pendingFor(TENANT, LOCATION);
        assertThat(pending).singleElement().satisfies(entry -> {
            assertThat(entry.available()).isTrue();
            assertThat(entry.reasonCode()).isEqualTo("RESTOCKED");
        });
    }

    @Test
    void eachToggleIsItsOwnQueueEntryEvenForTheSameVariant() {
        trigger.onAvailabilityChanged(new ItemAvailabilityChanged(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, VARIANT, false, "SOLD_OUT", Instant.now()));
        trigger.onAvailabilityChanged(new ItemAvailabilityChanged(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, VARIANT, true, "RESTOCKED", Instant.now()));

        assertThat(digestQueue.pendingFor(TENANT, LOCATION)).hasSize(2);
    }

    private void insertTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'inventory-alert-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'inventory-alert-brand', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'inventory-alert-location', 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }
}
