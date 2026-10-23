package uz.horecaos.platform.tenancy.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import uz.horecaos.platform.web.api.AggregateVersion;

/**
 * The order a brand's storefront lists its branches in (gap-map row {@code 6.8}; ADR 0157 Release 4, built without waiting for its
 * trigger by ADR 0183 D17; {@code brands-and-locations.md} «Порядок в списке»).
 *
 * <p>The manager arranges the brand's branches once; the storefront uses that order when a customer has shared no point, and as
 * the tie-break after distance when one has. A branch left out of the order goes back to unset and sorts last. With nothing set
 * every branch is unset and the storefront orders them as it did before this existed (by name).
 *
 * <p><strong>A replacement presents the version of the order it was made against.</strong> The write is a replace-all, so a
 * console that opened the screen before a colleague placed a branch would, on saving, quietly return that branch to unset. The
 * order has no row of its own, so its version is derived from what it is: a digest of the placed branches and their positions
 * ({@link #versionOf}). It moves when a branch is placed, moved or unplaced and not when a branch is renamed or added unplaced
 * (which cannot change what a replacement does to it). A replacement against any other version is refused with
 * {@code STALE_VERSION} (ADR 0031), the brand's row is held while it is compared and written, and nothing changes.
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

    /** The brand's branches in storefront order, and the version of that order a replacement must present. */
    public record Order(List<Branch> branches, long version) {}

    @Transactional(readOnly = true)
    public Order list(UUID tenantId, UUID brandId) {
        requireBrand(tenantId, brandId);
        List<Branch> branches = store.list(tenantId, brandId);
        return new Order(branches, versionOf(branches));
    }

    /**
     * Replaces the order with the branches named, first to last; any other branch of the brand is unset.
     *
     * @param expectedVersion the version of the order the caller read, from {@link #list}
     * @throws uz.horecaos.platform.web.api.ApiException {@code STALE_VERSION} when the order has moved since
     * @throws IllegalArgumentException when a branch is named twice or is not this brand's
     */
    @Transactional
    public Order replace(UUID tenantId, UUID brandId, List<UUID> orderedIds, long expectedVersion) {
        requireBrand(tenantId, brandId);
        store.lockBrand(tenantId, brandId);
        List<Branch> before = store.list(tenantId, brandId);
        AggregateVersion.requireMatch(expectedVersion, versionOf(before));
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
        Set<UUID> moved = branchesThatMoved(before, after);
        audit.record(AuditFact.of("brand.storefront_branch_order.changed", AuditClass.BUSINESS)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("tenancy.brand", brandId)
                .because("Storefront branch order changed")
                .changed(ChangeDocuments.diff(snapshot(before, moved), snapshot(after, moved)))
                .correlatedBy(brandId.toString())
                .occurredAt(clock.instant())
                .build());
        return new Order(after, versionOf(after));
    }

    /**
     * The version of an order: 48 bits of a SHA-256 over the placed branches and their positions, first to last, so it is
     * the same for the same order whoever reads it and is a whole number a browser holds exactly.
     */
    static long versionOf(List<Branch> branches) {
        List<Branch> placed = new ArrayList<>();
        for (Branch branch : branches) {
            if (branch.storefrontSortOrder() != null) {
                placed.add(branch);
            }
        }
        placed.sort(Comparator.comparing((Branch branch) -> Objects.requireNonNull(branch.storefrontSortOrder()))
                .thenComparing(Branch::locationId));
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", missing);
        }
        for (Branch branch : placed) {
            digest.update(
                    (branch.locationId() + "=" + branch.storefrontSortOrder() + ";").getBytes(StandardCharsets.UTF_8));
        }
        byte[] hash = digest.digest();
        long version = 0;
        for (int i = 0; i < 6; i++) {
            version = (version << 8) | (hash[i] & 0xFF);
        }
        return version;
    }

    private void requireBrand(UUID tenantId, UUID brandId) {
        if (!brands.brandExists(tenantId, brandId)) {
            throw new TenantResourceNotFoundException("No brand " + brandId + " belongs to this tenant");
        }
    }

    /**
     * The branches whose position differs between the two lists. {@code ChangeDocuments.diff} keeps every field it is
     * given, changed or not, so the audit document is built over these alone: a reader of the activity log sees what
     * moved, not the whole brand with most of it unchanged.
     */
    private static Set<UUID> branchesThatMoved(List<Branch> before, List<Branch> after) {
        Map<UUID, Integer> was = new HashMap<>();
        before.forEach(branch -> was.put(branch.locationId(), branch.storefrontSortOrder()));
        Set<UUID> moved = new LinkedHashSet<>();
        for (Branch branch : after) {
            if (!Objects.equals(was.get(branch.locationId()), branch.storefrontSortOrder())) {
                moved.add(branch.locationId());
            }
        }
        return moved;
    }

    private static Map<String, Object> snapshot(List<Branch> branches, Set<UUID> only) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (Branch branch : branches) {
            if (only.contains(branch.locationId())) {
                snapshot.put("branch." + branch.locationId(), branch.storefrontSortOrder());
            }
        }
        return snapshot;
    }
}
