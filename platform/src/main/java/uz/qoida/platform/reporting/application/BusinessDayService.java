package uz.qoida.platform.reporting.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import uz.qoida.platform.reporting.domain.BusinessDayBoundary;
import uz.qoida.platform.reporting.infrastructure.persistence.JdbcReportingStore;

/**
 * Resolves a tenant's business-day boundary (ADR 0043).
 *
 * <p>Resolution order, most specific first: the tenant's stored policy, then the
 * platform default of midnight in the tenant's declared timezone. There is
 * deliberately no fallback beyond that — a tenant with no timezone at all is a
 * provisioning bug, and answering it with UTC would file a third of every Tashkent
 * evening under the following day while looking entirely healthy.
 */
@Service
public class BusinessDayService {

    private final JdbcReportingStore store;

    public BusinessDayService(JdbcReportingStore store) {
        this.store = store;
    }

    public BusinessDayBoundary boundaryFor(UUID tenantId) {
        return store.findBoundary(tenantId)
                .map(JdbcReportingStore.StoredBoundary::boundary)
                .orElseGet(() -> BusinessDayBoundary.midnight(zoneOf(tenantId)));
    }

    /**
     * How far a recut after a boundary change has got, if one is outstanding.
     *
     * <p>Empty means no boundary change is in flight and every stored day was
     * computed under the same regime.
     */
    public Optional<LocalDate> recutCompletedThrough(UUID tenantId) {
        return store.findBoundary(tenantId)
                .map(JdbcReportingStore.StoredBoundary::recutCompletedThrough);
    }

    /** Records a boundary. The caller is responsible for the ADR 0027 approval. */
    public void setBoundary(UUID tenantId, BusinessDayBoundary boundary, LocalDate effectiveFrom,
            LocalDate recutCompletedThrough) {
        store.upsertBoundary(tenantId, boundary, effectiveFrom, recutCompletedThrough);
    }

    private ZoneId zoneOf(UUID tenantId) {
        return ZoneId.of(store.findTenantTimezone(tenantId).orElseThrow(() ->
                new IllegalStateException(
                        "Tenant %s has no timezone, so no business day can be computed"
                                .formatted(tenantId))));
    }
}
