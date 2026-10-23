package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.fulfillment.api.ActiveCourierAssignmentsPort;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.notifications.api.CourierFirstNameDirectory;

/**
 * Telling a customer the first name of the courier who has their order (ADR 0045's «on the way, with
 * the courier's first name»; ADR 0184, D21).
 *
 * <p>The courier's name is {@code PERSONAL} and envelope-encrypted on {@code fulfillment.couriers}
 * (ADR 0029), revealed only under a declared purpose and only with an audit fact. This is the one path
 * that reveals it for a customer, and it reveals the first word and nothing else: the full name is
 * decrypted inside this method, cut, and dropped. The audit fact names the courier by id, the order,
 * and the purpose, and never the name.
 *
 * <p>An order carried by an external partner, an order with no open shipment, and a courier with no
 * name all answer empty, and the message says a neutral phrase. A courier is never asked for by
 * guessing: the courier is the one {@link ActiveCourierAssignmentsPort} reports for the order's open
 * shipment.
 */
@Service
public class CourierFirstNameDisclosure implements CourierFirstNameDirectory {

    /** Recorded on every reveal, so a customer message is distinguishable from a dispatcher opening a courier. */
    static final String PURPOSE = "CUSTOMER_ORDER_STATUS_MESSAGE";

    private final ActiveCourierAssignmentsPort assignments;
    private final JdbcCourierStore couriers;
    private final FieldProtection protection;
    private final AuditRecorder audit;
    private final Clock clock;

    public CourierFirstNameDisclosure(
            ActiveCourierAssignmentsPort assignments,
            JdbcCourierStore couriers,
            FieldProtection protection,
            AuditRecorder audit,
            Clock clock) {
        this.assignments = assignments;
        this.couriers = couriers;
        this.protection = protection;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Optional<String> firstNameCarrying(UUID tenantId, UUID orderId) {
        UUID courierId = assignments.assignedCouriers(tenantId, Set.of(orderId)).get(orderId);
        if (courierId == null) {
            return Optional.empty();
        }
        return couriers.findCourier(tenantId, courierId).flatMap(courier -> {
            String fullName = protection.reveal(
                    tenantId,
                    ProtectedValue.deserialize(courier.protectedFullName()),
                    new FieldProtection.RecordRef("fulfillment.couriers", "protected_full_name", courierId),
                    PURPOSE);
            Optional<String> firstName = firstWordOf(fullName);
            // The fact is written whether or not a name came out of it: the reveal happened.
            audit.record(AuditFact.of("courier.first_name.disclosed_to_customer", AuditClass.SECURITY)
                    .by(ActorRef.systemJob("customer-order-status-message"))
                    .at(ResourceScope.tenant(tenantId))
                    .target("fulfillment.courier", courierId)
                    .because("The courier's first name was put in the customer's on-the-way message")
                    .changed(ChangeDocuments.created(Map.of("orderId", orderId.toString(), "purpose", PURPOSE)))
                    .correlatedBy(orderId.toString())
                    .occurredAt(clock.instant())
                    .build());
            return firstName;
        });
    }

    /** The first whitespace-separated word, or empty for a blank name. Package-visible for its unit test. */
    static Optional<String> firstWordOf(String fullName) {
        if (fullName == null) {
            return Optional.empty();
        }
        String stripped = fullName.strip();
        if (stripped.isEmpty()) {
            return Optional.empty();
        }
        int space = stripped.indexOf(' ');
        return Optional.of(space < 0 ? stripped : stripped.substring(0, space));
    }
}
