package uz.horecaos.platform.ordering.application;

import io.micrometer.core.instrument.Metrics;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.inventory.api.DisposalRefusedException;
import uz.horecaos.platform.inventory.api.InventoryReservationPort;
import uz.horecaos.platform.inventory.api.StockDisposal;
import uz.horecaos.platform.inventory.api.StockDisposalCommand;
import uz.horecaos.platform.ordering.api.OrderLineProductionPort;
import uz.horecaos.platform.ordering.api.OrderSettlementPort;
import uz.horecaos.platform.ordering.api.OrderingConfigurationKeys;
import uz.horecaos.platform.ordering.application.AmendmentBasket.LineDecrease;
import uz.horecaos.platform.ordering.application.OrderAmendmentService.AmendmentRefusedException;
import uz.horecaos.platform.ordering.domain.OutcomeReasonKind;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderAmendmentStore.AmendmentRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderLineRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOutcomeReasonStore.ReasonRow;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * Taking a line off a confirmed order, or lowering its quantity (ADR 0169): the part of the amendment that is
 * about stock, the reason that decides what happens to it, and the cash the courier is told to collect.
 *
 * <p>{@link OrderAmendmentService} owns the amendment (the quote, the revision, the approval). This owns what a
 * line leaving the order means beyond the money:
 *
 * <ul>
 *   <li>the removal reason, a row the tenant authored once ({@code kind = LINE_REMOVAL}); the operator picks the
 *       reason and never a disposition, as ADR 0039 decided for cancellation;
 *   <li>what the kitchen's own record says about the line, which can only make the disposition more
 *       conservative: a line the kitchen has started or finished is a dish, and a dish is written off whatever
 *       the reason says about shelves;
 *   <li>the call to inventory's {@code dispose}, which reads the reservations itself to tell a unit still on a
 *       hold from a unit already sold;
 *   <li>the evidence row, one per line per amendment;
 *   <li>the cash plan of an order that is paid at the door, restated to the new total.
 * </ul>
 *
 * <p>The kitchen's answer is per line, not per unit: a ticket item carries one status for its whole quantity, so
 * a started line writes off every unit taken from it. The alternative needs a per-unit kitchen state the ticket
 * does not hold (ADR 0169, accepted trade-offs).
 */
@Component
public class LineRemovalCoordinator {

    private final ConfigurationResolver configuration;
    private final OrderOutcomeReasonService reasons;
    private final OrderLineProductionPort production;
    private final InventoryReservationPort inventory;
    private final OrderSettlementPort settlement;
    private final JdbcOrderStore orders;
    private final ObjectMapper objectMapper;

    public LineRemovalCoordinator(
            ConfigurationResolver configuration,
            OrderOutcomeReasonService reasons,
            OrderLineProductionPort production,
            InventoryReservationPort inventory,
            OrderSettlementPort settlement,
            JdbcOrderStore orders,
            ObjectMapper objectMapper) {
        this.configuration = configuration;
        this.reasons = reasons;
        this.production = production;
        this.inventory = inventory;
        this.settlement = settlement;
        this.orders = orders;
        this.objectMapper = objectMapper;
    }

    /**
     * What an operator is told about one line before the amendment is applied, and what the amendment
     * recorded when it was.
     *
     * @param kitchenState {@code NOT_STARTED}, {@code STARTED}, {@code MADE} or {@code UNKNOWN}
     * @param declaredDisposition what the removal reason says to do with a sold unit
     * @param effectiveDisposition what applies once the kitchen's record is taken into account: {@code
     *        WRITE_OFF} when the line is a made dish, otherwise the declared one
     * @param releasedUnits units that were still on a hold and are given back with no movement
     * @param returnedUnits units put back on the shelf
     * @param writtenOffUnits units lost: the kitchen's, plus any the reason writes off
     * @param noEffectUnits units of an item inventory does not count, or declared to have no effect
     */
    public record LineConsequence(
            UUID orderLineId,
            UUID variantId,
            String productName,
            BigDecimal quantityBefore,
            BigDecimal quantityAfter,
            int stockUnits,
            String kitchenState,
            String declaredDisposition,
            String effectiveDisposition,
            int releasedUnits,
            int returnedUnits,
            int writtenOffUnits,
            int noEffectUnits,
            UUID reasonId,
            String reasonCategory) {}

    /** One line carried out: what was told to the operator, the ledger rows it wrote, and the reason it cited. */
    public record Applied(
            LineConsequence consequence, List<UUID> movementIds, ReasonRow reason, String reasonSnapshot) {}

    /** Whether this brand has switched the removal path on ({@code ordering.amendment_line_removal_enabled}). */
    public boolean enabled(OrderRow order) {
        return Boolean.TRUE.equals(configuration.value(
                OrderingConfigurationKeys.AMENDMENT_LINE_REMOVAL_ENABLED,
                ResourceScope.brand(order.tenantId(), order.brandId())));
    }

    /**
     * The removal reason a line cites, resolved to its live row.
     *
     * @throws AmendmentRefusedException {@code REMOVAL_REASON_REQUIRED} when none was named, {@code
     *         REMOVAL_REASON_NOT_FOUND} when it is not a removal reason of this tenant, {@code
     *         REMOVAL_REASON_ARCHIVED} when the tenant has retired it
     */
    public ReasonRow requireReason(UUID tenantId, @Nullable UUID reasonId) {
        if (reasonId == null) {
            throw new AmendmentRefusedException(
                    "REMOVAL_REASON_REQUIRED",
                    "Taking a line off a confirmed order needs a removal reason, which decides what happens to its stock");
        }
        ReasonRow reason = reasons.find(tenantId, reasonId)
                .filter(found -> found.kind() == OutcomeReasonKind.LINE_REMOVAL)
                .orElseThrow(() -> new AmendmentRefusedException(
                        "REMOVAL_REASON_NOT_FOUND", "No removal reason " + reasonId + " exists for this tenant"));
        if (!"ACTIVE".equals(reason.status())) {
            throw new AmendmentRefusedException(
                    "REMOVAL_REASON_ARCHIVED", "The removal reason " + reasonId + " has been retired");
        }
        return reason;
    }

    /**
     * The reason of each line being taken off, keyed by that line's id.
     *
     * <p>A reason is named on a command's line, and a combo's component lines share the one purchase, so a
     * lowered combo takes the reason of whichever of its lines was named.
     *
     * @param named the reason id each command named, by the order line it named
     */
    public Map<UUID, ReasonRow> reasonsFor(
            UUID tenantId, List<AmendmentBasket.Unit> units, List<LineDecrease> decreases, Map<UUID, UUID> named) {
        Map<UUID, ReasonRow> resolved = new HashMap<>();
        Map<UUID, ReasonRow> byReasonId = new HashMap<>();
        for (LineDecrease decrease : decreases) {
            UUID reasonId = null;
            for (AmendmentBasket.Unit unit : units) {
                if (unit.lines().stream()
                        .anyMatch(line -> line.lineId().equals(decrease.line().lineId()))) {
                    for (OrderLineRow line : unit.lines()) {
                        UUID candidate = named.get(line.lineId());
                        if (candidate != null) {
                            reasonId = candidate;
                            break;
                        }
                    }
                    break;
                }
            }
            UUID key = reasonId;
            ReasonRow reason = key == null
                    ? requireReason(tenantId, null)
                    : byReasonId.computeIfAbsent(key, id -> requireReason(tenantId, id));
            resolved.put(decrease.line().lineId(), reason);
        }
        return resolved;
    }

    /**
     * What applying these decreases would do, line by line, without doing it.
     *
     * @param quoteIds the pricing quotes of the order's revisions: the owners of its reservations
     */
    public List<LineConsequence> preview(
            OrderRow order, List<LineDecrease> decreases, Map<UUID, ReasonRow> reasonByLine, List<UUID> quoteIds) {
        Map<UUID, OrderLineProductionPort.State> states = kitchenStates(order, decreases);
        List<LineConsequence> out = new ArrayList<>();
        for (LineDecrease decrease : orderedByLine(decreases)) {
            ReasonRow reason =
                    Objects.requireNonNull(reasonByLine.get(decrease.line().lineId()));
            OrderLineProductionPort.State state = stateOf(states, decrease);
            StockDisposal.LineResult result = null;
            if (decrease.stockUnits() > 0) {
                result = disposalOf(
                                order,
                                decrease,
                                reason,
                                state,
                                quoteIds,
                                "preview:" + decrease.line().lineId(),
                                null,
                                "USER",
                                null,
                                true)
                        .lines()
                        .get(0);
            }
            out.add(consequenceOf(decrease, reason, state, result));
        }
        return List.copyOf(out);
    }

    /**
     * Carries the decreases out: one {@code dispose} per line, under a key made of the amendment and the line, so
     * a retried apply gives back nothing twice, then the evidence row for each.
     *
     * <p>Runs inside the amendment's transaction. Any refusal rolls the revision back with it, so the order never
     * loses a line the ledger was not told about.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public List<Applied> carryOut(
            OrderRow order,
            AmendmentRow amendment,
            int newRevision,
            List<LineDecrease> decreases,
            Map<UUID, ReasonRow> reasonByLine,
            List<UUID> quoteIds,
            String actorType,
            @Nullable String actorId,
            @Nullable UUID approvalRequestId,
            Instant now) {
        Map<UUID, OrderLineProductionPort.State> states = kitchenStates(order, decreases);
        UUID actorUuid = uuidOrNull(actorId);
        List<Applied> applied = new ArrayList<>();
        for (LineDecrease decrease : orderedByLine(decreases)) {
            ReasonRow reason =
                    Objects.requireNonNull(reasonByLine.get(decrease.line().lineId()));
            OrderLineProductionPort.State state = stateOf(states, decrease);
            String key = "dispose:" + amendment.id() + ":" + decrease.line().lineId();
            StockDisposal.LineResult result = null;
            if (decrease.stockUnits() > 0) {
                result = disposalOf(order, decrease, reason, state, quoteIds, key, actorUuid, actorType, actorId, false)
                        .lines()
                        .get(0);
            }
            LineConsequence consequence = consequenceOf(decrease, reason, state, result);
            List<UUID> movements = result == null ? List.of() : result.movementIds();
            String snapshot = objectMapper.writeValueAsString(reasons.snapshotOf(reason));
            orders.insertLineRemoval(new JdbcOrderStore.NewLineRemoval(
                    UUID.randomUUID(),
                    order.tenantId(),
                    order.brandId(),
                    order.locationId(),
                    order.orderId(),
                    amendment.id(),
                    newRevision,
                    decrease.line().lineId(),
                    decrease.line().sourceVariantId(),
                    decrease.line().quantity(),
                    decrease.quantityAfter(),
                    decrease.stockUnits(),
                    consequence.releasedUnits(),
                    consequence.returnedUnits(),
                    consequence.writtenOffUnits(),
                    consequence.noEffectUnits(),
                    reason.id(),
                    reason.version(),
                    reason.systemCategory(),
                    snapshot,
                    consequence.declaredDisposition(),
                    consequence.effectiveDisposition(),
                    consequence.kitchenState(),
                    reason.liabilityParty(),
                    movements,
                    approvalRequestId,
                    actorType,
                    actorId,
                    now));
            Metrics.counter(
                            "horecaos.order.line_removals",
                            "reason_category",
                            reason.systemCategory(),
                            "outcome",
                            "APPLIED")
                    .increment();
            applied.add(new Applied(consequence, movements, reason, snapshot));
        }
        return List.copyOf(applied);
    }

    /**
     * The cash a courier or counter is told to collect, restated to the amended total (ADR 0169, decision 6).
     *
     * <p>A settlement that cannot be restated (its money is in, or in flight, or it carries a points tender
     * that would have to be resized) refuses the amendment, so the order and its tenders never say two totals.
     */
    public void restateCashDue(OrderRow order, long newTotalMinor, @Nullable String actorId) {
        if (!settlement.restateTotal(
                order.tenantId(), order.orderId(), newTotalMinor, actorId == null ? "ordering" : actorId)) {
            throw new AmendmentRefusedException(
                    "SETTLEMENT_NOT_RESTATABLE",
                    "The order's payment plan cannot be moved to its new total; cancel and take a new order instead");
        }
    }

    // ------------------------------------------------------------------------------------------ internals

    @SuppressWarnings("checkstyle:ParameterNumber")
    private StockDisposal disposalOf(
            OrderRow order,
            LineDecrease decrease,
            ReasonRow reason,
            OrderLineProductionPort.State state,
            List<UUID> quoteIds,
            String key,
            @Nullable UUID actorUuid,
            String actorType,
            @Nullable String actorId,
            boolean previewOnly) {
        int forced = state.madeOrBeingMade() ? decrease.stockUnits() : 0;
        StockDisposalCommand command = new StockDisposalCommand(
                order.tenantId(),
                order.brandId(),
                order.locationId(),
                order.orderId(),
                quoteIds,
                List.of(new StockDisposalCommand.Line(
                        decrease.line().sourceVariantId(), decrease.stockUnits(), forced)),
                StockDisposalCommand.Disposition.valueOf(reason.stockDisposition()),
                reason.systemCategory(),
                actorType,
                actorUuid,
                key);
        try {
            return previewOnly ? inventory.previewDispose(command) : inventory.dispose(command);
        } catch (DisposalRefusedException refused) {
            throw new AmendmentRefusedException(
                    refused.code(), Objects.requireNonNullElse(refused.getMessage(), refused.code()));
        }
    }

    private LineConsequence consequenceOf(
            LineDecrease decrease,
            ReasonRow reason,
            OrderLineProductionPort.State state,
            StockDisposal.@Nullable LineResult result) {
        String declared = reason.stockDisposition();
        boolean forced = state.madeOrBeingMade() && decrease.stockUnits() > 0;
        return new LineConsequence(
                decrease.line().lineId(),
                decrease.line().sourceVariantId(),
                decrease.line().productName(),
                decrease.line().quantity(),
                decrease.quantityAfter(),
                decrease.stockUnits(),
                state.name(),
                declared,
                forced ? "WRITE_OFF" : declared,
                result == null ? 0 : result.releasedUnits(),
                result == null ? 0 : result.returnedUnits(),
                result == null ? 0 : result.writtenOffUnits(),
                result == null ? 0 : result.noEffectUnits(),
                reason.id(),
                reason.systemCategory());
    }

    private Map<UUID, OrderLineProductionPort.State> kitchenStates(OrderRow order, List<LineDecrease> decreases) {
        List<UUID> lineIds =
                decreases.stream().map(decrease -> decrease.line().lineId()).toList();
        Map<UUID, OrderLineProductionPort.State> asked;
        try {
            asked = production.stateOfLines(order.tenantId(), order.orderId(), lineIds);
        } catch (RuntimeException unreadable) {
            // An answer that cannot be read is no answer: the reason decides, and the preview says UNKNOWN.
            asked = Map.of();
        }
        return new LinkedHashMap<>(asked);
    }

    private static OrderLineProductionPort.State stateOf(
            Map<UUID, OrderLineProductionPort.State> states, LineDecrease decrease) {
        return states.getOrDefault(decrease.line().lineId(), OrderLineProductionPort.State.UNKNOWN);
    }

    private static List<LineDecrease> orderedByLine(List<LineDecrease> decreases) {
        return decreases.stream()
                .sorted(Comparator.comparingInt(
                        (LineDecrease decrease) -> decrease.line().lineNumber()))
                .toList();
    }

    private static @Nullable UUID uuidOrNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
