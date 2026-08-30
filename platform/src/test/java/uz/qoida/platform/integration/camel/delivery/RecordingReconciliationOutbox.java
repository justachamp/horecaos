package uz.qoida.platform.integration.camel.delivery;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import uz.qoida.platform.integration.outbox.ShipmentReconciliationOutbox;

/**
 * The reconciliation outbox with the database taken out.
 *
 * <p>Used by the route tests, which are about what the route does next after
 * each of the four outcomes and have no business starting PostgreSQL to find
 * out. The full path — a command on Kafka, through the inbox, out through the
 * route, back as an outbox row — is proved against a real database in
 * {@code ShipmentReconciliationPathTests}, and this double is deliberately not
 * used there.
 */
final class RecordingReconciliationOutbox extends ShipmentReconciliationOutbox {

    private final List<Command> requested = new CopyOnWriteArrayList<>();

    RecordingReconciliationOutbox() {
        super(null, null, Clock.systemUTC());
    }

    @Override
    public void requestReconciliation(UUID tenantId, Command command, String correlationId) {
        requested.add(command);
    }

    List<Command> requested() {
        return List.copyOf(requested);
    }
}
