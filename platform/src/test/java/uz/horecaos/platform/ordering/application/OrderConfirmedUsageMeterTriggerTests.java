package uz.horecaos.platform.ordering.application;

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
import uz.horecaos.platform.ordering.api.OrderCancelled;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * {@link OrderConfirmedUsageMeterTrigger} is the caller {@link UsageMeter} was
 * missing for {@code orders.monthly_included} (ADR 0021): a real {@link
 * OrderConfirmed} fact, shaped the way {@code OrderStateService} actually
 * publishes it, must turn into exactly one usage movement — never zero, and
 * never two for a redelivered or duplicated publish.
 */
class OrderConfirmedUsageMeterTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-07T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private OrderConfirmedUsageMeterTrigger trigger;

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
                VALUES (:id, 'order-trigger-tenant', 'Order trigger tenant', 'Order trigger tenant', 'UZS',
                    'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();

        UsageMeter usage =
                CommercialDefaults.wire(jdbc, Clock.fixed(NOW, ZoneOffset.UTC)).usage();
        trigger = new OrderConfirmedUsageMeterTrigger(usage);
    }

    @Test
    @DisplayName("a confirmed order meters one against orders.monthly_included")
    void aConfirmedOrderMetersOne() {
        trigger.onOrderingEvent(orderConfirmed(UUID.randomUUID()));

        assertThat(consumed()).isEqualTo(1);
    }

    @Test
    @DisplayName("a redelivered confirmation does not meter twice")
    void aRedeliveredConfirmationDoesNotDoubleCount() {
        OrderConfirmed confirmed = orderConfirmed(UUID.randomUUID());

        trigger.onOrderingEvent(confirmed);
        trigger.onOrderingEvent(confirmed);

        assertThat(consumed())
                .as("OrderStateService's own conditional UPDATE means this fires once per order, "
                        + "but the meter must not depend on that alone")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("two distinct confirmed orders meter two")
    void twoDistinctOrdersMeterTwo() {
        trigger.onOrderingEvent(orderConfirmed(UUID.randomUUID()));
        trigger.onOrderingEvent(orderConfirmed(UUID.randomUUID()));

        assertThat(consumed()).isEqualTo(2);
    }

    @Test
    @DisplayName("an unrelated ordering fact is ignored")
    void anUnrelatedFactIsIgnored() {
        trigger.onOrderingEvent(new OrderCancelled(
                UUID.randomUUID(),
                new TenantId(TENANT),
                UUID.randomUUID(),
                NOW,
                BRAND,
                LOCATION,
                "CUSTOMER",
                "CUSTOMER_REQUEST",
                "CONFIRMED",
                "CANCELLED",
                1));

        assertThat(consumed()).isZero();
    }

    private OrderConfirmed orderConfirmed(UUID orderId) {
        return new OrderConfirmed(
                UUID.randomUUID(),
                new TenantId(TENANT),
                orderId,
                NOW,
                BRAND,
                LOCATION,
                "AUTO",
                null,
                NOW,
                "UZS",
                45_000L,
                "CONFIRMED",
                1);
    }

    private long consumed() {
        return jdbc.sql("""
                SELECT COALESCE(SUM(consumed_quantity), 0) FROM commercial.usage_aggregates
                 WHERE tenant_id = :tenantId AND entitlement_key = 'orders.monthly_included'
                   AND period_key = '2026-09'
                """).param("tenantId", TENANT).query(Long.class).single();
    }
}
