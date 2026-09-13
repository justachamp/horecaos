package uz.horecaos.platform.tenancy.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcBranchTagStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcBranchTagStore.Assignment;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcBranchTagStore.BranchTag;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * 10.10d: a chain's own registry of branch tags, and the filter-and-group
 * affordance settings.md names as the whole point of having them — "which
 * branches carry this tag" is the read this service composes for that.
 *
 * <p>Delever ships this page empty, per the same doc. Archived rather than
 * deleted, the shape V0029's outcome reasons already established: an
 * already-tagged branch must keep resolving the tag it carries.
 */
@Service
public class BranchTagService {

    private final JdbcBranchTagStore tags;
    private final AuditRecorder audit;
    private final Clock clock;

    public BranchTagService(JdbcBranchTagStore tags, AuditRecorder audit, Clock clock) {
        this.tags = tags;
        this.audit = audit;
        this.clock = clock;
    }

    public List<BranchTag> list(UUID tenantId, boolean activeOnly) {
        return tags.list(tenantId, activeOnly);
    }

    /** Every branch's tags, tenant-wide, for the settings screen's filter-and-group grid. */
    public List<Assignment> assignments(UUID tenantId) {
        return tags.assignments(tenantId);
    }

    public List<UUID> tagsOf(UUID tenantId, UUID locationId) {
        return tags.tagsOf(tenantId, locationId);
    }

    @Transactional
    public UUID create(UUID tenantId, String code, String displayName, ActorRef actor, String reason) {
        UUID id = Ids.newId();
        try {
            tags.insert(tenantId, id, code, displayName);
        } catch (DuplicateKeyException already) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "A tag with that code already exists");
        }
        audit.record(AuditFact.of("tenant.branch-tag.created", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("BranchTag", id)
                .because(reason)
                .changed(Map.of("code", code, "displayName", displayName))
                .usingCapability(Capability.LOCATION_WRITE.code())
                .correlatedBy(id.toString())
                .occurredAt(clock.instant())
                .build());
        return id;
    }

    @Transactional
    public void archive(UUID tenantId, UUID tagId, ActorRef actor, String reason) {
        BranchTag tag = tags.find(tenantId, tagId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such tag"));
        if (!tags.archive(tenantId, tagId)) {
            return;
        }
        audit.record(AuditFact.of("tenant.branch-tag.archived", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("BranchTag", tagId)
                .because(reason)
                .changed(Map.of("code", tag.code()))
                .usingCapability(Capability.LOCATION_WRITE.code())
                .correlatedBy(tagId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    @Transactional
    public void setTagsForLocation(
            UUID tenantId, UUID brandId, UUID locationId, List<UUID> tagIds, ActorRef actor, String reason) {
        List<UUID> before = tags.tagsOf(tenantId, locationId);
        List<UUID> after = List.copyOf(tagIds);
        for (UUID tagId : after) {
            if (!before.contains(tagId)) {
                tags.assign(tenantId, locationId, tagId);
            }
        }
        for (UUID tagId : before) {
            if (!after.contains(tagId)) {
                tags.unassign(tenantId, locationId, tagId);
            }
        }
        if (before.equals(after)) {
            return;
        }
        audit.record(AuditFact.of("tenant.branch-tag.assignment-set", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("Location", locationId)
                .because(reason)
                .changed(Map.of(
                        "from",
                                before.stream()
                                        .map(UUID::toString)
                                        .sorted()
                                        .toList()
                                        .toString(),
                        "to",
                                after.stream()
                                        .map(UUID::toString)
                                        .sorted()
                                        .toList()
                                        .toString()))
                .usingCapability(Capability.LOCATION_WRITE.code())
                .correlatedBy(locationId.toString())
                .occurredAt(clock.instant())
                .build());
    }
}
