package uz.horecaos.platform.notifications.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The first name of the courier carrying an order, for the one customer message that may say it
 * (ADR 0184, D21; ADR 0045).
 *
 * <p>Declared here, in the consumer's module, and implemented by the module that owns the courier's
 * name. The other direction cannot exist: {@code courier} already depends on {@code payments}, which
 * depends on {@code notifications}, so a {@code notifications} dependency on {@code courier} would be a
 * cycle. The port is the same shape as {@link NotificationTransport}: notifications names what it needs
 * and somebody else supplies it.
 *
 * <p>Like {@code RecipientContactDirectory#resolveValue}, an answer to be used for the length of one
 * call and written nowhere: the notification row, its variables, its hashes and every log line stay free
 * of it. ADR 0045 allows the first name and no more, and an implementation returns exactly that. The
 * courier's surname and number are not on this port, and no port is planned for either.
 */
public interface CourierFirstNameDirectory {

    /**
     * The first name of the in-house courier carrying this order's active shipment.
     *
     * @return empty when no in-house courier is carrying the order (it has no open shipment, or an
     *         external partner carries it) or the courier has no name on file; the caller words that
     *         with a neutral phrase and never an empty hole
     */
    Optional<String> firstNameCarrying(UUID tenantId, UUID orderId);
}
