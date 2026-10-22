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
import uz.horecaos.platform.customers.application.ConsentTypeService.ConsentTypeView;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcConsentTypeStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.LocalizedLabels;

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
        List<ConsentTypeView> types = consentTypes.list(TENANT, ACTOR);

        assertThat(types)
                .extracting(view -> view.row().code())
                .containsExactlyInAnyOrder("MARKETING_PROMOTIONS", "TERMS_OF_SERVICE", "review.public_display");
        assertThat(types).allSatisfy(view -> assertThat(view.row().active()).isTrue());
    }

    @Test
    @DisplayName("a second read never duplicates the seeded defaults")
    void secondReadDoesNotDuplicate() {
        consentTypes.list(TENANT, ACTOR);
        List<ConsentTypeView> secondRead = consentTypes.list(TENANT, ACTOR);

        assertThat(secondRead).hasSize(3);
        assertThat(jdbc.sql("SELECT count(*) FROM customer.consent_types WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Long.class)
                        .single())
                .isEqualTo(3L);
    }

    @Test
    @DisplayName(
            "the review purpose is a default of every registry, so a customer's tick has a purpose to be recorded against")
    void theReviewPurposeIsADefault() {
        assertThat(consentTypes.list(TENANT, ACTOR))
                .filteredOn(view -> "review.public_display".equals(view.row().code()))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.row().active()).isTrue();
                    assertThat(view.labels().get(LocalizedLabels.RU)).isNotBlank();
                    assertThat(view.labels().get(LocalizedLabels.EN)).contains("without my name");
                });
    }

    @Test
    @DisplayName("one tenant's registry never reaches another tenant's read")
    void neverCrossesATenantBoundary() {
        consentTypes.list(TENANT, ACTOR);

        List<ConsentTypeView> theirs = consentTypes.list(OTHER_TENANT, ACTOR);

        assertThat(theirs).allSatisfy(view -> assertThat(view.row().tenantId()).isEqualTo(OTHER_TENANT));
        assertThat(jdbc.sql("SELECT count(*) FROM customer.consent_types WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Long.class)
                        .single())
                .as("tenant A's own three defaults are untouched by tenant B's read")
                .isEqualTo(3L);
    }

    @Test
    @DisplayName(
            "every default is worded in all three live languages, in the translations table and in the legacy columns")
    void theDefaultsAreWordedInEveryLiveLanguage() {
        List<ConsentTypeView> types = consentTypes.list(TENANT, ACTOR);

        assertThat(types)
                .allSatisfy(view -> assertThat(view.labels())
                        .containsKeys(LocalizedLabels.RU, LocalizedLabels.UZ_LATN, LocalizedLabels.EN)
                        .allSatisfy((locale, label) -> assertThat(label).isNotBlank()));
        assertThat(jdbc.sql("SELECT count(*) FROM customer.consent_type_translations WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Long.class)
                        .single())
                .as("three purposes in three languages")
                .isEqualTo(9L);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM customer.consent_types
                         WHERE tenant_id = :t AND label_ru IS NOT NULL AND label_uz IS NOT NULL AND label_en IS NOT NULL
                        """).param("t", TENANT).query(Long.class).single())
                .as("written alongside for one release, so the previous build still finds what it reads")
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("a purpose worded in a fourth language returns it, so the console is not limited to three columns")
    void aFourthLanguageIsReturned() {
        List<ConsentTypeView> types = consentTypes.list(TENANT, ACTOR);
        UUID marketing = types.stream()
                .filter(view -> "MARKETING_PROMOTIONS".equals(view.row().code()))
                .findFirst()
                .orElseThrow()
                .row()
                .id();
        jdbc.sql("""
                INSERT INTO customer.consent_type_translations (tenant_id, consent_type_id, locale, label)
                VALUES (:t, :id, 'kk', 'Маркетингтік хабарламалар')
                """).param("t", TENANT).param("id", marketing).update();

        assertThat(consentTypes.list(TENANT, ACTOR))
                .filteredOn(view -> view.row().id().equals(marketing))
                .singleElement()
                .satisfies(view -> assertThat(view.labels())
                        .containsEntry("kk", "Маркетингтік хабарламалар")
                        .containsEntry(LocalizedLabels.EN, "Marketing messages"));
    }

    @Test
    @DisplayName("a row written by a build that knew only the three columns still reads in those languages")
    void aColumnOnlyRowStillReads() {
        UUID legacy = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.consent_types (id, tenant_id, code, label_ru, label_uz, label_en, created_by, updated_by)
                VALUES (:id, :t, 'LEGACY', 'Старое', 'Eski', 'Legacy', 'a-test', 'a-test')
                """).param("id", legacy).param("t", TENANT).update();

        assertThat(consentTypes.list(TENANT, ACTOR))
                .filteredOn(view -> view.row().id().equals(legacy))
                .singleElement()
                .satisfies(view -> assertThat(view.labels())
                        .containsEntry(LocalizedLabels.RU, "Старое")
                        .containsEntry(LocalizedLabels.UZ_LATN, "Eski")
                        .containsEntry(LocalizedLabels.EN, "Legacy"));
    }

    @Test
    @DisplayName(
            "a purpose can be stored without the platform triple, because a tenant's default language may be outside it")
    void theLegacyColumnsAreNoLongerRequired() {
        UUID only = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.consent_types (id, tenant_id, code, created_by, updated_by)
                VALUES (:id, :t, 'KK_ONLY', 'a-test', 'a-test')
                """).param("id", only).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO customer.consent_type_translations (tenant_id, consent_type_id, locale, label)
                VALUES (:t, :id, 'kk', 'Тек қазақша')
                """).param("t", TENANT).param("id", only).update();

        assertThat(consentTypes.list(TENANT, ACTOR))
                .filteredOn(view -> view.row().id().equals(only))
                .singleElement()
                .satisfies(view -> assertThat(view.labels()).containsOnlyKeys("kk"));
    }

    @Test
    @DisplayName("a translation can never be attached to another tenant's purpose")
    void aTranslationIsTenantScoped() {
        List<ConsentTypeView> mine = consentTypes.list(TENANT, ACTOR);
        UUID mineId = mine.getFirst().row().id();

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.sql("""
                                INSERT INTO customer.consent_type_translations (tenant_id, consent_type_id, locale, label)
                                VALUES (:other, :id, 'kk', 'Чужой')
                                """)
                        .param("other", OTHER_TENANT)
                        .param("id", mineId)
                        .update());
    }

    @Test
    @DisplayName(
            "a code is registered when it is an active purpose of the registry, or a default a tenant has not been given yet")
    void registeredMeansInTheRegistryOrAUnbootstrappedDefault() {
        assertThat(consentTypes.isRegistered(TENANT, "MARKETING_PROMOTIONS"))
                .as("no registry yet: the defaults it would get count")
                .isTrue();
        assertThat(consentTypes.isRegistered(TENANT, "MARKETING")).isFalse();

        consentTypes.list(TENANT, ACTOR);
        jdbc.sql("UPDATE customer.consent_types SET active = false WHERE tenant_id = :t AND code = 'TERMS_OF_SERVICE'")
                .param("t", TENANT)
                .update();

        assertThat(consentTypes.isRegistered(TENANT, "MARKETING_PROMOTIONS")).isTrue();
        assertThat(consentTypes.isRegistered(TENANT, "TERMS_OF_SERVICE"))
                .as("a deactivated purpose is not one a decision may cite")
                .isFalse();
        assertThat(consentTypes.isRegistered(OTHER_TENANT, "MARKETING_PROMOTIONS"))
                .as("the other tenant has no registry yet and gets the defaults")
                .isTrue();
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }
}
