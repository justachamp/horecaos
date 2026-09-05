package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0064: "a call that becomes an order is an ordinary operations order
 * whose provenance records the call id" — the whole of that decision, in one
 * write-once field.
 *
 * <p>Deliberately not a dependency from {@code ordering} onto the {@code
 * voice} module. The frontend already holds both identifiers by the time it
 * calls this — the order id it just created or opened, and the call id its
 * own screen-pop card carries — so nothing here needs to ask voice whether the
 * call actually exists. Ordering stays a leaf with respect to voice, the same
 * as it is with respect to every other channel.
 */
@Service
public class OrderCallProvenanceService {

    private static final String DEFAULT_REASON =
            "Recorded when the operations app linked this order to the call it originated from";

    private final JdbcOrderStore orders;
    private final AuditRecorder audit;
    private final Clock clock;

    public OrderCallProvenanceService(JdbcOrderStore orders, AuditRecorder audit, Clock clock) {
        this.orders = orders;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public void record(UUID tenantId, UUID orderId, UUID callId, ActorRef actor, String capabilityUsed) {
        JdbcOrderStore.OrderRow order = orders.find(tenantId, orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));

        if (orders.recordCallProvenance(tenantId, orderId, callId)) {
            audit.record(AuditFact.of("ordering.order.call_provenance_recorded", AuditClass.BUSINESS)
                    .by(actor)
                    .at(ResourceScope.location(tenantId, order.brandId(), order.locationId()))
                    .target("Order", orderId)
                    .because(DEFAULT_REASON)
                    .usingCapability(capabilityUsed)
                    .changed(Map.of("callId", callId.toString()))
                    .correlatedBy(orderId.toString())
                    .occurredAt(clock.instant())
                    .build());
            return;
        }

        if (orders.hasCallProvenance(tenantId, orderId, callId)) {
            // The same operator's double submit, or a poll-driven retry. Not an
            // error: the fact this call already recorded is exactly the fact
            // this request is trying to establish.
            return;
        }

        throw new ApiException(
                ErrorCode.RESOURCE_CONFLICT,
                "This order already carries a different call id and provenance is write-once");
    }
}
