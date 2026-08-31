package uz.horecaos.platform.integration.outbox;

import java.util.UUID;

/**
 * The half of {@link ShipmentReconciliationOutbox} that
 * {@link uz.horecaos.platform.integration.camel.delivery.DeliveryProcessor}
 * actually needs.
 *
 * <p>Narrowed so the route processor depends on "can ask for a reconciliation",
 * not on the JDBC store and object mapper the concrete outbox also carries —
 * interface segregation rather than a fat dependency. It is also what lets a
 * route test's recording double implement this directly, with no database and
 * no null placeholders standing in for collaborators it never uses.
 *
 * <p>A top-level type rather than a member of {@link ShipmentReconciliationOutbox}
 * itself: {@code class X implements X.Y} is a cyclic-inheritance error in javac —
 * resolving {@code X}'s supertypes requires completing its member {@code Y}, and
 * completing a member type requires its enclosing type already resolved.
 */
public interface ReconciliationRequester {

    void requestReconciliation(UUID tenantId, ShipmentReconciliationOutbox.Command command, String correlationId);
}
