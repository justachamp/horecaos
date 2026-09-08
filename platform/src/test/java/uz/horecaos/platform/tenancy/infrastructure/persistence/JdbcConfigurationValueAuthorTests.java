package uz.horecaos.platform.tenancy.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.AuthoredConfigurationValue;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.application.port.ConfigurationValueCache;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0030 configuration-value authoring at the SQL boundary: the writer
 * {@code tenant.configuration_values} never had before this wave.
 *
 * <p>Built directly ({@code new JdbcConfigurationValueAuthor(...)}), so
 * {@code @CacheEvict} on {@code JdbcConfigurationResolver#evict} never runs
 * through Spring's proxy here — {@link RecordingCache} asserts the call
 * itself happens with the right arguments, and {@code
 * CacheEvictionIntegrationTests} is what proves the eviction actually reaches
 * a live Caffeine cache.
 */
class JdbcConfigurationValueAuthorTests {

    private static final ConfigurationKey<Integer> TIMEOUT = ConfigurationKey.of(
                    "ordering.approval_timeout_seconds", Integer.class)
            .defaultValue(600)
            .build();

    private static final ConfigurationKey<String> TENANT_ONLY_KEY = ConfigurationKey.of(
                    "ordering.tenant_only_setting", String.class)
            .defaultValue("x")
            .settableAt(ScopeType.TENANT)
            .build();

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac130301");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac130302");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac130303");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac130401");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcConfigurationResolver resolver;
    private RecordingCache cache;
    private RecordingAuditRecorder audit;
    private JdbcConfigurationValueAuthor author;
    private Clock clock;

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
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        resolver = new JdbcConfigurationResolver(jdbc);
        cache = new RecordingCache();
        audit = new RecordingAuditRecorder();
        clock = Clock.fixed(Instant.parse("2026-09-08T10:00:00Z"), ZoneOffset.UTC);
        author = new JdbcConfigurationValueAuthor(jdbc, audit, clock, cache);

        insertTenantHierarchy(TENANT, "tenant-cfg-author");
        insertBrandAndLocation(TENANT, BRAND, LOCATION);
        insertTenantHierarchy(OTHER_TENANT, "tenant-cfg-author-other");
    }

    @Test
    void createsTheFirstValueAtAScope() {
        AuthoredConfigurationValue<Integer> authored = author.set(
                TIMEOUT, ResourceScope.tenant(TENANT), 900, false, null, ActorRef.user("op-1", null), "initial value");

        assertThat(authored.version()).isEqualTo(0L);
        assertThat(authored.value()).isEqualTo(900);
        assertThat(resolver.resolve(TIMEOUT, ResourceScope.tenant(TENANT)).value())
                .isEqualTo(900);
    }

    @Test
    void updatesUnderTheExpectedVersionAndIncrementsIt() {
        author.set(TIMEOUT, ResourceScope.tenant(TENANT), 900, false, null, ActorRef.user("op-1", null), "initial");

        AuthoredConfigurationValue<Integer> updated = author.set(
                TIMEOUT, ResourceScope.tenant(TENANT), 120, false, 0L, ActorRef.user("op-1", null), "shorten it");

        assertThat(updated.version()).isEqualTo(1L);
        assertThat(resolver.resolve(TIMEOUT, ResourceScope.tenant(TENANT)).value())
                .isEqualTo(120);
    }

    @Test
    void refusesCreatingASecondValueOverAnExistingOne() {
        author.set(TIMEOUT, ResourceScope.tenant(TENANT), 900, false, null, ActorRef.user("op-1", null), "initial");

        assertThatThrownBy(() -> author.set(
                        TIMEOUT, ResourceScope.tenant(TENANT), 800, false, null, ActorRef.user("op-2", null), "again"))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        error -> assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
    }

    @Test
    void refusesAnUpdateUnderAStaleVersion() {
        author.set(TIMEOUT, ResourceScope.tenant(TENANT), 900, false, null, ActorRef.user("op-1", null), "initial");
        author.set(TIMEOUT, ResourceScope.tenant(TENANT), 800, false, 0L, ActorRef.user("op-1", null), "second");

        // op-2 read the row back when it was still at version 0.
        assertThatThrownBy(() -> author.set(
                        TIMEOUT,
                        ResourceScope.tenant(TENANT),
                        700,
                        false,
                        0L,
                        ActorRef.user("op-2", null),
                        "racing edit"))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        error -> assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.STALE_VERSION));

        // The losing write never took effect.
        assertThat(resolver.resolve(TIMEOUT, ResourceScope.tenant(TENANT)).value())
                .isEqualTo(800);
    }

    @Test
    void refusesAnUpdateWhenNothingIsSetYet() {
        assertThatThrownBy(() -> author.set(
                        TIMEOUT, ResourceScope.tenant(TENANT), 700, false, 0L, ActorRef.user("op-1", null), "reason"))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        error -> assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
    }

    @Test
    void refusesAScopeTheKeyDoesNotDeclareSettable() {
        assertThatThrownBy(() -> author.set(
                        TENANT_ONLY_KEY,
                        ResourceScope.location(TENANT, BRAND, LOCATION),
                        "y",
                        false,
                        null,
                        ActorRef.user("op-1", null),
                        "reason"))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        error -> assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThat(resolver.resolve(TENANT_ONLY_KEY, ResourceScope.location(TENANT, BRAND, LOCATION))
                        .cameFromDefault())
                .as("a refused write must never reach the database")
                .isTrue();
    }

    @Test
    void refusesAMissingReason() {
        assertThatThrownBy(() -> author.set(
                        TIMEOUT, ResourceScope.tenant(TENANT), 700, false, null, ActorRef.user("op-1", null), " "))
                .isInstanceOf(ApiException.class)
                .satisfies(
                        error -> assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void explicitNullRoundTripsAndContinuesResolution() {
        author.set(
                TIMEOUT, ResourceScope.tenant(TENANT), 900, false, null, ActorRef.user("op-1", null), "tenant default");
        author.set(
                TIMEOUT,
                ResourceScope.location(TENANT, BRAND, LOCATION),
                null,
                true,
                null,
                ActorRef.user("op-1", null),
                "deliberately unset at this location");

        assertThat(resolver.resolve(TIMEOUT, ResourceScope.location(TENANT, BRAND, LOCATION))
                        .value())
                .as("an explicit null continues resolution up the chain rather than resolving to null")
                .isEqualTo(900);
        assertThat(author.currentVersion(TIMEOUT, ResourceScope.location(TENANT, BRAND, LOCATION)))
                .contains(0L);
    }

    @Test
    void currentVersionIsAbsentUntilSomethingIsSet() {
        assertThat(author.currentVersion(TIMEOUT, ResourceScope.tenant(TENANT))).isEmpty();

        author.set(TIMEOUT, ResourceScope.tenant(TENANT), 900, false, null, ActorRef.user("op-1", null), "initial");

        assertThat(author.currentVersion(TIMEOUT, ResourceScope.tenant(TENANT))).contains(0L);
    }

    @Test
    void anotherTenantsRowIsInvisibleToCurrentVersion() {
        author.set(
                TIMEOUT, ResourceScope.tenant(OTHER_TENANT), 900, false, null, ActorRef.user("op-1", null), "reason");

        assertThat(author.currentVersion(TIMEOUT, ResourceScope.tenant(TENANT))).isEmpty();
    }

    @Test
    void aLocationValueCannotReferenceAnotherTenantsLocation() {
        assertThatThrownBy(() -> author.set(
                        TIMEOUT,
                        new ResourceScope(ScopeType.LOCATION, OTHER_TENANT, BRAND, LOCATION),
                        1,
                        false,
                        null,
                        ActorRef.user("op-1", null),
                        "cross-tenant"))
                .isInstanceOf(ApiException.class)
                .satisfies(error ->
                        assertThat(((ApiException) error).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void evictsExactlyTheScopeItJustWrote() {
        author.set(TIMEOUT, ResourceScope.tenant(TENANT), 900, false, null, ActorRef.user("op-1", null), "initial");

        assertThat(cache.evictions)
                .containsExactly(new RecordingCache.Eviction(TIMEOUT.code(), ResourceScope.tenant(TENANT)));
    }

    @Test
    void recordsAnAuditFactWithTheActorReasonAndBeforeAfterValues() {
        author.set(TIMEOUT, ResourceScope.tenant(TENANT), 900, false, null, ActorRef.user("op-1", null), "initial");
        author.set(TIMEOUT, ResourceScope.tenant(TENANT), 120, false, 0L, ActorRef.user("op-2", null), "shorten it");

        assertThat(audit.facts).hasSize(2);
        AuditFact second = audit.facts.get(1);
        assertThat(second.actor().subject()).isEqualTo("op-2");
        assertThat(second.reason()).isEqualTo("shorten it");
        @SuppressWarnings("unchecked")
        Map<String, Object> valueChange =
                (Map<String, Object>) second.changeDocument().get("value");
        assertThat(valueChange).containsEntry("before", 900).containsEntry("after", 120);
        assertThat(second.targetType()).isEqualTo("ConfigurationValue");
        assertThat(second.targetId()).isNotNull();
    }

    private void insertTenantHierarchy(UUID tenantId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
    }

    private void insertBrandAndLocation(UUID tenantId, UUID brandId, UUID locationId) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'BRAND_CFG_AUTHOR', 'brand-cfg-author', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("tenantId", tenantId).update();

        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'LOC_CFG_AUTHOR', 'loc-cfg-author', 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();
    }

    /** Records every {@code evict} call so a missing or mistargeted eviction fails a test. */
    private static final class RecordingCache implements ConfigurationValueCache {
        private final List<Eviction> evictions = new ArrayList<>();

        @Override
        public void evict(String keyCode, ResourceScope scope) {
            evictions.add(new Eviction(keyCode, scope));
        }

        private record Eviction(String keyCode, ResourceScope scope) {}
    }

    private static final class RecordingAuditRecorder implements AuditRecorder {
        private final List<AuditFact> facts = new ArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }
    }
}
