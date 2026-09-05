package uz.horecaos.platform.legal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
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
import uz.horecaos.platform.legal.domain.TermsVersion;
import uz.horecaos.platform.legal.domain.TermsVersionSummary;
import uz.horecaos.platform.legal.infrastructure.persistence.JdbcTermsStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * ADR 0067: publishing never mutates a prior version.
 *
 * <p>The single fact every test here is built to catch a broken version of:
 * publishing version 2 must leave the row for version 1 — the one row a
 * customer's acceptance might already point at — completely untouched. A
 * service that instead updated a "current" row in place would pass a naive
 * "current version reads back correctly" test just as happily while failing
 * this one.
 */
class TermsPublishingServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120c01");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120c02");
    private static final UUID OTHER_BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120c03");
    private static final ActorRef OWNER = ActorRef.user("owner-subject", "Tenant Owner");
    private static final Instant START = Instant.parse("2026-09-01T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private TermsPublishingService publishing;
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
        jdbc.sql("TRUNCATE TABLE legal.terms_version_contents, legal.terms_versions CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenancy();

        clock = new MutableClock(START);
        JdbcAuditRecorder recorder =
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build());
        publishing = new TermsPublishingService(new JdbcTermsStore(jdbc), recorder, clock);
    }

    @Test
    void firstPublishIsVersionOne() {
        TermsVersion published = publishing.publish(TENANT, BRAND, Map.of("en", "Hello."), OWNER, null);

        assertThat(published.version()).isEqualTo(1);
        assertThat(published.contentsByLocale()).containsEntry("en", "Hello.");
        assertThat(publishing.current(TENANT, BRAND)).contains(published);
    }

    @Test
    void publishingANewVersionNeverChangesAnEarlierOne() {
        TermsVersion v1 = publishing.publish(TENANT, BRAND, Map.of("en", "Version one text."), OWNER, null);

        clock.advance(Duration.ofDays(30));
        TermsVersion v2 =
                publishing.publish(TENANT, BRAND, Map.of("en", "Version two text, quite different."), OWNER, null);

        // The row publish(v1) returned, re-read straight from storage after v2
        // exists: this is the row a customer's acceptance, if any, points at.
        TermsVersion v1ReadAgain = publishing.version(TENANT, BRAND, 1).orElseThrow();

        assertThat(v1ReadAgain.contentsByLocale()).isEqualTo(v1.contentsByLocale());
        assertThat(v1ReadAgain.publishedAt()).isEqualTo(v1.publishedAt());
        assertThat(v2.version()).isEqualTo(2);
        assertThat(publishing.current(TENANT, BRAND).orElseThrow().version())
                .as("current now means the new one, not the old one")
                .isEqualTo(2);
    }

    @Test
    void historyListsEveryVersionNewestFirstWithoutLosingOlderOnes() {
        publishing.publish(TENANT, BRAND, Map.of("en", "v1"), OWNER, null);
        clock.advance(Duration.ofDays(1));
        publishing.publish(TENANT, BRAND, Map.of("en", "v2", "ru", "в2"), OWNER, "Added Russian");

        List<TermsVersionSummary> history = publishing.history(TENANT, BRAND);

        assertThat(history).extracting(TermsVersionSummary::version).containsExactly(2, 1);
        assertThat(history.get(0).locales()).containsExactlyInAnyOrder("en", "ru");
        assertThat(history.get(1).locales()).containsExactly("en");
    }

    @Test
    void aVersionMayCoverFewerThanAllThreeLanguages() {
        TermsVersion published =
                publishing.publish(TENANT, BRAND, Map.of("uz-Latn", "Matn.", "ru", "Текст."), OWNER, null);

        assertThat(published.contentsByLocale()).containsOnlyKeys("uz-Latn", "ru");
    }

    @Test
    void publishingWithNoLanguagesAtAllIsRefused() {
        assertThatThrownBy(() -> publishing.publish(TENANT, BRAND, Map.of(), OWNER, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void publishingOnlyBlankBodiesIsRefused() {
        assertThatThrownBy(() -> publishing.publish(TENANT, BRAND, Map.of("en", "   "), OWNER, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void anUnknownLocaleIsRefused() {
        assertThatThrownBy(() -> publishing.publish(TENANT, BRAND, Map.of("fr", "Bonjour."), OWNER, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aBrandWithNoPublishedVersionHasNoCurrentOne() {
        assertThat(publishing.current(TENANT, BRAND)).isEmpty();
    }

    @Test
    void oneBrandsVersionsAreNotAnothers() {
        publishing.publish(TENANT, BRAND, Map.of("en", "This brand's terms."), OWNER, null);

        assertThat(publishing.current(TENANT, OTHER_BRAND)).isEmpty();
        assertThat(publishing.current(TENANT, BRAND)).isPresent();
    }

    @Test
    void versionNumbersRestartPerBrand() {
        publishing.publish(TENANT, BRAND, Map.of("en", "Brand A v1"), OWNER, null);
        TermsVersion otherBrandFirst = publishing.publish(TENANT, OTHER_BRAND, Map.of("en", "Brand B v1"), OWNER, null);

        assertThat(otherBrandFirst.version())
                .as("each brand's own version sequence, not a shared counter")
                .isEqualTo(1);
    }

    private void insertTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'terms-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'OTHER', 'other', 'Other Brand', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("tenantId", TENANT).update();
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
