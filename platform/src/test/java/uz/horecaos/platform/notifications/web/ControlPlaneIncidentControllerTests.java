package uz.horecaos.platform.notifications.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.notifications.api.ControlPlaneAlert;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcControlPlaneAlertStore;
import uz.horecaos.platform.support.TestDatabase;

/** ADR 0085: a person takes an incident, then closes it with what was done; both are audited once. */
class ControlPlaneIncidentControllerTests {

    private static final Instant T0 = Instant.parse("2026-09-11T01:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcControlPlaneAlertStore store;
    private AuditRecorder audit;
    private ControlPlaneIncidentController controller;

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
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE notifications.control_plane_alerts").update();
        store = new JdbcControlPlaneAlertStore(jdbc, JsonMapper.builder().build());
        audit = mock(AuditRecorder.class);
        controller = new ControlPlaneIncidentController(
                store,
                audit,
                () -> new AuthenticatedActor("support-1", Set.of("platform-support"), Map.of()),
                Clock.fixed(T0.plusSeconds(600), ZoneOffset.UTC));
    }

    @Test
    void anIncidentIsTakenThenResolvedAndEachStepIsAuditedOnce() {
        store.raise(new ControlPlaneAlert(
                "CONTROL_BAND_ESCALATED", "CONTROL_BAND", "outbox.dead", Map.of("band", "outbox.dead"), T0));
        UUID id = store.list(false, 10).getFirst().id();

        assertThat(controller
                        .acknowledge(id, new ControlPlaneIncidentController.NoteRequest("looking at the relay"))
                        .getStatusCode()
                        .value())
                .isEqualTo(204);
        controller.acknowledge(id, new ControlPlaneIncidentController.NoteRequest("again"));
        assertThat(store.find(id).orElseThrow().status()).isEqualTo("ACKNOWLEDGED");
        assertThat(store.find(id).orElseThrow().acknowledgedBy()).isEqualTo("support-1");

        controller.resolve(id, new ControlPlaneIncidentController.ResolveRequest("replayed the dead letters"));
        controller.resolve(id, new ControlPlaneIncidentController.ResolveRequest("twice"));

        assertThat(store.find(id).orElseThrow().status()).isEqualTo("RESOLVED");
        assertThat(store.find(id).orElseThrow().resolutionNote()).isEqualTo("replayed the dead letters");
        // One fact per step taken, none for the repeats.
        verify(audit, times(2)).record(any(AuditFact.class));
    }
}
