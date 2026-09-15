package uz.horecaos.platform.fulfillment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort.DeliveryOrder;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingCommand;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingIntent;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingReceipt;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.QuoteOutcome;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService.DeliveryResourceNotFoundException;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryQuote;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliverySubsidyPolicy;
import uz.horecaos.platform.fulfillment.domain.sourcing.PlanStatus;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryQuoteStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore.DispatchBranch;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher;
import uz.horecaos.platform.telemetry.api.ScopeKey;
import uz.horecaos.platform.telemetry.api.StreamChannel;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * Provider quote-delta confirmation — the Millenium pattern (ADR 0014; gap
 * map row 1.2f).
 *
 * <p>Before this class there was no operator-invoked provider booking
 * anywhere: automated sourcing is the only caller of {@link
 * ShipmentBookingPort#book}, so there was no moment at which a re-quoted
 * price could be shown to a human before it committed. This class is that
 * moment, in two steps that are deliberately never one call:
 *
 * <ol>
 *   <li>{@link #quote} asks exactly one partner what the journey costs, right
 *       now, and records the answer as {@code fulfillment.delivery_quotes}
 *       evidence the same way automated sourcing does — {@link
 *       ShipmentBookingPort#quote} is side-effect-free by its own contract, so
 *       nothing is booked yet and nothing needs to be abandoned if the
 *       operator walks away.</li>
 *   <li>{@link #book} either commits to that exact quote ({@link
 *       Decision#ACCEPT}) or records that the operator saw it and refused
 *       ({@link Decision#ABANDON}). Acceptance re-reads the persisted quote
 *       by id rather than trust a price the client echoes back — the one
 *       property that makes "a price increase cannot be accepted implicitly"
 *       true on the server, not only in the dialog.</li>
 * </ol>
 *
 * <p>Booking reuses {@link SourcingJournal#openPartnerAttempt}/{@link
 * SourcingJournal#settlePartnerAttempt} — the exact primitives automated
 * sourcing uses to win the plan's single shipment — so a manual accept loses
 * the same race a scheduler tick would, and {@link #recordSubsidyIfAny}
 * mirrors {@link DeliverySourcingService#recordSubsidyIfAny} so a merchant
 * who accepts a price increase gets the same {@code DELIVERY_COST_SUBSIDY}
 * fact an automated booking would have written, not a silent gap in the
 * report the automated path is already counted in.
 */
@Service
public class ManualExternalBookingService {

    private static final Logger log = LoggerFactory.getLogger(ManualExternalBookingService.class);

    /** Mirrors {@link DeliverySourcingService}'s own TTL — see that class's doc for why two minutes. */
    private static final int QUOTE_TTL_SECONDS = 120;

    private final JdbcDeliveryPlanStore plans;
    private final JdbcDeliveryQuoteStore quotes;
    private final DeliveryOrderPort orders;
    private final JdbcDispatchBranchStore branches;
    private final ShipmentBookingPort bookings;
    private final SourcingJournal journal;
    private final PolicyResolver policies;
    private final AuditRecorder audit;
    private final RealtimeSignalPublisher realtime;
    private final Clock clock;

    public ManualExternalBookingService(
            JdbcDeliveryPlanStore plans,
            JdbcDeliveryQuoteStore quotes,
            DeliveryOrderPort orders,
            JdbcDispatchBranchStore branches,
            ShipmentBookingPort bookings,
            SourcingJournal journal,
            PolicyResolver policies,
            AuditRecorder audit,
            RealtimeSignalPublisher realtime,
            Clock clock) {
        this.plans = plans;
        this.quotes = quotes;
        this.orders = orders;
        this.branches = branches;
        this.bookings = bookings;
        this.journal = journal;
        this.policies = policies;
        this.audit = audit;
        this.realtime = realtime;
        this.clock = clock;
    }

    /** Every external partner this branch has configured — the picker behind "call an external courier". */
    public List<PartnerOption> partners(UUID tenantId, UUID brandId, UUID locationId) {
        return bookings.partners(tenantId, brandId, locationId);
    }

    /**
     * A non-binding price from one partner, against this plan's own customer
     * fee — the delta the dialog exists to show.
     */
    public QuoteResult quote(UUID tenantId, UUID brandId, UUID locationId, UUID planId, UUID bindingId) {
        DeliveryPlan plan = requirePlan(tenantId, planId, locationId);
        PartnerOption partner = partners(tenantId, brandId, locationId).stream()
                .filter(candidate -> candidate.bindingId().equals(bindingId))
                .findFirst()
                .orElseThrow(() -> new DeliveryResourceNotFoundException("No external partner " + bindingId + " here"));

        DeliveryOrder order = requireOrder(tenantId, plan.orderId());
        DispatchBranch branch = requireBranch(tenantId, brandId, locationId);

        UUID requestId = Ids.newId();
        Instant now = clock.instant();
        QuoteOutcome outcome = bookings.quote(new BookingCommand(
                requestId,
                tenantId,
                brandId,
                locationId,
                bindingId,
                BookingIntent.BOOK_NOW,
                order.orderReference(),
                branch.asWaypoint(),
                order.dropoff(),
                null,
                order.prepaid(),
                order.itemValueMinor(),
                order.currency(),
                plan.id().toString()));

        if (!outcome.hasPrice()) {
            return QuoteResult.unavailable(partner.providerType(), outcome.failureCode());
        }

        DeliveryQuote quote = new DeliveryQuote(
                Ids.newId(),
                bindingId,
                partner.providerType(),
                requestId,
                outcome.priceMinor(),
                outcome.currency(),
                outcome.pickupEtaSeconds(),
                outcome.deliveryEtaSeconds(),
                outcome.distanceMeters(),
                outcome.deadHeadMeters(),
                outcome.expiresAt() != null ? outcome.expiresAt() : now.plusSeconds(QUOTE_TTL_SECONDS),
                outcome.partnerSuppliedExpiry(),
                outcome.failureCode(),
                now);
        journal.recordQuotes(tenantId, planId, List.of(quote));

        return QuoteResult.priced(quote, plan.customerDeliveryFeeMinor());
    }

    /**
     * The operator's accept or abandon on a quote {@link #quote} already
     * recorded.
     *
     * <p>{@code quoteId} is what makes a price increase impossible to accept
     * implicitly: the price booked is whatever {@code fulfillment
     * .delivery_quotes} holds under that id, never a figure the request body
     * carries, so a client that sends a stale or edited price books the
     * quote's own number regardless.
     */
    public BookOutcome book(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID planId,
            UUID bindingId,
            UUID quoteId,
            Decision decision,
            String reasonCode,
            ActorRef actor) {

        DeliveryPlan plan = requirePlan(tenantId, planId, locationId);
        Instant now = clock.instant();
        DeliveryQuote quote = quotes.find(tenantId, planId, quoteId)
                .orElseThrow(() -> new DeliveryResourceNotFoundException("No quote " + quoteId + " on plan " + planId));
        if (!quote.bindingId().equals(bindingId)) {
            throw new IllegalArgumentException("Quote " + quoteId + " was not requested against binding " + bindingId);
        }

        if (decision == Decision.ABANDON) {
            recordAudit(tenantId, brandId, locationId, planId, actor, reasonCode, "external-book-abandon", quote, null);
            return BookOutcome.abandon();
        }

        if (!quote.usableAt(now)) {
            return BookOutcome.conflict("QUOTE_EXPIRED");
        }
        if (journal.assignedShipment(tenantId, planId).isPresent()) {
            return BookOutcome.conflict("ALREADY_ASSIGNED");
        }

        DeliveryOrder order = requireOrder(tenantId, plan.orderId());
        DispatchBranch branch = requireBranch(tenantId, brandId, locationId);

        UUID commandId = Ids.newId();
        SourcingJournal.OpenAttempt attempt = journal.openPartnerAttempt(new SourcingJournal.PartnerAttempt(
                tenantId, planId, bindingId, commandId.toString(), quote.id(), OPERATOR_REASON, null, 0, now));
        if (!attempt.needsCall()) {
            return BookOutcome.conflict("ALREADY_BEING_SOURCED");
        }

        BookingCommand command = new BookingCommand(
                commandId,
                tenantId,
                brandId,
                locationId,
                bindingId,
                BookingIntent.BOOK_NOW,
                order.orderReference(),
                branch.asWaypoint(),
                order.dropoff(),
                null,
                order.prepaid(),
                order.itemValueMinor(),
                order.currency(),
                plan.id().toString());

        BookingReceipt receipt = bookings.book(command);
        boolean won = journal.settlePartnerAttempt(tenantId, attempt.attemptId(), receipt, now);

        if (!won) {
            log.info("Plan {} manual external booking with {} finished as {}", planId, bindingId, receipt.status());
            return BookOutcome.conflict(receipt.status().name());
        }

        recordSubsidyIfAny(tenantId, brandId, locationId, plan, quote, now);
        boolean moved = plans.transition(tenantId, planId, plan.status(), PlanStatus.ASSIGNED, now);
        int newPlanVersion = moved ? plan.version() + 1 : plan.version();
        recordAudit(tenantId, brandId, locationId, planId, actor, reasonCode, "external-book-accept", quote, receipt);
        signal(tenantId, locationId, planId, newPlanVersion, now);

        return BookOutcome.applied(
                newPlanVersion, journal.assignedShipment(tenantId, planId).orElse(null));
    }

    // ---------------------------------------------------------------- helpers

    private DeliveryPlan requirePlan(UUID tenantId, UUID planId, UUID locationId) {
        DeliveryPlan plan = plans.find(tenantId, planId)
                .orElseThrow(() -> new DeliveryResourceNotFoundException("No delivery plan " + planId));
        if (!plan.locationId().equals(locationId)) {
            throw new DeliveryResourceNotFoundException("No delivery plan " + planId + " at this location");
        }
        return plan;
    }

    private DeliveryOrder requireOrder(UUID tenantId, UUID orderId) {
        return orders.deliveryOrder(tenantId, orderId)
                .orElseThrow(() ->
                        new DeliveryResourceNotFoundException("Order " + orderId + " cannot be read for dispatch"));
    }

    private DispatchBranch requireBranch(UUID tenantId, UUID brandId, UUID locationId) {
        return branches.find(tenantId, brandId, locationId)
                .orElseThrow(() -> new DeliveryResourceNotFoundException("No branch " + locationId));
    }

    /**
     * The same {@code DELIVERY_COST_SUBSIDY} recognition {@link
     * DeliverySourcingService#recordSubsidyIfAny} performs for an automated
     * booking, against the operator's own accepted quote rather than the
     * scored winner of a sourcing tick. See that method's doc for the
     * currency-mismatch and re-read-failure cases this mirrors exactly.
     */
    private void recordSubsidyIfAny(
            UUID tenantId, UUID brandId, UUID locationId, DeliveryPlan plan, DeliveryQuote quote, Instant now) {

        if (quote.priceMinor() == null || quote.currency() == null) {
            return;
        }
        if (!quote.currency().equals(plan.currency())) {
            log.warn(
                    "Plan {} manual quote currency {} does not match the customer fee currency {}; no "
                            + "DELIVERY_COST_SUBSIDY can be computed",
                    plan.id(),
                    quote.currency(),
                    plan.currency());
            return;
        }
        long gapMinor = quote.priceMinor() - plan.customerDeliveryFeeMinor();
        if (gapMinor <= 0) {
            return;
        }
        Optional<UUID> shipmentId = journal.assignedShipment(tenantId, plan.id());
        if (shipmentId.isEmpty()) {
            log.warn(
                    "Plan {} won its shipment but it could not be re-read; DELIVERY_COST_SUBSIDY not recorded",
                    plan.id());
            return;
        }

        ResolvedPolicy<DeliverySubsidyPolicy> subsidyPolicy = resolveSubsidyPolicy(tenantId, brandId, locationId);
        journal.recordCostSubsidy(new SourcingJournal.CostSubsidy(
                tenantId,
                brandId,
                locationId,
                plan.id(),
                shipmentId.get(),
                quote.bindingId(),
                quote.providerType(),
                plan.customerDeliveryFeeMinor(),
                quote.priceMinor(),
                gapMinor,
                plan.currency(),
                subsidyPolicy.document().bearer(),
                subsidyPolicy.policyId(),
                subsidyPolicy.policyVersion(),
                now));
    }

    private ResolvedPolicy<DeliverySubsidyPolicy> resolveSubsidyPolicy(UUID tenantId, UUID brandId, UUID locationId) {
        ResourceScope scope = ResourceScope.location(tenantId, brandId, locationId);
        return policies.resolve(DeliverySourcingPolicies.SUBSIDY, scope)
                .orElseGet(() -> new ResolvedPolicy<>(
                        DeliverySourcingPolicies.SUBSIDY.code(),
                        DeliverySourcingService.DEFAULTS_ID,
                        1,
                        scope.type(),
                        "defaults",
                        DeliverySubsidyPolicy.DEFAULTS));
    }

    private void recordAudit(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID planId,
            ActorRef actor,
            String reasonCode,
            String actionSuffix,
            DeliveryQuote quote,
            @Nullable BookingReceipt receipt) {

        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("quoteId", quote.id());
        changed.put("providerType", quote.providerType());
        if (quote.priceMinor() != null) {
            changed.put("priceMinor", quote.priceMinor());
            changed.put("currency", quote.currency());
        }
        if (receipt != null) {
            changed.put("bookingStatus", receipt.status().name());
        }
        audit.record(AuditFact.of("fulfillment.dispatch." + actionSuffix, AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("fulfillment.delivery_plan", planId)
                .because(reasonCode)
                .changed(changed)
                .correlatedBy(planId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    private void signal(UUID tenantId, UUID locationId, UUID planId, int version, Instant now) {
        realtime.publish(RealtimeSignal.of(
                tenantId,
                StreamChannel.DISPATCH_BOARD,
                ScopeKey.location(locationId),
                "DeliveryPlan",
                planId,
                (long) version,
                now));
    }

    /** The decision reason a manual attempt's journal row carries — never a partner name, matching every other reason code here. */
    private static final String OPERATOR_REASON = "OPERATOR_EXTERNAL_BOOKING";

    // --------------------------------------------------------------- results

    public enum Decision {
        ACCEPT,
        ABANDON
    }

    /**
     * @param priced       false when the partner had nothing to say — an
     *                     out-of-zone address, an outage — in which case
     *                     {@code quote} is null and {@code failureCode} says why
     * @param customerFeeMinor the figure the delta is measured against; 0 and
     *                     meaningless when {@code !priced}
     */
    public record QuoteResult(
            boolean priced,
            @Nullable DeliveryQuote quote,
            long customerFeeMinor,
            @Nullable String providerType,
            @Nullable String failureCode) {

        static QuoteResult priced(DeliveryQuote quote, long customerFeeMinor) {
            return new QuoteResult(true, quote, customerFeeMinor, quote.providerType(), null);
        }

        static QuoteResult unavailable(@Nullable String providerType, @Nullable String failureCode) {
            return new QuoteResult(false, null, 0, providerType, failureCode);
        }
    }

    /**
     * @param applied    true for an accept that won the plan's shipment, and
     *                   for every abandon — an abandon always "applies", it
     *                   simply has nothing to assign
     * @param abandoned  true when this call recorded a refusal rather than a booking
     * @param reason     present only on a refused accept: {@code
     *                   QUOTE_EXPIRED}, {@code ALREADY_ASSIGNED}, {@code
     *                   ALREADY_BEING_SOURCED}, or a {@code BookingStatus} name
     */
    public record BookOutcome(
            boolean applied,
            boolean abandoned,
            @Nullable Integer planVersion,
            @Nullable UUID shipmentId,
            @Nullable String reason) {

        static BookOutcome applied(int planVersion, @Nullable UUID shipmentId) {
            return new BookOutcome(true, false, planVersion, shipmentId, null);
        }

        static BookOutcome abandon() {
            return new BookOutcome(true, true, null, null, null);
        }

        static BookOutcome conflict(String reason) {
            return new BookOutcome(false, false, null, null, reason);
        }
    }
}
