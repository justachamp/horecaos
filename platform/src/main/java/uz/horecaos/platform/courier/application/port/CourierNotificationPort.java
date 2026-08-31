package uz.horecaos.platform.courier.application.port;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The seam onto ADR 0020 for the registration warning ladder.
 *
 * <p>A port rather than a direct call into notifications, for one reason that is
 * about this ADR rather than about layering: the payload must never carry the
 * registration identifier or the courier's name (ADR 0029), and an interface
 * whose parameters are identifiers and a date cannot be widened by accident into
 * one that does. The template resolves the rest, on the notification side, from
 * the courier id.
 */
public interface CourierNotificationPort {

    /**
     * Warns that a registration is running out.
     *
     * @param daysRemaining the ladder rung: 30, 14, 7, 1, or 0 for the lapse
     */
    void registrationExpiring(
            UUID tenantId, UUID courierId, LocalDate validUntil, int daysRemaining, Audience audience);

    void registrationLapsed(UUID tenantId, UUID courierId, LocalDate validUntil);

    enum Audience {
        COURIER,

        /**
         * From day fourteen. A courier who ignores the message is the tenant's
         * problem too: the branch loses a rider on the evening it happens.
         */
        MANAGER
    }
}
