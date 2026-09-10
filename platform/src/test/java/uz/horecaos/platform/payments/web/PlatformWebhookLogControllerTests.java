package uz.horecaos.platform.payments.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/** ADR 0086: the webhook log's query agrees with the callback table, with and without its filters. */
class PlatformWebhookLogControllerTests {

    private static TestDatabase.Handle db;

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

    @Test
    void theQueryRunsWithAndWithoutItsFilters() {
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE payments.provider_callbacks").update();
        var log = new PlatformWebhookLogController(jdbc);

        assertThat(log.deliveries(null, false, 50)).isEmpty();
        assertThat(log.deliveries("CLICK", true, 50)).isEmpty();
        assertThat(log.deliveries(" ", false, 50)).isEmpty();
    }
}
