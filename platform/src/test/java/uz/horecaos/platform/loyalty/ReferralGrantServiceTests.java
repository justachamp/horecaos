package uz.horecaos.platform.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import uz.horecaos.platform.loyalty.api.ReferralGrantPort.GrantResult;
import uz.horecaos.platform.loyalty.api.ReferralGrantPort.ReferralGrantCommand;
import uz.horecaos.platform.loyalty.application.ReferralGrantService;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.AccountRow;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * The one caller a new ADR's referral program has into the loyalty ledger
 * (ADR 0046).
 *
 * <p>Against a real PostgreSQL for the same reason every other loyalty
 * ledger test is: whether a replayed grant credits once is a property of the
 * conditional insert in {@code JdbcLoyaltyStore.appendEntry}, and asserting
 * the resulting balance — not merely that <em>an</em> entry exists — is what
 * would actually catch a double-credit, per the 2026-08-26 audit's lesson
 * that the adjacent quantity can stay green while the one that matters
 * breaks.
 */
class ReferralGrantServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID REDEMPTION = UUID.randomUUID();

    private static final Instant NOW = Instant.parse("2026-09-05T07:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcLoyaltyStore store;
    private ReferralGrantService grants;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for loyalty tests");
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
        jdbc.sql("TRUNCATE TABLE loyalty.entries, loyalty.lots, loyalty.accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        store = new JdbcLoyaltyStore(jdbc);
        grants = new ReferralGrantService(store);

        seedTenancy();
    }

    @Test
    @DisplayName("a grant credits the account and opens a lot with the requested lifetime")
    void grantCreditsTheAccountAndOpensALot() {
        GrantResult result = grants.grant(new ReferralGrantCommand(
                        TENANT, BRAND, CUSTOMER, 10_000, "UZS", "REFERRAL_REFERRER_REWARD", REDEMPTION, 90, NOW))
                .orElseThrow();

        assertThat(result.balanceAfterMinor()).isEqualTo(10_000);

        AccountRow account = store.findAccount(TENANT, BRAND, CUSTOMER).orElseThrow();
        assertThat(account.balanceMinor())
                .as("the account's own balance column, not merely the presence of an entry")
                .isEqualTo(10_000);
        assertThat(store.entries(TENANT, account.id(), 10)).hasSize(1);

        List<JdbcLoyaltyStore.LotRow> lots = store.openLots(TENANT, account.id());
        assertThat(lots).hasSize(1);
        assertThat(lots.get(0).expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
    }

    @Test
    @DisplayName("a replayed grant under the same reason and reference credits nothing a second time")
    void replayedGrantDoesNotDoubleCredit() {
        ReferralGrantCommand command = new ReferralGrantCommand(
                TENANT, BRAND, CUSTOMER, 10_000, "UZS", "REFERRAL_REFERRER_REWARD", REDEMPTION, 90, NOW);

        assertThat(grants.grant(command)).isPresent();
        assertThat(grants.grant(command))
                .as("the second delivery of the same qualifying event is a no-op, not a second credit")
                .isEmpty();

        AccountRow account = store.findAccount(TENANT, BRAND, CUSTOMER).orElseThrow();
        assertThat(account.balanceMinor())
                .as("balance == SUM(entries), asserted directly rather than assuming it from one call's return value")
                .isEqualTo(10_000);
        assertThat(store.entries(TENANT, account.id(), 10)).hasSize(1);
    }

    @Test
    @DisplayName("the referrer's and referee's grants for one redemption never collide with each other")
    void differentReasonCodesForTheSameRedemptionAreIndependent() {
        grants.grant(new ReferralGrantCommand(
                TENANT, BRAND, CUSTOMER, 10_000, "UZS", "REFERRAL_REFERRER_REWARD", REDEMPTION, 90, NOW));
        UUID otherCustomer = insertCustomer();
        grants.grant(new ReferralGrantCommand(
                TENANT, BRAND, otherCustomer, 5_000, "UZS", "REFERRAL_REFEREE_REWARD", REDEMPTION, 90, NOW));

        assertThat(store.findAccount(TENANT, BRAND, CUSTOMER).orElseThrow().balanceMinor())
                .isEqualTo(10_000);
        assertThat(store.findAccount(TENANT, BRAND, otherCustomer).orElseThrow().balanceMinor())
                .isEqualTo(5_000);
    }

    @Test
    @DisplayName("a grant never debits — zero or negative is refused before anything moves")
    void aGrantRefusesANonPositiveAmount() {
        assertThatThrownBy(() -> grants.grant(new ReferralGrantCommand(
                        TENANT, BRAND, CUSTOMER, 0, "UZS", "REFERRAL_REFERRER_REWARD", REDEMPTION, 90, NOW)))
                .isInstanceOf(ApiException.class);
        assertThat(store.findAccount(TENANT, BRAND, CUSTOMER)).isEmpty();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'referral-grant-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'MAIN', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        insertCustomerWithId(CUSTOMER);
    }

    private UUID insertCustomer() {
        UUID id = UUID.randomUUID();
        insertCustomerWithId(id);
        return id;
    }

    private void insertCustomerWithId(UUID id) {
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status,
                    identity_policy_version, version)
                VALUES (:id, :tenantId, 'ACTIVE', 1, 1)
                """).param("id", id).param("tenantId", TENANT).update();
    }
}
