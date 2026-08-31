package uz.horecaos.platform.integration.camel.delivery;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import uz.horecaos.platform.integration.outbox.ReconciliationRequester;
import uz.horecaos.platform.integration.outbox.ShipmentReconciliationOutbox.Command;

/**
 * The reconciliation outbox with the database taken out.
 *
 * <p>Used by the route tests, which are about what the route does next after
 * each of the four outcomes and have no business starting PostgreSQL to find
 * out. The full path — a command on Kafka, through the inbox, out through the
 * route, back as an outbox row — is proved against a real database in
 * {@code ShipmentReconciliationPathTests}, and this double is deliberately not
 * used there.
 *
 * <p>Implements {@link ReconciliationRequester} directly rather than extending
 * {@code ShipmentReconciliationOutbox}: the concrete class also carries a JDBC
 * store and an object mapper that this double has no database to back, and that
 * {@link DeliveryProcessor} never asks it for.
 */
final class RecordingReconciliationOutbox implements ReconciliationRequester {

    private final List<Command> requested = new CopyOnWriteArrayList<>();

    @Override
    public void requestReconciliation(UUID tenantId, Command command, String correlationId) {
        requested.add(command);
    }

    List<Command> requested() {
        return List.copyOf(requested);
    }
}
