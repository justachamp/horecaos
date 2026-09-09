package uz.horecaos.platform.pos.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.pos.domain.CatalogSnapshot;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosLiveAvailabilityStore;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosLiveAvailabilityStore.ReplaceResult;

/**
 * The transactional half of the stop-list poll: nothing but the local write.
 *
 * <p>Its own bean rather than a method on {@link PosAvailabilityPoll}, for the
 * identical reason {@link PosSyncSchedulingService}'s own class doc gives:
 * {@code @Transactional} on a method the {@code @Scheduled} bean calls on itself
 * bypasses the Spring proxy and the annotation describes a transaction that never
 * opens. Every claim and every local write in this module has lived on a bean the
 * scheduler calls into, never on the scheduler itself, since {@code
 * OnboardingScheduler}'s own incident.
 *
 * <p>Deliberately narrow: this method never calls the provider (that already
 * happened, in {@link PosAvailabilityPoll}, before this transaction opened — see
 * its class doc on why a provider HTTP call must never share a transaction with a
 * pooled connection) and never calls {@code inventory} (that happens afterward,
 * outside this transaction, because each toggle is its own independent fact and a
 * failure toggling one product must not roll back the reading this poll just
 * wrote).
 */
@Service
public class PosAvailabilityPollService {

    private final JdbcPosLiveAvailabilityStore store;

    public PosAvailabilityPollService(JdbcPosLiveAvailabilityStore store) {
        this.store = store;
    }

    @Transactional
    public ReplaceResult replace(
            UUID tenantId, UUID bindingId, List<CatalogSnapshot.Availability> entries, Instant now) {
        return store.replace(tenantId, bindingId, entries, now);
    }
}
