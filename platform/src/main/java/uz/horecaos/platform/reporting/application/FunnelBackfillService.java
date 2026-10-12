package uz.horecaos.platform.reporting.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcFunnelStore;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcFunnelStore.DateRange;

/**
 * The one-time walk over business days closed before the funnel existed (ADR 0167, "Close job and
 * backfill").
 *
 * <p>Each already-closed date is captured as a day would be at close, from whatever the live tables
 * still hold: carts and checkout attempts for dates inside the retention window, and
 * {@code NULL} (not recorded) for dates outside it, with registrations and orders counted from the
 * tables that keep them. A tenant is walked once, then marked in {@code reporting.funnel_backfill_state};
 * days closed afterwards are captured by {@link DayCloseService} itself.
 *
 * <p>Each tenant is one transaction: a half-walked history is worse than an unwalked one, for the
 * reason the close job gives.
 */
@Service
public class FunnelBackfillService {

    private static final Logger log = LoggerFactory.getLogger(FunnelBackfillService.class);

    private final JdbcFunnelStore store;
    private final FunnelCaptureService capture;
    private final BusinessDayService businessDays;
    private final Clock clock;

    public FunnelBackfillService(
            JdbcFunnelStore store, FunnelCaptureService capture, BusinessDayService businessDays, Clock clock) {
        this.store = store;
        this.capture = capture;
        this.businessDays = businessDays;
        this.clock = clock;
    }

    /** @return how many business dates were walked; zero for a tenant already done or with no closed day yet */
    @Transactional
    public int backfill(UUID tenantId) {
        if (store.isBackfilled(tenantId)) {
            return 0;
        }
        Optional<DateRange> closed = store.closedRange(tenantId);
        if (closed.isEmpty()) {
            return 0;
        }
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        int walked = 0;
        for (LocalDate date = closed.get().first(); !date.isAfter(closed.get().last()); date = date.plusDays(1)) {
            capture.captureFromStoredFacts(tenantId, date, boundary);
            walked++;
        }
        store.markBackfilled(tenantId, closed.get().last(), clock.instant());
        log.info("Customer funnel backfill for tenant {}: {} closed business days walked", tenantId, walked);
        return walked;
    }
}
