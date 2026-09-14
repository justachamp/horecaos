package uz.horecaos.platform.loyalty.infrastructure.notification;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.loyalty.api.LoyaltyExpiryWarningPort;

/**
 * The pre-expiry warning, logged until ADR 0020's template and channel
 * selection for a points-expiry message exist — the exact placeholder shape
 * {@code courier.infrastructure.notification.LoggingCourierNotificationPort}
 * already uses for its own warning ladder, and for the same reason: {@link
 * uz.horecaos.platform.loyalty.application.LoyaltyMaintenanceService
 * #warnExpiringLots}'s decision to warn is the thing worth having, and losing
 * it because the transport is not built yet would mean nobody could tell
 * whether the sweep works at all. The log line carries identifiers, an
 * amount, and a date — never a customer's name or contact value — which is
 * the same constraint the real implementation will run under (ADR 0029).
 */
@Component
public class LoggingLoyaltyExpiryWarningPort implements LoyaltyExpiryWarningPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingLoyaltyExpiryWarningPort.class);

    @Override
    public void lotExpiring(
            UUID tenantId, UUID accountId, UUID lotId, Instant expiresAt, long remainingMinor, long daysRemaining) {
        log.info(
                "Loyalty lot expiring: tenant={} account={} lot={} expiresAt={} remainingMinor={} daysRemaining={}",
                tenantId,
                accountId,
                lotId,
                expiresAt,
                remainingMinor,
                daysRemaining);
    }
}
