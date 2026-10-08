package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
import uz.horecaos.platform.notifications.application.NotificationTemplateService.IncompleteTranslationException;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Resolution;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcBrandLocaleLookup;

/**
 * ADR 0020's rule, as ADR 0149 (Decision 4) changed what it counts: a template version needs a wording
 * in every locale <em>the template's brand serves</em>, not in every language the platform has.
 *
 * <p>Runs against the migrated schema with the real {@code tenant.brand_locales} lookup, because the
 * rule is a statement about two tables (a brand's set, a version's rows) and a fake of either would
 * prove the fake.
 */
class BrandScopedRequiredLocalesTests {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private NotificationTemplateService templates;
    private UUID tenantId;
    private UUID twoLanguageBrand;
    private UUID unconfiguredBrand;

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
        templates = new NotificationTemplateService(
                new JdbcTemplateStore(jdbc),
                JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                AuditTrail.discarding(),
                AuditTrail.actor("template-author"),
                new JdbcBrandLocaleLookup(jdbc));

        tenantId = UUID.randomUUID();
        twoLanguageBrand = UUID.randomUUID();
        unconfiguredBrand = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'languages', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).update();
        brand(twoLanguageBrand, "TWO", "two-languages");
        brand(unconfiguredBrand, "NONE", "no-languages");
        locale(twoLanguageBrand, "ru", true);
        locale(twoLanguageBrand, "uz-Latn", false);
    }

    @Test
    @DisplayName(
            "a brand serving ru and uz-Latn saves and activates a two-wording version; one serving nothing owes all three")
    void aBrandCountsItsOwnLanguages() {
        UUID template = template(twoLanguageBrand);
        int version = templates.addVersion(
                tenantId, twoLanguageBrand, template, wordings(MessageLocale.RU, MessageLocale.UZ_LATN), Map.of());
        templates.activate(tenantId, twoLanguageBrand, template, version, "tester");

        assertThat(templates.versions(tenantId, twoLanguageBrand, template, version))
                .extracting(row -> row.locale() + ":" + row.status())
                .containsExactlyInAnyOrder("ru:ACTIVE", "uz-Latn:ACTIVE");

        UUID other = template(unconfiguredBrand);
        assertThatThrownBy(() -> templates.addVersion(
                        tenantId,
                        unconfiguredBrand,
                        other,
                        wordings(MessageLocale.RU, MessageLocale.UZ_LATN),
                        Map.of()))
                .as("a brand that has chosen nothing serves the platform's content tier, as every editor reads it")
                .isInstanceOf(IncompleteTranslationException.class)
                .hasMessageEndingWith("missing [en]");
    }

    @Test
    @DisplayName("a brand that serves three is refused a two-wording version and the missing language is named")
    void aMissingLanguageIsNamed() {
        locale(twoLanguageBrand, "en", false);
        UUID template = template(twoLanguageBrand);

        assertThatThrownBy(() -> templates.addVersion(
                        tenantId,
                        twoLanguageBrand,
                        template,
                        wordings(MessageLocale.RU, MessageLocale.UZ_LATN),
                        Map.of()))
                .isInstanceOf(IncompleteTranslationException.class)
                .hasMessageEndingWith("missing [en]");
    }

    @Test
    @DisplayName(
            "a draft authored in more languages than the brand serves is kept whole, and activation counts only what the brand serves")
    void anExtraWordingIsKeptAndDoesNotBlockActivation() {
        UUID template = template(twoLanguageBrand);
        int version = templates.addVersion(
                tenantId,
                twoLanguageBrand,
                template,
                wordings(MessageLocale.RU, MessageLocale.UZ_LATN, MessageLocale.EN),
                Map.of());

        templates.activate(tenantId, twoLanguageBrand, template, version, "tester");

        assertThat(templates.versions(tenantId, twoLanguageBrand, template, version))
                .as("all three rows activate together: half of a version being live is worse than none")
                .extracting(row -> row.locale() + ":" + row.status())
                .containsExactlyInAnyOrder("ru:ACTIVE", "uz-Latn:ACTIVE", "en:ACTIVE");
    }

    @Test
    @DisplayName("a language the registry declares but does not send in is never required: nothing could author it")
    void aDeclaredButInactiveLanguageIsNotRequired() {
        locale(twoLanguageBrand, "kk", false);

        assertThat(templates.requiredLocales(tenantId, twoLanguageBrand))
                .extracting(MessageLocale::tag)
                .containsExactly("ru", "uz-Latn");
    }

    @Test
    @DisplayName("each brand is counted on its own: one brand's choice never changes what another owes")
    void anotherBrandsChoiceDoesNotLeak() {
        assertThat(templates.requiredLocales(tenantId, twoLanguageBrand))
                .extracting(MessageLocale::tag)
                .containsExactly("ru", "uz-Latn");
        assertThat(templates.requiredLocales(tenantId, unconfiguredBrand))
                .extracting(MessageLocale::tag)
                .containsExactly("ru", "uz-Latn", "en");

        UUID template = template(unconfiguredBrand);
        int version = templates.addVersion(
                tenantId,
                unconfiguredBrand,
                template,
                wordings(MessageLocale.RU, MessageLocale.UZ_LATN, MessageLocale.EN),
                Map.of());
        templates.activate(tenantId, unconfiguredBrand, template, version, "tester");
        locale(twoLanguageBrand, "en", false);

        assertThat(templates
                        .resolveExact(
                                tenantId,
                                unconfiguredBrand,
                                "ORDER_CONFIRMED",
                                NotificationChannel.SMS,
                                MessageLocale.EN,
                                null,
                                null)
                        .isFound())
                .as("the other brand's active version is untouched by this brand adding a language")
                .isTrue();
    }

    @Test
    @DisplayName("a tenant-wide template serves every brand, so it counts the union of the tenant's")
    void aTenantWideTemplateCountsTheUnion() {
        UUID tenantWide = templates.createTemplate(
                tenantId,
                null,
                "ORDER_CONFIRMED",
                NotificationClass.TRANSACTIONAL_REQUIRED,
                NotificationChannel.SMS,
                null);

        assertThatThrownBy(() -> templates.addVersion(
                        tenantId,
                        twoLanguageBrand,
                        tenantWide,
                        wordings(MessageLocale.RU, MessageLocale.UZ_LATN),
                        Map.of()))
                .as("the unconfigured brand beside it needs en")
                .isInstanceOf(IncompleteTranslationException.class)
                .hasMessageEndingWith("missing [en]");

        assertThat(templates.requiredLocales(tenantId, null))
                .extracting(MessageLocale::tag)
                .containsExactly("ru", "uz-Latn", "en");
    }

    @Test
    @DisplayName("a customer whose language the brand does not serve is written to in the brand's default")
    void anUnsupportedPreferenceGetsTheBrandDefault() {
        activateTwoLanguageTemplate();

        Resolution english = templates.resolve(
                tenantId, twoLanguageBrand, "ORDER_CONFIRMED", NotificationChannel.SMS, MessageLocale.EN, null, null);
        assertThat(english.isFound()).isTrue();
        assertThat(servedIn(english)).as("the brand's default, which is ru").isEqualTo("ru");

        Resolution uzbek = templates.resolve(
                tenantId,
                twoLanguageBrand,
                "ORDER_CONFIRMED",
                NotificationChannel.SMS,
                MessageLocale.UZ_LATN,
                null,
                null);
        assertThat(servedIn(uzbek)).as("a supported preference is honoured").isEqualTo("uz-Latn");
    }

    @Test
    @DisplayName("a language the brand added after the template went live falls back instead of going silent")
    void aLanguageAddedLaterFallsBack() {
        activateTwoLanguageTemplate();
        locale(twoLanguageBrand, "en", false);

        Resolution english = templates.resolve(
                tenantId, twoLanguageBrand, "ORDER_CONFIRMED", NotificationChannel.SMS, MessageLocale.EN, null, null);

        assertThat(english.isFound())
                .as("en is now served but this template has no en wording: the customer reads the default, not nothing")
                .isTrue();
        assertThat(servedIn(english)).isEqualTo("ru");
        assertThat(templates
                        .resolveExact(
                                tenantId,
                                twoLanguageBrand,
                                "ORDER_CONFIRMED",
                                NotificationChannel.SMS,
                                MessageLocale.EN,
                                null,
                                null)
                        .outcome())
                .as("the exact read, which the campaign composer uses, still says it is missing")
                .isEqualTo(Resolution.Outcome.NO_TEMPLATE_FOR_LOCALE);
    }

    @Test
    @DisplayName("where the brand's default is missing too, the registry's fallback is tried before giving up")
    void theRegistryFallbackIsTheLastResort() {
        // A brand whose default is uz-Latn but whose only wording is Russian (written before it set a default).
        jdbc.sql("DELETE FROM tenant.brand_locales WHERE brand_id = :brand")
                .param("brand", twoLanguageBrand)
                .update();
        locale(twoLanguageBrand, "uz-Latn", true);
        locale(twoLanguageBrand, "ru", false);
        UUID template = template(twoLanguageBrand);
        int version = templates.addVersion(
                tenantId, twoLanguageBrand, template, wordings(MessageLocale.RU, MessageLocale.UZ_LATN), Map.of());
        templates.activate(tenantId, twoLanguageBrand, template, version, "tester");
        jdbc.sql("DELETE FROM notifications.template_versions WHERE locale = 'uz-Latn'")
                .update();

        Resolution resolution = templates.resolve(
                tenantId, twoLanguageBrand, "ORDER_CONFIRMED", NotificationChannel.SMS, MessageLocale.EN, null, null);

        assertThat(resolution.isFound()).isTrue();
        assertThat(servedIn(resolution)).isEqualTo("ru");
    }

    // ------------------------------------------------------------- fixtures

    private void activateTwoLanguageTemplate() {
        UUID template = template(twoLanguageBrand);
        int version = templates.addVersion(
                tenantId, twoLanguageBrand, template, wordings(MessageLocale.RU, MessageLocale.UZ_LATN), Map.of());
        templates.activate(tenantId, twoLanguageBrand, template, version, "tester");
    }

    private static String servedIn(Resolution resolution) {
        return Objects.requireNonNull(resolution.version()).locale();
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

    private void brand(UUID id, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, :code, :slug, :code, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private void locale(UUID brand, String locale, boolean isDefault) {
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
}
