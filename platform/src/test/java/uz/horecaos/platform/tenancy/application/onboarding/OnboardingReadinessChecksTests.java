package uz.horecaos.platform.tenancy.application.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler.StepResult;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTenantControlPlaneStore;

/**
 * Gap map row {@code 10.0}: the three settings-home readiness conditions that
 * had no read behind them — fiscal classification coverage, channel
 * payment-method coverage and secret-rotation age — each constructed directly
 * against a real database, the way {@code OnboardingStepHandlersTests} tests
 * the {@code VALIDATING}-phase handlers. What is under test is each check's own
 * rule and what its findings say; {@code OnboardingServiceTests} covers how
 * {@code validate} folds them into the dry-run response.
 */
class OnboardingReadinessChecksTests {

    private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration ROTATION_PERIOD = Duration.ofDays(180);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcTenantControlPlaneStore tenants;
    private UUID tenantId;
    private UUID brandId;
    private UUID locationId;

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
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE payments.merchant_bindings CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.bindings, integration.installations, "
                        + "integration.provider_environments CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.fiscal_classifications, catalog.fees, catalog.modifier_options, "
                        + "catalog.modifier_groups, catalog.location_offerings, catalog.variants, "
                        + "catalog.products CASCADE")
                .update();
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
        insertTenant(tenantId);
        insertBrand(tenantId, brandId, "MAIN", "ACTIVE");
        insertLocation(tenantId, brandId, locationId);
    }

    // ------------------------------------------------- FISCAL_CLASSIFICATION_COVERAGE

    @Test
    void fiscalCoveragePassesWhenABrandHasNothingToClassifyYet() {
        // An empty menu is the catalog check's finding (NO_PUBLISHED_MENU), not this one's.
        StepResult result = fiscal().check(tenantId);

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void fiscalCoverageNamesABrandWhoseMenuIsNotFullyClassified() {
        UUID classified = insertVariant(tenantId, brandId, "SOUP", "ACTIVE");
        insertClassification("variant_id", classified, "10101001001000000", "1", 1234, "Soup");
        insertVariant(tenantId, brandId, "STEW", "ACTIVE");
        insertVariant(tenantId, brandId, "PILAF", "ACTIVE");

        List<StepResult.Finding> findings = findings(fiscal().check(tenantId));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.errorCode()).isEqualTo("FISCAL_CLASSIFICATION_INCOMPLETE");
            assertThat(finding.detail())
                    .as("names the brand and how much of its menu is short, not merely that something is")
                    .isEqualTo("Brand MAIN has 2 of 3 menu items without a complete fiscal classification");
            assertThat(finding.locationId())
                    .as("brand-scoped: there is no branch to deep-link into")
                    .isNull();
        });
    }

    @Test
    void fiscalCoveragePassesOnceEveryNodeCarriesAllFourFields() {
        UUID soup = insertVariant(tenantId, brandId, "SOUP", "ACTIVE");
        insertClassification("variant_id", soup, "10101001001000000", "1", 1234, "Soup");

        StepResult result = fiscal().check(tenantId);

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void fiscalCoverageCountsAnyMissingFieldAsIncomplete() {
        // The ИКПУ is there, the package code, unit and name are not: V0021's own
        // check would have called this classified, ADR 0038's four fields do not.
        UUID soup = insertVariant(tenantId, brandId, "SOUP", "ACTIVE");
        insertClassification("variant_id", soup, "10101001001000000", null, null, null);

        List<StepResult.Finding> findings = findings(fiscal().check(tenantId));

        assertThat(findings)
                .singleElement()
                .satisfies(finding -> assertThat(finding.detail()).contains("1 of 1"));
    }

    @Test
    void fiscalCoverageCountsUnlinkedModifierOptionsAndTheDeliveryFeeButNotLinkedOnes() {
        UUID soup = insertVariant(tenantId, brandId, "SOUP", "ACTIVE");
        insertClassification("variant_id", soup, "10101001001000000", "1", 1234, "Soup");
        UUID group = insertModifierGroup("EXTRAS");
        insertModifierOption(group, "CHEESE", null);
        // A modifier that is itself a sellable variant is classified through the
        // link and carries no classification of its own (V0028): it must not double
        // the dish it links to.
        insertModifierOption(group, "SIDE", soup);
        insertFee("DELIVERY", "ACTIVE");
        insertFee("SERVICE", "ARCHIVED");

        List<StepResult.Finding> findings = findings(fiscal().check(tenantId));

        assertThat(findings)
                .singleElement()
                .satisfies(finding -> assertThat(finding.detail())
                        .as("the variant is classified; the unlinked option and the active fee are not; "
                                + "the linked option and the archived fee are not nodes at all")
                        .isEqualTo("Brand MAIN has 2 of 3 menu items without a complete fiscal classification"));
    }

    @Test
    void fiscalCoverageIgnoresArchivedMenuItems() {
        insertVariant(tenantId, brandId, "OLD", "ARCHIVED");

        StepResult result = fiscal().check(tenantId);

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void fiscalCoverageNamesEveryBrandThatCanSellAndNoneThatCannot() {
        UUID secondBrand = UUID.randomUUID();
        insertBrand(tenantId, secondBrand, "SECOND", "ACTIVE");
        UUID suspendedBrand = UUID.randomUUID();
        insertBrand(tenantId, suspendedBrand, "GONE", "SUSPENDED");
        insertVariant(tenantId, brandId, "SOUP", "ACTIVE");
        insertVariant(tenantId, secondBrand, "STEW", "ACTIVE");
        insertVariant(tenantId, suspendedBrand, "DUST", "ACTIVE");

        List<StepResult.Finding> findings = findings(fiscal().check(tenantId));

        assertThat(findings)
                .as("both sellable brands are named, in one pass; a suspended brand nobody can sell from is not")
                .hasSize(2)
                .extracting(StepResult.Finding::detail)
                .anyMatch(detail -> detail.startsWith("Brand MAIN "))
                .anyMatch(detail -> detail.startsWith("Brand SECOND "))
                .noneMatch(detail -> detail.contains("GONE"));
    }

    @Test
    void fiscalCoverageNeverReadsAnotherTenantsMenu() {
        UUID otherTenant = UUID.randomUUID();
        UUID otherBrand = UUID.randomUUID();
        insertTenant(otherTenant);
        insertBrand(otherTenant, otherBrand, "MAIN", "ACTIVE");
        insertVariant(otherTenant, otherBrand, "THEIRS", "ACTIVE");
        UUID soup = insertVariant(tenantId, brandId, "SOUP", "ACTIVE");
        insertClassification("variant_id", soup, "10101001001000000", "1", 1234, "Soup");

        StepResult result = fiscal().check(tenantId);

        assertThat(result.outcome())
                .as("the other tenant's unclassified dish is not this tenant's gap")
                .isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void fiscalCoverageWritesNothing() {
        insertVariant(tenantId, brandId, "SOUP", "ACTIVE");
        long feesBefore = count("catalog.fees");
        long classificationsBefore = count("catalog.fiscal_classifications");

        fiscal().check(tenantId);

        assertThat(count("catalog.fees"))
                .as("the coverage screen vivifies the delivery-fee row on first visit; a dry run must not")
                .isEqualTo(feesBefore);
        assertThat(count("catalog.fiscal_classifications")).isEqualTo(classificationsBefore);
    }

    @Test
    void fiscalCoverageIsAdvisoryBecauseThePublishGateStillLetsAnIncompleteMenuThrough() {
        assertThat(fiscal().advisory()).isTrue();
        assertThat(fiscal().checkKey()).isEqualTo("FISCAL_CLASSIFICATION_COVERAGE_VALIDATE");
    }

    // ------------------------------------------------- CHANNEL_PAYMENT_COVERAGE

    @Test
    void channelCoverageNamesAnActiveChannelWithNoEnabledPaymentMethod() {
        insertChannel(tenantId, "STOREFRONT", "ACTIVE");

        List<StepResult.Finding> findings = findings(channels().check(tenantId));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.errorCode()).isEqualTo("CHANNEL_NO_PAYMENT_METHOD");
            assertThat(finding.detail()).contains("STOREFRONT");
            assertThat(finding.locationId())
                    .as("a channel is a route to market for the whole tenant, not one branch")
                    .isNull();
        });
    }

    @Test
    void channelCoveragePassesWhenEveryActiveChannelHasAnEnabledMethod() {
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        enablePaymentMethod(storefront, "CASH", true);

        StepResult result = channels().check(tenantId);

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void channelCoverageDoesNotCountADisabledMethod() {
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        enablePaymentMethod(storefront, "CASH", false);

        List<StepResult.Finding> findings = findings(channels().check(tenantId));

        assertThat(findings)
                .as("a method that is registered on the channel but switched off offers nothing")
                .singleElement()
                .satisfies(finding -> assertThat(finding.detail()).contains("STOREFRONT"));
    }

    @Test
    void channelCoverageNamesEveryOffendingChannelRatherThanOnlyTheFirst() {
        insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        insertChannel(tenantId, "KIOSK", "ACTIVE");
        UUID covered = insertChannel(tenantId, "POS", "ACTIVE");
        enablePaymentMethod(covered, "CASH", true);

        List<StepResult.Finding> findings = findings(channels().check(tenantId));

        assertThat(findings)
                .as("both uncovered channels named in one pass, in a stable order")
                .extracting(StepResult.Finding::detail)
                .containsExactly(
                        "Sales channel KIOSK has no enabled payment method",
                        "Sales channel STOREFRONT has no enabled payment method");
    }

    @Test
    void channelCoverageIgnoresChannelsThatAreNotActive() {
        insertChannel(tenantId, "RETIRED_ONE", "INACTIVE");
        insertChannel(tenantId, "OLD_ONE", "ARCHIVED");

        StepResult result = channels().check(tenantId);

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void channelCoverageNeverReadsAnotherTenantsChannels() {
        UUID otherTenant = UUID.randomUUID();
        insertTenant(otherTenant);
        insertChannel(otherTenant, "THEIRS", "ACTIVE");
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        enablePaymentMethod(storefront, "CASH", true);

        StepResult result = channels().check(tenantId);

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void channelCoverageIsBlockingBecauseAChannelWithNoMethodCannotFinishACart() {
        assertThat(channels().advisory()).isFalse();
        assertThat(channels().checkKey()).isEqualTo("CHANNEL_PAYMENT_COVERAGE_VALIDATE");
    }

    // ------------------------------------------------- SECRET_ROTATION_AGE

    @Test
    void rotationAgeNamesAnInstallationNeverRotatedAndOlderThanThePeriod() {
        insertInstallation("Clopos main", "CLOPOS", "ACTIVE", daysAgo(200), null, true);

        List<StepResult.Finding> findings = findings(rotation(ROTATION_PERIOD).check(tenantId));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.errorCode()).isEqualTo("INSTALLATION_SECRET_ROTATION_DUE");
            assertThat(finding.detail()).contains("Clopos main", "CLOPOS", "200 days", "180 days");
            assertThat(finding.locationId()).isNull();
        });
    }

    @Test
    void rotationAgeCountsFromTheLastRotationNotFromSetUp() {
        insertInstallation("Clopos main", "CLOPOS", "ACTIVE", daysAgo(400), daysAgo(10), true);

        StepResult result = rotation(ROTATION_PERIOD).check(tenantId);

        assertThat(result.outcome())
                .as("set up 400 days ago but rotated 10 days ago: the rotation resets the clock")
                .isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void rotationAgeHonoursTheBoundaryExactlyLikeTheControlPlanesCredentialsDueList() {
        insertInstallation("On the line", "CLOPOS", "ACTIVE", daysAgo(180), null, true);

        assertThat(rotation(ROTATION_PERIOD).check(tenantId).outcome())
                .as("exactly one period old is not yet past it (strictly older, as CredentialRotationController)")
                .isEqualTo(StepResult.Outcome.COMPLETED);

        insertInstallation("Just past", "IIKO", "ACTIVE", daysAgo(181), null, true);

        assertThat(findings(rotation(ROTATION_PERIOD).check(tenantId)))
                .singleElement()
                .satisfies(finding -> assertThat(finding.detail()).contains("Just past"));
    }

    @Test
    void rotationAgeUsesTheConfiguredPeriodRatherThanAFixedOne() {
        insertInstallation("Clopos main", "CLOPOS", "ACTIVE", daysAgo(45), null, true);

        assertThat(rotation(ROTATION_PERIOD).check(tenantId).outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
        assertThat(findings(rotation(Duration.ofDays(30)).check(tenantId)))
                .singleElement()
                .satisfies(finding -> assertThat(finding.detail()).contains("45 days", "30 days"));
    }

    @Test
    void rotationAgeIgnoresAnInstallationWithNoCredentialAndARetiredOne() {
        insertInstallation("No secret yet", "CLOPOS", "DRAFT", daysAgo(400), null, false);
        insertInstallation("Retired", "IIKO", "RETIRED", daysAgo(400), null, true);

        StepResult result = rotation(ROTATION_PERIOD).check(tenantId);

        assertThat(result.outcome())
                .as("nothing uses a credential that was never set or belongs to a retired installation")
                .isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void rotationAgeNamesAMerchantAccountPastThePeriodWithoutItsAccountOrSecretReference() {
        UUID legalEntity = insertLegalEntity("ACME");
        insertMerchantBinding(legalEntity, "CLICK", "ACTIVE", daysAgo(300), null);

        List<StepResult.Finding> findings = findings(rotation(ROTATION_PERIOD).check(tenantId));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.errorCode()).isEqualTo("MERCHANT_SECRET_ROTATION_DUE");
            assertThat(finding.detail()).contains("CLICK", "300 days");
            assertThat(finding.detail())
                    .as("ADR 0028: a reference to a secret is not for a readiness list either, "
                            + "nor is the merchant's own account reference")
                    .doesNotContain("horecaos:test", "acct-1");
        });
    }

    @Test
    void rotationAgeIgnoresARetiredMerchantBinding() {
        UUID legalEntity = insertLegalEntity("ACME");
        insertMerchantBinding(legalEntity, "CLICK", "RETIRED", daysAgo(300), null);

        StepResult result = rotation(ROTATION_PERIOD).check(tenantId);

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void rotationAgeNamesEveryDueCredentialOldestFirst() {
        insertInstallation("Newer", "CLOPOS", "ACTIVE", daysAgo(200), null, true);
        insertInstallation("Oldest", "IIKO", "ACTIVE", daysAgo(500), null, true);
        UUID legalEntity = insertLegalEntity("ACME");
        insertMerchantBinding(legalEntity, "PAYME", "ACTIVE", daysAgo(300), null);

        List<StepResult.Finding> findings = findings(rotation(ROTATION_PERIOD).check(tenantId));

        assertThat(findings)
                .extracting(StepResult.Finding::errorCode)
                .containsExactly(
                        "INSTALLATION_SECRET_ROTATION_DUE",
                        "MERCHANT_SECRET_ROTATION_DUE",
                        "INSTALLATION_SECRET_ROTATION_DUE");
        assertThat(findings.get(0).detail()).contains("Oldest");
        assertThat(findings.get(2).detail()).contains("Newer");
    }

    @Test
    void rotationAgeNeverReadsAnotherTenantsCredentials() {
        UUID otherTenant = UUID.randomUUID();
        insertTenant(otherTenant);
        insertInstallationFor(otherTenant, "Theirs", "CLOPOS", "ACTIVE", daysAgo(500), null, true);

        StepResult result = rotation(ROTATION_PERIOD).check(tenantId);

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void rotationAgeIsAdvisoryBecauseAnOldCredentialStillWorks() {
        assertThat(rotation(ROTATION_PERIOD).advisory()).isTrue();
        assertThat(rotation(ROTATION_PERIOD).checkKey()).isEqualTo("SECRET_ROTATION_AGE_VALIDATE");
    }

    // --------------------------------------------------------------------------- fixtures

    private OnboardingReadinessChecks.FiscalClassificationCoverage fiscal() {
        return new OnboardingReadinessChecks.FiscalClassificationCoverage(tenants, jdbc);
    }

    private OnboardingReadinessChecks.ChannelPaymentCoverage channels() {
        return new OnboardingReadinessChecks.ChannelPaymentCoverage(jdbc);
    }

    private OnboardingReadinessChecks.SecretRotationAge rotation(Duration period) {
        return new OnboardingReadinessChecks.SecretRotationAge(jdbc, CLOCK, period);
    }

    @SuppressWarnings("unchecked")
    private static List<StepResult.Finding> findings(StepResult result) {
        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        return (List<StepResult.Finding>) Objects.requireNonNull(result.result().get(StepResult.FINDINGS_KEY));
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static Instant daysAgo(long days) {
        return NOW.minus(Duration.ofDays(days));
    }

    private void insertTenant(UUID id) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("slug", "t-" + id.toString().substring(0, 8))
                .update();
    }

    private void insertBrand(UUID owner, UUID id, String code, String status) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, :code, :status, 0)
                """)
                .param("id", id)
                .param("tenantId", owner)
                .param("code", code)
                .param("slug", "b-" + id.toString().substring(0, 8))
                .param("status", status)
                .update();
    }

    private void insertLocation(UUID owner, UUID ownerBrand, UUID id) {
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', :slug, 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", owner)
                .param("brandId", ownerBrand)
                .param("slug", "l-" + id.toString().substring(0, 8))
                .update();
    }

    private UUID insertVariant(UUID owner, UUID ownerBrand, String code, String status) {
        UUID productId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, :status)
                """)
                .param("id", productId)
                .param("tenantId", owner)
                .param("brandId", ownerBrand)
                .param("code", code)
                .param("status", status)
                .update();
        UUID variantId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status)
                VALUES (:id, :tenantId, :brandId, :productId, :sku, :status)
                """)
                .param("id", variantId)
                .param("tenantId", owner)
                .param("brandId", ownerBrand)
                .param("productId", productId)
                .param("sku", "SKU-" + code)
                .param("status", status)
                .update();
        return variantId;
    }

    private UUID insertModifierGroup(String code) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.modifier_groups (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .update();
        return id;
    }

    private void insertModifierOption(UUID groupId, String code, @Nullable UUID linkedVariantId) {
        jdbc.sql("""
                INSERT INTO catalog.modifier_options
                    (id, tenant_id, brand_id, modifier_group_id, code, linked_variant_id, status)
                VALUES (:id, :tenantId, :brandId, :groupId, :code, :linked, 'ACTIVE')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("groupId", groupId)
                .param("code", code)
                .param("linked", linkedVariantId)
                .update();
    }

    private void insertFee(String code, String status) {
        jdbc.sql("""
                INSERT INTO catalog.fees (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, :status)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .param("status", status)
                .update();
    }

    /** {@code nodeColumn} is one of the table's own three node columns, never caller-supplied text. */
    private void insertClassification(
            String nodeColumn,
            UUID nodeId,
            String mxik,
            @Nullable String packageCode,
            @Nullable Integer unitCode,
            @Nullable String fiscalName) {
        jdbc.sql("""
                INSERT INTO catalog.fiscal_classifications
                    (id, tenant_id, brand_id, %s, mxik_code, package_code, fiscal_unit_code, fiscal_name, source)
                VALUES (:id, :tenantId, :brandId, :nodeId, :mxik, :packageCode, :unitCode, :fiscalName, 'MANUAL')
                """.formatted(nodeColumn))
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("nodeId", nodeId)
                .param("mxik", mxik)
                .param("packageCode", packageCode)
                .param("unitCode", unitCode)
                .param("fiscalName", fiscalName)
                .update();
    }

    private UUID insertChannel(UUID owner, String code, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, :code, 'WEB', :code, :status)
                """)
                .param("id", id)
                .param("tenantId", owner)
                .param("code", code)
                .param("status", status)
                .update();
        return id;
    }

    private void enablePaymentMethod(UUID channelId, String code, boolean enabled) {
        // V0175: payment_method_code is a foreign key onto payments.payment_methods.
        jdbc.sql("""
                INSERT INTO payments.payment_methods (id, tenant_id, code, display_name, responsibility, status)
                VALUES (:id, :tenantId, :code, :code, 'OPERATOR', 'ACTIVE')
                ON CONFLICT ON CONSTRAINT uq_payment_method_code DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("code", code)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.channel_payment_methods (tenant_id, channel_id, payment_method_code, enabled)
                VALUES (:tenantId, :channelId, :code, :enabled)
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("code", code)
                .param("enabled", enabled)
                .update();
    }

    private UUID insertInstallation(
            String displayName,
            String providerType,
            String status,
            Instant createdAt,
            @Nullable Instant lastRotatedAt,
            boolean withSecret) {
        return insertInstallationFor(tenantId, displayName, providerType, status, createdAt, lastRotatedAt, withSecret);
    }

    private UUID insertInstallationFor(
            UUID owner,
            String displayName,
            String providerType,
            String status,
            Instant createdAt,
            @Nullable Instant lastRotatedAt,
            boolean withSecret) {
        String envCode = "env-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'POS', :providerType, 'https://example.test', false, 'example.test')
                """).param("code", envCode).param("providerType", providerType).update();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status,
                     secret_reference, created_at, last_secret_rotated_at)
                VALUES (:id, :tenantId, 'POS', :providerType, :env, :name, :status,
                        :secretReference, :createdAt, :lastRotatedAt)
                """)
                .param("id", id)
                .param("tenantId", owner)
                .param("providerType", providerType)
                .param("env", envCode)
                .param("name", displayName)
                .param("status", status)
                .param("secretReference", withSecret ? "horecaos:test:provider_pos:tenant:" + id : null)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .param("lastRotatedAt", lastRotatedAt == null ? null : lastRotatedAt.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    private UUID insertLegalEntity(String code) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, vat_registered, status)
                VALUES (:id, :tenantId, :code, :legalName, '123456789', false, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("legalName", code + " LLC")
                .update();
        return id;
    }

    private void insertMerchantBinding(
            UUID legalEntityId,
            String providerType,
            String status,
            Instant createdAt,
            @Nullable Instant lastRotatedAt) {
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
        jdbc.sql("""
                INSERT INTO payments.merchant_bindings
                    (id, tenant_id, legal_entity_id, provider_type, installation_id, binding_id,
                     merchant_account_reference, secret_reference, callback_path_segment,
                     supports_reversal, supports_partner_fiscalization, status, effective_from,
                     created_at, last_secret_rotated_at)
                VALUES (:id, :tenantId, :legalEntityId, :providerType, :installationId, :bindingId,
                        'acct-1', :secretRef, :segment, true, true, :status, :from, :createdAt, :lastRotatedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("legalEntityId", legalEntityId)
                .param("providerType", providerType)
                .param("installationId", installationId)
                .param("bindingId", bindingId)
                .param("secretRef", "horecaos:test:provider_payment:tenant:" + providerType.toLowerCase(Locale.ROOT))
                .param("segment", "seg-" + UUID.randomUUID().toString().substring(0, 10))
                .param("status", status)
                .param("from", LocalDate.of(2025, 1, 1))
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .param("lastRotatedAt", lastRotatedAt == null ? null : lastRotatedAt.atOffset(ZoneOffset.UTC))
                .update();
    }
}
