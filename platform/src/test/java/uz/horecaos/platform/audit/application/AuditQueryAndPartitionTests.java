package uz.horecaos.platform.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.infrastructure.persistence.AuditPartitionManager;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/** ADR 0027 querying and partition upkeep. */
class AuditQueryAndPartitionTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121301");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121302");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121303");

    private static TestDatabase.Handle db;

    /** {@link uz.horecaos.platform.iam.api.accounts.StaffDisplayNames} stand-in: only the names a test puts here resolve. */
    private final Map<String, String> knownDisplayNames = new HashMap<>();

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
        JsonMapper objectMapper = JsonMapper.builder().build();
        knownDisplayNames.clear();
        queries = new AuditQueryService(jdbc, objectMapper, knownDisplayNames::get);
        recorder = new JdbcAuditRecorder(jdbc, objectMapper);
        partitions = new AuditPartitionManager(jdbc, clock);

        insertTenant(TENANT, "tenant-audit-query");
        insertTenant(OTHER_TENANT, "tenant-audit-other");
    }

    @Test
    void findsEventsForOneTenant() {
        record("tenant.suspended", TENANT, "operator-1");
        record("tenant.suspended", OTHER_TENANT, "operator-2");

        var results = queries.search(new AuditQueryService.AuditQuery(
                TENANT, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().actorSubject()).isEqualTo("operator-1");
    }

    @Test
    void anotherTenantsEvidenceIsNeverReturned() {
        record("tenant.suspended", OTHER_TENANT, "operator-2");

        assertThat(queries.search(new AuditQueryService.AuditQuery(
                        TENANT, null, null, null, null, null, null, null, null, null, null, null)))
                .as("an audit trail readable across tenants is a second copy of the data it protects")
                .isEmpty();
    }

    /** Staff 9.3b: the read-time fix, since ~99 write-side call sites still pass no display name. */
    @Test
    void resolvesActorDisplayAtReadTimeForARowWrittenWithANullDisplay() {
        record("tenant.suspended", TENANT, "operator-1");
        knownDisplayNames.put("operator-1", "Operator One");

        var results = queries.search(new AuditQueryService.AuditQuery(
                TENANT, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(results.getFirst().actorDisplay())
                .as("actor_display was written null; the resolver fills it in on the way out")
                .isEqualTo("Operator One");
    }

    @Test
    void detailAlsoResolvesTheActorDisplayName() {
        UUID eventId = UUID.randomUUID();
        recorder.record(AuditFact.of("order.cancel", AuditClass.BUSINESS)
                .id(eventId)
                .by(ActorRef.user("operator-3", null))
                .at(ResourceScope.tenant(TENANT))
                .because("test")
                .correlatedBy("detail-name-test")
                .occurredAt(Instant.parse("2026-08-20T09:00:00Z"))
                .build());
        knownDisplayNames.put("operator-3", "Operator Three");

        assertThat(queries.findDetail(TENANT, eventId).orElseThrow().actorDisplay())
                .isEqualTo("Operator Three");
    }

    /**
     * The premise a caller must never be able to break: resolution only ever
     * touches a subject whose row the caller's own tenant-scoped query already
     * decided to return. Another tenant's row is refused before the resolver
     * is ever consulted, so its actor's name is never surfaced either.
     */
    @Test
    void neverResolvesANameForAPrincipalTheCallerCouldNotOtherwiseSee() {
        record("tenant.suspended", OTHER_TENANT, "operator-2");
        knownDisplayNames.put("operator-2", "Operator Two");

        assertThat(queries.search(new AuditQueryService.AuditQuery(
                        TENANT, null, null, null, null, null, null, null, null, null, null, null)))
                .as("the row belongs to another tenant, so its actor's name is never surfaced either")
                .isEmpty();
    }

    /** A row that already carries a real display name is left exactly as it was written. */
    @Test
    void aRowAlreadyCarryingADisplayNameIsNotOverwritten() {
        recorder.record(AuditFact.of("tenant.suspended", AuditClass.BUSINESS)
                .by(ActorRef.user("operator-1", "Written At Record Time"))
                .at(ResourceScope.tenant(TENANT))
                .because("test")
                .correlatedBy("pre-named")
                .occurredAt(Instant.parse("2026-08-20T09:00:00Z"))
                .build());
        knownDisplayNames.put("operator-1", "Should Never Win");

        assertThat(queries.search(new AuditQueryService.AuditQuery(
                                TENANT, null, null, null, null, null, null, null, null, null, null, null))
                        .getFirst()
                        .actorDisplay())
                .isEqualTo("Written At Record Time");
    }

    @Test
    void filtersByActorAndAction() {
        record("tenant.suspended", TENANT, "operator-1");
        record("brand.created", TENANT, "operator-1");
        record("brand.created", TENANT, "operator-2");

        assertThat(queries.search(new AuditQueryService.AuditQuery(
                        TENANT, "operator-1", "brand.created", null, null, null, null, null, null, null, null, null)))
                .hasSize(1);
    }

    @Test
    void limitsAreBoundedSoABroadQueryCannotBecomeAnExport() {
        for (int index = 0; index < 20; index++) {
            record("brand.created", TENANT, "operator-1");
        }

        assertThat(queries.search(new AuditQueryService.AuditQuery(
                        TENANT, null, null, null, null, null, null, null, null, null, null, 10_000)))
                .hasSizeLessThanOrEqualTo(AuditQueryService.MAXIMUM_PAGE);
    }

    /**
     * {@code Page.last(events)} used to run unconditionally in {@code
     * AuditController}, so past 200 events an operator had no way to see the
     * rest of the log (Staff 9.3). {@code id} is a random UUID, not a v7 one,
     * so the cursor has to carry {@code (recorded_at, id)} together — a
     * single-column cursor could skip or repeat rows once two events land in
     * the same instant.
     */
    @Test
    void cursorPagingReturnsTheRestOfTheLogPastTheFirstPage() {
        for (int index = 0; index < 5; index++) {
            record("brand.created", TENANT, "operator-1");
        }

        var firstPage = queries.search(new AuditQueryService.AuditQuery(
                TENANT, null, null, null, null, null, null, null, null, null, null, 2));
        assertThat(firstPage).hasSize(2);

        String cursor = AuditQueryService.cursorFor(firstPage.getLast());
        var secondPage = queries.search(new AuditQueryService.AuditQuery(
                TENANT, null, null, null, null, null, null, null, null, null, null, 2, cursor));

        assertThat(secondPage)
                .as("the second page must not repeat anything the first page already returned")
                .hasSize(2)
                .noneMatch(
                        event -> firstPage.stream().anyMatch(seen -> seen.id().equals(event.id())));

        var thirdPage = queries.search(new AuditQueryService.AuditQuery(
                TENANT,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                2,
                AuditQueryService.cursorFor(secondPage.getLast())));
        assertThat(thirdPage)
                .as("five rows, two pages of two already taken: exactly one remains")
                .hasSize(1);
    }

    @Test
    void aMalformedCursorIsRejectedAsAClientError() {
        assertThatThrownBy(() -> queries.search(new AuditQueryService.AuditQuery(
                        TENANT, null, null, null, null, null, null, null, null, null, null, null, "not-a-cursor")))
                .as("a cursor this endpoint never minted is a client error, not a 500")
                .isInstanceOf(ApiException.class);
    }

    @Test
    void theChangeDocumentIsNotReturnedInAList() {
        record("tenant.suspended", TENANT, "operator-1");

        var view = queries.search(new AuditQueryService.AuditQuery(
                        TENANT, null, null, null, null, null, null, null, null, null, null, null))
                .getFirst();

        assertThat(view.getClass().getRecordComponents())
                .as("redacted structure is still revealing in bulk, so it is a separate audited read")
                .noneMatch(component ->
                        component.getName().toLowerCase(Locale.ROOT).contains("change"));
    }

    @Test
    void outcomeScopeAndCorrelationFiltersEachNarrowTheSearch() {
        recorder.record(AuditFact.of("order.cancel", AuditClass.BUSINESS)
                .by(ActorRef.user("operator-1", null))
                .at(ResourceScope.tenant(TENANT))
                .outcome(AuditFact.Outcome.REJECTED)
                .because("test")
                .correlatedBy("bulk-run-1")
                .occurredAt(Instant.parse("2026-08-20T09:00:00Z"))
                .build());
        recorder.record(AuditFact.of("order.cancel", AuditClass.BUSINESS)
                .by(ActorRef.user("operator-1", null))
                .at(ResourceScope.brand(TENANT, BRAND))
                .because("test")
                .correlatedBy("bulk-run-1")
                .occurredAt(Instant.parse("2026-08-20T09:05:00Z"))
                .build());

        assertThat(queries.search(new AuditQueryService.AuditQuery(
                        TENANT, null, null, null, null, "REJECTED", null, null, null, null, null, null)))
                .as("only the rejected row matches")
                .hasSize(1);

        assertThat(queries.search(new AuditQueryService.AuditQuery(
                        TENANT, null, null, null, null, null, "BRAND", BRAND, null, null, null, null)))
                .as("only the brand-scoped row matches")
                .hasSize(1);

        assertThat(queries.search(new AuditQueryService.AuditQuery(
                        TENANT, null, null, null, null, null, null, null, "bulk-run-1", null, null, null)))
                .as("a bulk action's N rows all share one correlation id — the «Часть массового действия» chip")
                .hasSize(2);
    }

    @Test
    void detailReturnsTheChangeDocumentAndIsScopedToItsTenant() {
        UUID eventId = UUID.randomUUID();
        recorder.record(AuditFact.of("order.cancel", AuditClass.BUSINESS)
                .id(eventId)
                .by(ActorRef.user("operator-1", null))
                .at(ResourceScope.tenant(TENANT))
                .because("Customer no longer answering")
                .changed(java.util.Map.of("status", java.util.Map.of("before", "CONFIRMED", "after", "CANCELLED")))
                .correlatedBy("detail-test-1")
                .occurredAt(Instant.parse("2026-08-20T09:00:00Z"))
                .build());

        var found = queries.findDetail(TENANT, eventId);
        assertThat(found).isPresent();
        assertThat(found.get().changeDocument()).containsKey("status");
        assertThat(found.get().reason()).isEqualTo("Customer no longer answering");

        assertThat(queries.findDetail(OTHER_TENANT, eventId))
                .as("another tenant's id does not resolve this event")
                .isEmpty();
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
