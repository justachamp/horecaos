package uz.horecaos.platform.notifications.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.notifications.api.ControlPlaneAlert;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcControlPlaneAlertStore.StoredAlert;
import uz.horecaos.platform.support.TestDatabase;

/** ADR 0085 against PostgreSQL: one live incident per thing that is wrong, closed by a person. */
class JdbcControlPlaneAlertStoreTests {

    private static final Instant T0 = Instant.parse("2026-09-11T01:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcControlPlaneAlertStore store;

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
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE notifications.control_plane_alerts").update();
        store = new JdbcControlPlaneAlertStore(jdbc, JsonMapper.builder().build());
    }

    @Test
    void aRepeatedAlertCountsAnotherOccurrenceOnTheSameIncident() {
        store.raise(backlog("1", T0));
        store.raise(backlog("2", T0.plusSeconds(60)));
        store.raise(backlog("3", T0.plusSeconds(120)));

        assertThat(store.list(false, 50)).singleElement().satisfies(incident -> {
            assertThat(incident.occurrences()).isEqualTo(3);
            assertThat(incident.firstRaisedAt()).isEqualTo(T0);
            assertThat(incident.lastRaisedAt()).isEqualTo(T0.plusSeconds(120));
            assertThat(incident.variables()).as("the latest numbers").containsEntry("tier", "3");
            assertThat(incident.status()).isEqualTo("OPEN");
        });
    }

    @Test
    void acknowledgingAndResolvingAreEachDoneOnceAndARaiseAfterResolutionOpensANewIncident() {
        store.raise(backlog("2", T0));
        StoredAlert incident = store.list(false, 50).getFirst();

        assertThat(store.acknowledge(incident.id(), "ops-1", T0.plusSeconds(30)))
                .isTrue();
        assertThat(store.acknowledge(incident.id(), "ops-2", T0.plusSeconds(40)))
                .isFalse();
        store.raise(backlog("2", T0.plusSeconds(50)));
        assertThat(store.find(incident.id()).orElseThrow().occurrences())
                .as("an acknowledged incident is still live and still counts")
                .isEqualTo(2);

        assertThat(store.resolve(incident.id(), "ops-1", "drained after the broker restart", T0.plusSeconds(90)))
                .isTrue();
        assertThat(store.resolve(incident.id(), "ops-2", "again", T0.plusSeconds(95)))
                .isFalse();

        store.raise(backlog("1", T0.plusSeconds(600)));
        assertThat(store.list(false, 50))
                .as("the resolved incident keeps its record; the new raise is a new incident")
                .singleElement()
                .satisfies(fresh -> {
                    assertThat(fresh.id()).isNotEqualTo(incident.id());
                    assertThat(fresh.occurrences()).isEqualTo(1);
                });
        assertThat(store.list(true, 50)).hasSize(2);
        assertThat(store.find(incident.id()).orElseThrow().resolutionNote())
                .isEqualTo("drained after the broker restart");
    }

    private static ControlPlaneAlert backlog(String tier, Instant at) {
        return new ControlPlaneAlert(
                "CONTROL_BAND_ESCALATED", "ControlBandMetric", "outbox-backlog", Map.of("tier", tier), at);
    }
}
