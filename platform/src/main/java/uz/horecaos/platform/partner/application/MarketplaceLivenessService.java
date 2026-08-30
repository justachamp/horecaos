package uz.horecaos.platform.partner.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.partner.infrastructure.persistence.JdbcPartnerStore;

/**
 * Noticing that a channel has gone quiet (ADR 0040).
 *
 * <p>A dead marketplace integration produces no errors, because nothing is being
 * called. An expired token or a revoked venue looks exactly like a quiet Tuesday
 * until the manager notices Friday was quiet too. ADR 0006 records failures that
 * happened and has no concept of work that stopped arriving, which is why this
 * exists beside it rather than inside it.
 *
 * <p>The threshold is per binding for a reason that is not tuning: a branch
 * taking two Uzum orders a day and one taking two hundred have completely
 * different silences, and a single global number alerts constantly on the first
 * and never on the second. The observed trailing median sits beside the
 * threshold so the number gets set from evidence rather than guessed, and a
 * binding that has never received anything reports no silence at all — "nothing
 * has ever arrived here" is an unfinished configuration and "nothing has arrived
 * for three hours" is a working integration that stopped, and an operator fixes
 * them in different places.
 */
@Service
public class MarketplaceLivenessService {

    private final JdbcPartnerStore store;
    private final Clock clock;

    public MarketplaceLivenessService(JdbcPartnerStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** The locations-by-bindings matrix an operator reads as «последний заказ». */
    public List<JdbcPartnerStore.LivenessRow> matrix(UUID tenantId) {
        return store.liveness(tenantId, clock.instant());
    }

    /**
     * Raises the alert on every binding whose silence has crossed its own
     * threshold, and answers with the bindings that just changed.
     *
     * <p>Idempotent by construction: the UPDATE only touches rows still
     * {@code HEALTHY}, so running it every minute raises one alert per binding
     * rather than one per run. The recovery direction is handled where the
     * evidence is — a successful push clears the alert in the same statement
     * that records it, because a channel that has just delivered an order is
     * demonstrably alive and should not wait for a sweep to say so.
     */
    public List<UUID> evaluateStaleness(UUID tenantId) {
        return store.markStale(tenantId, clock.instant());
    }
}
