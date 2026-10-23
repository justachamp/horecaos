package uz.horecaos.platform.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcReceiptPaymentFacts;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * What a receipt has to add up to, read through the real {@link JdbcReceiptPaymentFacts} (ADR 0166, ADR 0046).
 *
 * <p>{@code ReceiptLineBuilderTests} and {@code PartnerFiscalizationBridgeTests} state the redeemed points in one
 * line through a double, which is the right way to test the arithmetic and the wrong way to test that the figure is
 * what the database holds at the moment the builder asks. This class is that: the settlement is written the way
 * {@code OrderSettlementService.plan} writes it, with the points tender in each status it passes through, and the
 * facts are read back.
 *
 * <p>The moment that matters is the checkout. Payme's lines are built when the payment is first presented, before
 * the money arrives, and at that moment a balance tender is {@code RESERVED}, never yet {@code SETTLED}
 * ({@code OrderSettlementService.plan} reserves it and {@code settleWhenMoneyArrives} settles it on capture). A
 * redemption that only counted {@code SETTLED} tenders answered zero there, the lines then totalled the order and
 * not the money leg, and the checkout was refused as {@code RECEIPT_NOT_BUILDABLE}.
 *
 * <p>Rows are seeded through a connection with {@code session_replication_role = replica}, which switches foreign
 * keys off for the seeding only (an order, a cart and a quote would be a page of fixture for a question about two
 * tables); the CHECK constraints of the migrations still apply to every row.
 */
class JdbcReceiptPaymentFactsTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-2000-7000-8000-0000000d1001");
    private static final UUID POINTS_METHOD = UUID.fromString("018f6f4e-2000-7000-8000-0000000d1002");
    private static final UUID CASH_METHOD = UUID.fromString("018f6f4e-2000-7000-8000-0000000d1003");

    private static TestDatabase.Handle db;
    private static SingleConnectionDataSource seedConnection;

    private JdbcClient seed;
    private JdbcReceiptPaymentFacts facts;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for payments tests");
        db = TestDatabase.migrated();
        seedConnection = new SingleConnectionDataSource(db.jdbcUrl(), db.username(), db.password(), true);
        JdbcClient.create(seedConnection)
                .sql("SET session_replication_role = replica")
                .update();
    }

    @AfterAll
    static void stopDatabase() {
        if (seedConnection != null) {
            seedConnection.destroy();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        seed = JdbcClient.create(seedConnection);
        seed.sql("TRUNCATE TABLE payments.tenders, payments.order_settlements, payments.payment_methods CASCADE")
                .update();
        seed.sql("""
                INSERT INTO payments.payment_methods (
                    id, tenant_id, code, display_name, responsibility, settles_from_balance, status, version)
                VALUES (:id, :t, 'POINTS', 'Points', 'OPERATOR', true, 'ACTIVE', 1)
                """).param("id", POINTS_METHOD).param("t", TENANT).update();
        seed.sql("""
                INSERT INTO payments.payment_methods (
                    id, tenant_id, code, display_name, responsibility, settles_from_balance, status, version)
                VALUES (:id, :t, 'CASH', 'Cash', 'OPERATOR', false, 'ACTIVE', 1)
                """).param("id", CASH_METHOD).param("t", TENANT).update();

        JdbcClient reader = JdbcClient.create(db.dataSource());
        facts = new JdbcReceiptPaymentFacts(new JdbcPaymentIntentStore(reader), new JdbcSettlementStore(reader));
    }

    @Test
    @DisplayName("points held for the order and not yet settled are the redemption a checkout receipt carries")
    void aReservedPointsTenderIsTheRedemptionAtCheckout() {
        UUID order = settlementWithPoints("RESERVED", 12_000L, 94_000L);

        assertThat(facts.pointsRedeemedMinor(TENANT, order))
                .as("at presentation the points are reserved, the money leg is still planned and the "
                        + "intent is for 82 000: a figure of zero here makes the lines add up to 94 000")
                .isEqualTo(12_000L);
    }

    @Test
    @DisplayName("points that settled are the redemption as well")
    void aSettledPointsTenderIsTheRedemption() {
        UUID order = settlementWithPoints("SETTLED", 12_000L, 94_000L);

        assertThat(facts.pointsRedeemedMinor(TENANT, order)).isEqualTo(12_000L);
    }

    @Test
    @DisplayName("points that went back to the customer are no redemption, whatever the reason")
    void pointsGivenBackAreNotARedemption() {
        for (String status : new String[] {"RELEASED", "FAILED", "REVERSED"}) {
            UUID order = settlementWithPoints(status, 12_000L, 94_000L);

            assertThat(facts.pointsRedeemedMinor(TENANT, order))
                    .as("a %s points tender", status)
                    .isZero();
        }
    }

    @Test
    @DisplayName("a points tender only planned (no hold taken yet) is not a redemption")
    void aPlannedPointsTenderIsNotYetARedemption() {
        UUID order = settlementWithPoints("PLANNED", 12_000L, 94_000L);

        assertThat(facts.pointsRedeemedMinor(TENANT, order)).isZero();
    }

    @Test
    @DisplayName("an order paid with money alone, and an order with no settlement, redeem nothing")
    void noPointsTenderMeansNothingRedeemed() {
        UUID moneyOnly = UUID.randomUUID();
        UUID settlementId = settlement(moneyOnly, 94_000L);
        tender(settlementId, 1, CASH_METHOD, false, 94_000L, "PLANNED");

        assertThat(facts.pointsRedeemedMinor(TENANT, moneyOnly)).isZero();
        assertThat(facts.pointsRedeemedMinor(TENANT, UUID.randomUUID())).isZero();
    }

    /** A points tender in the given status beside the money leg that is still planned: the shape of a checkout. */
    private UUID settlementWithPoints(String pointsStatus, long pointsMinor, long totalMinor) {
        UUID order = UUID.randomUUID();
        UUID settlementId = settlement(order, totalMinor);
        tender(settlementId, 1, POINTS_METHOD, true, pointsMinor, pointsStatus);
        tender(settlementId, 2, CASH_METHOD, false, totalMinor - pointsMinor, "PLANNED");
        return order;
    }

    private UUID settlement(UUID orderId, long totalMinor) {
        UUID id = UUID.randomUUID();
        seed.sql("""
                INSERT INTO payments.order_settlements (
                    id, tenant_id, order_id, currency, total_due_minor, settled_minor, status, version)
                VALUES (:id, :t, :order, 'UZS', :total, 0, 'PLANNED', 1)
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("order", orderId)
                .param("total", totalMinor)
                .update();
        return id;
    }

    private void tender(
            UUID settlementId, int sequence, UUID methodId, boolean fromBalance, long amountMinor, String status) {
        // ck_tender_settled_at_pairs_with_status: settled_at is set exactly when the tender is SETTLED or REVERSED.
        boolean stamped = "SETTLED".equals(status) || "REVERSED".equals(status);
        seed.sql("""
                INSERT INTO payments.tenders (
                    id, tenant_id, settlement_id, sequence, payment_method_id, settles_from_balance,
                    amount_minor, currency, status, settled_at, idempotency_key, version)
                VALUES (
                    gen_random_uuid(), :t, :settlement, :sequence, :method, :fromBalance,
                    :amount, 'UZS', :status, CASE WHEN :stamped THEN now() ELSE NULL END, :key, 1)
                """)
                .param("t", TENANT)
                .param("settlement", settlementId)
                .param("sequence", sequence)
                .param("method", methodId)
                .param("fromBalance", fromBalance)
                .param("amount", amountMinor)
                .param("status", status)
                .param("stamped", stamped)
                .param("key", "tender-" + settlementId + "-" + sequence)
                .update();
    }
}
