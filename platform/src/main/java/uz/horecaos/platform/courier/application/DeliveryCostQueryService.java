package uz.horecaos.platform.courier.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.courier.domain.CostBasis;
import uz.horecaos.platform.courier.domain.CostPath;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore.CostLineRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcDeliveryCostStore.PathTotal;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Reading delivery cost, always at a stated basis (ADR 0042).
 *
 * <p>The basis-less query is refused. That will be experienced as the platform
 * being difficult, and it is the mitigation for the honest problem underneath:
 * every delivery-cost figure now carries a basis label and readers ignore
 * labels. Refusing the unlabelled question is the only version of the rule that
 * survives contact with a reader in a hurry.
 *
 * <p>The result is two lines and a total, never one number. An in-house accrual
 * and a partner invoice are recognised at different instants, move by different
 * mechanisms, and rest on different tax documents; adding them and presenting
 * one figure is the specific error the two-path model exists to prevent.
 */
@Service
public class DeliveryCostQueryService {

    private final JdbcDeliveryCostStore costs;

    public DeliveryCostQueryService(JdbcDeliveryCostStore costs) {
        this.costs = costs;
    }

    /**
     * @param basis required. A caller with none is asking a question with two
     *              answers and no way to tell which one it got
     */
    public CostReport report(UUID tenantId, CostBasis basis, LocalDate from, LocalDate to) {
        if (basis == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A delivery-cost total is taken over a single basis and states it. "
                            + "Name one of ACCRUED, INVOICED, or SETTLED (ADR 0042).",
                    java.util.Map.of(
                            "acceptedValues",
                            List.of(CostBasis.ACCRUED, CostBasis.INVOICED, CostBasis.SETTLED).stream()
                                    .map(Enum::name)
                                    .toList()));
        }
        if (to.isBefore(from)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The range ends before it starts");
        }

        List<PathTotal> totals = costs.totalsByPath(tenantId, basis, from, to);
        long internal = totals.stream()
                .filter(total -> total.costPath() == CostPath.INTERNAL)
                .mapToLong(PathTotal::totalMinor)
                .sum();
        long partner = totals.stream()
                .filter(total -> total.costPath() == CostPath.PARTNER)
                .mapToLong(PathTotal::totalMinor)
                .sum();

        return new CostReport(
                basis,
                from,
                to,
                internal,
                partner,
                internal + partner,
                costs.shipmentsMissingBasis(tenantId, basis, from, to),
                totals);
    }

    /** Every live cost line on one shipment. There may legitimately be several. */
    public List<CostLineRow> linesOf(UUID tenantId, UUID shipmentId) {
        return costs.linesOfShipment(tenantId, shipmentId);
    }

    /**
     * @param shipmentsWithoutThisBasis shipments carrying cost at some other
     *                                  basis, reported beside the total rather
     *                                  than dropped. A report at INVOICED that
     *                                  quietly omitted every open internal
     *                                  accrual would look like a cheap week
     */
    public record CostReport(
            CostBasis basis,
            LocalDate from,
            LocalDate to,
            long internalMinor,
            long partnerMinor,
            long totalMinor,
            int shipmentsWithoutThisBasis,
            List<PathTotal> byPath) {}
}
