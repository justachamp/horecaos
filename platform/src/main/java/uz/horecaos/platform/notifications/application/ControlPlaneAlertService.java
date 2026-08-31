package uz.horecaos.platform.notifications.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.notifications.api.ControlPlaneAlert;
import uz.horecaos.platform.notifications.api.ControlPlaneAlertPort;

/**
 * v1 of {@link ControlPlaneAlertPort} — see that interface's own Javadoc for
 * why a log line and a counter, not yet a Telegram send.
 */
@Component
public class ControlPlaneAlertService implements ControlPlaneAlertPort {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneAlertService.class);

    private final MeterRegistry meters;

    public ControlPlaneAlertService(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void raise(ControlPlaneAlert alert) {
        meters.counter("horecaos.notifications.control_plane_alerts", "event_class", alert.eventClass())
                .increment();
        // No PII by construction: ControlPlaneAlert carries no tenant/brand/
        // location and its own Javadoc says why, and every caller's
        // variables map is the same allowlisted-vocabulary discipline every
        // other ADR 0058 trigger in this build follows.
        log.warn(
                "Control-plane alert {} on {} {}: {}",
                alert.eventClass(),
                alert.subjectType(),
                alert.subjectId(),
                alert.variables());
    }
}
