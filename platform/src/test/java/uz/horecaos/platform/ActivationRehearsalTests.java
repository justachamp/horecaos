package uz.horecaos.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.notifications.application.NotificationTemplateService;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.IncompleteTranslationException;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.PlatformLocale;
import uz.horecaos.platform.tenancy.api.PlatformLocale.Direction;
import uz.horecaos.platform.tenancy.api.PlatformLocale.Script;
import uz.horecaos.platform.tenancy.api.PlatformLocale.Tier;
import uz.horecaos.platform.tenancy.api.PlatformLocales;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet;
import uz.horecaos.platform.tenancy.domain.BrandProfile;
import uz.horecaos.platform.tenancy.domain.BrandProfile.BrandLocale;
import uz.horecaos.platform.tenancy.domain.BrandRegionalFormats;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcBrandLocaleLookup;
import uz.horecaos.platform.tenancy.web.PlatformLocaleController;
import uz.horecaos.platform.tenancy.web.PlatformLocaleController.PlatformLocaleEntryResponse;
import uz.horecaos.platform.tenancy.web.StorefrontBrandLocaleController;

/**
 * ADR 0149's exit criterion, rehearsed: declaring a language live in a tier is a change to its registry
 * entry and nothing else -- "no migration and no change to any module other than its catalogue".
 *
 * <p>No language is activated by the record, so the claim has never been seen to hold. This declares a
 * synthetic fifth language (a tag the platform has never heard of, with a script and a face it already
 * has) live in the content and messages tiers for the length of each test, and checks that every
 * reader the record names follows without being touched: the brand editor's closed set, the
 * template requirement, the set a tenant-scoped vocabulary is authored in, the public registry
 * read and a brand's own public set. The same database, the same migrated schema: if a table had
 * kept a closed list of languages, the insert of the fifth would be refused.
 *
 * <p>It cannot prove the client half (a catalogue per client, a face); the console and storefront
 * specs rehearse that against their own copy of the registry.
 */
class ActivationRehearsalTests {

    /** A language nobody has declared: a plausible tag, an existing script and face, direction left to right. */
    private static final PlatformLocale FIFTH = new PlatformLocale(
            "zz",
            "zz",
            Set.of(),
            Script.LATN,
            Direction.LTR,
            5,
            "ibm-plex-sans",
            Set.of(Tier.CONTENT, Tier.MESSAGES),
            Map.of("ru", "Пятый", "en", "Fifth", "zz", "Zzzz"));

    private static final Instant NOW = Instant.parse("2026-10-23T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private NotificationTemplateService templates;
    private JdbcBrandLocaleLookup lookup;
    private UUID tenantId;
    private UUID brandId;

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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE notifications.templates CASCADE").update();
        lookup = new JdbcBrandLocaleLookup(jdbc);
        templates = new NotificationTemplateService(
                new JdbcTemplateStore(jdbc),
                JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                AuditTrail.discarding(),
                AuditTrail.actor("template-author"),
                lookup);
        tenantId = UUID.randomUUID();
        brandId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'rehearsal', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'FIFTH', 'fifth', 'FIFTH', 'ACTIVE')
                """).param("id", brandId).param("tenantId", tenantId).update();
    }

    @Test
    @DisplayName("before the rehearsal the registry has five entries and three are live, and after it nothing remains")
    void theRehearsalLeavesNothingBehind() throws Exception {
        List<String> before =
                PlatformLocales.all().stream().map(PlatformLocale::tag).toList();
        try (AutoCloseable declared = PlatformLocales.rehearseActivation(FIFTH)) {
            assertThat(declared).isNotNull();
            assertThat(PlatformLocales.activeTags(Tier.CONTENT)).containsExactly("ru", "uz-Latn", "en", "zz");
            assertThat(PlatformLocales.activeTags(Tier.STAFF_UI))
                    .as("live in the tiers it was declared in and no others")
                    .containsExactly("ru", "uz-Latn", "en");
        }
        assertThat(PlatformLocales.all().stream().map(PlatformLocale::tag).toList())
                .as("a rehearsal is a scope, not a state")
                .isEqualTo(before);
        assertThat(PlatformLocales.byTag("zz")).isEmpty();
    }

    @Test
    @DisplayName("a brand can choose the new language, and the schema takes it with no migration")
    void aBrandCanChooseItAndTheSchemaTakesIt() throws Exception {
        try (AutoCloseable declared = PlatformLocales.rehearseActivation(FIFTH)) {
            assertThat(new BrandProfile(
                            null,
                            null,
                            null,
                            null,
                            List.of(new BrandLocale("ru", null, true), new BrandLocale("zz", null, false)),
                            BrandRegionalFormats.defaults()))
                    .as("the brand editor's closed set is the registry's content tier")
                    .isNotNull();

            brandLocale("ru", true);
            brandLocale("zz", false);
            assertThat(lookup.brandLocaleSet(tenantId, brandId).locales()).containsExactly("ru", "zz");
        }
        assertThatThrownBy(() -> new BrandProfile(
                        null,
                        null,
                        null,
                        null,
                        List.of(new BrandLocale("ru", null, true), new BrandLocale("zz", null, false)),
                        BrandRegionalFormats.defaults()))
                .as("and once the rehearsal is over the same choice is refused again")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported locale 'zz'");
    }

    @Test
    @DisplayName(
            "a brand that serves it owes a wording in it before a template can be activated, and no other brand does")
    void theTemplateRequirementFollowsTheRegistry() throws Exception {
        UUID otherBrand = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'OTHER', 'other', 'OTHER', 'ACTIVE')
                """).param("id", otherBrand).param("tenantId", tenantId).update();
        brandLocale(brandId, "ru", true);
        brandLocale(otherBrand, "ru", true);

        try (AutoCloseable declared = PlatformLocales.rehearseActivation(FIFTH)) {
            brandLocale(brandId, "zz", false);
            MessageLocale zz = MessageLocale.parse("zz").orElseThrow();
            UUID template = template(brandId);

            assertThatThrownBy(() ->
                            templates.addVersion(tenantId, brandId, template, wordings(MessageLocale.RU), Map.of()))
                    .as("the brand serves zz, so a Russian-only version is refused and zz is named")
                    .isInstanceOf(IncompleteTranslationException.class)
                    .hasMessageEndingWith("missing [zz]");

            int version = templates.addVersion(tenantId, brandId, template, wordings(MessageLocale.RU, zz), Map.of());
            templates.activate(tenantId, brandId, template, version, "tester");
            assertThat(templates.versions(tenantId, brandId, template, version))
                    .extracting(row -> row.locale() + ":" + row.status())
                    .containsExactlyInAnyOrder("ru:ACTIVE", "zz:ACTIVE");

            UUID untouched = template(otherBrand);
            int fine = templates.addVersion(tenantId, otherBrand, untouched, wordings(MessageLocale.RU), Map.of());
            templates.activate(tenantId, otherBrand, untouched, fine, "tester");
            assertThat(templates.requiredLocales(tenantId, otherBrand))
                    .as("a brand that did not choose it owes nothing in it")
                    .extracting(MessageLocale::tag)
                    .containsExactly("ru");
        }
    }

    @Test
    @DisplayName(
            "the set a tenant-scoped vocabulary is authored in, and the platform fallback, widen with the registry")
    void theTenantSetsFollowTheRegistry() throws Exception {
        try (AutoCloseable declared = PlatformLocales.rehearseActivation(FIFTH)) {
            assertThat(TenantLocaleSet.platformFallback().locales())
                    .as("a brand that has chosen nothing serves every live content language, the new one included")
                    .containsExactly("ru", "uz-Latn", "en", "zz");

            brandLocale("uz-Latn", true);
            brandLocale("zz", false);
            assertThat(lookup.tenantLocaleSet(tenantId).locales())
                    .as("the union an outcome reason or a payment method is worded in")
                    .containsExactly("uz-Latn", "zz");
        }
        assertThat(TenantLocaleSet.platformFallback().locales()).containsExactly("ru", "uz-Latn", "en");
    }

    @Test
    @DisplayName(
            "the public reads show it live where it is live, its direction and its catalogue code, and a brand's own set")
    void thePublicReadsFollowTheRegistry() throws Exception {
        try (AutoCloseable declared = PlatformLocales.rehearseActivation(FIFTH)) {
            PlatformLocaleEntryResponse entry = new PlatformLocaleController()
                    .storefront().locales().stream()
                            .filter(candidate -> candidate.tag().equals("zz"))
                            .findFirst()
                            .orElseThrow();
            assertThat(entry.tiers()).containsExactly("CONTENT", "MESSAGES");
            assertThat(entry.direction()).as("what <html dir> is set from").isEqualTo("LTR");
            assertThat(entry.catalogCode()).isEqualTo("zz");
            assertThat(entry.face()).isEqualTo("ibm-plex-sans");

            brandLocale("zz", true);
            brandLocale("ru", false);
            var served = Objects.requireNonNull(new StorefrontBrandLocaleController(lookup)
                    .brandLocales(tenantId, brandId)
                    .getBody());
            assertThat(served.locales())
                    .as("what the storefront's picker offers")
                    .containsExactly("zz", "ru");
            assertThat(served.defaultLocale()).isEqualTo("zz");
        }
        var afterwards = Objects.requireNonNull(new StorefrontBrandLocaleController(lookup)
                .brandLocales(tenantId, brandId)
                .getBody());
        assertThat(afterwards.locales())
                .as("once the language is no longer live a stored choice of it is not offered")
                .containsExactly("ru");
    }

    // ------------------------------------------------------------- fixtures

    private void brandLocale(String locale, boolean isDefault) {
        brandLocale(brandId, locale, isDefault);
    }

    private void brandLocale(UUID brand, String locale, boolean isDefault) {
        jdbc.sql("""
                INSERT INTO tenant.brand_locales (tenant_id, brand_id, locale, is_default)
                VALUES (:tenantId, :brandId, :locale, :isDefault)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brand)
                .param("locale", locale)
                .param("isDefault", isDefault)
                .update();
    }

    private UUID template(UUID brand) {
        return templates.createTemplate(
                tenantId,
                brand,
                "ORDER_CONFIRMED",
                NotificationClass.TRANSACTIONAL_REQUIRED,
                NotificationChannel.SMS,
                null);
    }

    private static Map<MessageLocale, Wording> wordings(MessageLocale... locales) {
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        for (MessageLocale locale : locales) {
            wordings.put(locale, new Wording(null, "Order accepted (" + locale.tag() + ")"));
        }
        return wordings;
    }
}
