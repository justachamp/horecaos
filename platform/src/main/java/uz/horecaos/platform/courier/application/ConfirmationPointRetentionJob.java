package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.courier.domain.CourierCompensationPolicy;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;

/**
 * Deleting the two confirmation coordinates once they can no longer be needed
 * (ADR 0042, ADR 0029).
 *
 * <p>Retention is tied to the reason the points are kept at all. A courier
 * disputes a statement, and a statement exists from period close; thirty days
 * past settlement is one full period plus a working month for a dispute to be
 * raised and answered. Beyond that the platform would be keeping a movement
 * history because storage is cheap.
 *
 * <p>What survives is what the accrual was computed from — the on-time outcome,
 * the unverified-geolocation flag, the distance and its source. None of it is
 * personal data, and every figure computed from the coordinates stays exactly as
 * it was, which is what makes a dispute in month four answerable about amounts.
 */
@Service
public class ConfirmationPointRetentionJob {

    private final JdbcCourierLedgerStore ledger;
    private final Clock clock;

    public ConfirmationPointRetentionJob(JdbcCourierLedgerStore ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * @param policy the resolved retention window. Passed in rather than resolved
     *               per row: this sweeps every tenant, and a per-row policy read
     *               would make the deletion depend on the order rows came back in
     */
    @Transactional
    public int purge(CourierCompensationPolicy policy) {
        Instant cutoff = clock.instant().minus(policy.confirmationPointRetentionDays(), ChronoUnit.DAYS);
        return ledger.purgeConfirmationPoints(cutoff);
    }
}
