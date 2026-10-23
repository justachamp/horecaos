package uz.horecaos.platform.payments.settlement;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.reviews.spi.CompensationDirectory;

/**
 * Describes an ADR 0048 order remedy to a service-recovery case (ADR 0159, Decision 9).
 *
 * <p>The direction is the point. A call from {@code reviews} into {@code payments} would close {@code
 * reviews -> payments -> notifications -> reviews} and {@code reviews -> payments -> integration ->
 * reviews}, so {@code reviews} declares the port in {@code reviews.spi} and {@code payments} implements
 * it -- the direction {@code dinein} already takes for {@code customers.spi.CustomerErasureParticipant}.
 * {@code payments} depends on {@code reviews}, never the reverse, and {@code payments}' schema is
 * untouched: the case holds no foreign key into {@code order_remedies}, only the id and the amount and
 * currency this read returns, which it copies at the moment of attaching.
 *
 * <p>A pure read of one row: an id of another tenant is absent, the same answer as "does not exist". The
 * remedy itself is recorded, approved and executed through {@link OrderRemedyService} under its own
 * threshold; nothing here records, changes or pays anything.
 */
@Component
class OrderRemedyCompensationDirectory implements CompensationDirectory {

    private final JdbcRemedyStore remedies;

    OrderRemedyCompensationDirectory(JdbcRemedyStore remedies) {
        this.remedies = remedies;
    }

    @Override
    public String kind() {
        return "ORDER_REMEDY";
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CompensationSummary> summary(UUID tenantId, UUID refId) {
        return remedies.findRemedy(tenantId, refId)
                .map(remedy -> new CompensationSummary(
                        kind(), remedy.id(), remedy.orderId(), remedy.amountMinor(), remedy.currency()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CompensationCandidate> candidatesForOrder(UUID tenantId, UUID orderId) {
        return remedies.remediesOfOrder(tenantId, orderId).stream()
                .map(remedy -> new CompensationCandidate(
                        kind(),
                        remedy.id(),
                        remedy.remedyType().name(),
                        remedy.executedAt() == null ? "RECORDED" : "EXECUTED",
                        remedy.amountMinor(),
                        remedy.currency(),
                        remedy.recordedAt()))
                .toList();
    }
}
