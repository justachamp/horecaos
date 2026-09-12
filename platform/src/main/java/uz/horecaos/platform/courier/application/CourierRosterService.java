package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierGroupRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * How a manager organises the fleet: groups, and which branches a courier rides
 * for (IA 3.3).
 *
 * <p>Separate from {@link CourierEngagementService} because these are not
 * engagement facts. Suspending an engagement stops somebody working; moving them
 * out of the «night» group does not, and the two should not be reachable through
 * one object whose methods a reader has to tell apart by name.
 *
 * <p>Nothing here authorises a delivery. Dispatch reads the engagement's standing
 * and the open shift, exactly as it did before this class existed — a branch
 * binding that quietly gated an offer would be a second dispatch rule, invisible
 * beside the documented one, and the first symptom would be a courier who is on
 * shift and never offered anything.
 */
@Service
public class CourierRosterService {

    private final JdbcCourierStore couriers;
    private final AuditRecorder audit;
    private final Clock clock;

    public CourierRosterService(JdbcCourierStore couriers, AuditRecorder audit, Clock clock) {
        this.couriers = couriers;
        this.audit = audit;
        this.clock = clock;
    }

    public List<CourierGroupRow> groups(UUID tenantId) {
        return couriers.listGroups(tenantId);
    }

    @Transactional
    public UUID createGroup(
            UUID tenantId, String code, String displayName, ActorRef actor, String reason, String correlationId) {
        UUID groupId = Ids.newId();
        couriers.insertGroup(groupId, tenantId, code, displayName);

        audit.record(AuditFact.of("courier.group.created", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier_group", groupId)
                .because(reason)
                .changed(Map.of("code", code, "displayName", displayName))
                .usingCapability("courier.engagement.manage")
                .correlatedBy(correlationId)
                .occurredAt(clock.instant())
                .build());

        return groupId;
    }

    @Transactional
    public void archiveGroup(UUID tenantId, UUID groupId, ActorRef actor, String reason, String correlationId) {
        if (!couriers.archiveGroup(tenantId, groupId, clock.instant())) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "No active courier group " + groupId + " to archive");
        }

        audit.record(AuditFact.of("courier.group.archived", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier_group", groupId)
                .because(reason)
                .changed(Map.of("status", "ARCHIVED"))
                .usingCapability("courier.engagement.manage")
                .correlatedBy(correlationId)
                .occurredAt(clock.instant())
                .build());
    }

    /**
     * Puts a courier in a group. Repeating the call is not an error and writes
     * no second audit fact — the membership is the same membership, and a
     * retried request should not read afterwards as two decisions.
     */
    @Transactional
    public void addToGroup(
            UUID tenantId, UUID groupId, UUID courierId, ActorRef actor, String reason, String correlationId) {
        if (!couriers.addToGroup(tenantId, groupId, courierId, actor.subject(), clock.instant())) {
            return;
        }

        audit.record(AuditFact.of("courier.group.joined", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier", courierId)
                .because(reason)
                .changed(Map.of("groupId", groupId.toString()))
                .usingCapability("courier.engagement.manage")
                .correlatedBy(correlationId)
                .occurredAt(clock.instant())
                .build());
    }

    @Transactional
    public void removeFromGroup(
            UUID tenantId, UUID groupId, UUID courierId, ActorRef actor, String reason, String correlationId) {
        if (!couriers.removeFromGroup(tenantId, groupId, courierId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "That courier is not in that group");
        }

        audit.record(AuditFact.of("courier.group.left", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier", courierId)
                .because(reason)
                .changed(Map.of("groupId", groupId.toString()))
                .usingCapability("courier.engagement.manage")
                .correlatedBy(correlationId)
                .occurredAt(clock.instant())
                .build());
    }

    @Transactional
    public void bindToBranch(
            UUID tenantId,
            UUID courierId,
            UUID brandId,
            UUID locationId,
            boolean primary,
            ActorRef actor,
            String reason,
            String correlationId) {

        couriers.bindToBranch(tenantId, courierId, brandId, locationId, primary, actor.subject(), clock.instant());

        audit.record(AuditFact.of("courier.branch.bound", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("courier", courierId)
                .because(reason)
                .changed(Map.of("locationId", locationId.toString(), "primary", Boolean.toString(primary)))
                .usingCapability("courier.engagement.manage")
                .correlatedBy(correlationId)
                .occurredAt(clock.instant())
                .build());
    }

    @Transactional
    public void unbindFromBranch(
            UUID tenantId, UUID courierId, UUID locationId, ActorRef actor, String reason, String correlationId) {
        if (!couriers.unbindFromBranch(tenantId, courierId, locationId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "That courier is not bound to that branch");
        }

        audit.record(AuditFact.of("courier.branch.unbound", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier", courierId)
                .because(reason)
                .changed(Map.of("locationId", locationId.toString()))
                .usingCapability("courier.engagement.manage")
                .correlatedBy(correlationId)
                .occurredAt(clock.instant())
                .build());
    }
}
