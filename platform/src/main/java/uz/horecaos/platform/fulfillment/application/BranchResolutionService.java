package uz.horecaos.platform.fulfillment.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.fulfillment.api.BranchResolutionPort;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcBranchResolutionStore;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/** Thin {@link BranchResolutionPort} implementation over {@link JdbcBranchResolutionStore}. */
@Service
public class BranchResolutionService implements BranchResolutionPort {

    private final JdbcBranchResolutionStore store;

    public BranchResolutionService(JdbcBranchResolutionStore store) {
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryBranchMatch> deliveryCandidates(UUID tenantId, UUID brandId, GeoPoint point, Instant at) {
        return store.deliveryCandidates(tenantId, brandId, point, at);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PickupBranchCandidate> pickupCandidates(UUID tenantId, UUID brandId) {
        return store.pickupCandidates(tenantId, brandId);
    }
}
