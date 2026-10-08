package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import uz.horecaos.platform.customers.api.ConsentDirectory;
import uz.horecaos.platform.customers.api.ConsentRecorder;
import uz.horecaos.platform.customers.application.ConsentService;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.ConsentHistoryRow;
import uz.horecaos.platform.ordering.OrderBoardFixtures;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link ConsentRecorder#recordWithdrawal}, the one withdrawal the port records (ADR 0159, Decision 6):
 * a customer taking back, through their own storefront, a purpose they granted there.
 *
 * <p>The evidence store is append-only, so every assertion is about what is <em>added</em> and what is
 * refused without adding anything. A withdrawal by an import, a migration, a support agent or an API
 * client is a different act with different evidence and must not be possible through this port, and a
 * withdrawal of something never granted would write a decision that never happened.
 */
class ConsentWithdrawalTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-9000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f10-9000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9f10-9000-7000-8000-0000000000b2");
    private static final UUID LOCATION = UUID.fromString("018f9f10-9000-7000-8000-0000000000c1");
    private static final String PURPOSE = "review.public_display";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ConsentService consent;
    private MutableClock clock;
    private UUID account;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the consent tests");
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
        jdbc.sql("TRUNCATE TABLE customer.consent_decisions CASCADE").update();
        OrderBoardFixtures fixtures = new OrderBoardFixtures(jdbc);
        fixtures.clean();
        fixtures.tenant(TENANT, "consent-withdrawal", BRAND, LOCATION);
        account = OrderBoardFixtures.customerOf(TENANT);

        clock = new MutableClock(Instant.parse("2026-10-08T10:00:00Z"));
        consent = new ConsentService(new JdbcCustomerStore(jdbc), clock);
    }

    @Test
    @DisplayName("a withdrawal is a new row beside the grant, the grant stays, and the purpose is no longer permitted")
    void aWithdrawalIsAppendedAndTheGrantStays() {
        UUID grant = consent.recordGrant(TENANT, account, BRAND, PURPOSE, null, "1", "STOREFRONT", "review:1");
        clock.advance(Duration.ofMinutes(5));

        UUID withdrawal =
                consent.recordWithdrawal(TENANT, account, BRAND, PURPOSE, null, "1", "STOREFRONT", "review:1");

        assertThat(withdrawal).isNotEqualTo(grant);
        List<ConsentHistoryRow> history = consent.history(TENANT, account);
        assertThat(history)
                .extracting(ConsentHistoryRow::decision)
                .as("append-only: both decisions are on the record")
                .containsExactlyInAnyOrder("GRANTED", "WITHDRAWN");
        assertThat(history).allSatisfy(row -> assertThat(row.source()).isEqualTo("STOREFRONT"));
        assertThat(permitted()).isFalse();
        assertThat(consent.consentFor(TENANT, account, BRAND, PURPOSE, null)).hasValueSatisfying(current -> {
            assertThat(current.granted()).isFalse();
            assertThat(current.decidedAt())
                    .as("dated now, never backdated")
                    .isEqualTo(Instant.parse("2026-10-08T10:05:00Z"));
        });
    }

    @Test
    @DisplayName(
            "only the customer's own storefront can withdraw through the port: an import, a migration, an agent or an API client cannot")
    void anythingButTheStorefrontIsRefused() {
        consent.recordGrant(TENANT, account, BRAND, PURPOSE, null, "1", "STOREFRONT", null);

        for (String source : List.of("IMPORT", "MIGRATION", "SUPPORT_AGENT", "API", "storefront", "")) {
            assertThatThrownBy(() -> consent.recordWithdrawal(TENANT, account, BRAND, PURPOSE, null, "1", source, null))
                    .as(source)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(consent.history(TENANT, account)).hasSize(1);
        assertThat(permitted()).isTrue();
    }

    @Test
    @DisplayName("nothing is withdrawn that was never granted, and nothing is withdrawn twice")
    void onlyAGrantedPurposeCanBeWithdrawn() {
        assertThatThrownBy(
                        () -> consent.recordWithdrawal(TENANT, account, BRAND, PURPOSE, null, "1", "STOREFRONT", null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(consent.history(TENANT, account)).isEmpty();

        consent.recordGrant(TENANT, account, BRAND, PURPOSE, null, "1", "STOREFRONT", null);
        consent.recordWithdrawal(TENANT, account, BRAND, PURPOSE, null, "1", "STOREFRONT", null);

        assertThatThrownBy(
                        () -> consent.recordWithdrawal(TENANT, account, BRAND, PURPOSE, null, "1", "STOREFRONT", null))
                .as("already withdrawn")
                .isInstanceOf(IllegalStateException.class);
        assertThat(consent.history(TENANT, account)).hasSize(2);
    }

    @Test
    @DisplayName("a purpose granted at one brand is not withdrawn by a withdrawal at another brand or at the tenant")
    void aWithdrawalIsScopedLikeTheGrant() {
        consent.recordGrant(TENANT, account, BRAND, PURPOSE, null, "1", "STOREFRONT", null);

        assertThatThrownBy(() ->
                        consent.recordWithdrawal(TENANT, account, OTHER_BRAND, PURPOSE, null, "1", "STOREFRONT", null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () -> consent.recordWithdrawal(TENANT, account, null, PURPOSE, null, "1", "STOREFRONT", null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(permitted()).isTrue();
    }

    @Test
    @DisplayName("a customer who withdrew may grant again, and the latest decision wins")
    void aGrantAfterAWithdrawalIsTheCurrentDecision() {
        consent.recordGrant(TENANT, account, BRAND, PURPOSE, null, "1", "STOREFRONT", null);
        clock.advance(Duration.ofMinutes(1));
        consent.recordWithdrawal(TENANT, account, BRAND, PURPOSE, null, "1", "STOREFRONT", null);
        clock.advance(Duration.ofMinutes(1));

        consent.recordGrant(TENANT, account, BRAND, PURPOSE, null, "1", "STOREFRONT", null);

        assertThat(permitted()).isTrue();
        assertThat(consent.history(TENANT, account)).hasSize(3);
    }

    /** Whether the purpose is permitted now, asked the way the port asks it: no channel. */
    private boolean permitted() {
        return consent.consentFor(TENANT, account, BRAND, PURPOSE, null)
                .map(ConsentDirectory.ConsentState::granted)
                .orElse(false);
    }

    /** The test's clock: every duration asserted advances it. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
