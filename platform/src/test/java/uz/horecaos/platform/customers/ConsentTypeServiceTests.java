package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.customers.application.ConsentTypeService;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcConsentTypeStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcConsentTypeStore.ConsentTypeRow;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The tenant-wide consent-purpose registry (V0289, ADR 0109) — 5.2b's own doc
 * named this gap: "a decision log exists, a type registry does not". The
 * properties under test: a tenant with no registry yet sees the code-owned
 * defaults on its first read, a second read never duplicates them, and one
 * tenant's registry never reaches another's.
 */
class ConsentTypeServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final ActorRef ACTOR = ActorRef.user("owner-1", null);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private ConsentTypeService consentTypes;

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
        jdbc.sql("TRUNCATE TABLE customer.consent_types CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenant(TENANT, "tenant-consent-a");
        insertTenant(OTHER_TENANT, "tenant-consent-b");

        Clock clock = Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC);
        consentTypes = new ConsentTypeService(new JdbcConsentTypeStore(jdbc), clock);
    }

    @Test
    @DisplayName("a tenant with no registry yet sees the code-owned defaults on first read")
    void firstReadSeedsTheDefaults() {
        List<ConsentTypeRow> types = consentTypes.list(TENANT, ACTOR);

        assertThat(types)
                .extracting(ConsentTypeRow::code)
                .containsExactlyInAnyOrder("MARKETING_PROMOTIONS", "TERMS_OF_SERVICE");
        assertThat(types).allSatisfy(row -> assertThat(row.active()).isTrue());
    }

    @Test
    @DisplayName("a second read never duplicates the seeded defaults")
    void secondReadDoesNotDuplicate() {
        consentTypes.list(TENANT, ACTOR);
        List<ConsentTypeRow> secondRead = consentTypes.list(TENANT, ACTOR);

        assertThat(secondRead).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM customer.consent_types WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("one tenant's registry never reaches another tenant's read")
    void neverCrossesATenantBoundary() {
        consentTypes.list(TENANT, ACTOR);

        List<ConsentTypeRow> theirs = consentTypes.list(OTHER_TENANT, ACTOR);

        assertThat(theirs).allSatisfy(row -> assertThat(row.tenantId()).isEqualTo(OTHER_TENANT));
        assertThat(jdbc.sql("SELECT count(*) FROM customer.consent_types WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Long.class)
                        .single())
                .as("tenant A's own two defaults are untouched by tenant B's read")
                .isEqualTo(2L);
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }
}
