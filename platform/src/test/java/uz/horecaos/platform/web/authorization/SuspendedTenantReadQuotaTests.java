package uz.horecaos.platform.web.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The allowance that stops a suspended tenant's read access being an export
 * (ADR 0078).
 *
 * <p>Runs against a real database because the property worth asserting is the
 * one a stub would agree with itself about: that the count survives, and that
 * the window is a duration rather than an instant. The clock moves in these
 * tests for exactly that reason — an allowance "per ninety days" asserted
 * without advancing time is an allowance asserted against a single moment, and
 * would pass just as happily if the window were a century or a second.
 */
class SuspendedTenantReadQuotaTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String ENDPOINT = "OrderController#list";
    private static final String SUBJECT = "staff-1";

    private static TestDatabase.Handle db;
    private static JdbcClient jdbc;

    private MutableClock clock;
    private SuspendedTenantReadQuota quota;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        db = TestDatabase.migrated();
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-quota', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'SUSPENDED', 0)
                """).param("id", TENANT).update();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE tenant.suspended_read_log").update();
        clock = new MutableClock(Instant.parse("2026-09-08T09:00:00Z"));
        quota = new SuspendedTenantReadQuota(jdbc, clock);
    }

    @Test
    @DisplayName("three reads of an endpoint are allowed and the fourth is not")
    void theFourthReadOfAnEndpointIsRefused() {
        assertThat(quota.consume(TENANT, ENDPOINT, SUBJECT)).isTrue();
        assertThat(quota.consume(TENANT, ENDPOINT, SUBJECT)).isTrue();
        assertThat(quota.consume(TENANT, ENDPOINT, SUBJECT))
                .as("the third is still within the owner's allowance of three")
                .isTrue();
        assertThat(quota.consume(TENANT, ENDPOINT, SUBJECT))
                .as("the fourth is the one that would be a loop over a listing endpoint")
                .isFalse();
    }

    @Test
    @DisplayName("the refused attempt is still on record")
    void aRefusalIsRecordedRatherThanDiscarded() {
        for (int i = 0; i < 4; i++) {
            quota.consume(TENANT, ENDPOINT, SUBJECT);
        }

        assertThat(rowsFor(ENDPOINT))
                .as("a suspended tenant probing an endpoint it is out of allowance for is exactly "
                        + "what somebody will want to see later, so the refusal is logged too")
                .isEqualTo(4L);
    }

    @Test
    @DisplayName("the allowance is per endpoint, so one exhausted list does not close the others")
    void eachEndpointCarriesItsOwnAllowance() {
        for (int i = 0; i < 4; i++) {
            quota.consume(TENANT, ENDPOINT, SUBJECT);
        }

        assertThat(quota.consume(TENANT, "CustomerController#list", SUBJECT)).isTrue();
    }

    @Test
    @DisplayName("the allowance is the tenant's, not the caller's")
    void asecondStaffAccountDoesNotBuyThreeMoreReads() {
        quota.consume(TENANT, ENDPOINT, "staff-1");
        quota.consume(TENANT, ENDPOINT, "staff-2");
        quota.consume(TENANT, ENDPOINT, "staff-3");

        assertThat(quota.consume(TENANT, ENDPOINT, "staff-4"))
                .as("a per-principal quota is bought off by inviting a colleague")
                .isFalse();
    }

    @Test
    @DisplayName("the window rolls: reads older than ninety days stop counting")
    void theAllowanceReturnsOnceTheWindowHasPassed() {
        for (int i = 0; i < 3; i++) {
            quota.consume(TENANT, ENDPOINT, SUBJECT);
        }
        assertThat(quota.consume(TENANT, ENDPOINT, SUBJECT)).isFalse();

        // Eighty-nine days is still inside the window. Asserting only the
        // hundred-day case would pass with a window of one day.
        clock.advance(Duration.ofDays(89));
        assertThat(quota.consume(TENANT, ENDPOINT, SUBJECT))
                .as("the reads that spent the allowance are still inside ninety days")
                .isFalse();

        clock.advance(Duration.ofDays(2));
        assertThat(quota.consume(TENANT, ENDPOINT, SUBJECT))
                .as("ninety-one days after the first three, they no longer count against it")
                .isTrue();
    }

    private long rowsFor(String endpoint) {
        return jdbc.sql("SELECT count(*) FROM tenant.suspended_read_log WHERE tenant_id = :t AND endpoint = :e")
                .param("t", TENANT)
                .param("e", endpoint)
                .query(Long.class)
                .single();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
