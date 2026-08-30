package uz.horecaos.platform.tenancy.application.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
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
import uz.horecaos.platform.fulfillment.application.DeliveryTariffService;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService;
import uz.horecaos.platform.fulfillment.domain.VersionStatus;
import uz.horecaos.platform.fulfillment.domain.tariff.DeliveryTariff;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceMode;
import uz.horecaos.platform.fulfillment.domain.tariff.FeeSource;
import uz.horecaos.platform.fulfillment.domain.zone.ZoneRole;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore;
import uz.horecaos.platform.media.api.MediaAvailability;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcMediaAssetStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler.StepResult;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcLegalEntityStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTenantControlPlaneStore;

/**
 * ADR 0008: each of the six ADR 0008 step handlers that live in {@link
 * OnboardingStepHandlers} (everything except {@code ACTIVATION_SMOKE_TEST},
 * tested next to its own handler in {@code ordering}), both passing and
 * failing on a fixture shaped like production data.
 *
 * <p>Handlers are constructed directly against a real database, the same way
 * {@code IdentityDriftReporterTests} tests one component in isolation, rather
 * than driven through {@link OnboardingService}: what is under test here is
 * each handler's own business rule, not the workflow machinery that
 * {@code OnboardingServiceTests} already covers.
 */
class OnboardingStepHandlersTests {

    private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 21);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcTenantControlPlaneStore tenants;
    private UUID tenantId;
    private UUID brandId;
    private UUID locationId;
    private UUID channelId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for these tests");
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
        jdbc.sql("TRUNCATE TABLE payments.merchant_bindings CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.bindings, integration.installations, "
                        + "integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE fulfillment.zone_location_bindings, fulfillment.location_tariff_bindings, "
                        + "fulfillment.service_zone_versions, fulfillment.service_zones, "
                        + "fulfillment.delivery_tariff_versions, fulfillment.delivery_tariffs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.media_relations, catalog.publications, catalog.location_offerings, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE media.assets CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.location_fiscal_assignments, tenant.legal_entities CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.channel_payment_methods, tenant.channel_fulfillment_modes, "
                        + "tenant.sales_channel_locations, tenant.sales_channels CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        tenants = new JdbcTenantControlPlaneStore(jdbc);
        tenantId = UUID.randomUUID();
        brandId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        insertTenant();
        insertBrand();
        insertLocation();
        channelId = insertChannel("STOREFRONT", "WEB");
        bindChannelToLocation(channelId, locationId);
    }

    // ----------------------------------------------------- PAYMENT_CONFIGURATION_VALIDATE

    @Test
    void paymentConfigurationFailsWithoutALegalEntity() {
        StepResult result = paymentHandler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("NO_LEGAL_ENTITY");
    }

    @Test
    void paymentConfigurationPassesForACashOnlyTenantWithNoMerchantBinding() {
        insertLegalEntity("ACME", "ACTIVE");
        enablePaymentMethod(channelId, "CASH");

        StepResult result = paymentHandler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void paymentConfigurationFailsWhenANonCashMethodHasNoMerchantBinding() {
        insertLegalEntity("ACME", "ACTIVE");
        enablePaymentMethod(channelId, "CLICK");

        StepResult result = paymentHandler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("NO_MERCHANT_BINDING");
        assertThat(result.detail()).contains("CLICK");
    }

    @Test
    void paymentConfigurationPassesWhenTheMerchantBindingExists() {
        UUID legalEntityId = insertLegalEntity("ACME", "ACTIVE");
        enablePaymentMethod(channelId, "CLICK");
        insertMerchantBinding(legalEntityId, "CLICK", TODAY.minusDays(1));

        StepResult result = paymentHandler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void paymentConfigurationIgnoresMarketplaceAndDoesNotRequireABinding() {
        insertLegalEntity("ACME", "ACTIVE");
        enablePaymentMethod(channelId, "MARKETPLACE");

        StepResult result = paymentHandler().execute(context());

        assertThat(result.outcome())
                .as("the aggregator collects the money; HorecaOS never needs a merchant account for it")
                .isEqualTo(StepResult.Outcome.COMPLETED);
    }

    private OnboardingStepHandlers.PaymentConfigurationValidate paymentHandler() {
        return new OnboardingStepHandlers.PaymentConfigurationValidate(
                tenants, new JdbcLegalEntityStore(jdbc), jdbc, CLOCK);
    }

    // ---------------------------------------------------- DELIVERY_CONFIGURATION_VALIDATE

    @Test
    void deliveryConfigurationPassesForAPickupOnlyTenant() {
        enableFulfillmentMode(channelId, "PICKUP");

        StepResult result = deliveryHandler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void deliveryConfigurationFailsWhenDeliveryIsOfferedWithNoZoneBound() {
        enableFulfillmentMode(channelId, "DELIVERY");

        StepResult result = deliveryHandler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("NO_DELIVERY_ZONE");
    }

    @Test
    void deliveryConfigurationFailsWhenTheBoundZoneNamesNoTariffAndNoneResolves() {
        enableFulfillmentMode(channelId, "DELIVERY");
        giveLocationCoordinates(locationId, 41.311081, 69.240562);
        activeDeliveryZone(null);

        StepResult result = deliveryHandler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("NO_DELIVERY_TARIFF");
    }

    @Test
    void deliveryConfigurationPassesWhenTheZoneResolvesItsOwnTariff() {
        enableFulfillmentMode(channelId, "DELIVERY");
        giveLocationCoordinates(locationId, 41.311081, 69.240562);
        UUID tariffId = seedFlatTariff("FLAT", 10_000L);
        activeDeliveryZone(tariffId);

        StepResult result = deliveryHandler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    private OnboardingStepHandlers.DeliveryConfigurationValidate deliveryHandler() {
        return new OnboardingStepHandlers.DeliveryConfigurationValidate(tenants, jdbc, CLOCK);
    }

    // --------------------------------------------------------------- POS_BINDINGS_VALIDATE

    @Test
    void posBindingsPassesWhenNoneIsConfigured() {
        StepResult result = posHandler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void posBindingsFailsWhenAConfiguredBindingIsNotHealthy() {
        insertPosBinding("ACTIVE", "ACTIVE", "FAILED");

        StepResult result = posHandler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("POS_BINDING_UNHEALTHY");
    }

    @Test
    void posBindingsPassesWhenAConfiguredBindingIsHealthy() {
        insertPosBinding("ACTIVE", "ACTIVE", "SUCCEEDED");

        StepResult result = posHandler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    private OnboardingStepHandlers.PosBindingsValidate posHandler() {
        return new OnboardingStepHandlers.PosBindingsValidate(jdbc);
    }

    // ---------------------------------------------------------- CATALOG_READINESS_VALIDATE

    @Test
    void catalogReadinessFailsWithNoPublication() {
        StepResult result = catalogHandler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("NO_PUBLISHED_MENU");
    }

    @Test
    void catalogReadinessFailsWhenPublishedWithNoAvailableItem() {
        UUID catalogId = insertCatalog();
        insertPublication(catalogId, "STOREFRONT");

        StepResult result = catalogHandler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("NO_AVAILABLE_ITEM");
    }

    @Test
    void catalogReadinessPassesWithAPublishedAvailableItem() {
        UUID catalogId = insertCatalog();
        UUID variantId = insertProductAndVariant(catalogId, "BURGER");
        insertPublication(catalogId, "STOREFRONT");
        insertLocationOffering(variantId, "AVAILABLE");

        StepResult result = catalogHandler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    private OnboardingStepHandlers.CatalogReadinessValidate catalogHandler() {
        return new OnboardingStepHandlers.CatalogReadinessValidate(tenants, jdbc);
    }

    // ------------------------------------------------------------ MEDIA_READINESS_VALIDATE

    @Test
    void mediaReadinessPassesWithNoMediaReferencedAtAll() {
        StepResult result = mediaHandler().execute(context());

        assertThat(result.outcome())
                .as("media is optional in v1; nothing referenced is nothing to fail on")
                .isEqualTo(StepResult.Outcome.COMPLETED);
        assertThat(result.result()).containsEntry("referenced", 0);
    }

    @Test
    void mediaReadinessFailsWhenAReferencedAssetIsNotAvailable() {
        UUID assetId = insertMediaAsset("PENDING_UPLOAD");
        referenceMediaAsset(assetId);

        StepResult result = mediaHandler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("MEDIA_NOT_AVAILABLE");
    }

    @Test
    void mediaReadinessPassesWhenTheReferencedAssetIsAvailable() {
        UUID assetId = insertMediaAsset("AVAILABLE");
        referenceMediaAsset(assetId);

        StepResult result = mediaHandler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    private OnboardingStepHandlers.MediaReadinessValidate mediaHandler() {
        JdbcMediaAssetStore store = new JdbcMediaAssetStore(jdbc);
        MediaAvailability media = (tid, assetIds) -> assetIds.stream()
                .allMatch(id -> store.findOwned(tid, id)
                        .map(asset -> asset.status().isDisplayable())
                        .orElse(false));
        return new OnboardingStepHandlers.MediaReadinessValidate(jdbc, media);
    }

    // ------------------------------------------------------------ FRONTEND_DOMAIN_VALIDATE

    @Test
    void frontendDomainAlwaysPassesTodayBecauseNoTenantCanRequestOne() {
        StepResult result = new OnboardingStepHandlers.FrontendDomainValidate().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    // --------------------------------------------------------------------------- fixtures

    private OnboardingStepHandler.StepContext context() {
        return new OnboardingStepHandler.StepContext(UUID.randomUUID(), tenantId, Map.of(), null, 1);
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "t-" + tenantId.toString().substring(0, 8))
                .update();
    }

    private void insertBrand() {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("slug", "b-" + brandId.toString().substring(0, 8))
                .update();
    }

    private void insertLocation() {
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', :slug, 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("slug", "l-" + locationId.toString().substring(0, 8))
                .update();
    }

    private UUID insertChannel(String code, String systemType) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, :code, :systemType, :code, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("systemType", systemType)
                .update();
        return id;
    }

    private void bindChannelToLocation(UUID channelId, UUID locationId) {
        jdbc.sql("""
                INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
                VALUES (:tenantId, :channelId, :locationId, 'ACTIVE')
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("locationId", locationId)
                .update();
    }

    private void enablePaymentMethod(UUID channelId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.channel_payment_methods (tenant_id, channel_id, payment_method_code, enabled)
                VALUES (:tenantId, :channelId, :code, true)
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("code", code)
                .update();
    }

    private void enableFulfillmentMode(UUID channelId, String mode) {
        jdbc.sql("""
                INSERT INTO tenant.channel_fulfillment_modes (tenant_id, channel_id, fulfillment_mode, enabled)
                VALUES (:tenantId, :channelId, :mode, true)
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("mode", mode)
                .update();
    }

    private UUID insertLegalEntity(String code, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, vat_registered, status)
                VALUES (:id, :tenantId, :code, :legalName, '123456789', false, :status)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("legalName", code + " LLC")
                .param("status", status)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.location_fiscal_assignments
                    (id, tenant_id, brand_id, location_id, legal_entity_id, effective_from, approved_by)
                VALUES (:id, :tenantId, :brandId, :locationId, :legalEntityId, :from, 'test')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("legalEntityId", id)
                .param("from", TODAY.minusYears(1))
                .update();
        return id;
    }

    private void insertMerchantBinding(UUID legalEntityId, String providerType, LocalDate effectiveFrom) {
        String envCode = "env-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'PAYMENT', :providerType, 'https://example.test', false, 'example.test')
                """).param("code", envCode).param("providerType", providerType).update();

        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :tenantId, 'PAYMENT', :providerType, :env, :name, 'ACTIVE')
                """)
                .param("id", installationId)
                .param("tenantId", tenantId)
                .param("providerType", providerType)
                .param("env", envCode)
                .param("name", providerType + " installation")
                .update();

        UUID bindingId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", bindingId)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .param("brandId", brandId)
                .update();

        String segment = "seg-" + UUID.randomUUID().toString().substring(0, 10);
        jdbc.sql("""
                INSERT INTO payments.merchant_bindings
                    (id, tenant_id, legal_entity_id, provider_type, installation_id, binding_id,
                     merchant_account_reference, secret_reference, callback_path_segment,
                     supports_reversal, supports_partner_fiscalization, status, effective_from)
                VALUES (:id, :tenantId, :legalEntityId, :providerType, :installationId, :bindingId,
                        'acct-1', :secretRef, :segment, true, true, 'ACTIVE', :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("legalEntityId", legalEntityId)
                .param("providerType", providerType)
                .param("installationId", installationId)
                .param("bindingId", bindingId)
                .param("secretRef", "horecaos:test:provider_payment:tenant:" + providerType.toLowerCase(Locale.ROOT))
                .param("segment", segment)
                .param("from", effectiveFrom)
                .update();
    }

    private void insertPosBinding(String bindingStatus, String installationStatus, String lastConnectionStatus) {
        String envCode = "pos-env-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'POS', 'CLOPOS', 'https://example.test', false, 'example.test')
                """).param("code", envCode).update();
        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name,
                     status, last_connection_status)
                VALUES (:id, :tenantId, 'POS', 'CLOPOS', :env, 'POS', :status, :lastConnection)
                """)
                .param("id", installationId)
                .param("tenantId", tenantId)
                .param("env", envCode)
                .param("status", installationStatus)
                .param("lastConnection", lastConnectionStatus)
                .update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, :status)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .param("brandId", brandId)
                .param("status", bindingStatus)
                .update();
    }

    private UUID insertCatalog() {
        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();
        return catalogId;
    }

    private UUID insertProductAndVariant(UUID catalogId, String code) {
        UUID productId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .update();
        UUID variantId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status)
                VALUES (:id, :tenantId, :brandId, :productId, :sku, 'ACTIVE')
                """)
                .param("id", variantId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .param("sku", "SKU-" + code)
                .update();
        return variantId;
    }

    private void insertPublication(UUID catalogId, String channel) {
        jdbc.sql("""
                INSERT INTO catalog.publications
                    (id, tenant_id, brand_id, catalog_id, channel, status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, :channel, 'PUBLISHED', 'hash', now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId)
                .param("channel", channel)
                .update();
    }

    private void insertLocationOffering(UUID variantId, String status) {
        jdbc.sql("""
                INSERT INTO catalog.location_offerings (id, tenant_id, brand_id, location_id, variant_id, status)
                VALUES (:id, :tenantId, :brandId, :locationId, :variantId, :status)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("variantId", variantId)
                .param("status", status)
                .update();
    }

    private UUID insertMediaAsset(String status) {
        UUID assetId = UUID.randomUUID();
        boolean available = "AVAILABLE".equals(status);
        jdbc.sql("""
                INSERT INTO media.assets
                    (asset_id, tenant_id, owner_scope, owner_id, object_key, bucket,
                     status, visibility, declared_content_type, declared_size_bytes,
                     verified_content_type, verified_size_bytes, verified_checksum_sha256)
                VALUES (:id, :tenantId, 'BRAND', :brandId, :key, 'test-bucket', :status, 'PUBLIC',
                        'image/jpeg', 1000, :verifiedType, :verifiedSize, :verifiedChecksum)
                """)
                .param("id", assetId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("key", "tenants/" + tenantId + "/brand/" + assetId)
                .param("status", status)
                // ck_media_asset_verified: AVAILABLE requires the verified facts to be set.
                .param("verifiedType", available ? "image/jpeg" : null)
                .param("verifiedSize", available ? 1000L : null)
                .param("verifiedChecksum", available ? "0".repeat(64) : null)
                .update();
        return assetId;
    }

    private void referenceMediaAsset(UUID assetId) {
        jdbc.sql("""
                INSERT INTO catalog.media_relations (tenant_id, brand_id, entity_type, entity_id, media_asset_id, role)
                VALUES (:tenantId, :brandId, 'PRODUCT', :entityId, :assetId, 'PRIMARY')
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("entityId", UUID.randomUUID())
                .param("assetId", assetId)
                .update();
    }

    private void giveLocationCoordinates(UUID locationId, double lat, double lon) {
        jdbc.sql("""
                UPDATE tenant.locations
                   SET latitude = :lat, longitude = :lon, coordinate_source = 'MERCHANT_PIN'
                 WHERE id = :id
                """)
                .param("id", locationId)
                .param("lat", lat)
                .param("lon", lon)
                .update();
    }

    /** Creates, activates and binds a DELIVERY zone at {@code locationId}, naming {@code tariffId} or none. */
    private void activeDeliveryZone(UUID tariffId) {
        var zoneStore = new JdbcServiceZoneStore(jdbc);
        var zones = new ServiceZoneService(zoneStore, JsonMapper.builder().build(), CLOCK);
        UUID actor = UUID.randomUUID();
        UUID zoneId = zones.createZone(tenantId, brandId, ZoneRole.DELIVERY, "ZONE1", "Zone", "Zone", "Zone");
        var drafted = zones.draftCircleVersion(
                new ServiceZoneService.NewVersion(
                        tenantId, brandId, zoneId, ZoneRole.DELIVERY, null, 100, "UZS", tariffId, null, null, actor),
                locationId,
                5_000);
        zones.activate(tenantId, brandId, zoneId, drafted.version(), actor);
        zones.bindLocation(tenantId, brandId, zoneId, locationId);
    }

    /** A flat-fee tariff, activated and ready to be named by a zone. */
    private UUID seedFlatTariff(String code, long feeMinor) {
        var tariffStore = new JdbcDeliveryTariffStore(jdbc);
        var tariffs = new DeliveryTariffService(tariffStore, CLOCK);
        UUID actor = UUID.randomUUID();
        UUID tariffId = tariffs.createTariff(tenantId, brandId, code, code, false);
        var drafted = tariffs.draftVersion(
                tenantId,
                brandId,
                new DeliveryTariff(
                        tariffId,
                        0,
                        VersionStatus.DRAFT,
                        "UZS",
                        FeeSource.TARIFF,
                        DistanceMode.RADIUS,
                        13_000,
                        null,
                        15_000,
                        0L,
                        40_000L,
                        List.of(new uz.horecaos.platform.fulfillment.domain.tariff.TariffBand(
                                0, 0, 15_000, feeMinor, 0L)),
                        List.of()),
                actor);
        tariffs.activate(tenantId, brandId, tariffId, drafted.version(), actor);
        return tariffId;
    }
}
