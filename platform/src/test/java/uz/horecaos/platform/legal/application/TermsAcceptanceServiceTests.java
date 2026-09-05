package uz.horecaos.platform.legal.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.customers.application.ConsentService;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.legal.application.TermsAcceptanceService.AcceptanceRecord;
import uz.horecaos.platform.legal.application.TermsAcceptanceService.AcceptanceStatus;
import uz.horecaos.platform.legal.domain.EffectiveTerms;
import uz.horecaos.platform.legal.domain.PlatformDefaultTerms;
import uz.horecaos.platform.legal.infrastructure.persistence.JdbcTermsStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0067: what a storefront customer is shown and what accepting it means.
 *
 * <p>{@link #publishingANewVersionDoesNotRetroactivelyChangeWhatWasAlreadyAccepted()}
 * is the wave's own required proof: a customer's acceptance is evidence of
 * what they read at the time, and nothing published afterwards may alter
 * what that evidence says.
 */
class TermsAcceptanceServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120d01");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120d02");
    private static final UUID ACCOUNT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120d03");
    private static final ActorRef OWNER = ActorRef.user("owner-subject", "Tenant Owner");
    private static final String BRAND_NAME = "Tandir Go";
    private static final Instant START = Instant.parse("2026-09-01T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private TermsPublishingService publishing;
    private TermsAcceptanceService acceptance;
    private MutableClock clock;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for terms tests");
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
        jdbc.sql("TRUNCATE TABLE customer.consent_decisions CASCADE").update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE legal.terms_version_contents, legal.terms_versions CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenancy();
        insertCustomerAccount();

        clock = new MutableClock(START);
        JdbcAuditRecorder recorder =
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build());
        publishing = new TermsPublishingService(new JdbcTermsStore(jdbc), recorder, clock);
        ConsentService consent = new ConsentService(new JdbcCustomerStore(jdbc), clock);
        acceptance = new TermsAcceptanceService(publishing, consent, consent, clock);
    }

    @Test
    void aBrandThatNeverPublishedServesThePlatformDefaultWithItsOwnNameInIt() {
        EffectiveTerms effective = acceptance.effective(TENANT, BRAND, "en", BRAND_NAME);

        assertThat(effective.isPlatformDefault()).isTrue();
        assertThat(effective.documentVersion()).isNull();
        assertThat(effective.body())
                .as("the default names the actual brand, never a hardcoded one")
                .contains(BRAND_NAME)
                .doesNotContain("JizBiz");
        assertThat(effective.body()).isEqualTo(PlatformDefaultTerms.forLocale("en", BRAND_NAME));
    }

    @Test
    void aLocaleTheTenantNeverWroteFallsBackToThePlatformDefaultForThatLanguageOnly() {
        publishing.publish(TENANT, BRAND, Map.of("uz-Latn", "O'z matn."), OWNER, null);

        EffectiveTerms uzbek = acceptance.effective(TENANT, BRAND, "uz-Latn", BRAND_NAME);
        EffectiveTerms english = acceptance.effective(TENANT, BRAND, "en", BRAND_NAME);

        assertThat(uzbek.isPlatformDefault()).isFalse();
        assertThat(uzbek.body()).isEqualTo("O'z matn.");
        assertThat(english.isPlatformDefault())
                .as("English falls back to the platform default, not to the tenant's Uzbek text")
                .isTrue();
        assertThat(english.body()).contains(BRAND_NAME);
    }

    @Test
    void acceptingRecordsTheExactVersionAndLocaleShown() {
        publishing.publish(TENANT, BRAND, Map.of("ru", "Текст версии один."), OWNER, null);

        AcceptanceRecord recorded = acceptance.accept(TENANT, BRAND, ACCOUNT, "ru", BRAND_NAME);

        assertThat(recorded.policyVersionLabel()).isEqualTo("v1:ru");
        AcceptanceStatus status = acceptance.status(TENANT, BRAND, ACCOUNT, "ru", BRAND_NAME);
        assertThat(status.accepted()).isTrue();
        assertThat(status.lastAcceptedLabel()).isEqualTo("v1:ru");
    }

    @Test
    void acceptingThePlatformDefaultIsRecordedAsSuchAndDistinguishableFromATenantVersion() {
        AcceptanceRecord recorded = acceptance.accept(TENANT, BRAND, ACCOUNT, "en", BRAND_NAME);

        assertThat(recorded.policyVersionLabel()).isEqualTo("default-v" + PlatformDefaultTerms.VERSION + ":en");
    }

    /**
     * The wave's required proof. A customer accepts version 1; the tenant
     * later publishes version 2 with materially different words; the
     * customer's already-recorded acceptance must still read back exactly as
     * it did the moment it was made — same label, same timestamp — regardless
     * of what {@code current()} now answers.
     */
    @Test
    void publishingANewVersionDoesNotRetroactivelyChangeWhatWasAlreadyAccepted() {
        publishing.publish(TENANT, BRAND, Map.of("en", "Original terms: 30-day returns."), OWNER, null);
        AcceptanceRecord acceptedV1 = acceptance.accept(TENANT, BRAND, ACCOUNT, "en", BRAND_NAME);
        Instant acceptedAt = acceptedV1.acceptedAt();

        // Time passes and the tenant rewrites the deal entirely.
        clock.advance(Duration.ofDays(60));
        publishing.publish(TENANT, BRAND, Map.of("en", "New terms: no returns, price doubled."), OWNER, null);

        AcceptanceStatus statusAfterRepublish = acceptance.status(TENANT, BRAND, ACCOUNT, "en", BRAND_NAME);

        assertThat(statusAfterRepublish.lastAcceptedLabel())
                .as("what this customer accepted does not change when the tenant publishes again")
                .isEqualTo("v1:en")
                .isEqualTo(acceptedV1.policyVersionLabel());
        assertThat(statusAfterRepublish.currentVersionLabel())
                .as("what is now in force did change")
                .isEqualTo("v2:en");
        assertThat(statusAfterRepublish.accepted())
                .as("the old acceptance no longer covers the new version, so re-acceptance is due")
                .isFalse();
        assertThat(statusAfterRepublish.lastAcceptedAt())
                .as("the acceptance record's own timestamp is untouched by the later publish")
                .isEqualTo(acceptedAt);

        // And the exact words that customer agreed to are still readable, unchanged.
        assertThat(publishing.version(TENANT, BRAND, 1).orElseThrow().contentsByLocale())
                .containsEntry("en", "Original terms: 30-day returns.");
    }

    private void insertTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'accept-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
    }

    private void insertCustomerAccount() {
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, identity_partition_brand_id, status, display_name, version)
                VALUES (:id, :tenantId, :brandId, 'ACTIVE', 'Test Customer', 0)
                """)
                .param("id", ACCOUNT)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
