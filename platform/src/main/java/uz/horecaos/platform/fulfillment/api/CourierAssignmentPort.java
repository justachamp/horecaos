package uz.horecaos.platform.fulfillment.api;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.audit.api.ActorRef;

/**
 * How a person's bulk assignment reaches an order's delivery plan (ADR 0172).
 *
 * <p>Called by {@code ordering}, the direction {@link ShipmentCancellationPort}
 * already runs: {@code ordering} depends on {@code fulfillment.api}, never the
 * reverse. Unlike that port this one is a <em>mutation that is the whole act</em>,
 * not a cascade after the order's own change has committed, so the
 * implementation opens the item's own transaction and every failure it can
 * name comes back as a stable problem code, never as an exception message.
 *
 * <p>The implementation shares {@code JdbcAssignmentStore.open} and {@code win}
 * with the single assignment on the dispatch board, so the two paths keep one
 * single-winner guarantee (ADR 0014).
 */
public interface CourierAssignmentPort {

    /**
     * Assigns {@code courierId} to the order's open delivery plan.
     *
     * @param attemptKey      the idempotency key the assignment attempt is opened
     *                        under (ADR 0172 Decision 8), so a retried item reaches
     *                        the attempt it already opened rather than a second one
     * @param bulkOperationId the bulk request that asked, carried on the audit fact
     */
    Outcome assignForOrder(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID orderId,
            UUID courierId,
            String reasonCode,
            String attemptKey,
            UUID bulkOperationId,
            ActorRef actor);

    /** Whether the tenant has such a courier: a request-level check, before any item runs. */
    boolean courierExists(UUID tenantId, UUID courierId);

    /**
     * What {@link #assignForOrder} did.
     *
     * @param problemCode null when the assignment happened; otherwise one of
     *                    {@code NO_DELIVERY_PLAN}, {@code PLAN_NOT_OPEN},
     *                    {@code ALREADY_ASSIGNED_TO_THIS_COURIER},
     *                    {@code ALREADY_ASSIGNED}, {@code ALREADY_BEING_SOURCED},
     *                    {@code PARTNER_ATTEMPT_UNRECONCILED},
     *                    {@code COURIER_NOT_ELIGIBLE}, {@code COURIER_AT_CAPACITY}
     * @param notes       non-fatal annotations on an applied assignment
     *                    ({@code OVERRODE_DISPATCH_RULE}, {@code OUTSIDE_DISTANCE_BAND},
     *                    {@code NO_OPEN_SHIFT}); on a {@code COURIER_NOT_ELIGIBLE}
     *                    refusal, the dispatch gate's named reasons
     */
    record Outcome(
            @Nullable UUID planId,
            @Nullable UUID shipmentId,
            @Nullable String problemCode,
            List<String> notes) {

        public Outcome {
            notes = List.copyOf(notes);
        }

        public boolean applied() {
            return problemCode == null;
        }

        public static Outcome applied(UUID planId, UUID shipmentId, List<String> notes) {
            return new Outcome(planId, shipmentId, null, notes);
        }

        public static Outcome refused(@Nullable UUID planId, String problemCode, List<String> notes) {
            return new Outcome(planId, null, problemCode, notes);
        }
    }
}
