package uz.qoida.platform.audit.infrastructure.persistence;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;

import uz.qoida.platform.support.TestDatabase;

/**
 * ADR 0034's scaling move is {@code --scale api=2}, and every replica runs this
 * job on the same daily timer. Check-then-act DDL is therefore not idempotent
 * where it matters: both replicas see no partition, both issue the
 * {@code CREATE}, and one of them fails on a race whose outcome was correct.
 *
 * <p>Asserted with a barrier rather than by luck, because the window is a few
 * milliseconds wide once a day and a partition manager that cries wolf is a
 * partition manager whose alerts get muted.
 */
class AuditPartitionRaceTests {

    /** Far enough out that no ordinary run has already created it. */
    private static final int YEAR = 2091;

    private static final int REPLICAS = 8;

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;

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
        // DriverManagerDataSource opens a connection per statement, so the
        // replicas below really do contend rather than queueing on one session.
        jdbc = JdbcClient.create(dataSource);
        dropPartition();
    }

    @AfterEach
    void tearDown() {
        dropPartition();
    }

    @Test
    void concurrentReplicasBothSucceedAndCreateOnePartition() throws Exception {
        AuditPartitionManager manager = new AuditPartitionManager(
                jdbc, Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC));

        CyclicBarrier together = new CyclicBarrier(REPLICAS);
        try (ExecutorService replicas = Executors.newFixedThreadPool(REPLICAS)) {
            List<Callable<Void>> attempts = IntStream.range(0, REPLICAS)
                    .<Callable<Void>>mapToObj(ignored -> () -> {
                        together.await();
                        manager.ensurePartition(YEAR);
                        return null;
                    })
                    .toList();

            for (Future<Void> attempt : replicas.invokeAll(attempts)) {
                // get() rethrows whatever the replica threw. Before the DDL was
                // made race-tolerant this is a unique violation on pg_class.
                attempt.get();
            }
        }

        assertThat(partitionCount())
                .as("the losers of the race must neither fail nor create a second partition")
                .isEqualTo(1);
    }

    @Test
    void aSecondRunOverAnExistingPartitionIsSilent() {
        AuditPartitionManager manager = new AuditPartitionManager(
                jdbc, Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC));

        manager.ensurePartition(YEAR);
        manager.ensurePartition(YEAR);

        assertThat(partitionCount()).isEqualTo(1);
    }

    private long partitionCount() {
        return jdbc.sql("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'audit' AND table_name = :table
                """)
                .param("table", "audit_events_" + YEAR)
                .query(Long.class)
                .single();
    }

    private void dropPartition() {
        jdbc.sql("DROP TABLE IF EXISTS audit.audit_events_%d".formatted(YEAR)).update();
    }
}
