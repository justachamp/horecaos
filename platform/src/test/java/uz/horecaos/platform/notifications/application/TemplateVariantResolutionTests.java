package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
import uz.horecaos.platform.notifications.application.NotificationTemplateService.Wording;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore.TemplateRow;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;

/**
 * Gap-map row {@code 10.9a}: one template key can now have variants by
 * fulfilment mode and channel source, with the most specific variant
 * resolving at send time and a null-dimension row (a wildcard) as the
 * fallback. Against the migrated schema, so {@code V0392}'s widened
 * {@code ux_template_variant} index and {@code JdbcTemplateStore.activeTemplate}'s
 * specificity ordering are the real ones.
 */
class TemplateVariantResolutionTests {

    private static final Instant NOW = Instant.parse("2026-09-23T09:00:00Z");

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
                AuditTrail.actor("template-author"));

        tenantId = UUID.randomUUID();
        brandId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'variants', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'PILOT', 'variants-brand', 'Pilot brand', 'ACTIVE')
                """).param("id", brandId).param("tenantId", tenantId).update();
    }

    @Test
    @DisplayName("a fulfilment-mode variant wins over the tenant's wildcard default for a matching order")
    void aFulfilmentVariantWinsOverTheWildcardDefault() {
        activate(null, FulfillmentMode.DINE_IN, null, "Any order");
        activate(null, null, null, "Wildcard");
        // ^ order deliberately mixed: creation order must not decide the winner.

        assertBody(resolve(FulfillmentMode.DINE_IN, SalesChannelSystemType.QR_TABLE), "Any order");
        assertBody(resolve(FulfillmentMode.DELIVERY, SalesChannelSystemType.WEB), "Wildcard");
    }

    @Test
    @DisplayName("a variant naming both dimensions outranks a variant naming only one")
    void aTwoDimensionVariantOutranksAOneDimensionVariant() {
        activate(null, null, null, "Wildcard");
        activate(null, FulfillmentMode.DELIVERY, null, "Any delivery");
        activate(null, FulfillmentMode.DELIVERY, SalesChannelSystemType.AGGREGATOR, "Aggregator delivery");

        assertBody(resolve(FulfillmentMode.DELIVERY, SalesChannelSystemType.AGGREGATOR), "Aggregator delivery");
        assertBody(resolve(FulfillmentMode.DELIVERY, SalesChannelSystemType.WEB), "Any delivery");
        assertBody(resolve(FulfillmentMode.PICKUP, SalesChannelSystemType.AGGREGATOR), "Wildcard");
    }

    @Test
    @DisplayName(
            "of two equally-specific single-dimension variants, the fulfilment-mode one wins over the channel-source one")
    void aFulfilmentOnlyVariantOutranksAChannelOnlyVariantOfEqualSpecificity() {
        activate(null, null, SalesChannelSystemType.WEB, "Storefront wording");
        activate(null, FulfillmentMode.DELIVERY, null, "Delivery wording");
        // ^ order deliberately mixed: creation order must not decide the winner.

        // A delivery order placed on the storefront matches both rows (each pins down exactly one of
        // the two dimensions), so this is a genuine tie on how many variant dimensions are pinned.
        // ADR 0184, D20 decides it: fulfilment mode outranks channel source. The ORDER BY lists
        // fulfillment_mode before channel_source for that reason, no longer by accident of column
        // order; this test is the other half of the decision.
        assertBody(resolve(FulfillmentMode.DELIVERY, SalesChannelSystemType.WEB), "Delivery wording");
    }

    @Test
    @DisplayName("ADR 0184 D20: a brand-wide generic template beats a tenant-wide template pinned on both dimensions")
    void aBrandWideGenericTemplateBeatsATenantWideTemplatePinnedOnBothDimensions() {
        activate(null, FulfillmentMode.DELIVERY, SalesChannelSystemType.WEB, "Tenant delivery on the storefront");
        activate(brandId, null, null, "Brand generic");
        // ^ the case no earlier test pinned. The old ORDER BY summed brand_id as one more dimension, so
        // the tenant row (two pinned dimensions) scored 2 against the brand row's 1 and the brand's
        // own override was silently ignored for exactly the orders the tenant had pinned.

        assertBody(resolve(FulfillmentMode.DELIVERY, SalesChannelSystemType.WEB), "Brand generic");
        assertBody(resolve(FulfillmentMode.PICKUP, SalesChannelSystemType.WEB), "Brand generic");
    }

    @Test
    @DisplayName(
            "ADR 0184 D20: scope first, then how many dimensions are pinned, then fulfilment mode over channel source")
    void scopeOutranksVariantAndVariantOutranksNothingElse() {
        // Every row of the lattice that matches a delivery order on the storefront, created in an order
        // that is the reverse of the order they should win in.
        activate(null, null, null, "tenant, no dimension");
        activate(null, null, SalesChannelSystemType.WEB, "tenant, channel");
        activate(null, FulfillmentMode.DELIVERY, null, "tenant, mode");
        activate(null, FulfillmentMode.DELIVERY, SalesChannelSystemType.WEB, "tenant, mode and channel");
        activate(brandId, null, null, "brand, no dimension");
        activate(brandId, null, SalesChannelSystemType.WEB, "brand, channel");
        activate(brandId, FulfillmentMode.DELIVERY, null, "brand, mode");
        activate(brandId, FulfillmentMode.DELIVERY, SalesChannelSystemType.WEB, "brand, mode and channel");

        // Each step knocks out the winner and the next one must take over. Retiring is done through the
        // template status because a template cannot be deleted; a RETIRED row never resolves.
        java.util.List<String> expectedOrder = java.util.List.of(
                "brand, mode and channel",
                "brand, mode",
                "brand, channel",
                "brand, no dimension",
                "tenant, mode and channel",
                "tenant, mode",
                "tenant, channel",
                "tenant, no dimension");
        for (String expected : expectedOrder) {
            assertBody(resolve(FulfillmentMode.DELIVERY, SalesChannelSystemType.WEB), expected);
            retire(expected);
        }
    }

    @Test
    @DisplayName("a brand override still outranks a same-specificity tenant-wide variant")
    void aBrandOverrideOutranksATenantWideVariantOfEqualSpecificity() {
        activate(null, FulfillmentMode.DINE_IN, null, "Tenant dine-in");
        activate(brandId, null, null, "Brand default");

        // Both rows match exactly one dimension (brand-only vs fulfilment-only):
        // the brand override is the documented tiebreak.
        assertBody(resolve(FulfillmentMode.DINE_IN, SalesChannelSystemType.QR_TABLE), "Brand default");
    }

    @Test
    @DisplayName("a brand-scoped variant beats every less specific row")
    void aBrandScopedVariantBeatsEveryLessSpecificRow() {
        activate(null, null, null, "Tenant wildcard");
        activate(brandId, null, null, "Brand default");
        activate(brandId, FulfillmentMode.DINE_IN, null, "Brand dine-in");

        assertBody(resolve(FulfillmentMode.DINE_IN, SalesChannelSystemType.QR_TABLE), "Brand dine-in");
        assertBody(resolve(FulfillmentMode.DELIVERY, SalesChannelSystemType.WEB), "Brand default");
    }

    @Test
    @DisplayName("a message with no known fulfilment mode or channel resolves only the wildcard row, never a variant")
    void unknownDimensionsMatchOnlyTheWildcard() {
        activate(null, FulfillmentMode.DINE_IN, null, "Dine-in variant");
        activate(null, null, null, "Wildcard");

        // The four-argument overload is what every pre-10.9a caller (and
        // CampaignTelegramDeliveryService today) still uses — proving it keeps
        // resolving to the wildcard row confirms the delegation, not only the
        // six-argument method's own null handling.
        java.util.Optional<TemplateRow> found =
                new JdbcTemplateStore(jdbc).activeTemplate(tenantId, brandId, "ORDER_CONFIRMED", "SMS");
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().isVariant())
                .as("an unscoped caller must never land on a variant it did not ask for")
                .isFalse();
    }

    // ------------------------------------------------------------- fixtures

    private void activate(
            @Nullable UUID brand,
            @Nullable FulfillmentMode fulfillmentMode,
            @Nullable SalesChannelSystemType channelSource,
            String body) {
        UUID templateId = templates.createTemplate(
                tenantId,
                brand,
                "ORDER_CONFIRMED",
                NotificationClass.TRANSACTIONAL_REQUIRED,
                NotificationChannel.SMS,
                null,
                fulfillmentMode,
                channelSource);

        Map<MessageLocale, Wording> wordings = new LinkedHashMap<>();
        for (MessageLocale locale : MessageLocale.required()) {
            wordings.put(locale, new Wording(null, body));
        }
        // addVersion/activate take the caller's own authorisation scope, not
        // the row's — a tenant-wide row (brand null at creation) is still
        // authored under a real brand's own capability, the same way
        // requireOwnedByBrand's own doc explains a null-owned template is
        // visible to every brand. `brandId` (the fixture's fixed field) is
        // that scope for every row this test creates, whatever `brand` above
        // named as the row's own scope.
        int versionNumber = templates.addVersion(tenantId, brandId, templateId, wordings, Map.of());
        templates.activate(tenantId, brandId, templateId, versionNumber, "tester");
    }

    /** Takes the template that carries this body out of resolution, to see which one wins next. */
    private void retire(String body) {
        int updated =
                jdbc.sql("""
                UPDATE notifications.templates t SET status = 'RETIRED'
                WHERE t.tenant_id = :tenantId
                  AND EXISTS (SELECT 1 FROM notifications.template_versions v
                               WHERE v.tenant_id = t.tenant_id AND v.template_id = t.id
                                 AND v.body_template = :body)
                """).param("tenantId", tenantId).param("body", body).update();
        assertThat(updated).as("exactly the template that carries '%s'", body).isEqualTo(1);
    }

    private NotificationTemplateService.Resolution resolve(
            FulfillmentMode fulfillmentMode, SalesChannelSystemType channelSource) {
        return templates.resolve(
                tenantId,
                brandId,
                "ORDER_CONFIRMED",
                NotificationChannel.SMS,
                MessageLocale.RU,
                fulfillmentMode,
                channelSource);
    }

    private void assertBody(NotificationTemplateService.Resolution resolution, String expectedBody) {
        var version = resolution.version();
        assertThat(version).isNotNull();
        assertThat(Objects.requireNonNull(version).bodyTemplate()).isEqualTo(expectedBody);
    }
}
