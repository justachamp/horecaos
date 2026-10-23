package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Resolution;
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.domain.PlatformDefaultTemplates;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcBrandLocaleLookup;

/**
 * ADR 0020's resolution order, brand then tenant then platform, with the last layer ADR 0184 builds
 * (D20 orders the first two; see {@code TemplateVariantResolutionTests}).
 *
 * <p>Against the migrated schema and the real {@code tenant.brand_locales} lookup, because the layer
 * is a statement about what two tables do not hold.
 */
class PlatformDefaultResolutionTests {

    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private NotificationTemplateService templates;
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
        templates = new NotificationTemplateService(
                new JdbcTemplateStore(jdbc),
                JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                AuditTrail.discarding(),
                AuditTrail.actor("template-author"),
                new JdbcBrandLocaleLookup(jdbc));
        tenantId = UUID.randomUUID();
        brandId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'defaults', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'PILOT', 'defaults-brand', 'Pilot brand', 'ACTIVE')
                """).param("id", brandId).param("tenantId", tenantId).update();
    }

    @Test
    @DisplayName("with no template at any scope, the platform's wording answers, in the customer's language")
    void thePlatformAnswersWhenNobodyWrote() {
        Resolution russian = resolve(
                "ORDER_CONFIRMED", NotificationChannel.SMS, MessageLocale.RU, NotificationClass.TRANSACTIONAL_REQUIRED);
        Resolution uzbek = resolve(
                "ORDER_CONFIRMED",
                NotificationChannel.SMS,
                MessageLocale.UZ_LATN,
                NotificationClass.TRANSACTIONAL_REQUIRED);

        assertThat(russian.isPlatformDefault()).isTrue();
        assertThat(russian.isFound()).isTrue();
        assertThat(russian.template()).as("there is no row").isNull();
        assertThat(wording(russian).body()).startsWith("Заказ {{orderNumber}} принят");
        assertThat(wording(russian).version()).isEqualTo(PlatformDefaultTemplates.VERSION);
        assertThat(wording(uzbek).body()).startsWith("{{orderNumber}}-buyurtma qabul qilindi");
        assertThat(wording(uzbek).locale()).isEqualTo("uz-Latn");
    }

    @Test
    @DisplayName("brand, then tenant, then platform: each layer is what answers once the one above it is gone")
    void theLayersAnswerInOrder() {
        UUID tenantWide = activate(null, "ORDER_CONFIRMED", "tenant wording");
        UUID brandOwn = activate(brandId, "ORDER_CONFIRMED", "brand wording");

        assertThat(bodyOf(resolveRequired())).isEqualTo("brand wording");

        retire(brandOwn);
        assertThat(bodyOf(resolveRequired())).isEqualTo("tenant wording");

        retire(tenantWide);
        Resolution last = resolveRequired();
        assertThat(last.isPlatformDefault())
                .as("only now does the platform's wording answer")
                .isTrue();
    }

    @Test
    @DisplayName("a tenant template that exists but has nothing in any language tried is a visible gap, not a default")
    void aTenantGapIsNotQuietlyPapered() {
        // The brand serves ru and uz-Latn; the tenant's wording is gone from both (a language row removed
        // after activation), the customer reads English, and the registry's fallback is Russian too.
        locale("ru", true);
        locale("uz-Latn", false);
        UUID template = activate(brandId, "ORDER_CONFIRMED", "tenant wording");
        jdbc.sql("DELETE FROM notifications.template_versions WHERE template_id = :id")
                .param("id", template)
                .update();
        jdbc.sql("""
                INSERT INTO notifications.template_versions (
                    id, tenant_id, template_id, version_number, locale, body_template, variables_schema,
                    content_hash, status, approved_by, activated_at)
                VALUES (:id, :tenantId, :templateId, 1, 'en', 'English only', '{}'::jsonb, 'h', 'ACTIVE', 'x', now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("templateId", template)
                .update();

        Resolution resolution = resolve(
                "ORDER_CONFIRMED", NotificationChannel.SMS, MessageLocale.EN, NotificationClass.TRANSACTIONAL_REQUIRED);

        assertThat(resolution.isPlatformDefault())
                .as("the tenant wrote a template and it is incomplete: say so, do not hide it under the platform's")
                .isFalse();
        assertThat(resolution.outcome()).isEqualTo(Resolution.Outcome.NO_TEMPLATE_FOR_LOCALE);
    }

    @Test
    @DisplayName("an operations alert of the same key is never answered with the sentence written for the customer")
    void anOperationsAlertIsNeverAnsweredWithTheCustomersSentence() {
        Resolution alert = resolve(
                "ORDER_CONFIRMED", NotificationChannel.TELEGRAM, MessageLocale.RU, NotificationClass.OPERATIONS_ALERT);

        assertThat(alert.isFound()).isFalse();
        assertThat(alert.outcome()).isEqualTo(Resolution.Outcome.NO_ACTIVE_TEMPLATE);
    }

    @Test
    @DisplayName("a required key asked as an optional message, and the reverse, finds no default")
    void theClassMustBeTheOneThePlatformWroteFor() {
        assertThat(resolve(
                                "ORDER_PREPARING",
                                NotificationChannel.SMS,
                                MessageLocale.RU,
                                NotificationClass.TRANSACTIONAL_REQUIRED)
                        .isFound())
                .isFalse();
        assertThat(resolve(
                                "ORDER_CONFIRMED",
                                NotificationChannel.SMS,
                                MessageLocale.RU,
                                NotificationClass.TRANSACTIONAL_OPTIONAL)
                        .isFound())
                .isFalse();
        assertThat(resolve(
                                "ORDER_PREPARING",
                                NotificationChannel.SMS,
                                MessageLocale.RU,
                                NotificationClass.TRANSACTIONAL_OPTIONAL)
                        .isPlatformDefault())
                .isTrue();
    }

    @Test
    @DisplayName("a channel with no adapter, a key with no default and a caller with no class get no platform wording")
    void whereThePlatformStaysSilent() {
        assertThat(resolve(
                                "ORDER_CONFIRMED",
                                NotificationChannel.EMAIL,
                                MessageLocale.RU,
                                NotificationClass.TRANSACTIONAL_REQUIRED)
                        .isFound())
                .isFalse();
        assertThat(resolve(
                                "ORDER_COMPLETED",
                                NotificationChannel.SMS,
                                MessageLocale.RU,
                                NotificationClass.TRANSACTIONAL_REQUIRED)
                        .isFound())
                .as("the rating prompt stays opt-in (ADR 0075)")
                .isFalse();
        assertThat(resolve(
                                "A_TENANTS_OWN_KEY",
                                NotificationChannel.SMS,
                                MessageLocale.RU,
                                NotificationClass.TRANSACTIONAL_REQUIRED)
                        .isFound())
                .isFalse();
        assertThat(templates
                        .resolve(
                                tenantId,
                                brandId,
                                "ORDER_CONFIRMED",
                                NotificationChannel.SMS,
                                MessageLocale.RU,
                                null,
                                null)
                        .isFound())
                .as("every caller that is not an order message keeps the old answer")
                .isFalse();
    }

    @Test
    @DisplayName("a language the platform has no sentence in falls through the same chain a tenant template does")
    void anUnwrittenLanguageFallsBack() {
        // The customer reads a language the brand does not serve; the brand's default is Uzbek.
        locale("uz-Latn", true);
        locale("ru", false);

        Resolution resolution = resolve(
                "ORDER_CONFIRMED", NotificationChannel.SMS, MessageLocale.EN, NotificationClass.TRANSACTIONAL_REQUIRED);

        assertThat(wording(resolution).locale())
                .as("written to in the brand's default, as ADR 0149 says of a tenant template")
                .isEqualTo("uz-Latn");
    }

    /**
     * ADR 0184, D20.3 and ADR 0020's status addition: the platform's wording answers only when the tenant
     * has <em>no active template at any scope</em> for the key and channel. The resolver used to ask the
     * narrower question «does a template match this order's variant», so a tenant that wrote only a
     * delivery wording, deciding that pickup and dine-in customers should hear nothing, had those customers
     * sent the platform's unreviewed sentence instead.
     */
    @Test
    @DisplayName(
            "a template pinned to another fulfilment mode is a tenant's choice: the platform does not cover the miss")
    void aVariantMissIsNotAnAbsenceOfTemplates() {
        activatePinned(null, FulfillmentMode.DELIVERY, null, "Delivery only");

        Resolution delivery = resolveFor(FulfillmentMode.DELIVERY, SalesChannelSystemType.WEB);
        Resolution pickup = resolveFor(FulfillmentMode.PICKUP, SalesChannelSystemType.WEB);

        assertThat(bodyOf(delivery)).as("the order the tenant wrote it for").isEqualTo("Delivery only");
        assertThat(pickup.isPlatformDefault())
                .as("the tenant has an active template for this key; a pickup order is its decision, not a gap")
                .isFalse();
        assertThat(pickup.outcome()).isEqualTo(Resolution.Outcome.NO_ACTIVE_TEMPLATE);
    }

    @Test
    @DisplayName(
            "a template pinned to another channel source is the same: the miss is the tenant's, not the platform's")
    void aChannelSourceMissIsNotAnAbsenceEither() {
        activatePinned(brandId, null, SalesChannelSystemType.AGGREGATOR, "Aggregator orders only");

        assertThat(resolveFor(FulfillmentMode.DELIVERY, SalesChannelSystemType.WEB)
                        .isPlatformDefault())
                .isFalse();
        assertThat(bodyOf(resolveFor(FulfillmentMode.DELIVERY, SalesChannelSystemType.AGGREGATOR)))
                .isEqualTo("Aggregator orders only");
    }

    @Test
    @DisplayName("only a template that could have answered this brand on this channel counts: a sibling brand's, "
            + "another channel's and a retired one do not")
    void onlyATemplateOfThisBrandAndChannelSilencesTheDefault() {
        UUID sibling = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'SIBLING', 'defaults-sibling', 'Sibling brand', 'ACTIVE')
                """).param("id", sibling).param("tenantId", tenantId).update();
        activateAs(
                sibling,
                sibling,
                "ORDER_CONFIRMED",
                NotificationChannel.SMS,
                FulfillmentMode.DELIVERY,
                null,
                "Sibling");
        activateAs(
                null, brandId, "ORDER_CONFIRMED", NotificationChannel.TELEGRAM, FulfillmentMode.DELIVERY, null, "Chat");
        UUID retired = activatePinned(null, FulfillmentMode.DELIVERY, null, "Retired delivery wording");
        retire(retired);

        assertThat(resolveFor(FulfillmentMode.PICKUP, SalesChannelSystemType.WEB)
                        .isPlatformDefault())
                .as("nothing that was written for this brand's SMS is in force, so the platform answers")
                .isTrue();
    }

    @Test
    @DisplayName(
            "a caller that knows nothing of the order's variant still gets the platform's wording when no template exists")
    void noTemplateAtAllStillGetsTheDefaultForAnOrderOfAnyVariant() {
        assertThat(resolveFor(FulfillmentMode.PICKUP, SalesChannelSystemType.WEB)
                        .isPlatformDefault())
                .isTrue();
    }

    // ------------------------------------------------------------- fixtures

    private Resolution resolveFor(FulfillmentMode fulfillmentMode, SalesChannelSystemType channelSource) {
        return templates.resolve(
                tenantId,
                brandId,
                "ORDER_CONFIRMED",
                NotificationChannel.SMS,
                MessageLocale.RU,
                fulfillmentMode,
                channelSource,
                NotificationClass.TRANSACTIONAL_REQUIRED);
    }

    private UUID activatePinned(
            @Nullable UUID rowBrand,
            @Nullable FulfillmentMode fulfillmentMode,
            @Nullable SalesChannelSystemType channelSource,
            String body) {
        return activateAs(
                rowBrand, brandId, "ORDER_CONFIRMED", NotificationChannel.SMS, fulfillmentMode, channelSource, body);
    }

    /** {@code authorBrand} is the scope the author holds the capability under; {@code rowBrand} is the row's own. */
    private UUID activateAs(
            @Nullable UUID rowBrand,
            UUID authorBrand,
            String key,
            NotificationChannel channel,
            @Nullable FulfillmentMode fulfillmentMode,
            @Nullable SalesChannelSystemType channelSource,
            String body) {
        UUID templateId = templates.createTemplate(
                tenantId,
                rowBrand,
                key,
                NotificationClass.TRANSACTIONAL_REQUIRED,
                channel,
                null,
                fulfillmentMode,
                channelSource);
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        templates
                .requiredLocales(tenantId, authorBrand)
                .forEach(locale -> wordings.put(locale, new Wording(null, body)));
        int version = templates.addVersion(tenantId, authorBrand, templateId, wordings, Map.of());
        templates.activate(tenantId, authorBrand, templateId, version, "tester");
        return templateId;
    }

    private Resolution resolveRequired() {
        return resolve(
                "ORDER_CONFIRMED", NotificationChannel.SMS, MessageLocale.RU, NotificationClass.TRANSACTIONAL_REQUIRED);
    }

    private Resolution resolve(
            String key, NotificationChannel channel, MessageLocale locale, NotificationClass notificationClass) {
        return templates.resolve(tenantId, brandId, key, channel, locale, null, null, notificationClass);
    }

    private String bodyOf(Resolution resolution) {
        return java.util.Objects.requireNonNull(resolution.version()).bodyTemplate();
    }

    private NotificationTemplateService.PlatformWording wording(Resolution resolution) {
        return java.util.Objects.requireNonNull(resolution.platformDefault());
    }

    private UUID activate(@Nullable UUID brand, String key, String body) {
        UUID templateId = templates.createTemplate(
                tenantId, brand, key, NotificationClass.TRANSACTIONAL_REQUIRED, NotificationChannel.SMS, null);
        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        templates.requiredLocales(tenantId, brandId).forEach(locale -> wordings.put(locale, new Wording(null, body)));
        int version = templates.addVersion(tenantId, brandId, templateId, wordings, Map.of());
        templates.activate(tenantId, brandId, templateId, version, "tester");
        return templateId;
    }

    private void retire(UUID templateId) {
        jdbc.sql("UPDATE notifications.templates SET status = 'RETIRED' WHERE id = :id")
                .param("id", templateId)
                .update();
    }

    private void locale(String tag, boolean isDefault) {
        jdbc.sql("""
                INSERT INTO tenant.brand_locales (tenant_id, brand_id, locale, is_default)
                VALUES (:tenantId, :brandId, :locale, :isDefault)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locale", tag)
                .param("isDefault", isDefault)
                .update();
    }
}
