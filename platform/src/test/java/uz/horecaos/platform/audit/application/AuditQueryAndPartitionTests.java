package uz.horecaos.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.infrastructure.persistence.AuditPartitionManager;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.support.TestDatabase;

/** ADR 0027 querying and partition upkeep. */
class AuditQueryAndPartitionTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121301");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121302");

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private AuditQueryService queries;
    private JdbcAuditRecorder recorder;
    private AuditPartitionManager partitions;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
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

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC);
        queries = new AuditQueryService(jdbc);
        recorder = new JdbcAuditRecorder(jdbc, JsonMapper.builder().build());
        partitions = new AuditPartitionManager(jdbc, clock);

        insertTenant(TENANT, "tenant-audit-query");
        insertTenant(OTHER_TENANT, "tenant-audit-other");
    }

    @Test
    void findsEventsForOneTenant() {
        record("tenant.suspended", TENANT, "operator-1");
        record("tenant.suspended", OTHER_TENANT, "operator-2");

        var results =
                queries.search(new AuditQueryService.AuditQuery(TENANT, null, null, null, null, null, null, null));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().actorSubject()).isEqualTo("operator-1");
    }

    @Test
    void anotherTenantsEvidenceIsNeverReturned() {
        record("tenant.suspended", OTHER_TENANT, "operator-2");

        assertThat(queries.search(new AuditQueryService.AuditQuery(TENANT, null, null, null, null, null, null, null)))
                .as("an audit trail readable across tenants is a second copy of the data it protects")
                .isEmpty();
    }

    @Test
    void filtersByActorAndAction() {
        record("tenant.suspended", TENANT, "operator-1");
        record("brand.created", TENANT, "operator-1");
        record("brand.created", TENANT, "operator-2");

        assertThat(queries.search(new AuditQueryService.AuditQuery(
                        TENANT, "operator-1", "brand.created", null, null, null, null, null)))
                .hasSize(1);
    }

    @Test
    void limitsAreBoundedSoABroadQueryCannotBecomeAnExport() {
        for (int index = 0; index < 20; index++) {
            record("brand.created", TENANT, "operator-1");
        }

        assertThat(queries.search(new AuditQueryService.AuditQuery(TENANT, null, null, null, null, null, null, 10_000)))
                .hasSizeLessThanOrEqualTo(AuditQueryService.MAXIMUM_PAGE);
    }

    @Test
    void theChangeDocumentIsNotReturnedInAList() {
        record("tenant.suspended", TENANT, "operator-1");

        var view = queries.search(new AuditQueryService.AuditQuery(TENANT, null, null, null, null, null, null, null))
                .getFirst();

        assertThat(view.getClass().getRecordComponents())
                .as("redacted structure is still revealing in bulk, so it is a separate audited read")
                .noneMatch(component -> component.getName().toLowerCase().contains("change"));
    }

    @Test
    void aPartitionIsCreatedWhenMissingAndTheCallIsIdempotent() {
        int futureYear = 2031;
        partitions.ensurePartition(futureYear);
        partitions.ensurePartition(futureYear);

        assertThat(partitionExists("audit_events_" + futureYear)).isTrue();
    }

    @Test
    void keepingPartitionsAheadStopsRowsLandingInTheDefault() {
        partitions.ensurePartitions();

        assertThat(partitionExists("audit_events_2027")).isTrue();
        assertThat(partitionExists("audit_events_2028")).isTrue();
        assertThat(partitions.defaultPartitionRowCount())
                .as("rows in the default partition are a symptom, not a design")
                .isZero();
    }

    private boolean partitionExists(String table) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.tables
                     WHERE table_schema = 'audit' AND table_name = :table)
                """).param("table", table).query(Boolean.class).single();
    }

    private void record(String actionCode, UUID tenantId, String actor) {
        recorder.record(AuditFact.of(actionCode, AuditClass.BUSINESS)
                .by(ActorRef.user(actor, null))
                .at(ResourceScope.tenant(tenantId))
                .because("test")
                .correlatedBy("correlation-1")
                .occurredAt(Instant.parse("2026-08-20T09:00:00Z"))
                .build());
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }
}
