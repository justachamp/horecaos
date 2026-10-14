package uz.horecaos.platform.tenancy.application;

import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.BrandContentFacts;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStorefrontBranchOrderStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStorefrontBranchOrderStore.Branch;

/**
 * The order a brand's storefront lists its branches in (gap-map row {@code 6.8}; ADR 0157 Release 4, built without waiting for its
 * trigger by ADR 0183 D17; {@code brands-and-locations.md} «Порядок в списке»).
 *
 * <p>The manager arranges the brand's branches once; the storefront uses that order when a customer has shared no point, and as
 * the tie-break after distance when one has. A branch left out of the order goes back to unset and sorts last. With nothing set
 * every branch is unset and the storefront orders them as it did before this existed (by name).
 *
 * <p>Every write leaves an audit fact in its transaction.
 */
@Service
public class StorefrontBranchOrderService {

    private final JdbcStorefrontBranchOrderStore store;
    private final BrandContentFacts brands;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final Clock clock;

    public StorefrontBranchOrderService(
            JdbcStorefrontBranchOrderStore store,
            BrandContentFacts brands,
            AuditRecorder audit,
            CurrentActor currentActor,
            Clock clock) {
        this.store = store;
        this.brands = brands;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Branch> list(UUID tenantId, UUID brandId) {
        requireBrand(tenantId, brandId);
        return store.list(tenantId, brandId);
    }

    /**
     * Replaces the order with the branches named, first to last; any other branch of the brand is unset.
     *
     * @throws IllegalArgumentException when a branch is named twice or is not this brand's
     */
    @Transactional
    public List<Branch> replace(UUID tenantId, UUID brandId, List<UUID> orderedIds) {
        requireBrand(tenantId, brandId);
        List<Branch> before = store.list(tenantId, brandId);
        Set<UUID> known = new HashSet<>();
        before.forEach(branch -> known.add(branch.locationId()));
        Set<UUID> seen = new HashSet<>();
        for (UUID id : orderedIds) {
            if (!known.contains(id)) {
                throw new IllegalArgumentException("A branch named in the order does not belong to this brand");
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException("A branch is named twice in the order");
            }
        }
        store.replace(tenantId, brandId, orderedIds, clock.instant());
        List<Branch> after = store.list(tenantId, brandId);
        audit.record(AuditFact.of("brand.storefront_branch_order.changed", AuditClass.BUSINESS)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("tenancy.brand", brandId)
                .because("Storefront branch order changed")
                .changed(ChangeDocuments.diff(snapshot(before), snapshot(after)))
                .correlatedBy(brandId.toString())
                .occurredAt(clock.instant())
                .build());
        return after;
    }

    private void requireBrand(UUID tenantId, UUID brandId) {
        if (!brands.brandExists(tenantId, brandId)) {
            throw new TenantResourceNotFoundException("No brand " + brandId + " belongs to this tenant");
        }
    }

    private static Map<String, Object> snapshot(List<Branch> branches) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (Branch branch : branches) {
            snapshot.put("branch." + branch.locationId(), branch.storefrontSortOrder());
        }
        return snapshot;
    }
}
