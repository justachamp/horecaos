package uz.horecaos.platform.loyalty.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The seam onto ADR 0020 for the pre-expiry warning ADR 0046's own {@code expiryWarningDays} has authored since
 * V0042 (operations §6.3 Loyalty, ADR 0181).
 *
 * <p>A port rather than a direct call into notifications, for two reasons. The payload must stay identifiers, an
 * amount, and a date, never a customer's name or contact value (ADR 0029), and an interface shaped that way cannot
 * be widened by accident into one that carries a person. And the module graph forbids the direct call: {@code
 * notifications} already reads {@code customers} and {@code marketing}, both of which read {@code loyalty}, so
 * {@code loyalty} cannot import {@code notifications}. The adapter therefore lives in {@code notifications} and
 * implements this interface, the same direction the order and fiscal triggers take.
 *
 * <p>One call is one customer's warning, not one lot's: ADR 0046's product default is "14 days before, at most one
 * message per customer per day", and a customer whose three lots expire in the same week hears about all three in
 * one message.
 */
public interface LoyaltyExpiryWarningPort {

    /**
     * Warns a customer that points are about to lapse.
     *
     * <p>Idempotent per customer and day: the adapter keys the message on the account and the day of {@code now},
     * so the sweep retrying after a crash between the send and the mark does not tell the customer twice. A throw
     * means the message was not handed over and the lots stay due.
     *
     * @param brandId the brand the points belong to (a loyalty account is per customer per brand)
     * @param customerAccountId the customer the message is for, so the adapter can choose a channel and resolve the
     *     contact on its own side
     * @param accountId the loyalty account the lots belong to
     * @param currency the currency the points are denominated in, for rendering the amount
     * @param lots the lots now inside their warning window, earliest expiry first; never empty
     * @param now the instant of the sweep, for the days remaining and the idempotency day
     */
    void lotsExpiring(
            UUID tenantId,
            UUID brandId,
            UUID customerAccountId,
            UUID accountId,
            String currency,
            List<ExpiringLot> lots,
            Instant now);

    /**
     * One lot inside its window.
     *
     * @param remainingMinor what the lot still holds: the warning is about this amount, not the account's whole
     *     balance
     */
    record ExpiringLot(UUID lotId, Instant expiresAt, long remainingMinor) {}
}
