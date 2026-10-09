package uz.horecaos.platform.payments.settlement;

import static org.assertj.core.api.Assertions.assertThat;

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
import uz.horecaos.platform.reviews.spi.CompensationDirectory.CompensationSummary;
import uz.horecaos.platform.support.TestDatabase;

/**
 * An ADR 0048 order remedy, described to a service-recovery case (ADR 0159, Decision 9): its order and
 * the amount and currency the case copies at the moment of attaching. A read of one row and nothing more.
 *
 * <p>The remedy of another tenant is absent, not forbidden: the case must not be able to tell a remedy
 * that is somebody else's from one that was never recorded.
 */
class OrderRemedyCompensationDirectoryTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-6000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9f10-6000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018f9f10-6000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9f10-6000-7000-8000-0000000000b2");
    private static final UUID LOCATION = UUID.fromString("018f9f10-6000-7000-8000-0000000000c1");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f9f10-6000-7000-8000-0000000000c2");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private OrderBoardFixtures fixtures;
    private OrderRemedyCompensationDirectory directory;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the remedy directory tests");
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
        jdbc = JdbcClient.create(db.dataSource());
        fixtures = new OrderBoardFixtures(jdbc);
        jdbc.sql("TRUNCATE TABLE payments.order_remedies CASCADE").update();
        fixtures.clean();
        fixtures.tenant(TENANT, "remedy-directory", BRAND, LOCATION);
        fixtures.tenant(OTHER_TENANT, "remedy-directory-other", OTHER_BRAND, OTHER_LOCATION);
        directory = new OrderRemedyCompensationDirectory(new JdbcRemedyStore(jdbc));
    }

    private UUID order(UUID tenant, UUID brand, UUID location, String seed) {
        return fixtures.insertOrder(
                OrderBoardFixtures.order(seed).at(tenant, brand, location).status("COMPLETED"));
    }

    private UUID remedy(UUID tenant, UUID brand, UUID orderId, long amountMinor) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.order_remedies (id, tenant_id, brand_id, order_id, remedy_type, reason_code, reason,
                    currency, amount_minor, attested_money_minor, platform_settled_minor, settlement_basis,
                    execution_channel, executed_by, executed_at, recorded_by, recorded_at, idempotency_key, version)
                VALUES (:id, :t, :b, :o, 'ORDER_REFUND', 'LATE_DELIVERY', 'cold soup', 'UZS', :amount, :amount, 0,
                    'OPERATOR_ATTESTED', 'CASH_DRAWER', 'manager-1', now(), 'manager-1', now(), :key, 1)
                """)
                .param("id", id)
                .param("t", tenant)
                .param("b", brand)
                .param("o", orderId)
                .param("amount", amountMinor)
                .param("key", "remedy-" + id)
                .update();
        return id;
    }

    @Test
    @DisplayName("it names itself ORDER_REMEDY, the kind a link to it carries")
    void itsKindIsOrderRemedy() {
        assertThat(directory.kind()).isEqualTo("ORDER_REMEDY");
    }

    @Test
    @DisplayName("it describes a remedy by its order, its amount in minor units and its currency")
    void itDescribesARemedy() {
        UUID order = order(TENANT, BRAND, LOCATION, "R-1");
        UUID remedy = remedy(TENANT, BRAND, order, 45_000L);

        assertThat(directory.summary(TENANT, remedy))
                .hasValue(new CompensationSummary("ORDER_REMEDY", remedy, order, 45_000L, "UZS"));
    }

    @Test
    @DisplayName("a remedy of another tenant, and one never recorded, are the same absence")
    void anotherTenantsRemedyIsAbsent() {
        UUID theirOrder = order(OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, "R-2");
        UUID theirs = remedy(OTHER_TENANT, OTHER_BRAND, theirOrder, 10_000L);

        assertThat(directory.summary(TENANT, theirs)).isEmpty();
        assertThat(directory.summary(TENANT, UUID.randomUUID())).isEmpty();
        assertThat(directory.summary(OTHER_TENANT, theirs)).isPresent();
    }
}
