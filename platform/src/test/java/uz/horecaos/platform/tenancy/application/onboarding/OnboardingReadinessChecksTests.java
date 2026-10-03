package uz.horecaos.platform.tenancy.application.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 21);

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

    @Test
    void channelPaymentFindingNamesTheChannelAsItsSubjectSoTheConsoleCanOpenIt() {
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");

        List<StepResult.Finding> findings = findings(channels().check(tenantId));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.subject()).isEqualTo(StepResult.FindingSubject.salesChannel(storefront));
            assertThat(finding.subject())
                    .extracting(StepResult.FindingSubject::type)
                    .isEqualTo("SALES_CHANNEL");
        });
    }

    // ------------------------------------------------- CHANNEL_FULFILLMENT_COVERAGE

    @Test
    void fulfilmentCoverageNamesAnActiveChannelWithNoEnabledMode() {
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");

        List<StepResult.Finding> findings = findings(fulfilment().check(tenantId));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.errorCode()).isEqualTo("CHANNEL_NO_FULFILLMENT_MODE");
            assertThat(finding.detail()).isEqualTo("Sales channel STOREFRONT has no enabled fulfilment mode");
            assertThat(finding.locationId())
                    .as("a channel is a route to market for the whole tenant, not one branch")
                    .isNull();
            assertThat(finding.subject())
                    .as("the channel itself, so the console can open its setup")
                    .isEqualTo(StepResult.FindingSubject.salesChannel(storefront));
        });
    }

    @Test
    void fulfilmentCoverageDoesNotCountADisabledMode() {
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        setChannelMode(storefront, "PICKUP", false);
        serveChannelAt(storefront, locationId, "ACTIVE");
        bindSchedule(locationId, "PICKUP");

        List<StepResult.Finding> findings = findings(fulfilment().check(tenantId));

        assertThat(findings)
                .as("a mode registered on the channel but switched off offers nothing, however well it is bound")
                .singleElement()
                .satisfies(finding -> assertThat(finding.errorCode()).isEqualTo("CHANNEL_NO_FULFILLMENT_MODE"));
    }

    @Test
    void fulfilmentCoveragePassesWhenAnEnabledModeHasAScheduleAtAnActiveLocationTheChannelServes() {
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        setChannelMode(storefront, "PICKUP", true);
        serveChannelAt(storefront, locationId, "ACTIVE");
        bindSchedule(locationId, "PICKUP");

        assertThat(fulfilment().check(tenantId).outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void fulfilmentCoverageNamesAChannelWhoseEnabledModesAreBoundAtNoLocation() {
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        setChannelMode(storefront, "DELIVERY", true);
        setChannelMode(storefront, "PICKUP", true);
        // Switched on at the location, but the location has no schedule for either mode.
        serveChannelAt(storefront, locationId, "ACTIVE");

        List<StepResult.Finding> findings = findings(fulfilment().check(tenantId));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.errorCode()).isEqualTo("CHANNEL_NO_SERVICEABLE_MODE");
            assertThat(finding.detail()).contains("STOREFRONT", "none has a schedule bound");
            assertThat(finding.subject()).isEqualTo(StepResult.FindingSubject.salesChannel(storefront));
        });
    }

    @Test
    void fulfilmentCoverageDoesNotAcceptAScheduleBoundForADifferentMode() {
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        setChannelMode(storefront, "DELIVERY", true);
        serveChannelAt(storefront, locationId, "ACTIVE");
        // The location has hours for dine-in only; the channel sells delivery.
        bindSchedule(locationId, "DINE_IN");

        assertThat(findings(fulfilment().check(tenantId)))
                .singleElement()
                .satisfies(finding -> assertThat(finding.errorCode()).isEqualTo("CHANNEL_NO_SERVICEABLE_MODE"));
    }

    @Test
    void fulfilmentCoverageNamesAChannelServedAtNoLocationAtAll() {
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        setChannelMode(storefront, "PICKUP", true);
        bindSchedule(locationId, "PICKUP");

        assertThat(findings(fulfilment().check(tenantId)))
                .as("a schedule at a location the channel is not switched on for reaches nobody on this channel")
                .singleElement()
                .satisfies(finding -> assertThat(finding.errorCode()).isEqualTo("CHANNEL_NO_SERVICEABLE_MODE"));
    }

    @Test
    void fulfilmentCoverageIgnoresAnInactiveChannelLocationLinkAndALocationThatIsNotActive() {
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        setChannelMode(storefront, "PICKUP", true);
        UUID paused = UUID.randomUUID();
        insertLocation(tenantId, brandId, paused, "PAUSED1", "SUSPENDED");
        bindSchedule(paused, "PICKUP");
        serveChannelAt(storefront, paused, "ACTIVE");
        serveChannelAt(storefront, locationId, "INACTIVE");
        bindSchedule(locationId, "PICKUP");

        assertThat(findings(fulfilment().check(tenantId)))
                .as("the only bound location is suspended, and the active one is switched off for the channel")
                .singleElement()
                .satisfies(finding -> assertThat(finding.errorCode()).isEqualTo("CHANNEL_NO_SERVICEABLE_MODE"));
    }

    @Test
    void fulfilmentCoverageNamesEveryOffendingChannelInCodeOrderAndSkipsCoveredAndInactiveOnes() {
        insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        insertChannel(tenantId, "KIOSK", "ACTIVE");
        UUID covered = insertChannel(tenantId, "POS", "ACTIVE");
        setChannelMode(covered, "DINE_IN", true);
        serveChannelAt(covered, locationId, "ACTIVE");
        bindSchedule(locationId, "DINE_IN");
        insertChannel(tenantId, "OLD_ONE", "ARCHIVED");

        List<StepResult.Finding> findings = findings(fulfilment().check(tenantId));

        assertThat(findings)
                .extracting(StepResult.Finding::detail)
                .containsExactly(
                        "Sales channel KIOSK has no enabled fulfilment mode",
                        "Sales channel STOREFRONT has no enabled fulfilment mode");
    }

    @Test
    void fulfilmentCoverageNeverReadsAnotherTenantsChannels() {
        UUID otherTenant = UUID.randomUUID();
        insertTenant(otherTenant);
        insertChannel(otherTenant, "THEIRS", "ACTIVE");

        assertThat(fulfilment().check(tenantId).outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void fulfilmentCoverageIsBlockingBecauseAChannelServingNothingCannotFinishACart() {
        assertThat(fulfilment().advisory()).isFalse();
        assertThat(fulfilment().checkKey()).isEqualTo("CHANNEL_FULFILLMENT_COVERAGE_VALIDATE");
    }

    // ------------------------------------------------- LOCATION_SERVICE_BINDING_COVERAGE

    @Test
    void bindingCoverageNamesAnActiveLocationLackingAScheduleForAModeAChannelSellsThere() {
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        setChannelMode(storefront, "DELIVERY", true);
        setChannelMode(storefront, "PICKUP", true);
        serveChannelAt(storefront, locationId, "ACTIVE");
        bindSchedule(locationId, "PICKUP");

        List<StepResult.Finding> findings = findings(bindings().check(tenantId));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.errorCode()).isEqualTo("LOCATION_NO_SERVICE_SCHEDULE");
            assertThat(finding.detail()).isEqualTo("Location MAIN01 has no schedule bound for DELIVERY");
            assertThat(finding.locationId())
                    .as("location-scoped: the console opens the location where its hours are set")
                    .isEqualTo(locationId);
            assertThat(finding.subject()).isNull();
        });
    }

    @Test
    void bindingCoverageNamesEveryMissingModeOfALocationInOneRow() {
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        setChannelMode(storefront, "DELIVERY", true);
        setChannelMode(storefront, "PICKUP", true);
        setChannelMode(storefront, "DINE_IN", true);
        serveChannelAt(storefront, locationId, "ACTIVE");
        bindSchedule(locationId, "DINE_IN");

        assertThat(findings(bindings().check(tenantId)))
                .singleElement()
                .satisfies(finding -> assertThat(finding.detail())
                        .isEqualTo("Location MAIN01 has no schedule bound for DELIVERY, PICKUP"));
    }

    @Test
    void bindingCoveragePassesOnceEveryModeTheLocationSellsHasASchedule() {
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        setChannelMode(storefront, "DELIVERY", true);
        setChannelMode(storefront, "PICKUP", true);
        serveChannelAt(storefront, locationId, "ACTIVE");
        bindSchedule(locationId, "DELIVERY");
        bindSchedule(locationId, "PICKUP");

        assertThat(bindings().check(tenantId).outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void bindingCoverageDoesNotDemandAScheduleForAModeNothingSellsThere() {
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        setChannelMode(storefront, "PICKUP", true);
        // Switched off, or reaching this location only through a channel that is off or inactive.
        setChannelMode(storefront, "DELIVERY", false);
        UUID retired = insertChannel(tenantId, "RETIRED_ONE", "INACTIVE");
        setChannelMode(retired, "DINE_IN", true);
        serveChannelAt(storefront, locationId, "ACTIVE");
        serveChannelAt(retired, locationId, "ACTIVE");
        UUID unserved = insertChannel(tenantId, "KIOSK", "ACTIVE");
        setChannelMode(unserved, "DINE_IN", true);
        serveChannelAt(unserved, locationId, "INACTIVE");
        bindSchedule(locationId, "PICKUP");

        assertThat(bindings().check(tenantId).outcome())
                .as("only PICKUP is sold here and PICKUP has hours")
                .isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void bindingCoverageNamesAnActiveLocationNoChannelReachesAndWithNoScheduleAtAll() {
        List<StepResult.Finding> findings = findings(bindings().check(tenantId));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.errorCode()).isEqualTo("LOCATION_NO_SERVICE_SCHEDULE");
            assertThat(finding.detail()).isEqualTo("Location MAIN01 has no service schedule bound");
            assertThat(finding.locationId()).isEqualTo(locationId);
        });
    }

    @Test
    void bindingCoveragePassesALocationNoChannelReachesOnceItHasAnySchedule() {
        bindSchedule(locationId, "DINE_IN");

        assertThat(bindings().check(tenantId).outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void bindingCoverageNamesEveryOffendingLocationInCodeOrderAndSkipsOnesThatAreNotActive() {
        UUID second = UUID.randomUUID();
        insertLocation(tenantId, brandId, second, "AAA01", "ACTIVE");
        UUID draft = UUID.randomUUID();
        insertLocation(tenantId, brandId, draft, "DRAFT1", "DRAFT");
        UUID archived = UUID.randomUUID();
        insertLocation(tenantId, brandId, archived, "OLD01", "ARCHIVED");

        List<StepResult.Finding> findings = findings(bindings().check(tenantId));

        assertThat(findings)
                .as("both active locations named in one pass, in a stable order; a draft or archived one is not open")
                .extracting(StepResult.Finding::locationId)
                .containsExactly(second, locationId);
    }

    @Test
    void bindingCoverageNeverReadsAnotherTenantsLocations() {
        bindSchedule(locationId, "PICKUP");
        UUID otherTenant = UUID.randomUUID();
        UUID otherBrand = UUID.randomUUID();
        insertTenant(otherTenant);
        insertBrand(otherTenant, otherBrand, "THEIRS", "ACTIVE");
        insertLocation(otherTenant, otherBrand, UUID.randomUUID(), "THEIRS1", "ACTIVE");

        assertThat(bindings().check(tenantId).outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void bindingCoverageIsBlockingBecauseALocationWithNoHoursIsClosedForThatMode() {
        assertThat(bindings().advisory()).isFalse();
        assertThat(bindings().checkKey()).isEqualTo("LOCATION_SERVICE_BINDING_COVERAGE_VALIDATE");
    }

    // ------------------------------------------------- LOCATION_FORCED_CLOSED_NO_EXPIRY

    @Test
    void forcedClosedNamesALocationClosedByHandWithNoEndTimeAndHowLongItHasBeenShut() {
        setServiceState(locationId, "FORCE_CLOSED", "FRYER_BROKEN", null, daysAgo(4), "call Aziz +998901112233");

        List<StepResult.Finding> findings = findings(forcedClosed().check(tenantId));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.errorCode()).isEqualTo("LOCATION_FORCED_CLOSED_NO_EXPIRY");
            assertThat(finding.locationId()).isEqualTo(locationId);
            assertThat(finding.detail())
                    .as("the code, the branch and the days it has been dark")
                    .isEqualTo("Location MAIN01 has been closed by hand for 4 days (reason FRYER_BROKEN) "
                            + "with no time set to reopen");
            assertThat(finding.detail())
                    .as("the free-text note can carry a name or a phone number and never leaves the table")
                    .doesNotContain("Aziz")
                    .doesNotContain("998");
        });
    }

    @Test
    void forcedClosedIgnoresAClosureThatHasAnEndTimeAndTheOtherOverrideModes() {
        UUID endsTomorrow = UUID.randomUUID();
        insertLocation(tenantId, brandId, endsTomorrow, "AAA01", "ACTIVE");
        setServiceState(endsTomorrow, "FORCE_CLOSED", "HOLIDAY", NOW.plus(Duration.ofDays(1)), daysAgo(1), null);
        UUID forcedOpen = UUID.randomUUID();
        insertLocation(tenantId, brandId, forcedOpen, "BBB01", "ACTIVE");
        setServiceState(forcedOpen, "FORCE_OPEN", "EVENT", null, daysAgo(9), null);
        setServiceState(locationId, "FOLLOW_SCHEDULE", null, null, daysAgo(9), null);

        assertThat(forcedClosed().check(tenantId).outcome())
                .as("a closure with an expiry reopens by itself; a forced-open or default state is not a closure")
                .isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void forcedClosedListsTheLongestClosedBranchFirstAndSkipsOnesThatAreNotActive() {
        UUID newer = UUID.randomUUID();
        insertLocation(tenantId, brandId, newer, "AAA01", "ACTIVE");
        setServiceState(newer, "FORCE_CLOSED", "STAFF_SHORT", null, daysAgo(1), null);
        setServiceState(locationId, "FORCE_CLOSED", "FRYER_BROKEN", null, daysAgo(11), null);
        UUID suspended = UUID.randomUUID();
        insertLocation(tenantId, brandId, suspended, "SUS01", "SUSPENDED");
        setServiceState(suspended, "FORCE_CLOSED", "OTHER", null, daysAgo(30), null);

        assertThat(findings(forcedClosed().check(tenantId)))
                .as(
                        "the branch dark for eleven days leads; a suspended branch is closed for a reason its status states")
                .extracting(StepResult.Finding::locationId)
                .containsExactly(locationId, newer);
    }

    @Test
    void forcedClosedNeverReadsAnotherTenantsLocationsAndIsAdvisory() {
        UUID otherTenant = UUID.randomUUID();
        UUID otherBrand = UUID.randomUUID();
        UUID otherLocation = UUID.randomUUID();
        insertTenant(otherTenant);
        insertBrand(otherTenant, otherBrand, "THEIRS", "ACTIVE");
        insertLocation(otherTenant, otherBrand, otherLocation, "THEIRS1", "ACTIVE");
        setServiceState(otherTenant, otherBrand, otherLocation, "FORCE_CLOSED", "OTHER", null, daysAgo(3), null);

        assertThat(forcedClosed().check(tenantId).outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
        assertThat(forcedClosed().advisory()).isTrue();
        assertThat(forcedClosed().severity()).isEqualTo(ReadinessSeverity.ADVISORY);
    }

    // ------------------------------------------------- LOCATION_CHANNEL_REACH

    @Test
    void channelReachNamesAnActiveLocationNoChannelIsSwitchedOnFor() {
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        UUID other = UUID.randomUUID();
        insertLocation(tenantId, brandId, other, "AAA01", "ACTIVE");
        serveChannelAt(storefront, other, "ACTIVE");

        List<StepResult.Finding> findings = findings(reach().check(tenantId));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.errorCode()).isEqualTo("LOCATION_NO_SALES_CHANNEL");
            assertThat(finding.locationId()).isEqualTo(locationId);
            assertThat(finding.detail()).isEqualTo("Location MAIN01 is not switched on for any active sales channel");
        });
    }

    @Test
    void channelReachCountsAnInactiveLinkOrAnInactiveChannelAsNotReached() {
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        UUID retired = insertChannel(tenantId, "RETIRED_ONE", "INACTIVE");
        UUID switchedOff = UUID.randomUUID();
        insertLocation(tenantId, brandId, switchedOff, "AAA01", "ACTIVE");
        serveChannelAt(storefront, switchedOff, "INACTIVE");
        serveChannelAt(retired, locationId, "ACTIVE");

        assertThat(findings(reach().check(tenantId)))
                .as("a link that is off, and a link to a channel that is not active, reach nobody")
                .extracting(StepResult.Finding::locationId)
                .containsExactly(switchedOff, locationId);
    }

    @Test
    void channelReachPassesOnceTheLocationIsOnAnActiveChannelAndSkipsLocationsThatAreNotActive() {
        UUID storefront = insertChannel(tenantId, "STOREFRONT", "ACTIVE");
        serveChannelAt(storefront, locationId, "ACTIVE");
        insertLocation(tenantId, brandId, UUID.randomUUID(), "DRAFT1", "DRAFT");
        insertLocation(tenantId, brandId, UUID.randomUUID(), "OLD01", "ARCHIVED");

        assertThat(reach().check(tenantId).outcome())
                .as("a draft or archived location is not open, so no channel needs to reach it")
                .isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void channelReachIsBlockingAndNeverReadsAnotherTenantsChannels() {
        UUID otherTenant = UUID.randomUUID();
        insertTenant(otherTenant);
        UUID theirs = insertChannel(otherTenant, "THEIRS", "ACTIVE");
        assertThat(theirs).isNotNull();

        assertThat(findings(reach().check(tenantId)))
                .as("the fixture location has no channel of its own tenant's")
                .hasSize(1);
        assertThat(reach().advisory()).isFalse();
        assertThat(reach().severity()).isEqualTo(ReadinessSeverity.BLOCKING);
    }

    // ------------------------------------------------- FISCAL_ASSIGNMENT_EXPIRY

    @Test
    void fiscalExpiryNamesAnAssignmentEndingWithinTheWindowWithNothingAfterIt() {
        UUID entity = insertLegalEntity("ACME");
        assignFiscal(locationId, entity, TODAY.minusYears(1), TODAY.plusDays(12));

        List<StepResult.Finding> findings = findings(expiry(Duration.ofDays(30)).check(tenantId));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.errorCode()).isEqualTo("LOCATION_FISCAL_ASSIGNMENT_ENDING");
            assertThat(finding.locationId()).isEqualTo(locationId);
            assertThat(finding.detail())
                    .isEqualTo("The fiscal assignment of location MAIN01 ends on 2026-09-02 (12 days) "
                            + "and no later assignment covers it");
        });
    }

    @Test
    void fiscalExpiryIgnoresAHandoverToTheNextCompanyButNotAGapBeforeIt() {
        UUID entity = insertLegalEntity("ACME");
        UUID successor = insertLegalEntity("NEXT");
        assignFiscal(locationId, entity, TODAY.minusYears(1), TODAY.plusDays(12));
        assignFiscal(locationId, successor, TODAY.plusDays(12), null);

        assertThat(expiry(Duration.ofDays(30)).check(tenantId).outcome())
                .as("a row starting on the end date is the handover, so the branch always has a seller")
                .isEqualTo(StepResult.Outcome.COMPLETED);

        jdbc.sql("DELETE FROM tenant.location_fiscal_assignments WHERE legal_entity_id = :id")
                .param("id", successor)
                .update();
        assignFiscal(locationId, successor, TODAY.plusDays(20), null);

        assertThat(findings(expiry(Duration.ofDays(30)).check(tenantId)))
                .as("a successor that starts eight days late leaves eight days with no seller")
                .singleElement()
                .satisfies(finding -> assertThat(finding.locationId()).isEqualTo(locationId));
    }

    @Test
    void fiscalExpiryIgnoresAnOpenEndedAssignmentOneBeyondTheWindowAndOneAlreadyOver() {
        UUID entity = insertLegalEntity("ACME");
        UUID far = UUID.randomUUID();
        insertLocation(tenantId, brandId, far, "AAA01", "ACTIVE");
        UUID over = UUID.randomUUID();
        insertLocation(tenantId, brandId, over, "BBB01", "ACTIVE");
        assignFiscal(locationId, entity, TODAY.minusYears(1), null);
        assignFiscal(far, entity, TODAY.minusYears(1), TODAY.plusDays(31));
        assignFiscal(over, entity, TODAY.minusYears(2), TODAY.minusDays(3));

        assertThat(expiry(Duration.ofDays(30)).check(tenantId).outcome())
                .as("no end date never expires, thirty-one days is outside a thirty-day window, and "
                        + "an assignment that already ended is NO_LEGAL_ENTITY's finding, not this one's")
                .isEqualTo(StepResult.Outcome.COMPLETED);
    }

    @Test
    void fiscalExpiryListsTheSoonestEndingFirstSkipsInactiveLocationsAndIsExpiringNotBlocking() {
        UUID entity = insertLegalEntity("ACME");
        UUID sooner = UUID.randomUUID();
        insertLocation(tenantId, brandId, sooner, "ZZZ01", "ACTIVE");
        UUID draft = UUID.randomUUID();
        insertLocation(tenantId, brandId, draft, "DRAFT1", "DRAFT");
        assignFiscal(locationId, entity, TODAY.minusYears(1), TODAY.plusDays(20));
        assignFiscal(sooner, entity, TODAY.minusYears(1), TODAY.plusDays(3));
        assignFiscal(draft, entity, TODAY.minusYears(1), TODAY.plusDays(2));

        assertThat(findings(expiry(Duration.ofDays(30)).check(tenantId)))
                .extracting(StepResult.Finding::locationId)
                .containsExactly(sooner, locationId);
        assertThat(expiry(Duration.ofDays(30)).severity()).isEqualTo(ReadinessSeverity.EXPIRING);
        assertThat(expiry(Duration.ofDays(30)).advisory())
                .as("an expiring finding does not stop trade today, so allPassed ignores it")
                .isTrue();
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
                    .as("names the legal entity that owns the account: the panel shows one row per account")
                    .contains("ACME");
            assertThat(finding.detail())
                    .as("ADR 0028: a reference to a secret is not for a readiness list either, "
                            + "nor is the merchant's own account reference")
                    .doesNotContain("horecaos:test", "acct-");
        });
    }

    @Test
    void rotationAgeTellsTwoMerchantAccountsOfTheSameProviderApartByTheirLegalEntity() {
        // One live account per legal entity per provider (ux_merchant_binding_live_per_entity),
        // so two CLICK accounts belong to two entities: the entity's code is the only thing that
        // separates their rows, and the account reference is deliberately kept out of the text.
        insertMerchantBinding(insertLegalEntity("ACME"), "CLICK", "ACTIVE", daysAgo(300), null);
        insertMerchantBinding(insertLegalEntity("BETA"), "CLICK", "ACTIVE", daysAgo(250), null);

        List<StepResult.Finding> findings = findings(rotation(ROTATION_PERIOD).check(tenantId));

        assertThat(findings)
                .extracting(StepResult.Finding::detail)
                .as("two accounts, two different sentences")
                .doesNotHaveDuplicates()
                .satisfiesExactly(
                        first -> assertThat(first).contains("ACME", "300 days"),
                        second -> assertThat(second).contains("BETA", "250 days"));
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

    private OnboardingReadinessChecks.ChannelFulfillmentCoverage fulfilment() {
        return new OnboardingReadinessChecks.ChannelFulfillmentCoverage(jdbc);
    }

    private OnboardingReadinessChecks.LocationServiceBindingCoverage bindings() {
        return new OnboardingReadinessChecks.LocationServiceBindingCoverage(jdbc);
    }

    private OnboardingReadinessChecks.LocationForcedClosedNoExpiry forcedClosed() {
        return new OnboardingReadinessChecks.LocationForcedClosedNoExpiry(jdbc, CLOCK);
    }

    private OnboardingReadinessChecks.LocationChannelReach reach() {
        return new OnboardingReadinessChecks.LocationChannelReach(jdbc);
    }

    private OnboardingReadinessChecks.FiscalAssignmentExpiry expiry(Duration window) {
        return new OnboardingReadinessChecks.FiscalAssignmentExpiry(jdbc, CLOCK, window);
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
        insertLocation(owner, ownerBrand, id, "MAIN01", "ACTIVE");
    }

    private void insertLocation(UUID owner, UUID ownerBrand, UUID id, String code, String status) {
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, 'Main', 'Asia/Tashkent', :status, 0)
                """)
                .param("id", id)
                .param("tenantId", owner)
                .param("brandId", ownerBrand)
                .param("code", code)
                .param("slug", "l-" + id.toString().substring(0, 8))
                .param("status", status)
                .update();
    }

    private void setServiceState(
            UUID atLocation,
            String mode,
            @Nullable String reasonCode,
            @Nullable Instant until,
            Instant changedAt,
            @Nullable String note) {
        setServiceState(tenantId, brandId, atLocation, mode, reasonCode, until, changedAt, note);
    }

    private void setServiceState(
            UUID owner,
            UUID ownerBrand,
            UUID atLocation,
            String mode,
            @Nullable String reasonCode,
            @Nullable Instant until,
            Instant changedAt,
            @Nullable String note) {
        jdbc.sql("""
                INSERT INTO tenant.location_service_state
                    (location_id, tenant_id, brand_id, mode, reason_code, note, effective_until, changed_at)
                VALUES (:locationId, :tenantId, :brandId, :mode, :reasonCode, :note, :until, :changedAt)
                """)
                .param("locationId", atLocation)
                .param("tenantId", owner)
                .param("brandId", ownerBrand)
                .param("mode", mode)
                .param("reasonCode", reasonCode)
                .param("note", note)
                .param("until", until == null ? null : OffsetDateTime.ofInstant(until, ZoneOffset.UTC))
                .param("changedAt", OffsetDateTime.ofInstant(changedAt, ZoneOffset.UTC))
                .update();
    }

    private void assignFiscal(UUID atLocation, UUID legalEntityId, LocalDate from, @Nullable LocalDate until) {
        jdbc.sql("""
                INSERT INTO tenant.location_fiscal_assignments
                    (id, tenant_id, brand_id, location_id, legal_entity_id, effective_from, effective_until,
                     approved_by)
                VALUES (:id, :tenantId, :brandId, :locationId, :legalEntityId, :from, :until, 'test')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", atLocation)
                .param("legalEntityId", legalEntityId)
                .param("from", from)
                .param("until", until)
                .update();
    }

    private void setChannelMode(UUID channelId, String mode, boolean enabled) {
        jdbc.sql("""
                INSERT INTO tenant.channel_fulfillment_modes (tenant_id, channel_id, fulfillment_mode, enabled)
                VALUES (:tenantId, :channelId, :mode, :enabled)
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("mode", mode)
                .param("enabled", enabled)
                .update();
    }

    private void serveChannelAt(UUID channelId, UUID atLocation, String status) {
        jdbc.sql("""
                INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
                VALUES (:tenantId, :channelId, :locationId, :status)
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("locationId", atLocation)
                .param("status", status)
                .update();
    }

    /** Binds a fresh schedule of the fixture brand to the location for one mode. */
    private void bindSchedule(UUID atLocation, String mode) {
        UUID scheduleId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.service_schedules (id, tenant_id, brand_id, name)
                VALUES (:id, :tenantId, :brandId, :name)
                """)
                .param("id", scheduleId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("name", "Hours " + scheduleId)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.location_service_bindings
                    (tenant_id, brand_id, location_id, fulfillment_mode, schedule_id)
                VALUES (:tenantId, :brandId, :locationId, :mode, :scheduleId)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", atLocation)
                .param("mode", mode)
                .param("scheduleId", scheduleId)
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
                VALUES (:id, :tenantId, :code, :legalName, :tin, false, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("legalName", code + " LLC")
                // Nine digits, and different for each code: a tenant's entities have distinct TINs.
                .param("tin", "%09d".formatted(Math.abs((long) code.hashCode()) % 1_000_000_000L))
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
                        :accountReference, :secretRef, :segment, true, true, :status, :from, :createdAt,
                        :lastRotatedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("legalEntityId", legalEntityId)
                .param("providerType", providerType)
                // A merchant account belongs to exactly one legal entity (ux_merchant_account_belongs_to_one_entity).
                .param("accountReference", "acct-" + legalEntityId)
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
