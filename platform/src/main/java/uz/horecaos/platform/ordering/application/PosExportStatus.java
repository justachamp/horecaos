package uz.horecaos.platform.ordering.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Whether an order's ticket has reached the till, and whether the till has
 * settled it.
 *
 * <p>Amendment asks this because the answer decides whether an edit is safe. A
 * ticket the kitchen has not seen can be amended freely; one the kitchen is
 * already cooking cannot, because the amendment would change an order nobody at
 * the pass will hear about.
 *
 * <p>A port in {@code ordering} rather than a call into {@code pos}, following
 * {@link OrderCatalogSnapshot}: ordering owns the question, and the adapter that
 * answers it reads the other module's table directly. The alternative — pos
 * publishing an event that ordering projects into its own process table — is a
 * second copy of a fact, and this guard exists precisely because the first copy
 * was never written.
 */
public interface PosExportStatus {

    /**
     * The export state for this order, or empty when no export exists.
     *
     * <p>Empty is a real answer and the common one: a location with no till
     * exports nothing, and an order placed before the export trigger existed has
     * no row either.
     */
    Optional<String> stateOf(UUID tenantId, UUID orderId);

    /**
     * Whether an amendment may proceed.
     *
     * <p>The default is deliberately spelled out here rather than in the adapter,
     * because it is a rule about orders and not about SQL.
     *
     * <p>{@code PENDING} is settled-enough on purpose: the row exists but nothing
     * has been sent, so the kitchen has seen nothing and the export will pick up
     * the amended order when it runs. Everything from {@code SENT} onwards is not,
     * including {@code UNCERTAIN} — an export that may or may not have landed is
     * exactly the case where amending could change an order already on a screen.
     * {@code REJECTED}, {@code RESOLVED_ABSENT} and {@code ABANDONED} are settled
     * because the till demonstrably does not have it.
     */
    default boolean settledFor(UUID tenantId, UUID orderId) {
        return stateOf(tenantId, orderId)
                .map(state -> switch (state) {
                    case "PENDING", "REJECTED", "RESOLVED_ABSENT", "ABANDONED" -> true;
                    default -> false;
                })
                .orElse(true);
    }
}
