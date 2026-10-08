package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcPolicyResolver;

/**
 * The {@code ordering.lateness} document resolved through the real ADR 0030
 * mechanism (gap map rows {@code 1.1g}/{@code X.39}) — against the real
 * resolver and real SQL, matching {@code OrderAcceptancePolicyServiceTests}'
 * own reasoning: the point of ADR 0030 is one precedence implementation, so a
 * stub resolver would test the thing ADR 0030 replaced.
 *
 * <p>No authoring here: {@code OrderLatenessPolicyService} only reads --
 * publishing is {@code OrderLatenessPolicyAuthoringService}, tested in its own
 * class -- so an override is inserted directly, the same way this suite's
 * sibling inserts one for {@code ordering.acceptance}.
 */
class OrderLatenessPolicyServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131101");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131102");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131104");
    private static final UUID SIBLING_LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131105");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private OrderLatenessPolicyService service;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
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
        jdbc.sql("TRUNCATE TABLE tenant.policy_current CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        service = new OrderLatenessPolicyService(
                new JdbcPolicyResolver(jdbc, JsonMapper.builder().build()),
                new uz.horecaos.platform.support.FakeConfigurationResolver());
        insertHierarchy();
    }

    @Test
    void fallsBackToThePlatformDefaultPerFulfilmentModeWhenNothingIsAuthored() {
        OrderLatenessPolicyService.Effective effective = service.resolve(TENANT, BRAND, LOCATION);

        assertThat(effective.isPlatformDefault())
                .as("that the platform default applied is itself a fact worth carrying")
                .isTrue();
        assertThat(effective.policy()).isEqualTo(OrderLatenessPolicy.platformDefault());
    }

    @Test
    void aLocationOverrideWinsOverTheTenantDefault() {
        activate("TENANT", null, null, thresholdsSet(300, 0, 2700));
        activate("LOCATION", BRAND, LOCATION, thresholdsSet(120, 30, 1800));

        OrderLatenessPolicy atLocation =
                service.resolve(TENANT, BRAND, LOCATION).policy();
        assertThat(atLocation.delivery().atRiskBeforeSeconds()).isEqualTo(120);
        assertThat(atLocation.delivery().lateAfterSeconds()).isEqualTo(30);
        assertThat(atLocation.delivery().noPromiseFallbackSeconds()).isEqualTo(1800);

        OrderLatenessPolicy atSibling =
                service.resolve(TENANT, BRAND, SIBLING_LOCATION).policy();
        assertThat(atSibling.delivery().atRiskBeforeSeconds())
                .as("the override belongs to this location alone")
                .isEqualTo(300);
    }

    @Test
    void eachFulfilmentModeRoundTripsItsOwnNumbersThroughTheStoredDocument() {
        String document = """
                {"delivery":{"atRiskBeforeSeconds":300,"lateAfterSeconds":60,"noPromiseFallbackSeconds":2700},
                 "pickup":{"atRiskBeforeSeconds":180,"lateAfterSeconds":0,"noPromiseFallbackSeconds":1800},
                 "dineIn":{"atRiskBeforeSeconds":90,"lateAfterSeconds":0,"noPromiseFallbackSeconds":1200}}""";
        activate("TENANT", null, null, document);

        OrderLatenessPolicy resolved = service.resolve(TENANT, BRAND, LOCATION).policy();

        assertThat(resolved.delivery().lateAfterSeconds())
                .as("delivery alone carries the grace period")
                .isEqualTo(60);
        assertThat(resolved.pickup().atRiskBeforeSeconds()).isEqualTo(180);
        assertThat(resolved.dineIn().noPromiseFallbackSeconds()).isEqualTo(1200);
    }

    @Test
    void resolveAtReadsTheSameChainAtAnyScope() {
        activate("TENANT", null, null, thresholdsSet(300, 0, 2700));

        OrderLatenessPolicyService.Effective atTenant = service.resolveAt(ResourceScope.tenant(TENANT));

        assertThat(atTenant.isPlatformDefault()).isFalse();
        assertThat(atTenant.policy().pickup().atRiskBeforeSeconds()).isEqualTo(300);
    }

    // -------------------------------------- X.39: the tenant's own scalars overlaid

    private OrderLatenessPolicyService serviceWith(java.util.Map<String, Object> configured) {
        return new OrderLatenessPolicyService(
                new JdbcPolicyResolver(jdbc, JsonMapper.builder().build()),
                new uz.horecaos.platform.support.FakeConfigurationResolver(configured));
    }

    @Test
    void aSetAtRiskScalarIsTheDefaultOnlyForTheModesTheDocumentLeavesUnset() {
        // Wave 16 (X.39 / 10.3b): the per-mode at-risk minutes replace batch 15's single scalar; the
        // scalar stays as the default for a mode the document does not set. DELIVERY is left unset
        // here, PICKUP and DINE_IN carry their own.
        activate("TENANT", null, null, """
                {"delivery":{"atRiskBeforeSeconds":null,"lateAfterSeconds":60,"noPromiseFallbackSeconds":2700},
                 "pickup":{"atRiskBeforeSeconds":180,"lateAfterSeconds":10,"noPromiseFallbackSeconds":1800},
                 "dineIn":{"atRiskBeforeSeconds":90,"lateAfterSeconds":0,"noPromiseFallbackSeconds":1200}}""");

        OrderLatenessPolicy resolved = serviceWith(java.util.Map.of("ordering.at_risk_before_minutes", 10))
                .resolve(TENANT, BRAND, LOCATION)
                .policy();

        assertThat(resolved.delivery().atRiskBeforeSeconds())
                .as("a mode with no value of its own takes the scalar")
                .isEqualTo(600);
        assertThat(resolved.pickup().atRiskBeforeSeconds())
                .as("a mode's own value beats the scalar")
                .isEqualTo(180);
        assertThat(resolved.dineIn().atRiskBeforeSeconds()).isEqualTo(90);
        assertThat(resolved.delivery().lateAfterSeconds())
                .as("grace stays per mode")
                .isEqualTo(60);
        assertThat(resolved.pickup().lateAfterSeconds()).isEqualTo(10);
        assertThat(resolved.dineIn().noPromiseFallbackSeconds()).isEqualTo(1200);
    }

    @Test
    void withNoDocumentAuthoredTheScalarStillMovesEveryModesAtRiskEdge() {
        OrderLatenessPolicy resolved = serviceWith(java.util.Map.of("ordering.at_risk_before_minutes", 10))
                .resolve(TENANT, BRAND, LOCATION)
                .policy();

        assertThat(resolved.delivery().atRiskBeforeSeconds()).isEqualTo(600);
        assertThat(resolved.pickup().atRiskBeforeSeconds()).isEqualTo(600);
        assertThat(resolved.dineIn().atRiskBeforeSeconds()).isEqualTo(600);
        assertThat(resolved.delivery().noPromiseFallbackSeconds()).isEqualTo(2700);
    }

    @Test
    void theKeysOwnDefaultNeverOverwritesAnAuthoredDocument() {
        activate("TENANT", null, null, thresholdsSet(180, 0, 2700));

        OrderLatenessPolicy resolved = service.resolve(TENANT, BRAND, LOCATION).policy();

        assertThat(resolved.delivery().atRiskBeforeSeconds())
                .as("nothing was set, so the document's own 180s stands rather than the key's default 5 minutes")
                .isEqualTo(180);
    }

    @Test
    void theKeyDefaultIsThePlatformDefaultsOwnAtRiskWindow() {
        assertThat(java.util.Objects.requireNonNull(
                                uz.horecaos.platform.ordering.api.OrderingConfigurationKeys.AT_RISK_BEFORE_MINUTES
                                        .defaultValue())
                        * 60)
                .isEqualTo(OrderLatenessPolicy.platformDefault().delivery().atRiskBeforeSeconds());
    }

    @Test
    void zeroMinutesMeansWarnOnlyAtThePromise() {
        OrderLatenessPolicy resolved = serviceWith(java.util.Map.of("ordering.at_risk_before_minutes", 0))
                .resolve(TENANT, BRAND, LOCATION)
                .policy();

        assertThat(resolved.pickup().atRiskBeforeSeconds()).isZero();
    }

    @Test
    void anUnusableStoredThresholdIsIgnoredRatherThanTakingTheBoardDown() {
        // Refused at write time, so this can only be a row written around the rule; LatenessThresholds
        // would throw on a negative number, and the board read would 500 for every user.
        OrderLatenessPolicy resolved = serviceWith(java.util.Map.of("ordering.at_risk_before_minutes", -3))
                .resolve(TENANT, BRAND, LOCATION)
                .policy();

        assertThat(resolved).isEqualTo(OrderLatenessPolicy.platformDefault());
    }

    @Test
    void aLateColourIsServedOnlyWhenItIsExactlySixHexDigitsAndIsLowerCased() {
        assertThat(serviceWith(java.util.Map.of("ordering.late_colour", "#8A3FFC"))
                        .resolve(TENANT, BRAND, LOCATION)
                        .lateColour())
                .isEqualTo("#8a3ffc");

        for (String unusable : java.util.List.of("", "red", "#fff", "#8a3ffc;", "url(x)", "#gggggg")) {
            assertThat(serviceWith(java.util.Map.of("ordering.late_colour", unusable))
                            .resolve(TENANT, BRAND, LOCATION)
                            .lateColour())
                    .as("'%s' must not reach a stylesheet", unusable)
                    .isNull();
        }
        assertThat(service.resolve(TENANT, BRAND, LOCATION).lateColour())
                .as("unset")
                .isNull();
    }

    // ------------------------------------ wave 16: the authored form and its default

    @Test
    void theAuthoredDocumentKeepsAnUnsetWindowUnsetWhereTheResolvedPolicyFillsItIn() {
        activate("TENANT", null, null, """
                {"delivery":{"atRiskBeforeSeconds":null,"lateAfterSeconds":60,"noPromiseFallbackSeconds":2700},
                 "pickup":{"atRiskBeforeSeconds":0,"lateAfterSeconds":0,"noPromiseFallbackSeconds":1800},
                 "dineIn":{"atRiskBeforeSeconds":90,"lateAfterSeconds":0,"noPromiseFallbackSeconds":1200}}""");
        OrderLatenessPolicyService withScalar = serviceWith(java.util.Map.of("ordering.at_risk_before_minutes", 12));

        OrderLatenessPolicyService.Authored authored =
                withScalar.authoredAt(ResourceScope.location(TENANT, BRAND, LOCATION));

        assertThat(authored.document().delivery().atRiskBeforeSeconds())
                .as("the editor must be able to tell 'unset' from a window")
                .isNull();
        assertThat(authored.document().pickup().atRiskBeforeSeconds()).isZero();
        assertThat(authored.winningScope()).isEqualTo(ResourceScope.ScopeType.TENANT);
        assertThat(authored.policyVersion()).isEqualTo(1);
        assertThat(withScalar
                        .resolve(TENANT, BRAND, LOCATION)
                        .policy()
                        .delivery()
                        .atRiskBeforeSeconds())
                .as("while the boards' answer has the scalar filled in")
                .isEqualTo(720);
    }

    @Test
    void withNothingAuthoredTheAuthoredDocumentIsTheUnsetPlatformDefaultWithNoWinningScope() {
        OrderLatenessPolicyService.Authored authored =
                service.authoredAt(ResourceScope.location(TENANT, BRAND, LOCATION));

        assertThat(authored.document())
                .isEqualTo(uz.horecaos.platform.ordering.domain.OrderLatenessDocument.platformDefault());
        assertThat(authored.policyId()).isNull();
        assertThat(authored.policyVersion()).isZero();
        assertThat(authored.winningScope()).isNull();
    }

    @Test
    void theDefaultWindowIsTheScalarOnlyWhenOneWasSetAndThePlatformsFiveMinutesOtherwise() {
        ResourceScope scope = ResourceScope.location(TENANT, BRAND, LOCATION);

        OrderLatenessPolicyService.AtRiskDefault unset = service.atRiskDefaultAt(scope);
        assertThat(unset.seconds()).isEqualTo(300);
        assertThat(unset.source()).isEqualTo(OrderLatenessPolicyService.AtRiskDefault.Source.PLATFORM_DEFAULT);

        OrderLatenessPolicyService.AtRiskDefault set = serviceWith(
                        java.util.Map.of("ordering.at_risk_before_minutes", 12))
                .atRiskDefaultAt(scope);
        assertThat(set.seconds()).isEqualTo(720);
        assertThat(set.source()).isEqualTo(OrderLatenessPolicyService.AtRiskDefault.Source.SCALAR);

        OrderLatenessPolicyService.AtRiskDefault zero = serviceWith(
                        java.util.Map.of("ordering.at_risk_before_minutes", 0))
                .atRiskDefaultAt(scope);
        assertThat(zero.seconds()).as("zero minutes is a real setting").isZero();
        assertThat(zero.source()).isEqualTo(OrderLatenessPolicyService.AtRiskDefault.Source.SCALAR);
    }

    @Test
    void eachModeResolvesItsOwnWindowThroughTheTenantBrandLocationChain() {
        activate("TENANT", null, null, """
                {"delivery":{"atRiskBeforeSeconds":900,"lateAfterSeconds":0,"noPromiseFallbackSeconds":2700},
                 "pickup":{"atRiskBeforeSeconds":null,"lateAfterSeconds":0,"noPromiseFallbackSeconds":2700},
                 "dineIn":{"atRiskBeforeSeconds":null,"lateAfterSeconds":0,"noPromiseFallbackSeconds":2700}}""");
        activate("LOCATION", BRAND, LOCATION, """
                {"delivery":{"atRiskBeforeSeconds":null,"lateAfterSeconds":0,"noPromiseFallbackSeconds":2700},
                 "pickup":{"atRiskBeforeSeconds":60,"lateAfterSeconds":0,"noPromiseFallbackSeconds":2700},
                 "dineIn":{"atRiskBeforeSeconds":null,"lateAfterSeconds":0,"noPromiseFallbackSeconds":2700}}""");
        OrderLatenessPolicyService withScalar = serviceWith(java.util.Map.of("ordering.at_risk_before_minutes", 7));

        OrderLatenessPolicy atLocation =
                withScalar.resolve(TENANT, BRAND, LOCATION).policy();
        OrderLatenessPolicy atSibling =
                withScalar.resolve(TENANT, BRAND, SIBLING_LOCATION).policy();

        assertThat(atLocation.delivery().atRiskBeforeSeconds())
                .as("the location's document replaces the tenant's whole: delivery says nothing there")
                .isEqualTo(420);
        assertThat(atLocation.pickup().atRiskBeforeSeconds()).isEqualTo(60);
        assertThat(atLocation.dineIn().atRiskBeforeSeconds()).isEqualTo(420);
        assertThat(atSibling.delivery().atRiskBeforeSeconds())
                .as("the sibling still resolves the tenant's own delivery window")
                .isEqualTo(900);
        assertThat(atSibling.pickup().atRiskBeforeSeconds()).isEqualTo(420);
    }

    // ---------------------- ADR 0150: the scalar is the default for the no-promise part

    private static final String LATE_THRESHOLD = "ordering.late_order_threshold_minutes";

    @Test
    void withTheLateOrderThresholdUnsetAnUnpromisedOrderIsLateAtFortyFiveMinutesFromCreation() {
        // Pinned first: this is what every tenant that never touched the setting has today, and what the
        // reader must not move.
        OrderLatenessPolicy resolved = service.resolve(TENANT, BRAND, LOCATION).policy();

        for (var mode : uz.horecaos.platform.tenancy.api.FulfillmentMode.values()) {
            assertThat(resolved.forMode(mode).noPromiseFallbackSeconds())
                    .as(mode.name())
                    .isEqualTo(2700);
        }
    }

    @Test
    void aTenantWideLateOrderThresholdIsTheNoPromiseFallbackOfEveryModeAndNothingElse() {
        OrderLatenessPolicy resolved = serviceWith(java.util.Map.of(LATE_THRESHOLD, 20))
                .resolve(TENANT, BRAND, LOCATION)
                .policy();

        for (var mode : uz.horecaos.platform.tenancy.api.FulfillmentMode.values()) {
            assertThat(resolved.forMode(mode).noPromiseFallbackSeconds())
                    .as("%s: 20 minutes from creation".formatted(mode))
                    .isEqualTo(1200);
            assertThat(resolved.forMode(mode).lateAfterSeconds())
                    .as("a promised order's grace is the document's, not the scalar's")
                    .isZero();
            assertThat(resolved.forMode(mode).atRiskBeforeSeconds()).isEqualTo(300);
        }
    }

    @Test
    void aModesOwnFallbackWinsOverTheScalarAndABlankOneTakesIt() {
        activate("TENANT", null, null, """
                {"delivery":{"atRiskBeforeSeconds":null,"lateAfterSeconds":60,"noPromiseFallbackSeconds":3600},
                 "pickup":{"atRiskBeforeSeconds":null,"lateAfterSeconds":0,"noPromiseFallbackSeconds":null},
                 "dineIn":{"atRiskBeforeSeconds":null,"lateAfterSeconds":0}}""");

        OrderLatenessPolicy resolved = serviceWith(java.util.Map.of(LATE_THRESHOLD, 20))
                .resolve(TENANT, BRAND, LOCATION)
                .policy();

        assertThat(resolved.delivery().noPromiseFallbackSeconds())
                .as("the document's own number for the mode wins")
                .isEqualTo(3600);
        assertThat(resolved.pickup().noPromiseFallbackSeconds())
                .as("a blank in the document means none of its own: the scalar applies")
                .isEqualTo(1200);
        assertThat(resolved.dineIn().noPromiseFallbackSeconds())
                .as("a document that leaves the field out reads the same as a blank")
                .isEqualTo(1200);
    }

    @Test
    void aDocumentWrittenBeforeTheFallbackWasOptionalStillOwnsItsNumbers() {
        activate("TENANT", null, null, thresholdsSet(300, 0, 1800));

        OrderLatenessPolicy resolved = serviceWith(java.util.Map.of(LATE_THRESHOLD, 20))
                .resolve(TENANT, BRAND, LOCATION)
                .policy();

        assertThat(resolved.delivery().noPromiseFallbackSeconds())
                .as("a stored value is a value of its own, whatever the scalar says")
                .isEqualTo(1800);
    }

    @Test
    void theNoPromiseDefaultIsTheScalarOnlyWhenOneWasSetAndThePlatformsFortyFiveOtherwise() {
        ResourceScope scope = ResourceScope.location(TENANT, BRAND, LOCATION);

        OrderLatenessPolicyService.NoPromiseDefault unset = service.noPromiseDefaultAt(scope);
        assertThat(unset.seconds()).isEqualTo(2700);
        assertThat(unset.source()).isEqualTo(OrderLatenessPolicyService.NoPromiseDefault.Source.PLATFORM_DEFAULT);

        OrderLatenessPolicyService.NoPromiseDefault set =
                serviceWith(java.util.Map.of(LATE_THRESHOLD, 20)).noPromiseDefaultAt(scope);
        assertThat(set.seconds()).isEqualTo(1200);
        assertThat(set.source()).isEqualTo(OrderLatenessPolicyService.NoPromiseDefault.Source.SCALAR);

        OrderLatenessPolicyService.NoPromiseDefault chosenAsTheDefault =
                serviceWith(java.util.Map.of(LATE_THRESHOLD, 45)).noPromiseDefaultAt(scope);
        assertThat(chosenAsTheDefault.seconds()).isEqualTo(2700);
        assertThat(chosenAsTheDefault.source())
                .as("a tenant that typed 45 chose it; only an unset key is the platform's own answer")
                .isEqualTo(OrderLatenessPolicyService.NoPromiseDefault.Source.SCALAR);
    }

    @Test
    void anUnusableStoredLateOrderThresholdIsIgnoredRatherThanTakingTheBoardDown() {
        // Refused at write time (1 to 600), so only a row written around the rule can be here; the
        // thresholds record would throw on a negative number and the board read would 500 for everyone.
        for (int unusable : new int[] {0, -5, 601, 1441, Integer.MAX_VALUE}) {
            OrderLatenessPolicyService unusableValue = serviceWith(java.util.Map.of(LATE_THRESHOLD, unusable));
            assertThat(unusableValue.resolve(TENANT, BRAND, LOCATION).policy())
                    .as("value %d", unusable)
                    .isEqualTo(OrderLatenessPolicy.platformDefault());
            assertThat(unusableValue
                            .noPromiseDefaultAt(ResourceScope.location(TENANT, BRAND, LOCATION))
                            .source())
                    .isEqualTo(OrderLatenessPolicyService.NoPromiseDefault.Source.PLATFORM_DEFAULT);
        }
    }

    @Test
    void theKeyDefaultIsThePlatformDefaultsOwnNoPromiseFallback() {
        assertThat(java.util.Objects.requireNonNull(
                                uz.horecaos.platform.ordering.api.OrderingConfigurationKeys.LATE_ORDER_THRESHOLD_MINUTES
                                        .defaultValue())
                        * 60)
                .as("registering the reader must change nothing for a tenant that has not set the key")
                .isEqualTo(OrderLatenessPolicy.platformDefault().delivery().noPromiseFallbackSeconds());
    }

    private String thresholdsSet(int atRiskBefore, int lateAfter, int noPromiseFallback) {
        String single = "{\"atRiskBeforeSeconds\":%d,\"lateAfterSeconds\":%d,\"noPromiseFallbackSeconds\":%d}"
                .formatted(atRiskBefore, lateAfter, noPromiseFallback);
        return "{\"delivery\":%s,\"pickup\":%s,\"dineIn\":%s}".formatted(single, single, single);
    }

    private void activate(String scopeType, @Nullable UUID brandId, @Nullable UUID locationId, String document) {
        UUID id = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO tenant.policies (
                    id, key_code, scope_type, tenant_id, brand_id, location_id, version, status,
                    document, document_hash, valid_from, created_by)
                VALUES (:id, 'ordering.lateness', :scopeType, :tenantId, :brandId, :locationId,
                        1, 'ACTIVE', CAST(:document AS jsonb), :hash, now(), 'test')
                """)
                .param("id", id)
                .param("scopeType", scopeType)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("document", document)
                .param("hash", "%064x".formatted(BigInteger.valueOf(document.hashCode() & 0xFFFFFFFFL)))
                .update();

        jdbc.sql("""
                INSERT INTO tenant.policy_current (
                    key_code, scope_type, tenant_id, brand_id, location_id,
                    policy_id, policy_version, activated_by)
                VALUES ('ordering.lateness', :scopeType, :tenantId, :brandId, :locationId,
                        :id, 1, 'test')
                """)
                .param("scopeType", scopeType)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("id", id)
                .update();
    }

    private static String suffix(UUID id) {
        String text = id.toString().replace("-", "");
        return text.substring(text.length() - 6);
    }

    private void insertHierarchy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-lateness', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", BRAND)
                .param("tenantId", TENANT)
                .param("code", "B" + suffix(BRAND).toUpperCase(Locale.ROOT))
                .param("slug", "b-" + suffix(BRAND))
                .update();
        for (UUID location : new UUID[] {LOCATION, SIBLING_LOCATION}) {
            jdbc.sql("""
                    INSERT INTO tenant.locations
                        (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                    VALUES (:id, :tenantId, :brandId, :code, :slug, 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", location)
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("code", "L" + suffix(location).toUpperCase(Locale.ROOT))
                    .param("slug", "l-" + suffix(location))
                    .update();
        }
    }
}
