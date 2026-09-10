package uz.horecaos.platform.notifications.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.notifications.api.ControlPlaneAlert;
import uz.horecaos.platform.notifications.api.ControlPlaneAlertPort;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcControlPlaneAlertStore;

/**
 * {@link ControlPlaneAlertPort}: a log line, a counter, and (ADR 0085) an
 * incident kept until someone resolves it. Not yet a Telegram send; see that
 * interface's own Javadoc.
 */
@Component
public class ControlPlaneAlertService implements ControlPlaneAlertPort {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneAlertService.class);

    private final MeterRegistry meters;
    private final JdbcControlPlaneAlertStore alerts;

    public ControlPlaneAlertService(MeterRegistry meters, JdbcControlPlaneAlertStore alerts) {
        this.meters = meters;
        this.alerts = alerts;
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
        // ADR 0085: kept as an incident someone can see, acknowledge and
        // resolve. Still fire-and-forget: a caller never fails because the
        // incident could not be written, and the log line above already holds
        // the fact.
        try {
            alerts.raise(alert);
        } catch (RuntimeException unstored) {
            log.warn("Control-plane alert {} could not be stored as an incident", alert.eventClass(), unstored);
        }
    }
}
