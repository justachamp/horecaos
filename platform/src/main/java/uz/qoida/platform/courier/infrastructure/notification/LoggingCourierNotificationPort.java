package uz.qoida.platform.courier.infrastructure.notification;

import java.time.LocalDate;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import uz.qoida.platform.courier.application.port.CourierNotificationPort;

/**
 * The registration ladder, logged until ADR 0020's template and channel
 * selection for courier-facing messages exist.
 *
 * <p>A deliberate placeholder rather than a silent no-op: the sweeper's decision
 * to warn is the thing worth having, and losing it because the transport is not
 * built yet would mean nobody could tell whether the ladder works at all. The
 * log line carries identifiers and a date only — never the registration number
 * and never the courier's name, per ADR 0029 — which is the same constraint the
 * real implementation will run under.
 */
@Component
public class LoggingCourierNotificationPort implements CourierNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingCourierNotificationPort.class);

    @Override
    public void registrationExpiring(UUID tenantId, UUID courierId, LocalDate validUntil,
            int daysRemaining, Audience audience) {

        log.info("Courier registration expiring: tenant={} courier={} validUntil={} "
                        + "daysRemaining={} audience={}",
                tenantId, courierId, validUntil, daysRemaining, audience);
    }

    @Override
    public void registrationLapsed(UUID tenantId, UUID courierId, LocalDate validUntil) {
        log.warn("Courier registration lapsed, dispatch suspended: tenant={} courier={} "
                        + "validUntil={}. Accrued earnings are unaffected.",
                tenantId, courierId, validUntil);
    }
}
