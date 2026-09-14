package uz.horecaos.platform.loyalty.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The seam onto ADR 0020 for the pre-expiry warning ADR 0046's own
 * {@code expiryWarningDays} has authored since V0042 and nothing has ever
 * sent (operations §6.3 Loyalty).
 *
 * <p>A port rather than a direct call into notifications, the same reason
 * {@code uz.horecaos.platform.courier.application.port.CourierNotificationPort}
 * is one: the payload must stay identifiers, an amount, and a date, never a
 * customer's name or contact value (ADR 0029), and an interface shaped that
 * way cannot be widened by accident into one that carries a person. The
 * template resolves the rest, on the notification side, from the account id.
 */
public interface LoyaltyExpiryWarningPort {

    /**
     * Warns that a lot is about to expire.
     *
     * @param remainingMinor what the lot still holds — the warning is about
     *                       this amount, not the account's whole balance,
     *                       because a customer with three lots should hear
     *                       about the one that is actually about to lapse
     * @param daysRemaining  whole days between the warning and {@code
     *                       expiresAt}, for the template to render
     */
    void lotExpiring(
            UUID tenantId, UUID accountId, UUID lotId, Instant expiresAt, long remainingMinor, long daysRemaining);
}
