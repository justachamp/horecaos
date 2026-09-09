package uz.horecaos.platform.pos.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.integration.api.pos.PosSyncRequestedPayload;
import uz.horecaos.platform.integration.api.pos.PosSyncRequester;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosScheduleStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The durable scheduler's own safety property (ADR 0012): two replicas polling
 * the same due schedule at once claim it at most once, never twice.
 *
 * <p>The outbox append itself is {@link uz.horecaos.platform.integration.outbox.PosSyncOutboxTests}'s
 * subject, in its own module, per {@code PosModuleBoundaryTests}'s own rule that
 * {@code pos} depends on {@link PosSyncRequester} and never on the outbox's
 * concrete implementation. This test's fake requester is what that boundary is
 * for: it proves the claim is exclusive without needing an outbox row at all,
 * and it never gets called more than once here — the whole point.
 *
 * <p><b>Why {@link TransactionTemplate} rather than relying on {@code
 * @Transactional}.</b> This test constructs {@link PosSyncSchedulingService}
 * with {@code new}, so there is no Spring proxy to notice the annotation — the
 * exact mistake {@code OnboardingScheduler}'s own class doc describes, where a
 * {@code @Transactional} that never goes through a proxy lets {@code FOR UPDATE
 * SKIP LOCKED} release its lock before anyone reads the row. Wrapping each call
 * in a real {@link TransactionTemplate} bound to the shared {@link DataSource}
 * opens the same kind of transaction production gets from the proxy — the same
 * technique {@code JdbcOutboxStoreTests
 * .rollsBackBusinessStateAndItsOutboxRecordTogether} already uses for exactly
 * this reason.
 */
class PosSyncSchedulingServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-7300-7000-8000-0000000c0001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-7300-7000-8000-0000000c0002");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-7300-7000-8000-0000000c0003");
    private static final UUID BINDING = UUID.fromString("018f6f4e-7300-7000-8000-0000000c0004");

    private static final Instant DUE_NOW = Instant.parse("2026-08-24T05:00:00Z");
    /** Long past, so whatever {@code upsert} computes as due is well before {@link #DUE_NOW}. */
    private static final Instant WAY_BACK = Instant.parse("2020-01-01T00:00:00Z");

    private static TestDatabase.Handle db;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private JdbcPosScheduleStore scheduleStore;
    private RecordingRequester requester;
    private PosSyncSchedulingService scheduling;
    private TransactionTemplate transactions;

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
        dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);

        jdbc.sql("DELETE FROM integration.pos_sync_schedules WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM integration.bindings WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM integration.installations WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.brands WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id = :t").param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone,
                     status, version)
                VALUES (:id, 'pos-scheduler', 'Legal', 'POS scheduler', 'UZS',
                        'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'POS_BRAND', 'pos-brand', 'POS brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code,
                     display_name, status)
                VALUES (:id, :t, 'POS', 'clopos', 'clopos-open-api-v2', 'Pilot', 'ACTIVE')
                """).param("id", INSTALLATION).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :t, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", BINDING)
                .param("t", TENANT)
                .param("installationId", INSTALLATION)
                .param("brandId", BRAND)
                .update();

        scheduleStore = new JdbcPosScheduleStore(jdbc);
        requester = new RecordingRequester();
        scheduling = new PosSyncSchedulingService(scheduleStore, requester);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    @DisplayName("two replicas racing the same due schedule produce exactly one command, and the loser claims nothing")
    void twoReplicasClaimAtMostOnce() throws Exception {
        UUID scheduleId = scheduleStore.upsert(TENANT, BINDING, "Asia/Tashkent", LocalTime.of(4, 0), true, WAY_BACK);
        assertThat(due()).as("sanity: the seeded schedule is due").isTrue();

        List<Optional<UUID>> results = inParallel(
                () -> transactions.execute(status -> scheduling.claimAndDispatch(scheduleId, DUE_NOW)),
                () -> transactions.execute(status -> scheduling.claimAndDispatch(scheduleId, DUE_NOW)));

        assertThat(results.stream().filter(Optional::isPresent).count())
                .as("exactly one of the two concurrent attempts claims the occurrence")
                .isEqualTo(1);

        assertThat(requester.calls)
                .as("one claimed occurrence asks for exactly one PosSyncRequested, never two")
                .hasSize(1);
        assertThat(requester.calls.getFirst().bindingId()).isEqualTo(BINDING);
        assertThat(requester.calls.getFirst().scheduleId()).isEqualTo(scheduleId);

        assertThat(scheduleVersion(scheduleId))
                .as("the schedule row advances exactly once, not once per losing attempt too")
                .isEqualTo(1L);
        assertThat(nextRunAt(scheduleId)).isAfter(DUE_NOW);
    }

    @Test
    @DisplayName("a schedule that is not yet due is never claimed")
    void aScheduleNotYetDueIsNeverClaimed() {
        UUID scheduleId = scheduleStore.upsert(TENANT, BINDING, "Asia/Tashkent", LocalTime.of(4, 0), true, DUE_NOW);
        Instant beforeItsOwnNextRun = DUE_NOW.minusSeconds(1);

        Optional<UUID> claimed =
                transactions.execute(status -> scheduling.claimAndDispatch(scheduleId, beforeItsOwnNextRun));

        assertThat(claimed).isEmpty();
        assertThat(requester.calls).isEmpty();
    }

    private boolean due() {
        Instant nextRunAt = jdbc.sql("SELECT next_run_at FROM integration.pos_sync_schedules WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(java.time.OffsetDateTime.class)
                .single()
                .toInstant();
        return !nextRunAt.isAfter(DUE_NOW);
    }

    private long scheduleVersion(UUID scheduleId) {
        return jdbc.sql("SELECT version FROM integration.pos_sync_schedules WHERE id = :id")
                .param("id", scheduleId)
                .query(Long.class)
                .single();
    }

    private Instant nextRunAt(UUID scheduleId) {
        return jdbc.sql("SELECT next_run_at FROM integration.pos_sync_schedules WHERE id = :id")
                .param("id", scheduleId)
                .query(java.time.OffsetDateTime.class)
                .single()
                .toInstant();
    }

    private static <T> List<T> inParallel(Callable<T> first, Callable<T> second) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<T>> futures = pool.invokeAll(List.of(first, second));
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    /** Thread-safe: both replicas in {@link #twoReplicasClaimAtMostOnce()} may call this concurrently. */
    private static final class RecordingRequester implements PosSyncRequester {
        private final List<PosSyncRequestedPayload> calls = new CopyOnWriteArrayList<>();

        @Override
        public void requestSync(PosSyncRequestedPayload command, String correlationId) {
            calls.add(command);
        }
    }
}
