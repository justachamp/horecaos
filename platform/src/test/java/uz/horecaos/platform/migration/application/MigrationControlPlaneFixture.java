package uz.horecaos.platform.migration.application;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalRequestOwnership;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.migration.api.MigrationCapability;
import uz.horecaos.platform.migration.application.MigrationProgramService.OpenScopeCommand;
import uz.horecaos.platform.migration.application.MigrationScopeService.AdvanceCommand;
import uz.horecaos.platform.migration.application.MigrationScopeStore.ScopeRow;
import uz.horecaos.platform.migration.domain.ReconciliationSeverity;
import uz.horecaos.platform.migration.domain.RunType;
import uz.horecaos.platform.migration.domain.ScopeState;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcCutoverDecisionStore;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcEntityMappingStore;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcMigrationProgramStore;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcMigrationRunStore;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcMigrationScopeStore;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcQuarantineStore;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcReconciliationStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The real control plane, wired by hand over the real schema.
 *
 * <p>Every collaborator here is the production implementation: the seven JDBC
 * stores, the ADR 0027 audit recorder, the ADR 0025 access policy. There is no
 * stub, because each guarantee this suite exists to prove is a property of the
 * database or of the transition engine reading it, and a stub of either would
 * agree with whatever the test expected.
 *
 * <p>The test classes sit in {@code uz.horecaos.platform.migration.application}
 * rather than in the module root because {@code MigrationAudit} is package
 * private, and wiring the services through a Spring context instead would hide
 * exactly which collaborators are real.
 */
abstract class MigrationControlPlaneFixture {

    protected static final UUID TENANT = UUID.randomUUID();
    protected static final UUID OTHER_TENANT = UUID.randomUUID();
    protected static final UUID BRAND = UUID.randomUUID();
    protected static final UUID SECOND_BRAND = UUID.randomUUID();
    /** A brand belonging to {@link #OTHER_TENANT}, for the ancestry assertions. */
    protected static final UUID FOREIGN_BRAND = UUID.randomUUID();
    protected static final UUID LOCATION = UUID.randomUUID();
    protected static final UUID OTHER_LOCATION = UUID.randomUUID();

    protected static final String OPERATOR = "platform-operator";
    protected static final Instant NOW = Instant.parse("2026-08-22T09:00:00Z");

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    protected DataSource dataSource;
    protected JdbcClient jdbc;
    protected MutableClock clock;
    protected ObjectMapper objectMapper;

    protected JdbcMigrationScopeStore scopeStore;
    protected JdbcMigrationRunStore runStore;
    protected JdbcMigrationProgramStore programStore;
    protected JdbcQuarantineStore quarantineStore;
    protected JdbcReconciliationStore reconciliationStore;
    protected JdbcCutoverDecisionStore decisionStore;
    protected JdbcEntityMappingStore mappingStore;

    protected MigrationProgramService programs;
    protected MigrationScopeService scopeService;
    protected MigrationRunService runService;
    protected QuarantineService quarantineService;
    protected MigrationOwnershipService ownership;

    protected UUID programId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the migration control plane tests");
        db = TestDatabase.migrated();
        jdbcUrl = db.jdbcUrl();
        username = db.username();
        password = db.password();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    protected static String jdbcUrl() {
        return jdbcUrl;
    }

    protected static String username() {
        return username;
    }

    protected static String password() {
        return password;
    }

    @BeforeEach
    void setUp() {
        dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);

        jdbc.sql("""
                TRUNCATE TABLE migration.cutover_decisions, migration.reconciliation_results,
                    migration.quarantine_items, migration.entity_mappings, migration.runs,
                    migration.scopes, migration.programs CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();

        clock = new MutableClock(NOW);
        objectMapper = JsonMapper.builder().build();

        scopeStore = new JdbcMigrationScopeStore(jdbc, objectMapper);
        runStore = new JdbcMigrationRunStore(jdbc, objectMapper);
        programStore = new JdbcMigrationProgramStore(jdbc);
        quarantineStore = new JdbcQuarantineStore(jdbc);
        reconciliationStore = new JdbcReconciliationStore(jdbc);
        decisionStore = new JdbcCutoverDecisionStore(jdbc, objectMapper);
        mappingStore = new JdbcEntityMappingStore(jdbc);

        MigrationAudit audit = new MigrationAudit(new JdbcAuditRecorder(jdbc, objectMapper), clock);
        CurrentActor actor = () -> new AuthenticatedActor(OPERATOR, Set.of("platform-admin"), Map.of());
        MigrationAccessPolicy access = new MigrationAccessPolicy(actor, grantedAuthorization(), true);

        programs = new MigrationProgramService(programStore, scopeStore, access, audit, clock);
        scopeService = new MigrationScopeService(scopeStore, reconciliationStore, decisionStore,
                quarantineStore, access, new JdbcApprovalRequestOwnership(jdbc), audit, clock);
        runService = new MigrationRunService(runStore, scopeStore, access, audit, clock);
        quarantineService = new QuarantineService(quarantineStore, runStore, scopeStore, access,
                audit, clock);
        ownership = new MigrationOwnershipService(scopeStore, new SimpleMeterRegistry());

        seedTenancy();
        programId = programs.create(new MigrationProgramService.CreateProgramCommand(
                "Delever cutover", "delever-prod", "horecaos-prod", 3, "seeding the suite")).id();
    }

    // ------------------------------------------------------------ the fixture

    private void seedTenancy() {
        insertTenant(TENANT, "migrating-tenant");
        insertTenant(OTHER_TENANT, "other-tenant");
        insertBrand(BRAND, TENANT, "MAIN", "main");
        insertBrand(SECOND_BRAND, TENANT, "SIDE", "side");
        insertBrand(FOREIGN_BRAND, OTHER_TENANT, "MAIN", "main");
        insertLocation(LOCATION, "MAIN01", "main-01");
        insertLocation(OTHER_LOCATION, "MAIN02", "main-02");
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void insertBrand(UUID id, UUID tenantId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """).param("id", id).param("tenantId", tenantId).param("code", code)
                .param("slug", slug).update();
    }

    private void insertLocation(UUID id, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, 'Branch', 'Asia/Tashkent',
                    'ACTIVE', 0)
                """).param("id", id).param("tenantId", TENANT).param("brandId", BRAND)
                .param("code", code).param("slug", slug).update();
    }

    // ------------------------------------------------------------- shorthands

    protected UUID openScope(MigrationCapability capability, UUID brandId, UUID locationId) {
        return programs.openScope(programId, new OpenScopeCommand(TENANT, brandId, locationId,
                capability, "DELEVER", "HORECAOS_ORDERING", "opening for the suite")).id();
    }

    protected UUID openTenantWideScope(MigrationCapability capability) {
        return openScope(capability, null, null);
    }

    protected ScopeRow scope(UUID scopeId) {
        return scopeStore.findById(TENANT, scopeId).orElseThrow();
    }

    protected int scopeVersion(UUID scopeId) {
        return scope(scopeId).version();
    }

    /** Walks a scope along its ordinary path, one lawful transition at a time. */
    protected void advanceThrough(UUID scopeId, ScopeState... path) {
        for (ScopeState next : path) {
            scopeService.advance(TENANT, scopeId, new AdvanceCommand(next, scopeVersion(scopeId),
                    "walking the path", UUID.randomUUID().toString()));
        }
    }

    /** Drives a fresh tenant-wide ORDERS scope up to CANARY with its coverage published. */
    protected UUID scopeReadyForCanary() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);
        advanceThrough(scopeId,
                ScopeState.MAPPING_APPROVED,
                ScopeState.BACKFILLING,
                ScopeState.CATCHING_UP,
                ScopeState.SHADOW_READING,
                ScopeState.CANARY);
        scopeService.republishCoverage(TENANT, scopeId, 0, scopeVersion(scopeId),
                "every source decided");
        return scopeId;
    }

    protected UUID startRun(UUID scopeId, RunType runType, String key) {
        return runService.start(TENANT, scopeId, new MigrationRunService.StartRunCommand(
                runType, 1, OPERATOR, "starting for the suite", key)).id();
    }

    /**
     * Records a difference against a scope, through the production writer, so the
     * gate reads exactly what a reconciliation run would have left behind.
     */
    protected UUID recordDifference(UUID scopeId, UUID runId, String ruleCode,
            ReconciliationSeverity severity) {

        return reconciliationStore.record(new JdbcReconciliationStore.ReconciliationResult(
                UUID.randomUUID(), TENANT, runId, scopeId, ruleCode, 3, "",
                severity,
                JdbcReconciliationStore.ReconciliationMeasure.count(
                        BigInteger.valueOf(41_233), BigInteger.valueOf(41_197)),
                "evidence:recon/2026-08-22/orders", clock.instant()))
                .orElseThrow();
    }

    protected long countRows(String table, String predicate, Map<String, Object> params) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + predicate)
                .params(new HashMap<>(params))
                .query(Long.class)
                .single();
    }

    protected <T> T tx(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .execute(status -> work.get());
    }

    protected void tx(Runnable work) {
        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(status -> work.run());
    }

    /**
     * The real grant resolver, over a real grant, with enforcement on.
     *
     * <p>This suite used to hand the policy a resolver that threw and the flag
     * set to false, because that was the build's default; the effect was that the
     * migration control plane's own tests never once exercised the capability
     * half of its access decision. Enforcement is the default now, so the fixture
     * has to establish what a real operator would hold: the platform role row and
     * a platform-scoped grant of it.
     *
     * <p>The tenant truncate above cascades into {@code iam.roles}, which has a
     * foreign key to tenants, so the registry is republished on every setUp
     * before the grant can reference it.
     */
    private AuthorizationService grantedAuthorization() {
        new RoleRegistrySynchronizer(jdbc).synchronize();

        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, NULL, :subject, :roleId, true, 'PLATFORM', NULL,
                        'ACTIVE', 'test-fixture', 'migration control plane suite', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("subject", OPERATOR)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.PLATFORM_ADMIN))
                // The fixture clock is deliberately behind wall time, and the
                // column's default is now(), so a grant taking the default would
                // begin after the instant every query in this suite asks about.
                .param("validFrom", NOW.minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
                .update();

        // No current actor: this fixture exercises grants, and a null actor
        // means the platform-admin bypass cannot fire and mask a missing grant.
        return new JdbcAuthorizationService(jdbc, clock, () -> null);
    }

    /** Lets a test move time forward without sleeping. */
    protected static final class MutableClock extends java.time.Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
