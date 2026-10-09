package uz.horecaos.platform.reviews.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The service-recovery cases opened or closed in a window, for the day-close fact (ADR 0159, Decision
 * 12, ADR 0043).
 *
 * <p>A case counts as opened on its opening date and closed on its closing date, as a refund lands on
 * its own date, so a case appears in every window that contains either instant. Remedy money is read
 * from the amount and currency copied onto the compensation link when it was attached; this source reads
 * no {@code payments} table, and a benefit grant is a count, not money.
 */
public interface RecoveryFactSource {

    List<CaseFact> touchedBetween(UUID tenantId, Instant fromInclusive, Instant toExclusive);

    /**
     * @param firstResponseAt        null while nobody has started work
     * @param closedAt               null while the case is open
     * @param firstResponseBreached  the worker's mark, not a read-time calculation: a case closed in
     *                               the gap before the worker reached it is not counted as breached
     * @param remedyMoneyMinor       the sum of attached order remedies; zero when none
     * @param remedyCurrency         null when there is no remedy money
     */
    record CaseFact(
            UUID caseId,
            UUID brandId,
            UUID locationId,
            UUID orderId,
            UUID reviewId,
            int rating,
            String state,
            @Nullable String outcomeCode,
            Instant openedAt,
            @Nullable Instant firstResponseAt,
            @Nullable Instant closedAt,
            boolean firstResponseBreached,
            boolean resolutionBreached,
            int compensationCount,
            long remedyMoneyMinor,
            @Nullable String remedyCurrency) {}
}
