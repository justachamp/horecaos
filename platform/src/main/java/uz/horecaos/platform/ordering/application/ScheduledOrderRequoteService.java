package uz.horecaos.platform.ordering.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.fulfillment.api.PricingAuthority;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderRequoteStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderRequoteStore.Finding;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderRequoteStore.PromotionChange;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderModifierRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.RevisionRow;
import uz.horecaos.platform.pricing.api.CartPricingPort;
import uz.horecaos.platform.pricing.api.QuoteAcceptancePort;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A scheduled order's promotions, judged again at its checkpoint (ADR 0140, ADR 0019, row 6.1).
 *
 * <p>A pre-order is priced the moment it is taken and then waits for its kitchen. ADR 0140 says a
 * scheduled order is re-quoted at the ADR 0019 checkpoint, because a window condition read the
 * clock of the day the order was taken and the day it is made can be on the other side of it.
 * This prices the order's live basket again at the checkpoint instant, under everything else the
 * order was bought with (its channel, payment method and order position are inherited from the
 * quote it holds, exactly as an amendment inherits them), and records how the promotions and the
 * total differ.
 *
 * <p><b>It records, and does not reprice.</b> ADR 0019 is explicit that a long wait "may reprice"
 * but that "any customer-visible price/substitution change requires explicit acceptance or safe
 * cancellation", and ADR 0072 that the difference is never silently charged. So nothing here
 * writes to the order: its totals, lines, ledger rows and redemptions stay as the customer was
 * quoted, and the finding says what a re-pricing would do so a person can ask the customer or
 * cancel. The acceptance flow itself, and the checkpoint instants ADR 0019 leaves open, are not
 * decided by this class; the sweep takes its lead time from a property, and an operator can ask
 * for a re-check at any time.
 *
 * <p>The re-quote is a pricing quote like any other, and expires unused. It claims no promotion
 * slot, takes no coupon redemption and reserves no stock.
 */
@Service
public class ScheduledOrderRequoteService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledOrderRequoteService.class);

    /** The audit actor of the sweep. */
    static final ActorRef SWEEPER = ActorRef.systemJob("ordering.scheduled-order-requote");

    /** Newest findings an order's history returns. */
    public static final int HISTORY_LIMIT = 20;

    /** The metric name: one count per finding, labelled by its bounded outcome only. */
    public static final String REQUOTES = "horecaos.promo.requotes";

    /** Who asked for the re-quote. */
    public enum Trigger {
        /** The sweep, as the order's promised time approaches. */
        CHECKPOINT,
        /** A person with the order in front of them. */
        OPERATOR
    }

    private final JdbcOrderStore orders;
    private final JdbcOrderRequoteStore requotes;
    private final CartPricingPort pricing;
    private final QuoteAcceptancePort quotes;
    private final OrderDeliveryPoint deliveryPoint;
    private final AuditRecorder audit;
    private final Clock clock;
    private final MeterRegistry metrics;

    private final TransactionTemplate independently;

    @Autowired
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ScheduledOrderRequoteService(
            JdbcOrderStore orders,
            JdbcOrderRequoteStore requotes,
            CartPricingPort pricing,
            QuoteAcceptancePort quotes,
            OrderDeliveryPoint deliveryPoint,
            AuditRecorder audit,
            Clock clock,
            MeterRegistry metrics,
            TransactionTemplate unitOfWork) {
        this.orders = orders;
        this.requotes = requotes;
        this.pricing = pricing;
        this.quotes = quotes;
        this.deliveryPoint = deliveryPoint;
        this.audit = audit;
        this.clock = clock;
        this.metrics = metrics;
        // Pricing runs in a transaction of its own. A refusal leaves the pricing transaction by an
        // exception, which marks whatever transaction it joined rollback-only even when the caller catches
        // it; a refusal is a finding here, and the finding has to commit.
        this.independently = new TransactionTemplate(Objects.requireNonNull(
                unitOfWork.getTransactionManager(), "unitOfWork must already carry a transaction manager"));
        this.independently.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ reads

    /** The order's findings, newest first, bounded. Refuses an order that is not in this location. */
    @Transactional(readOnly = true)
    public List<Finding> findings(UUID tenantId, UUID brandId, UUID locationId, UUID orderId) {
        requireOrderAt(tenantId, brandId, locationId, orderId);
        return requotes.list(tenantId, orderId, HISTORY_LIMIT);
    }

    // ----------------------------------------------------------------- writes

    /**
     * A re-check somebody asked for: the order's promotions judged at this moment.
     *
     * @throws ApiException {@code RESOURCE_CONFLICT} when the order is not a scheduled order that is
     *     still waiting for its kitchen
     */
    @Transactional
    public Finding requoteNow(UUID tenantId, UUID brandId, UUID locationId, UUID orderId, ActorRef actor) {
        OrderRow order = requireOrderAt(tenantId, brandId, locationId, orderId);
        if (!requotes.isAwaitingItsCheckpoint(tenantId, orderId)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Only a scheduled order that its kitchen has not started can have its promotions re-checked",
                    Map.of("reason", "NOT_AWAITING_CHECKPOINT"));
        }
        Finding finding = judge(order, Trigger.OPERATOR, clock.instant());
        requotes.insert(finding);
        recordAudit(order, finding, actor);
        count(finding);
        return finding;
    }

    /**
     * The checkpoint re-quote of one order: judged at {@code at}, once per order.
     *
     * <p>A pricing refusal (a dish no longer priced, a menu unpublished) is a finding, not a failure:
     * "this order cannot be priced as it stands" is the most useful thing the checkpoint can say
     * about it. Anything else that goes wrong is left to the caller, which records a finding of its
     * own so the order is not retried for ever.
     *
     * @return the finding written, or empty when the order is gone, is no longer waiting for its
     *     kitchen, or already has its checkpoint finding (a second node got there first)
     */
    @Transactional
    public Optional<Finding> requoteAtCheckpoint(UUID tenantId, UUID orderId, Instant at) {
        OrderRow order = orders.find(tenantId, orderId).orElse(null);
        if (order == null || !requotes.isAwaitingItsCheckpoint(tenantId, orderId)) {
            return Optional.empty();
        }
        Finding finding = judge(order, Trigger.CHECKPOINT, at);
        if (!requotes.insert(finding)) {
            return Optional.empty();
        }
        recordAudit(order, finding, SWEEPER);
        count(finding);
        return Optional.of(finding);
    }

    /**
     * The finding for an order the checkpoint could not even attempt to price: written in its own
     * transaction by the sweep after an unexpected failure, so the next sweep does not pick the same
     * order up again for ever.
     */
    @Transactional
    public void recordFailure(UUID tenantId, UUID orderId, Instant at) {
        OrderRow order = orders.find(tenantId, orderId).orElse(null);
        if (order == null) {
            return;
        }
        Finding finding = refused(order, Trigger.CHECKPOINT, at, currentQuoteId(order), "REQUOTE_FAILED");
        if (requotes.insert(finding)) {
            recordAudit(order, finding, SWEEPER);
            count(finding);
        }
    }

    // ------------------------------------------------------------------ judge

    private Finding judge(OrderRow order, Trigger trigger, Instant at) {
        UUID heldQuoteId = currentQuoteId(order);
        QuoteSnapshot held = quotes.quoteSnapshot(order.tenantId(), heldQuoteId)
                .orElseThrow(() -> new IllegalStateException("The quote behind an order's revision is gone"));

        QuoteSnapshot requote;
        try {
            requote = Objects.requireNonNull(
                    independently.execute(status -> price(order, heldQuoteId, trigger, at)),
                    "A transaction template returns what its callback returned");
        } catch (CartPricingPort.PricingRefusedException refused) {
            return refused(order, trigger, at, heldQuoteId, refused.code());
        }

        List<PromotionChange> changes = changesBetween(held, requote);
        boolean unchanged = changes.isEmpty() && requote.totalMinor() == order.totalMinor();
        return new Finding(
                Ids.newId(),
                order.tenantId(),
                order.orderId(),
                trigger.name(),
                at,
                heldQuoteId,
                requote.quoteId(),
                unchanged ? "UNCHANGED" : "CHANGED",
                null,
                order.currency(),
                order.totalMinor(),
                order.discountMinor(),
                requote.totalMinor(),
                requote.discountMinor(),
                changes,
                clock.instant());
    }

    private Finding refused(OrderRow order, Trigger trigger, Instant at, UUID heldQuoteId, String code) {
        return new Finding(
                Ids.newId(),
                order.tenantId(),
                order.orderId(),
                trigger.name(),
                at,
                heldQuoteId,
                null,
                "NOT_PRICEABLE",
                code,
                order.currency(),
                order.totalMinor(),
                order.discountMinor(),
                null,
                null,
                List.of(),
                clock.instant());
    }

    /**
     * The whole live basket priced again through the cart's own entry point, with the service
     * instant fixed at the checkpoint and every other promotion input inherited from the quote the
     * order holds. The order's own redemption rides along, so a promo code earned at checkout is
     * not priced away, as in an amendment.
     */
    private QuoteSnapshot price(OrderRow order, UUID heldQuoteId, Trigger trigger, Instant at) {
        List<JdbcOrderStore.OrderLineRow> live = orders.lines(order.tenantId(), order.orderId());
        Map<UUID, List<OrderModifierRow>> modifiersByLine =
                orders.lineModifiers(order.tenantId(), order.orderId()).stream()
                        .collect(Collectors.groupingBy(OrderModifierRow::orderLineId));
        List<CartPricingPort.PricingCommand.Item> items =
                AmendmentBasket.pricingItems(AmendmentBasket.units(live), Map.of(), modifiersByLine);

        // The one input that differs from what the order was bought under: the instant. Window
        // conditions read it in the location's timezone; the rest is the recorded evidence.
        var frame = new CartPricingPort.PricingCommand.PromotionFrame(
                at, null, order.fulfillmentMode().name(), heldQuoteId, order.createdAt());

        GeoPoint point = deliveryPoint.of(order, "SCHEDULED_REQUOTE");
        CartPricingPort.PricingCommand.Delivery delivery =
                point == null ? null : new CartPricingPort.PricingCommand.Delivery(point, PricingAuthority.HORECAOS);
        return pricing.priceCart(new CartPricingPort.PricingCommand(
                order.tenantId(),
                order.brandId(),
                order.locationId(),
                order.customerAccountId(),
                order.channelCode(),
                items,
                // Keyed on the order, who asked and the instant, so a retried sweep prices once.
                "requote:%s:%s:%d"
                        .formatted(order.orderId(), trigger.name().toLowerCase(Locale.ROOT), at.toEpochMilli()),
                null,
                delivery,
                order.orderId(),
                order.fulfillmentMode(),
                frame));
    }

    /**
     * Which promotions give a different amount in the re-quote. A promotion's effect is the sum of
     * its signed adjustments (a discount is negative, a markup positive), so a free-delivery
     * promotion and an item discount are each one figure, compared by promotion id.
     */
    static List<PromotionChange> changesBetween(QuoteSnapshot held, QuoteSnapshot requote) {
        Map<UUID, Long> before = promotionEffects(held);
        Map<UUID, Long> after = promotionEffects(requote);
        List<PromotionChange> changes = new ArrayList<>();
        for (UUID id : new TreeSet<>(union(before, after))) {
            long heldMinor = before.getOrDefault(id, 0L);
            long requoteMinor = after.getOrDefault(id, 0L);
            if (heldMinor == requoteMinor) {
                continue;
            }
            String change = !after.containsKey(id) ? "DROPPED" : !before.containsKey(id) ? "GAINED" : "CHANGED";
            changes.add(new PromotionChange(id, change, heldMinor, requoteMinor));
        }
        return changes;
    }

    private static Set<UUID> union(Map<UUID, Long> a, Map<UUID, Long> b) {
        Set<UUID> ids = new HashSet<>(a.keySet());
        ids.addAll(b.keySet());
        return ids;
    }

    private static Map<UUID, Long> promotionEffects(QuoteSnapshot quote) {
        Map<UUID, Long> effects = new HashMap<>();
        for (QuoteSnapshot.Adjustment adjustment : quote.adjustments()) {
            if ("PROMOTION".equals(adjustment.sourceType())) {
                effects.merge(adjustment.sourceId(), adjustment.amountMinor(), Long::sum);
            }
        }
        return effects;
    }

    // ---------------------------------------------------------------- helpers

    private UUID currentQuoteId(OrderRow order) {
        return orders.revisions(order.tenantId(), order.orderId()).stream()
                .filter(revision -> revision.revision() == order.currentRevision())
                .map(RevisionRow::pricingQuoteId)
                .findFirst()
                .orElse(order.pricingQuoteId());
    }

    private OrderRow requireOrderAt(UUID tenantId, UUID brandId, UUID locationId, UUID orderId) {
        OrderRow order = orders.find(tenantId, orderId).orElse(null);
        if (order == null
                || !order.brandId().equals(brandId)
                || !order.locationId().equals(locationId)) {
            // The same answer for an order that is not there and one that is somewhere else.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order");
        }
        return order;
    }

    private void recordAudit(OrderRow order, Finding finding, ActorRef actor) {
        // Ids and signed amounts only. No customer, address or contact is in the order's re-quote.
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("trigger", finding.trigger());
        document.put("outcome", finding.outcome());
        document.put("heldTotalMinor", finding.heldTotalMinor());
        if (finding.requoteTotalMinor() != null) {
            document.put("requoteTotalMinor", finding.requoteTotalMinor());
        }
        if (finding.refusalCode() != null) {
            document.put("refusalCode", finding.refusalCode());
        }
        document.put("changedPromotions", finding.promotionChanges().size());
        audit.record(AuditFact.of("ordering.order.promotion_requoted", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.location(order.tenantId(), order.brandId(), order.locationId()))
                .target("ordering.order", order.orderId())
                .targetVersion((long) order.version())
                .because(
                        finding.trigger().equals(Trigger.CHECKPOINT.name())
                                ? "Scheduled order re-quoted at its checkpoint"
                                : "Scheduled order's promotions re-checked by an operator")
                .changed(ChangeDocuments.created(document))
                .correlatedBy(order.orderId().toString())
                .occurredAt(finding.createdAt())
                .build());
    }

    private void count(Finding finding) {
        metrics.counter(REQUOTES, "outcome", finding.outcome().toLowerCase(Locale.ROOT))
                .increment();
        if (log.isDebugEnabled()) {
            log.debug(
                    "Order {} re-quoted at {}: {}",
                    finding.orderId(),
                    finding.checkpointAt(),
                    Objects.requireNonNullElse(finding.refusalCode(), finding.outcome()));
        }
    }

    /** For tests: the registry the outcome counter lives in. */
    public MeterRegistry metrics() {
        return metrics;
    }
}
