package uz.horecaos.platform.web.idempotency;

import javax.sql.DataSource;

import uz.horecaos.platform.support.TestDatabase;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;

/**
 * ADR 0031 idempotency. The single-threaded cases document the contract; the
 * concurrent case is the one that matters, because a retried checkout arriving
 * while the original is still running is exactly how duplicate orders happen.
 */
class IdempotencyServiceTests {

    private static final String SCOPE = "storefront.checkout";
    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120701");
    private static final String BODY = """
            {"cartId":"018f6f4e-899d-7b1c-a8cf-0242ac120702","quoteId":"018f6f4e-899d-7b1c-a8cf-0242ac120703"}""";

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private MutableClock clock;
    private IdempotencyService service;

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
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        clock = new MutableClock(Instant.parse("2026-08-20T10:00:00Z"));
        service = new IdempotencyService(jdbc, clock);
    }

    @Test
    void aFirstRequestProceeds() {
        assertThat(service.begin(request("key-1"))).isInstanceOf(IdempotencyOutcome.Proceed.class);
    }

    @Test
    void anIdenticalRetryAfterCompletionReplaysTheStoredResponse() {
        IdempotencyOutcome.Proceed first = (IdempotencyOutcome.Proceed) service.begin(request("key-1"));
        service.complete(first.recordId(), 201, """
                {"orderId":"018f6f4e-899d-7b1c-a8cf-0242ac120704"}""");

        IdempotencyOutcome retry = service.begin(request("key-1"));

        assertThat(retry).isInstanceOf(IdempotencyOutcome.Replay.class);
        IdempotencyOutcome.Replay replay = (IdempotencyOutcome.Replay) retry;
        assertThat(replay.responseStatus()).isEqualTo(201);
        assertThat(replay.responseBody()).contains("018f6f4e-899d-7b1c-a8cf-0242ac120704");
    }

    @Test
    void theSameKeyWithADifferentBodyIsAConflict() {
        service.begin(request("key-1"));

        IdempotencyOutcome outcome = service.begin(new IdempotencyRequest(
                SCOPE, "key-1", TENANT, "subject-a", """
                {"cartId":"018f6f4e-899d-7b1c-a8cf-0242ac1207ff"}""",
                IdempotencyService.DEFAULT_RETENTION));

        assertThat(outcome)
                .as("reusing a key for a different request is a client bug, never a retry")
                .isInstanceOf(IdempotencyOutcome.Conflict.class);
    }

    @Test
    void anInFlightRequestIsNotRunTwice() {
        service.begin(request("key-1"));

        assertThat(service.begin(request("key-1"))).isInstanceOf(IdempotencyOutcome.InProgress.class);
    }

    @Test
    void aDeadAttemptIsTakenOverOnceItsLeaseExpires() {
        service.begin(request("key-1"));

        clock.advance(IdempotencyService.DEFAULT_LEASE.plusSeconds(1));

        assertThat(service.begin(request("key-1")))
                .as("a process that died mid-request must not block the key until retention expires")
                .isInstanceOf(IdempotencyOutcome.Proceed.class);
    }

    @Test
    void theSameKeyInADifferentOperationDoesNotCollide() {
        service.begin(request("key-1"));

        IdempotencyOutcome other = service.begin(new IdempotencyRequest(
                "storefront.cancellation", "key-1", TENANT, "subject-a", BODY,
                IdempotencyService.DEFAULT_RETENTION));

        assertThat(other).isInstanceOf(IdempotencyOutcome.Proceed.class);
    }

    @Test
    void theSameKeyInADifferentTenantDoesNotCollide() {
        service.begin(request("key-1"));

        IdempotencyOutcome other = service.begin(new IdempotencyRequest(
                SCOPE, "key-1", UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120801"),
                "subject-a", BODY, IdempotencyService.DEFAULT_RETENTION));

        assertThat(other).isInstanceOf(IdempotencyOutcome.Proceed.class);
    }

    @Test
    void aReleasedClaimCanBeRetried() {
        IdempotencyOutcome.Proceed first = (IdempotencyOutcome.Proceed) service.begin(request("key-1"));
        service.release(first.recordId());

        assertThat(service.begin(request("key-1"))).isInstanceOf(IdempotencyOutcome.Proceed.class);
    }

    @Test
    void concurrentIdenticalRequestsProduceExactlyOneProceed() throws Exception {
        int attempts = 16;
        try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
            List<Callable<IdempotencyOutcome>> calls = java.util.Collections.nCopies(
                    attempts, () -> service.begin(request("race-key")));

            List<Future<IdempotencyOutcome>> results = executor.invokeAll(calls);

            long proceeded = 0;
            for (Future<IdempotencyOutcome> result : results) {
                if (result.get() instanceof IdempotencyOutcome.Proceed) {
                    proceeded++;
                }
            }

            assertThat(proceeded)
                    .as("%d concurrent retries of one checkout must run the effect once", attempts)
                    .isEqualTo(1);
        }
    }

    @Test
    void expiredRecordsArePurged() {
        IdempotencyOutcome.Proceed first = (IdempotencyOutcome.Proceed) service.begin(request("key-1"));
        service.complete(first.recordId(), 201, "{}");

        clock.advance(IdempotencyService.DEFAULT_RETENTION.plusHours(1));

        assertThat(service.purgeExpired()).isEqualTo(1);
        assertThat(service.begin(request("key-1"))).isInstanceOf(IdempotencyOutcome.Proceed.class);
    }

    private IdempotencyRequest request(String key) {
        return IdempotencyRequest.of(SCOPE, key, TENANT, "subject-a", BODY);
    }

    /** A clock the test advances, so lease and retention behaviour is deterministic. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
