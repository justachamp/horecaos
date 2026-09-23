package uz.horecaos.platform.fulfillment.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which in-house courier, if any, is carrying each of a page of orders right
 * now (ADR 0014, gap map row 1.1).
 *
 * <p>Ordering's board read needs this to render a Курьер column without
 * joining {@code fulfillment.shipments} itself — the courier filter parameter
 * on {@code GET .../orders/board} is the one predicate ADR 0102 permits to
 * leave the {@code ordering} schema directly (see {@code JdbcOrderStore
 * .listForLocation}'s own doc); a second field on every row is a different
 * shape of access and goes through this port instead, the same boundary
 * {@link DeliveryOrderPort} and {@link ShipmentCancellationPort} already
 * draw for their own directions.
 *
 * <p>Answers only a {@code courierId} — an opaque UUID, not personal data —
 * never a name or a display reference: {@code fulfillment} has no dependency
 * on the {@code courier} module and must not acquire one just to label a
 * board column (it would close {@code fulfillment -> courier -> fulfillment}
 * into a cycle {@code ModularArchitectureTests} refuses, since {@code courier}
 * already depends on {@code fulfillment.api} for its own roster load count).
 * The caller resolves {@code courierId} to a display reference the same way
 * the order detail pane and the dispatch board already do: against a roster
 * it fetched itself, client-side — see {@code order-queue.ts}'s
 * {@code courierDisplayReference}.
 */
public interface ActiveCourierAssignmentsPort {

    /**
     * The in-house courier carrying each order's active shipment, keyed by
     * order id. An order absent from the result has no open shipment at all,
     * or one carried by an external partner rather than an in-house courier
     * — both read identically to a caller that only wants "who, if anyone,
     * from our own fleet".
     */
    Map<UUID, UUID> assignedCouriers(UUID tenantId, Set<UUID> orderIds);
}
