package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.api.OrderingConfigurationKeys;
import uz.horecaos.platform.ordering.application.OrderLatenessPolicyAuthoringService.Editor;
import uz.horecaos.platform.ordering.domain.OrderLatenessDocument;
import uz.horecaos.platform.ordering.domain.OrderLatenessDocument.ModeThresholds;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcPolicyAuthor;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcPolicyResolver;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The {@code ordering.lateness} editor's service (gap map rows {@code X.39}/{@code 10.3b}) against the
 * real resolver, the real writer and real SQL -- the same reason {@code OrderLatenessPolicyServiceTests}
 * gives: a stub would test the precedence and the version check this feature relies on being the
 * platform's one implementation.
 */
class OrderLatenessPolicyAuthoringServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131801");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131802");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131804");
    private static final UUID SIBLING_LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131805");

    private static final ActorRef OWNER = ActorRef.user("owner-1", null);
    private static final ResourceScope TENANT_SCOPE = ResourceScope.tenant(TENANT);
    private static final ResourceScope BRAND_SCOPE = ResourceScope.brand(TENANT, BRAND);
    private static final ResourceScope LOCATION_SCOPE = ResourceScope.location(TENANT, BRAND, LOCATION);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private final List<AuditFact> facts = new ArrayList<>();
    private OrderLatenessPolicyService reads;
    private OrderLatenessPolicyAuthoringService authoring;

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
        facts.clear();
        insertHierarchy();
        serviceWith(Map.of());
    }

    private void serviceWith(Map<String, Object> configured) {
        JsonMapper mapper = JsonMapper.builder().build();
        Clock clock = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);
        reads = new OrderLatenessPolicyService(
                new JdbcPolicyResolver(jdbc, mapper), new FakeConfigurationResolver(configured));
        authoring = new OrderLatenessPolicyAuthoringService(
                reads,
                new JdbcPolicyAuthor(jdbc, mapper, facts::add, clock, (keyCode, scope) -> {}),
                facts::add,
                clock);
    }

    // ------------------------------------------------------------------ publish

    @Test
    void aPublishedDocumentIsWhatTheBoardsResolveForEachModeAtOnce() {
        OrderLatenessDocument document = new OrderLatenessDocument(
                new ModeThresholds(600, 60, 3600),
                new ModeThresholds(120, 0, 1800),
                new ModeThresholds(null, 30, 1200));

        Editor published = authoring.author(TENANT_SCOPE, document, null, OWNER, "the kitchen is slower on deliveries");

        assertThat(published.policyVersion()).isEqualTo(1);
        assertThat(published.versionAtScope()).isEqualTo(1);
        assertThat(published.winningScope()).isEqualTo(ScopeType.TENANT);
        assertThat(published.isPlatformDefault()).isFalse();

        OrderLatenessPolicy resolved = reads.resolve(TENANT, BRAND, LOCATION).policy();
        assertThat(resolved.delivery().atRiskBeforeSeconds()).isEqualTo(600);
        assertThat(resolved.delivery().lateAfterSeconds()).isEqualTo(60);
        assertThat(resolved.delivery().noPromiseFallbackSeconds()).isEqualTo(3600);
        assertThat(resolved.pickup().atRiskBeforeSeconds()).isEqualTo(120);
        assertThat(resolved.dineIn().atRiskBeforeSeconds())
                .as("dine-in set none: the platform's five minutes")
                .isEqualTo(300);
        assertThat(resolved.dineIn().lateAfterSeconds()).isEqualTo(30);
        assertThat(published.effective()).isEqualTo(resolved);
    }

    @Test
    void aBlankWindowFollowsTheTenantsScalarAndSaysSo() {
        serviceWith(Map.of("ordering.at_risk_before_minutes", 12));

        Editor published = authoring.author(TENANT_SCOPE, uniform(null, 0, 2700), null, OWNER, "default everywhere");

        assertThat(published.atRiskDefault().seconds()).isEqualTo(720);
        assertThat(published.atRiskDefault().source())
                .isEqualTo(OrderLatenessPolicyService.AtRiskDefault.Source.SCALAR);
        assertThat(published.document().delivery().atRiskBeforeSeconds()).isNull();
        assertThat(published.effective().delivery().atRiskBeforeSeconds()).isEqualTo(720);
        assertThat(reads.resolve(TENANT, BRAND, LOCATION).policy().pickup().atRiskBeforeSeconds())
                .isEqualTo(720);
    }

    @Test
    void aSecondVersionSupersedesTheFirstForNewResolutions() {
        authoring.author(TENANT_SCOPE, uniform(300, 0, 2700), null, OWNER, "first");

        Editor second = authoring.author(TENANT_SCOPE, uniform(600, 0, 2700), 1, OWNER, "second");

        assertThat(second.policyVersion()).isEqualTo(2);
        assertThat(reads.resolve(TENANT, BRAND, LOCATION).policy().delivery().atRiskBeforeSeconds())
                .isEqualTo(600);
    }

    // ------------------------------------------------------------------- scope

    @Test
    void aBrandOrLocationInheritsUntilItPublishesItsOwnAndThenOnlyItIsAffected() {
        authoring.author(TENANT_SCOPE, uniform(300, 0, 2700), null, OWNER, "tenant default");

        Editor inherited = authoring.view(LOCATION_SCOPE);
        assertThat(inherited.winningScope()).isEqualTo(ScopeType.TENANT);
        assertThat(inherited.policyVersion()).isEqualTo(1);
        assertThat(inherited.versionAtScope())
                .as("nothing authored at exactly the location: it opens at 0 whatever its ancestor's version")
                .isZero();
        assertThat(inherited.levels())
                .extracting(OrderLatenessPolicyAuthoringService.Level::scopeType)
                .containsExactly(ScopeType.LOCATION, ScopeType.BRAND, ScopeType.TENANT, ScopeType.PLATFORM);
        assertThat(inherited.levels())
                .extracting(OrderLatenessPolicyAuthoringService.Level::authored)
                .containsExactly(false, false, true, false);

        Editor override = authoring.author(LOCATION_SCOPE, uniform(60, 0, 900), 0, OWNER, "a food-court counter");
        assertThat(override.versionAtScope()).isEqualTo(1);
        assertThat(override.winningScope()).isEqualTo(ScopeType.LOCATION);

        assertThat(reads.resolve(TENANT, BRAND, LOCATION).policy().pickup().atRiskBeforeSeconds())
                .isEqualTo(60);
        assertThat(reads.resolve(TENANT, BRAND, SIBLING_LOCATION)
                        .policy()
                        .pickup()
                        .atRiskBeforeSeconds())
                .as("the override is this location's alone")
                .isEqualTo(300);
        assertThat(authoring.view(BRAND_SCOPE).versionAtScope()).isZero();
    }

    @Test
    void theEditorAtAScopeWithNoDocumentAnywhereIsThePlatformDefault() {
        Editor view = authoring.view(BRAND_SCOPE);

        assertThat(view.isPlatformDefault()).isTrue();
        assertThat(view.winningScope()).isNull();
        assertThat(view.policyId()).isNull();
        assertThat(view.policyVersion()).isZero();
        assertThat(view.versionAtScope()).isZero();
        assertThat(view.document().delivery().atRiskBeforeSeconds()).isNull();
        assertThat(view.effective()).isEqualTo(OrderLatenessPolicy.platformDefault());
        assertThat(view.atRiskDefault().source())
                .isEqualTo(OrderLatenessPolicyService.AtRiskDefault.Source.PLATFORM_DEFAULT);
    }

    // --------------------------------------------------------------------- CAS

    @Test
    void aStaleFormIsRefusedAndTheOtherOperatorsVersionStands() {
        authoring.author(TENANT_SCOPE, uniform(300, 0, 2700), null, OWNER, "opened by both");
        authoring.author(TENANT_SCOPE, uniform(600, 0, 2700), 1, OWNER, "the first to save");
        facts.clear();

        assertThatThrownBy(() -> authoring.author(TENANT_SCOPE, uniform(120, 0, 2700), 1, OWNER, "the slow one"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ErrorCode.STALE_VERSION);
                    assertThat(error.properties()).containsEntry("currentVersion", 2L);
                });

        assertThat(reads.resolve(TENANT, BRAND, LOCATION).policy().delivery().atRiskBeforeSeconds())
                .isEqualTo(600);
        assertThat(facts).as("a refused write leaves no audit fact behind").isEmpty();
    }

    @Test
    void anOpenerWhoSawNothingAuthoredCannotOverwriteWhatWasPublishedSince() {
        authoring.author(BRAND_SCOPE, uniform(300, 0, 2700), null, OWNER, "someone got there first");

        assertThatThrownBy(() -> authoring.author(BRAND_SCOPE, uniform(120, 0, 2700), null, OWNER, "opened at 0"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
    }

    // ------------------------------------------------- the editor reads the table

    /**
     * What the production resolver does for up to a minute after a publication it was not told about
     * (a reader that cached the old version between the eviction and the commit): {@code resolve}
     * answers {@code held}, while {@code resolveUncached} and {@code pinned} go to the table.
     */
    private OrderLatenessPolicyAuthoringService authoringOverAResolverHolding(
            ResolvedPolicy<OrderLatenessDocument> held) {
        JsonMapper mapper = JsonMapper.builder().build();
        Clock clock = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);
        JdbcPolicyResolver table = new JdbcPolicyResolver(jdbc, mapper);
        PolicyResolver stale = new PolicyResolver() {
            @Override
            @SuppressWarnings("unchecked")
            public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
                return Optional.of((ResolvedPolicy<P>) held);
            }

            @Override
            public <P> Optional<ResolvedPolicy<P>> resolveUncached(PolicyKey<P> key, ResourceScope scope) {
                return table.resolveUncached(key, scope);
            }

            @Override
            public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
                return table.pinned(key, policyId, policyVersion);
            }
        };
        return new OrderLatenessPolicyAuthoringService(
                new OrderLatenessPolicyService(stale, new FakeConfigurationResolver(Map.of())),
                new JdbcPolicyAuthor(jdbc, mapper, facts::add, clock, (keyCode, scope) -> {}),
                facts::add,
                clock);
    }

    private ResolvedPolicy<OrderLatenessDocument> tenantVersionOneAsACacheStillHoldsIt() {
        authoring.author(TENANT_SCOPE, uniform(300, 0, 2700), null, OWNER, "version one");
        ResolvedPolicy<OrderLatenessDocument> held = new JdbcPolicyResolver(
                        jdbc, JsonMapper.builder().build())
                .resolveUncached(OrderingConfigurationKeys.LATENESS_POLICY, LOCATION_SCOPE)
                .orElseThrow();
        authoring.author(TENANT_SCOPE, uniform(600, 60, 2700), 1, OWNER, "version two");
        return held;
    }

    @Test
    void theEditorShowsTheDocumentInTheTableNotAnOlderOneTheResolverStillHolds() {
        OrderLatenessPolicyAuthoringService editor =
                authoringOverAResolverHolding(tenantVersionOneAsACacheStillHoldsIt());

        Editor view = editor.view(LOCATION_SCOPE);

        assertThat(view.policyVersion())
                .as("the resolver still holds version 1; the table has 2")
                .isEqualTo(2);
        assertThat(view.document().delivery().atRiskBeforeSeconds()).isEqualTo(600);
        assertThat(view.document().delivery().lateAfterSeconds()).isEqualTo(60);
        assertThat(view.versionAtScope())
                .as("and the location has authored nothing of its own")
                .isZero();
    }

    @Test
    void theAuditBeforeIsWhatTheTableHeldNotWhatTheResolverStillHeld() {
        OrderLatenessPolicyAuthoringService editor =
                authoringOverAResolverHolding(tenantVersionOneAsACacheStillHoldsIt());
        facts.clear();

        editor.author(LOCATION_SCOPE, uniform(60, 0, 900), 0, OWNER, "a food-court counter");

        AuditFact fact = facts.stream()
                .filter(candidate -> candidate.actionCode().equals("ordering.lateness-policy.authored"))
                .findFirst()
                .orElseThrow();
        assertThat(change(fact, "policyVersion"))
                .as("the tenant's version 2 was in force when the location overrode it")
                .containsEntry("before", 2);
        assertThat(change(fact, "delivery.atRiskBeforeSeconds")).containsEntry("before", 600);
    }

    // -------------------------------------------------------------- validation

    @Test
    void aDocumentOutsideTheBoundsIsRefusedNamingEveryBoxAndPublishesNothing() {
        OrderLatenessDocument bad = new OrderLatenessDocument(
                new ModeThresholds(90, 0, 2700), new ModeThresholds(null, -1, 2700), new ModeThresholds(null, 0, 0));

        assertThatThrownBy(() -> authoring.author(TENANT_SCOPE, bad, null, OWNER, "bad numbers"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(error.getMessage())
                            .contains("DELIVERY atRiskBeforeSeconds")
                            .contains("PICKUP lateAfterSeconds")
                            .contains("DINE_IN noPromiseFallbackSeconds");
                });

        assertThat(authoring.view(TENANT_SCOPE).versionAtScope()).isZero();
        assertThat(facts).isEmpty();
    }

    @Test
    void aBlankReasonIsRefused() {
        assertThatThrownBy(() -> authoring.author(TENANT_SCOPE, uniform(300, 0, 2700), null, OWNER, "  "))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    // ------------------------------------------------------------------- audit

    @Test
    void thePublicationLeavesAFieldLevelBeforeAfterAgainstWhatWasInForce() {
        authoring.author(TENANT_SCOPE, uniform(300, 0, 2700), null, OWNER, "tenant default");
        facts.clear();

        authoring.author(
                LOCATION_SCOPE,
                new OrderLatenessDocument(
                        new ModeThresholds(300, 0, 2700),
                        new ModeThresholds(300, 120, 2700),
                        new ModeThresholds(null, 0, 2700)),
                0,
                OWNER,
                "pickup gets two minutes of grace here");

        AuditFact fact = facts.stream()
                .filter(candidate -> candidate.actionCode().equals("ordering.lateness-policy.authored"))
                .findFirst()
                .orElseThrow();
        assertThat(fact.actor().subject()).isEqualTo("owner-1");
        assertThat(fact.scope()).isEqualTo(LOCATION_SCOPE);
        assertThat(fact.targetType()).isEqualTo("ordering.lateness-policy");
        assertThat(fact.targetVersion()).isEqualTo(1L);
        assertThat(fact.reason()).isEqualTo("pickup gets two minutes of grace here");

        assertThat(change(fact, "pickup.lateAfterSeconds"))
                .containsEntry("before", 0)
                .containsEntry("after", 120);
        assertThat(change(fact, "dineIn.atRiskBeforeSeconds"))
                .as("the tenant's dine-in window was 300; the location leaves it to the default, which is null here")
                .containsEntry("before", 300)
                .containsEntry("after", null);
        assertThat(change(fact, "policyVersion"))
                .as("before is the version that was in force (the tenant's v1), after the location's own v1")
                .containsEntry("before", 1)
                .containsEntry("after", 1);
        assertThat(change(fact, "delivery.atRiskBeforeSeconds").get("after"))
                .as("none of the numbers are redacted as if they were personal data")
                .isEqualTo(300);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> change(AuditFact fact, String field) {
        return (Map<String, Object>)
                java.util.Objects.requireNonNull(fact.changeDocument().get(field));
    }

    // ----------------------------------------------------------------- helpers

    private static OrderLatenessDocument uniform(
            @Nullable Integer atRiskSeconds, int lateAfterSeconds, int fallbackSeconds) {
        ModeThresholds mode = new ModeThresholds(atRiskSeconds, lateAfterSeconds, fallbackSeconds);
        return new OrderLatenessDocument(mode, mode, mode);
    }

    private static String suffix(UUID id) {
        String text = id.toString().replace("-", "");
        return text.substring(text.length() - 6);
    }

    private void insertHierarchy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-lateness-authoring', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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
