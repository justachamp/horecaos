package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.notifications.api.ControlPlaneAlert;

/** {@link ControlPlaneAlertService} — v1's log-and-counter sink, see its own Javadoc for why. */
class ControlPlaneAlertServiceTests {

    @Test
    void raisingAnAlertIncrementsACounterTaggedByEventClass() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ControlPlaneAlertService service = new ControlPlaneAlertService(
                meters,
                org.mockito.Mockito.mock(
                        uz.horecaos.platform.notifications.infrastructure.persistence.JdbcControlPlaneAlertStore
                                .class));

        service.raise(new ControlPlaneAlert(
                "ONBOARDING_RUN_STUCK", "OnboardingRun", "run-1", Map.of("stuckSeconds", "3600"), Instant.now()));
        service.raise(new ControlPlaneAlert(
                "ONBOARDING_RUN_STUCK", "OnboardingRun", "run-2", Map.of("stuckSeconds", "60"), Instant.now()));
        service.raise(new ControlPlaneAlert(
                "CONTROL_BAND_ESCALATED", "ControlBandMetric", "outbox-backlog", Map.of("tier", "2"), Instant.now()));

        assertThat(meters.counter("horecaos.notifications.control_plane_alerts", "event_class", "ONBOARDING_RUN_STUCK")
                        .count())
                .isEqualTo(2.0);
        assertThat(meters.counter(
                                "horecaos.notifications.control_plane_alerts", "event_class", "CONTROL_BAND_ESCALATED")
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void anAlertThatCannotBeStoredIsStillRaised() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var store = org.mockito.Mockito.mock(
                uz.horecaos.platform.notifications.infrastructure.persistence.JdbcControlPlaneAlertStore.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("database down"))
                .when(store)
                .raise(org.mockito.ArgumentMatchers.any());
        ControlPlaneAlertService service = new ControlPlaneAlertService(meters, store);

        org.assertj.core.api.Assertions.assertThatCode(() -> service.raise(new ControlPlaneAlert(
                        "CONTROL_BAND_ESCALATED", "ControlBandMetric", "outbox-backlog", Map.of(), Instant.now())))
                .as("fire-and-forget: a watcher never fails because the incident could not be written")
                .doesNotThrowAnyException();
        assertThat(meters.counter(
                                "horecaos.notifications.control_plane_alerts", "event_class", "CONTROL_BAND_ESCALATED")
                        .count())
                .isEqualTo(1.0);
    }
}
