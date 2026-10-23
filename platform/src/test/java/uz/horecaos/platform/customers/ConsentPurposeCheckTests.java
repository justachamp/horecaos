package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.customers.application.ConsentPurposeCheck;
import uz.horecaos.platform.customers.application.ConsentPurposeCheck.Mode;
import uz.horecaos.platform.customers.application.ConsentService;
import uz.horecaos.platform.customers.application.ConsentService.Decision;
import uz.horecaos.platform.customers.application.ConsentService.Source;
import uz.horecaos.platform.customers.application.ConsentTypeService;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcConsentTypeStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.support.JvmLogCapture;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0109's unchecked item, taken as far as it can go before legal answers: {@code ConsentService.record} can say, behind a
 * flag that defaults off, that a decision was recorded under a purpose the tenant has not registered.
 *
 * <p>What each test would still pass on if the code were broken: the OFF case asserts nothing was counted for a purpose that
 * is plainly unregistered, so a check that was always on fails it; the report case asserts the decision was still written, so a
 * check that refused fails it; the log case asserts the purpose string is absent from the log, so a check that logged what was
 * typed fails it.
 */
class ConsentPurposeCheckTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final ActorRef ACTOR = ActorRef.user("owner-1", null);
    private static final String TYPED_BY_A_PERSON = "marketing-for-jasur-9012345";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ConsentTypeService types;
    private JdbcCustomerStore customers;
    private Clock clock;

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
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE customer.consent_types CASCADE").update();
        jdbc.sql("TRUNCATE TABLE customer.consent_decisions CASCADE").update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'consent-check', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name, identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", ACCOUNT).param("t", TENANT).update();
        clock = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneOffset.UTC);
        types = new ConsentTypeService(new JdbcConsentTypeStore(jdbc), clock);
        customers = new JdbcCustomerStore(jdbc);
    }

    private ConsentService serviceWith(ConsentPurposeCheck check) {
        return new ConsentService(customers, clock, check);
    }

    private long decisions(String purpose) {
        return jdbc.sql("SELECT count(*) FROM customer.consent_decisions WHERE purpose = :p")
                .param("p", purpose)
                .query(Long.class)
                .single();
    }

    private void record(ConsentService service, String purpose, Source source) {
        service.record(TENANT, ACCOUNT, BRAND, purpose, null, Decision.GRANTED, "1", source, null, null);
    }

    @Test
    @DisplayName("off by default: a decision under an unregistered purpose is written and nothing is counted")
    void offCountsNothing() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ConsentPurposeCheck check = new ConsentPurposeCheck(types, Mode.OFF, meters);

        record(serviceWith(check), TYPED_BY_A_PERSON, Source.SUPPORT_AGENT);

        assertThat(decisions(TYPED_BY_A_PERSON)).isEqualTo(1);
        assertThat(check.observedCount()).isZero();
        assertThat(meters.find("horecaos.customers.consent.unregistered_purpose")
                        .counter())
                .as("OFF must not even look the purpose up")
                .isNull();
    }

    @Test
    @DisplayName("report-only counts a decision under an unregistered purpose, per source, and still records it")
    void reportOnlyCountsAndStillRecords() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ConsentPurposeCheck check = new ConsentPurposeCheck(types, Mode.REPORT_ONLY, meters);
        ConsentService service = serviceWith(check);

        record(service, "MARKETING", Source.IMPORT);
        record(service, "MARKETING", Source.IMPORT);
        record(service, "MARKETING_PROMOTIONS", Source.IMPORT);
        record(service, "NEWSLETTER", Source.SUPPORT_AGENT);

        assertThat(check.observedCount())
                .as("MARKETING twice and NEWSLETTER once")
                .isEqualTo(3);
        assertThat(meters.get("horecaos.customers.consent.unregistered_purpose")
                        .tag("source", "IMPORT")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        assertThat(meters.get("horecaos.customers.consent.unregistered_purpose")
                        .tag("source", "SUPPORT_AGENT")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(decisions("MARKETING"))
                .as("report-only never refuses: the decision is evidence and is written exactly as before")
                .isEqualTo(2);
        assertThat(decisions("NEWSLETTER")).isEqualTo(1);
    }

    @Test
    @DisplayName("a registered purpose, and a default the tenant has not been given yet, are not counted")
    void registeredPurposesAreQuiet() {
        ConsentPurposeCheck check = new ConsentPurposeCheck(types, Mode.REPORT_ONLY, new SimpleMeterRegistry());
        ConsentService service = serviceWith(check);

        record(service, "MARKETING_PROMOTIONS", Source.STOREFRONT);
        types.list(TENANT, ACTOR);
        record(service, "TERMS_OF_SERVICE", Source.STOREFRONT);

        assertThat(check.observedCount()).isZero();
    }

    @Test
    @DisplayName("the log names the source and the tenant, never the purpose somebody typed")
    void theLogCarriesNoFreeText() {
        ConsentPurposeCheck check = new ConsentPurposeCheck(types, Mode.REPORT_ONLY, new SimpleMeterRegistry());

        try (JvmLogCapture logs = JvmLogCapture.start()) {
            record(serviceWith(check), TYPED_BY_A_PERSON, Source.SUPPORT_AGENT);

            List<String> messages = logs.snapshot().stream()
                    .filter(event -> event.getLoggerName().equals(ConsentPurposeCheck.class.getName()))
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(messages)
                    .anySatisfy(message ->
                            assertThat(message).contains("SUPPORT_AGENT").contains(TENANT.toString()));
            assertThat(String.join("\n", messages))
                    .as("ADR 0029: free text a person typed stays out of logs")
                    .doesNotContain(TYPED_BY_A_PERSON);
        }
    }

    @Test
    @DisplayName("an unrecognised flag value leaves the check off rather than on")
    void anUnrecognisedValueIsOff() {
        ConsentPurposeCheck check = new ConsentPurposeCheck(types, "report only please", new SimpleMeterRegistry());

        assertThat(check.mode()).isEqualTo(Mode.OFF);
    }
}
