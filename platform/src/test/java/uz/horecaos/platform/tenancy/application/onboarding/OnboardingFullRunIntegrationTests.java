package uz.horecaos.platform.tenancy.application.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.fulfillment.application.DeliveryFeeResolver;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore;
import uz.horecaos.platform.iam.api.grants.TenantOwnerAuthorityGrantor;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner;
import uz.horecaos.platform.iam.application.GrantManagementService;
import uz.horecaos.platform.iam.application.TenantOwnerAuthorityGrantorAdapter;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcMediaAssetStore;
import uz.horecaos.platform.ordering.application.onboarding.OrderingOnboardingStepHandlers;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCatalogPricingContext;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler;
import uz.horecaos.platform.tenancy.application.ServiceabilityService;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcLegalEntityStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcServiceabilityStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTenantControlPlaneStore;

/**
 * ADR 0008 end to end: a tenant with a realistic, minimal-but-complete
 * configuration (cash-only, pickup-only, one published item, no media, no
 * POS, no custom domain) drains through every one of the eleven buildable
 * steps to {@code READY} — the state where only {@code TENANT_ACTIVATE},
 * which waits on a platform administrator by design, remains.
 *
 * <p>Every handler here is the real production class, wired against a real
 * database exactly as {@link uz.horecaos.platform.pricing.QuoteAndReservationTests}
 * and {@code DeliveryFeeResolutionTests} wire their own subjects. Only {@link
 * OrganizationProvisioner} is a fake, because no Keycloak runs in this suite —
 * ADR 0009's own adapter is proved against a real one elsewhere. The ADR 0025
 * grant {@link TenantOwnerAuthorityGrantorAdapter} makes is real, not faked: it
 * is exactly the missing half of ADR 0009 this build closes, and the
 * assertion that a {@code tenant-owner} row actually lands in {@code
 * iam.grants} is the point of driving this run for real rather than with a
 * hand-picked subset of handlers.
 */
class OnboardingFullRunIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String CHANNEL_CODE = "STOREFRONT";
    private static final ActorRef ADMIN = ActorRef.user("platform-admin-1", "Platform Admin");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private TransactionTemplate transactions;
    private OnboardingService service;
    private UUID tenantId;
    private UUID brandId;
    private UUID locationId;
    private UUID templateId;

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
        jdbc.sql("TRUNCATE TABLE tenant.onboarding_runs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.onboarding_templates CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.approval_policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quote_adjustments, pricing.quote_lines, pricing.quotes, "
                        + "pricing.prices, pricing.price_book_assignments, pricing.price_books, "
                        + "pricing.tax_profiles CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, "
                        + "catalog.location_offerings, catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.location_fiscal_assignments, tenant.legal_entities CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.channel_payment_methods, tenant.channel_fulfillment_modes, "
                        + "tenant.sales_channel_locations, tenant.sales_channels CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        // Run after every TRUNCATE ... CASCADE above, not before: iam.roles has
        // a foreign key onto tenant.tenants (nullable, for a tenant-defined
        // role), so truncating tenant.tenants CASCADE truncates the whole of
        // iam.roles with it — platform rows included, despite their tenant_id
        // being NULL. Production never truncates tables, so this ordering
        // constraint is a test-only artefact, not a production concern.
        // RoleRegistrySynchronizer itself runs once at Spring Boot startup
        // (it implements ApplicationRunner); grants have carried a real foreign
        // key onto iam.roles since V0089, so nothing here can reference
        // `tenant-owner` until the code-owned PlatformRole registry is
        // projected into it.
        new RoleRegistrySynchronizer(jdbc).synchronize();

        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        service = new OnboardingService(
                jdbc,
                transactions,
                allElevenHandlers(),
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                new JdbcApprovalService(
                        jdbc,
                        new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                        CLOCK,
                        new SimpleMeterRegistry()),
                event -> {},
                JsonMapper.builder().build(),
                CLOCK);

        tenantId = UUID.randomUUID();
        brandId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        templateId = UUID.randomUUID();
        seedARealisticTenant();
    }

    @Test
    void aRealisticCashOnlyPickupOnlyTenantReachesReadyOnEveryStep() {
        UUID runId = service.startRun(tenantId, templateId, 1, Map.of("ownerEmail", "owner@acme.example"), ADMIN);

        drain(runId);

        var steps = jdbc.sql("""
                SELECT step_key, status FROM tenant.onboarding_steps
                 WHERE run_id = :runId ORDER BY sequence_number
                """)
                .param("runId", runId)
                .query((rs, n) -> Map.entry(rs.getString("step_key"), rs.getString("status")))
                .list();

        assertThat(steps)
                .as("every step but TENANT_ACTIVATE — parked awaiting platform approval by design — completes")
                .filteredOn(entry -> !entry.getKey().equals("TENANT_ACTIVATE"))
                .allSatisfy(entry -> assertThat(entry.getValue())
                        .as(entry.getKey() + " last_error: " + lastErrorOf(runId, entry.getKey()))
                        .isEqualTo("COMPLETED"));

        assertThat(steps)
                .filteredOn(entry -> entry.getKey().equals("TENANT_ACTIVATE"))
                .extracting(Map.Entry::getValue)
                .containsExactly("PENDING");

        assertThat(service.outstandingRequiredSteps(runId)).isEmpty();
        assertThat(runStatus(runId))
                .as("READY: every required step done, awaiting only the platform administrator's activation")
                .isEqualTo("READY");

        // The ADR 0009 gap this build closes: the linked owner actually holds
        // platform-side authority, not just Keycloak organization membership.
        assertThat(jdbc.sql("""
                SELECT r.code FROM iam.grants g JOIN iam.roles r ON r.id = g.role_id
                 WHERE g.tenant_id = :tenantId AND g.principal_subject = 'owner-subject-1' AND g.status = 'ACTIVE'
                """).param("tenantId", tenantId).query(String.class).list())
                .as("TENANT_OWNER_LINK_OR_INVITE must grant the linked subject tenant-owner authority")
                .containsExactly("tenant-owner");
    }

    private String lastErrorOf(UUID runId, String stepKey) {
        return jdbc.sql("""
                SELECT coalesce(last_error_code, '') || ': ' || coalesce(last_error, '')
                  FROM tenant.onboarding_steps WHERE run_id = :runId AND step_key = :stepKey
                """)
                .param("runId", runId)
                .param("stepKey", stepKey)
                .query(String.class)
                .single();
    }

    private void drain(UUID runId) {
        for (int guard = 0; guard < 60 && service.runNextStep(runId); guard++) {
            // Fixed clock: nothing here is expected to retry.
        }
    }

    private String runStatus(UUID runId) {
        return jdbc.sql("SELECT status FROM tenant.onboarding_runs WHERE id = :id")
                .param("id", runId)
                .query(String.class)
                .single();
    }

    // --------------------------------------------------------------------- the handler graph

    private List<OnboardingStepHandler> allElevenHandlers() {
        var tenants = new JdbcTenantControlPlaneStore(jdbc);
        var provisioner = new FakeOrganizationProvisioner();

        var currentActor = new uz.horecaos.platform.iam.api.CurrentActor() {
            @Override
            public uz.horecaos.platform.iam.api.AuthenticatedActor get() {
                // Never reached: grantSystemInitiated skips the interactive
                // authorization gate on purpose — see GrantManagementService's
                // own javadoc for why a background workflow cannot hold one.
                throw new UnsupportedOperationException("no interactive actor in a background workflow");
            }
        };
        var authorizationService = new JdbcAuthorizationService(jdbc, CLOCK, currentActor);
        var grantManagement =
                new GrantManagementService(jdbc, authorizationService, authorizationService, event -> {}, CLOCK);
        TenantOwnerAuthorityGrantor authority = new TenantOwnerAuthorityGrantorAdapter(grantManagement);

        var channels = new JdbcSalesChannelStore(jdbc);
        var serviceability = new ServiceabilityService(new JdbcServiceabilityStore(jdbc), CLOCK);
        var deliveryFees = new DeliveryFeeResolver(
                new JdbcServiceZoneStore(jdbc),
                new JdbcDeliveryTariffStore(jdbc),
                new JdbcDeliveryFeeResolutionStore(jdbc, JsonMapper.builder().build()),
                (origin, destination, installationId) -> Optional.empty(),
                new SimpleMeterRegistry());
        var pricing = new QuoteService(
                new JdbcPricingStore(jdbc, JsonMapper.builder().build()),
                new PricingEngine(),
                new JdbcCatalogPricingContext(jdbc, "uz"),
                channels,
                deliveryFees,
                CLOCK);

        JdbcMediaAssetStore mediaStore = new JdbcMediaAssetStore(jdbc);
        uz.horecaos.platform.media.api.MediaAvailability media = (tid, assetIds) -> assetIds.stream()
                .allMatch(id -> mediaStore
                        .findOwned(tid, id)
                        .map(a -> a.status().isDisplayable())
                        .orElse(false));

        return List.of(
                new OnboardingStepHandlers.KeycloakOrganizationReconcile(provisioner, tenants),
                new OnboardingStepHandlers.TenantOwnerLinkOrInvite(provisioner, authority),
                new OnboardingStepHandlers.DefaultConfigurationApply(),
                new OnboardingStepHandlers.BrandsAndLocationsValidate(tenants),
                new OnboardingStepHandlers.PaymentConfigurationValidate(
                        tenants, new JdbcLegalEntityStore(jdbc), jdbc, CLOCK),
                new OnboardingStepHandlers.DeliveryConfigurationValidate(tenants, jdbc, CLOCK),
                new OnboardingStepHandlers.PosBindingsValidate(jdbc),
                new OnboardingStepHandlers.CatalogReadinessValidate(tenants, jdbc),
                new OnboardingStepHandlers.MediaReadinessValidate(jdbc, media),
                new OnboardingStepHandlers.FrontendDomainValidate(),
                new OrderingOnboardingStepHandlers.ActivationSmokeTest(jdbc, channels, serviceability, pricing, CLOCK));
    }

    /** Records nothing; every call simply succeeds, the way a healthy Keycloak would. */
    private static final class FakeOrganizationProvisioner implements OrganizationProvisioner {

        @Override
        public OrganizationRef ensureOrganization(EnsureOrganization command) {
            return new OrganizationRef("org-" + command.tenantId(), command.alias(), true);
        }

        @Override
        public Optional<OrganizationSnapshot> getOrganization(String organizationId) {
            return Optional.of(new OrganizationSnapshot(organizationId, "acme", "Acme", true));
        }

        @Override
        public MembershipRef ensureMembership(EnsureMembership command) {
            return new MembershipRef(command.organizationId(), "owner-subject-1", true);
        }
    }

    // ------------------------------------------------------------------------------ fixtures

    private void seedARealisticTenant() {
        jdbc.sql("""
                INSERT INTO tenant.onboarding_templates
                    (id, code, version, status, required_steps, created_by)
                VALUES (:id, 'default', 1, 'ACTIVE', '[]'::jsonb, 'test')
                """).param("id", templateId).update();

        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Acme Foods LLC', 'Acme', 'UZS', 'Asia/Tashkent', 'PROVISIONING', 0)
                """)
                .param("id", tenantId)
                .param("slug", "acme-" + tenantId.toString().substring(0, 8))
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'ACME', :slug, 'Acme Burgers', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("slug", "acme-brand-" + brandId.toString().substring(0, 8))
                .update();
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'LOC', :slug, 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("slug", "loc-" + locationId.toString().substring(0, 8))
                .update();

        // One channel, cash-only, pickup-only — the two owner-decided v1
        // defaults this build records: no non-cash method means
        // PAYMENT_CONFIGURATION_VALIDATE never needs a merchant binding, and no
        // DELIVERY mode means DELIVERY_CONFIGURATION_VALIDATE never needs a zone.
        UUID channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, :code, 'WEB', :code, 'ACTIVE')
                """)
                .param("id", channelId)
                .param("tenantId", tenantId)
                .param("code", CHANNEL_CODE)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
                VALUES (:tenantId, :channelId, :locationId, 'ACTIVE')
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("locationId", locationId)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.channel_payment_methods (tenant_id, channel_id, payment_method_code, enabled)
                VALUES (:tenantId, :channelId, 'CASH', true)
                """).param("tenantId", tenantId).param("channelId", channelId).update();
        jdbc.sql("""
                INSERT INTO tenant.channel_fulfillment_modes (tenant_id, channel_id, fulfillment_mode, enabled)
                VALUES (:tenantId, :channelId, 'PICKUP', true)
                """).param("tenantId", tenantId).param("channelId", channelId).update();

        // A legal entity, assigned to the location — required regardless of
        // payment method, per ADR 0038.
        UUID legalEntityId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, vat_registered, status)
                VALUES (:id, :tenantId, 'ACME', 'Acme Foods LLC', '123456789', false, 'ACTIVE')
                """).param("id", legalEntityId).param("tenantId", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.location_fiscal_assignments
                    (id, tenant_id, brand_id, location_id, legal_entity_id, effective_from, approved_by)
                VALUES (:id, :tenantId, :brandId, :locationId, :legalEntityId, :from, 'test')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("legalEntityId", legalEntityId)
                .param("from", java.time.LocalDate.of(2020, 1, 1))
                .update();

        // A published menu with one available item at the location.
        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();
        UUID productId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, 'BURGER', 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();
        UUID variantId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status)
                VALUES (:id, :tenantId, :brandId, :productId, 'SKU-BURGER', 'ACTIVE')
                """)
                .param("id", variantId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.publications
                    (id, tenant_id, brand_id, catalog_id, channel, status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, :channel, 'PUBLISHED', 'hash', now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId)
                .param("channel", CHANNEL_CODE)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.location_offerings (id, tenant_id, brand_id, location_id, variant_id, status)
                VALUES (:id, :tenantId, :brandId, :locationId, :variantId, 'AVAILABLE')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("variantId", variantId)
                .update();

        // Pricing: what ACTIVATION_SMOKE_TEST's real quote needs to succeed.
        UUID priceBookId = UUID.randomUUID();
        var validFrom = java.time.OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO pricing.price_books (id, tenant_id, brand_id, name, currency, status, valid_from, priority)
                VALUES (:id, :tenantId, :brandId, 'BRAND_MENU', 'UZS', 'ACTIVE', :from, 0)
                """)
                .param("id", priceBookId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("from", validFrom)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.price_book_assignments
                    (id, tenant_id, brand_id, price_book_id, scope_type, scope_id, valid_from, priority)
                VALUES (:id, :tenantId, :brandId, :priceBookId, 'BRAND', null, :from, 0)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("priceBookId", priceBookId)
                .param("from", validFrom)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.prices
                    (id, tenant_id, brand_id, price_book_id, priceable_type, priceable_id, amount_minor, valid_from)
                VALUES (:id, :tenantId, :brandId, :priceBookId, 'VARIANT', :variantId, 50000, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("priceBookId", priceBookId)
                .param("variantId", variantId)
                .param("from", validFrom)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.tax_profiles (id, tenant_id, brand_id, jurisdiction_code, mode,
                    rate_basis_points, valid_from)
                VALUES (:id, :tenantId, :brandId, 'UZ', 'INCLUSIVE', 1200, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("from", validFrom)
                .update();

        // No POS binding, no media reference, no custom domain: each of those
        // three steps passes on absence, by the owner-decided v1 defaults.
    }
}
