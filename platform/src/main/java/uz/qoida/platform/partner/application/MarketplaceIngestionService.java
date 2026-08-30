package uz.qoida.platform.partner.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import uz.qoida.platform.iam.api.protection.DataClass;
import uz.qoida.platform.iam.api.protection.FieldProtection;
import uz.qoida.platform.ordering.api.OrderSettlementPort;
import uz.qoida.platform.partner.api.PartnerPrincipal;
import uz.qoida.platform.partner.application.port.MarketplaceOrderIntake;
import uz.qoida.platform.partner.domain.DiscountFunding;
import uz.qoida.platform.partner.domain.ExternalReference;
import uz.qoida.platform.partner.domain.ExternalReferenceType;
import uz.qoida.platform.partner.domain.ExternalTotals;
import uz.qoida.platform.partner.domain.HandoverChallengeType;
import uz.qoida.platform.partner.domain.HandoverCodeHasher;
import uz.qoida.platform.partner.domain.RejectionCode;
import uz.qoida.platform.partner.infrastructure.persistence.JdbcPartnerStore;

/**
 * The ingestion transaction (ADR 0040).
 *
 * <p>A partner push lands in {@code partner.inbound_orders} and, in the same
 * transaction, either creates an order or records a rejection. No external call
 * of any kind happens inside it, per ADR 0019: an HTTP call to a partner from
 * inside the transaction that creates the order is how one slow aggregator holds
 * a row lock across a dinner service.
 *
 * <p><strong>Idempotency is the point of the shape.</strong> ADR 0031 requires a
 * client-supplied {@code Idempotency-Key} on every effectful mutation; partners
 * will not send one, so here the key is derived from
 * {@code (binding, external order id)}. That is a documented exception and the
 * stronger key: Qoida does not control the partner's retry client, while the
 * partner's own order identifier is stable by construction. The enforcement is a
 * unique constraint rather than a lookup — a "does it exist yet" check lets two
 * concurrent pushes both pass, and the cost of that is a restaurant cooking the
 * same order twice and a courier arriving for one of them.
 *
 * <p>The duplicate path is therefore a lost race rather than an error: the
 * transaction rolls back, the winner's order is read back, and the partner is
 * told about the order it already has. This is the same discipline the payment
 * adapters use for an uncertain outcome — the second attempt never assumes the
 * first did nothing.
 */
@Service
public class MarketplaceIngestionService {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceIngestionService.class);

    /**
     * The default silence a marketplace binding may have before it is called
     * stale. Twelve hours, so a branch that takes two orders a day is not alerted
     * every lunchtime; ADR 0030 resolves a per-binding value and this is only the
     * fallback for a binding nobody has tuned.
     */
    private static final int DEFAULT_STALE_AFTER_SECONDS = 12 * 60 * 60;

    /**
     * The tender of an order that arrived already paid (ADR 0046).
     *
     * <p>The aggregator took the customer's money before Qoida heard of the order,
     * so there is exactly one tender and it is neither cash nor a Qoida provider.
     * Naming it is what gives an aggregator order a settlement — and therefore a
     * refund, a remedy and a reportable figure.
     */
    private static final String MARKETPLACE_TENDER = "MARKETPLACE";

    private final JdbcPartnerStore store;
    private final MarketplaceOrderIntake intake;
    private final OrderSettlementPort settlements;
    private final FieldProtection protection;
    private final HandoverCodeHasher hasher;
    private final TransactionOperations transactions;
    private final Clock clock;

    public MarketplaceIngestionService(
            JdbcPartnerStore store,
            MarketplaceOrderIntake intake,
            OrderSettlementPort settlements,
            FieldProtection protection,
            HandoverCodeHasher hasher,
            TransactionOperations transactions,
            Clock clock) {
        this.store = store;
        this.intake = intake;
        this.settlements = settlements;
        this.protection = protection;
        this.hasher = hasher;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Outcome receive(PartnerPrincipal principal, PartnerOrderPush push) {
        Instant now = clock.instant();

        // Resolved before the transaction opens, and refused before anything is
        // written: a push naming a venue this credential does not hold is the
        // enumeration attempt the partner surface exists to refuse, and it must
        // not leave a staging row against a binding the caller does not own.
        Optional<JdbcPartnerStore.Venue> venue =
                store.findVenue(principal.tenantId(), push.venueReference(), now);
        if (venue.isEmpty()) {
            return Outcome.rejected(RejectionCode.UNKNOWN_VENUE, null);
        }
        JdbcPartnerStore.Venue resolved = venue.get();
        if (!principal.covers(resolved.bindingId())) {
            return Outcome.rejected(RejectionCode.VENUE_NOT_PERMITTED, null);
        }

        JdbcPartnerStore.InboundPush staged = stage(principal, resolved, push, now);

        try {
            return transactions.execute(status -> ingest(principal, resolved, push, staged, now));
        } catch (DuplicateKeyException duplicate) {
            // The retry and failed-sync recovery path, and the exact moment a
            // second order would otherwise be created. The transaction that lost
            // has rolled back entirely, so the settled outcome is now readable.
            //
            // A retried acceptance answers with the order the first push created.
            // A retried rejection answers with the same rejection, for ever: the
            // outcome of one partner order id is decided once, because letting a
            // second push be re-evaluated would let a partner restate a total
            // Qoida had already refused and have the restatement win silently.
            JdbcPartnerStore.StagedOutcome settled = store.findStagedOutcome(
                            principal.tenantId(), resolved.bindingId(), push.externalOrderId())
                    .orElseThrow(() -> duplicate);

            return settled.accepted()
                    ? Outcome.duplicate(settled.orderId())
                    : Outcome.rejected(RejectionCode.valueOf(settled.rejectionCode()), null);
        }
    }

    private Outcome ingest(PartnerPrincipal principal, JdbcPartnerStore.Venue venue,
            PartnerOrderPush push, JdbcPartnerStore.InboundPush staged, Instant now) {

        RejectionCode rejection = validate(principal, venue, push, now);
        if (rejection != null) {
            store.recordRejected(staged, rejection);
            // The failure is recorded against the channel too. A partner sending
            // malformed orders and a partner sending nothing look identical from
            // the order list, and only one of them is the integration's fault.
            store.recordFailure(principal.tenantId(), venue.bindingId(), venue.locationId(),
                    "INBOUND", rejection.name(), DEFAULT_STALE_AFTER_SECONDS, now);
            return Outcome.rejected(rejection, null);
        }

        List<String> externalItemIds = push.lines().stream()
                .map(PushLine::externalItemReference)
                .toList();
        Map<String, UUID> variants =
                store.resolveMenuItems(principal.tenantId(), venue.bindingId(), externalItemIds);

        List<MarketplaceOrderIntake.IntakeLine> lines = new ArrayList<>(push.lines().size());
        for (PushLine line : push.lines()) {
            UUID variantId = variants.get(line.externalItemReference());
            lines.add(new MarketplaceOrderIntake.IntakeLine(
                    null,
                    variantId,
                    line.externalItemReference(),
                    line.name(),
                    line.quantity(),
                    line.unitAmountMinor(),
                    line.lineAmountMinor(),
                    line.taxAmountMinor() == null ? 0L : line.taxAmountMinor()));
        }

        List<ExternalReference> references = new ArrayList<>(2);
        references.add(ExternalReference.partner(
                ExternalReferenceType.PARTNER_ORDER_ID, push.externalOrderId()));
        if (push.displayCode() != null && !push.displayCode().isBlank()) {
            references.add(ExternalReference.partner(
                    ExternalReferenceType.PARTNER_DISPLAY_CODE, push.displayCode()));
        }

        HandoverChallengeType challengeType = push.handoverCode() == null || push.handoverCode().isBlank()
                ? HandoverChallengeType.NONE
                : HandoverChallengeType.CODE;

        // The order id is chosen here rather than by the adapter, because the
        // handover hash is bound to it: a hash computed after the row exists
        // could be lifted from one order and replayed against another.
        UUID orderId = UUID.randomUUID();

        MarketplaceOrderIntake.Created created = intake.create(
                new MarketplaceOrderIntake.NewMarketplaceOrder(
                        orderId,
                        principal.tenantId(),
                        venue.brandId(),
                        venue.locationId(),
                        venue.channelId(),
                        venue.channelCode(),
                        venue.bindingId(),
                        push.externalOrderId(),
                        push.fulfillmentMode(),
                        guestReferenceHash(venue.bindingId(), push.externalOrderId()),
                        derivedIdempotencyKey(venue.bindingId(), push.externalOrderId()),
                        push.totals(),
                        push.discountFunding() == null ? DiscountFunding.UNKNOWN : push.discountFunding(),
                        push.rawTotalsJson(),
                        lines,
                        references,
                        challengeType,
                        challengeType == HandoverChallengeType.NONE
                                ? null
                                : hasher.hash(orderId, push.handoverCode()),
                        "PARTNER",
                        now));

        planSettlement(principal, venue, push, created.orderId());

        store.recordAccepted(staged, created.orderId());
        store.recordSuccess(principal.tenantId(), venue.bindingId(), venue.locationId(),
                "INBOUND", push.externalOrderId(), DEFAULT_STALE_AFTER_SECONDS, now);

        List<String> unmapped = push.lines().stream()
                .map(PushLine::externalItemReference)
                .filter(reference -> !variants.containsKey(reference))
                .toList();

        return Outcome.accepted(created.orderId(), created.publicOrderNumber(), unmapped);
    }

    /**
     * The settlement of an aggregator order (ADR 0046), in the same transaction as
     * the order itself.
     *
     * <p>Without it an aggregator order exists with no settlement and therefore no
     * tenders, and every remedy an operator records against it — a refund, a
     * delivery-fee reimbursement, the courier's cash figure — answers "the order has
     * no settlement". A partner order is a first-class order and this is part of
     * what that costs.
     *
     * <p>One tender, and never a balance tender: a marketplace order has no customer
     * account behind it by construction, so there is no balance to draw on and
     * nothing to hold. The tender is {@code MARKETPLACE}, for exactly what the
     * customer handed the aggregator, which is also the ceiling on what can ever be
     * refunded.
     *
     * <p><strong>An order the customer paid nothing for gets no settlement, and that
     * is the answer rather than a gap.</strong> This tendered such an order under a
     * planner-owned {@code MARKETPLACE_PROMOTION} method for the value of the
     * discount, on the argument that {@code ck_order_settlement_total} forbids a
     * settlement of zero and that the promotion's value was "the most a goodwill
     * remedy could be worth". Both halves of that are true and the conclusion was
     * still wrong, because the figure it produced was not read as a goodwill
     * ceiling. A promotion tender is not {@code settles_from_balance}, so
     * {@link uz.qoida.platform.payments.settlement.OrderSettlementService#refund}
     * counted the whole of it as money, and {@code OrderRemedyService.recordRefund}
     * would have recorded a <em>cash refund</em> of up to fifty thousand som to a
     * customer who paid nothing — attested, unverifiable, and indistinguishable in
     * every report from a real card reversal. It also put
     * {@code settlement.total_due_minor} permanently at odds with
     * {@code ordering.orders.total_minor}, which is zero for the same order.
     *
     * <p>So the arithmetic is left alone. A settlement is the record of money owed
     * and money moved; an order the customer paid nothing for has neither, and the
     * honest number of tenders for it is none. What that costs is a money remedy —
     * correctly, because there is no money to give back — and what it does not cost
     * is a goodwill remedy: {@code OrderRemedyService.grantFutureDiscount} makes no
     * settlement call at all and never did, so an entitlement is grantable on this
     * order exactly as it is on any other. (Its own rule that a guest order has
     * nobody to grant to still applies, and an aggregator order is a guest order;
     * that is a separate decision about identity, not about settlement.)
     *
     * <p>Who funded the discount is <em>not</em> restated on the tender.
     * {@link DiscountFunding} is recorded once, on
     * {@code ordering.order_external_pricing}, where ADR 0040 put it and where
     * {@code UNKNOWN} — the honest and frequent answer — reads as what it is. A
     * second copy on a tender would be a second opinion about the same fact, and
     * would make the settlement of a legitimate order depend on a field most partner
     * protocols never send.
     *
     * <p>The returned id is required rather than discarded. A planner that planned
     * nothing used to be indistinguishable from one that planned, at both call
     * sites; here it is the difference between an order an operator can remedy and
     * one they cannot, so the push fails and the whole ingestion transaction rolls
     * back. The partner retries an order Qoida never acknowledged, which is the
     * outcome this class is built around.
     */
    private void planSettlement(PartnerPrincipal principal, JdbcPartnerStore.Venue venue,
            PartnerOrderPush push, UUID orderId) {

        ExternalTotals totals = push.totals();
        if (totals.customerPaidTotalMinor() == 0) {
            // Ids and figures only (ADR 0029): nothing here names the customer.
            log.info("Order {} was pushed fully discounted, so no money is owed on it and it is "
                    + "planned no settlement. A discount of {} {} was applied by the partner; it "
                    + "is recorded on ordering.order_external_pricing and is not a tender.",
                    orderId, totals.discountMinor(), totals.currency());
            return;
        }

        settlements.planSettlement(new OrderSettlementPort.SettlementRequest(
                        principal.tenantId(), venue.brandId(), orderId, null,
                        totals.currency(), totals.customerPaidTotalMinor(), MARKETPLACE_TENDER, 0L,
                        derivedIdempotencyKey(venue.bindingId(), push.externalOrderId()),
                        "marketplace"))
                .orElseThrow(() -> new IllegalStateException(
                        "Order " + orderId + " was accepted with no settlement: the tender "
                                + MARKETPLACE_TENDER + " is not one this build can plan. An "
                                + "aggregator order the customer paid for and that has no "
                                + "settlement cannot be refunded or remedied, so it is not an "
                                + "order this platform will acknowledge."));
    }

    private RejectionCode validate(PartnerPrincipal principal, JdbcPartnerStore.Venue venue,
            PartnerOrderPush push, Instant now) {

        if (push.lines().isEmpty()) {
            // An order with no lines reconciles arithmetically at a total of
            // zero, reaches the kitchen, and produces a ticket with nothing on it.
            return RejectionCode.EMPTY_ORDER;
        }
        if (!push.totals().currency().equals(venue.tenantDefaultCurrency())) {
            return RejectionCode.CURRENCY_MISMATCH;
        }
        if (!push.totals().reconciles()) {
            return RejectionCode.EXTERNAL_TOTAL_MISMATCH;
        }
        if (push.totals().customerPaidTotalMinor() == 0 && push.totals().discountMinor() == 0) {
            // Every line priced at nothing, discounted from nothing. Distinct from a
            // fully discounted order, which is accepted: there the promotion is the
            // consideration, it is recorded on ordering.order_external_pricing, and
            // the branch has something to cook. Here there is no figure above zero
            // anywhere in the push — no money, no discount, no lines worth
            // anything — which is a malformed push rather than a free meal, and it
            // is refused at the door rather than cooked and discovered the next day.
            //
            // Neither one gets a settlement, because neither one is owed money.
            // What separates them is whether the push says anything at all.
            return RejectionCode.ZERO_VALUE_ORDER;
        }
        if (store.isForceClosed(principal.tenantId(), venue.locationId(), now)) {
            return RejectionCode.BRANCH_CLOSED;
        }
        return null;
    }

    private JdbcPartnerStore.InboundPush stage(PartnerPrincipal principal,
            JdbcPartnerStore.Venue venue, PartnerOrderPush push, Instant now) {

        // ADR 0029: the payload carries a proxied customer contact, so the body
        // is envelope-encrypted before it is stored and never written to a log,
        // an event, or an error response. The hash is taken over the plaintext
        // so two pushes that differ are visible as a restatement rather than a
        // retry, which encryption alone would hide.
        String payload = push.rawPayloadJson() == null ? "{}" : push.rawPayloadJson();
        UUID stagingId = UUID.randomUUID();
        String encrypted = protection.protect(
                        principal.tenantId(),
                        DataClass.PERSONAL,
                        new FieldProtection.RecordRef(
                                "partner.inbound_orders", "raw_payload_encrypted", stagingId),
                        payload)
                .serialize();

        return new JdbcPartnerStore.InboundPush(
                principal.tenantId(), venue.bindingId(), push.externalOrderId(),
                encrypted, sha256(payload), push.pickupExpectedAt(), now);
    }

    /**
     * The idempotency key ADR 0031 would otherwise require the client to supply.
     * Derived, documented, and stronger than a header here for the reason the
     * class comment gives.
     */
    public static String derivedIdempotencyKey(UUID bindingId, String externalOrderId) {
        return "marketplace:%s:%s".formatted(bindingId, externalOrderId);
    }

    /**
     * The order's owner reference. Deliberately a hash of the partner's order
     * identity and never of the customer's contact: aggregators proxy phone
     * numbers and recycle the proxy pool, so matching on one would merge
     * unrelated people into a single record and attach their addresses, history
     * and consent to each other. A marketplace order neither creates nor matches
     * a customer account, and ADR 0015's identity model is untouched by it.
     */
    private static String guestReferenceHash(UUID bindingId, String externalOrderId) {
        return sha256(bindingId + ":" + externalOrderId);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    /**
     * What a partner sent, after protocol mediation.
     *
     * <p>Protocol-agnostic by construction: every aggregator's wire format
     * differs, and an adapter's whole job is to produce this record. Nothing
     * downstream of here knows which partner it came from except through the
     * binding.
     *
     * @param handoverCode the plain code, which travels no further than the
     *                     hasher. It is never stored, never returned, never
     *                     logged, and never put in a trace.
     */
    public record PartnerOrderPush(
            String venueReference,
            String externalOrderId,
            String displayCode,
            String fulfillmentMode,
            ExternalTotals totals,
            DiscountFunding discountFunding,
            List<PushLine> lines,
            String handoverCode,
            Instant pickupExpectedAt,
            String rawPayloadJson,
            String rawTotalsJson) {

        public PartnerOrderPush {
            lines = List.copyOf(lines);
        }
    }

    /**
     * @param taxAmountMinor null when the partner stated no tax on the line,
     *                       which is not the same as tax of zero
     */
    public record PushLine(
            String externalItemReference,
            String name,
            int quantity,
            long unitAmountMinor,
            long lineAmountMinor,
            Long taxAmountMinor) { }

    /**
     * @param unmappedItems the partner item identifiers the catalogue does not
     *                      carry. Non-empty is an accepted order with a
     *                      location-visible exception on it, not a failure: a
     *                      flagged line is a problem a person solves in the
     *                      thirty seconds before the food is cooked.
     */
    public record Outcome(
            boolean accepted,
            boolean duplicate,
            UUID orderId,
            String publicOrderNumber,
            RejectionCode rejectionCode,
            List<String> unmappedItems) {

        public Outcome {
            unmappedItems = unmappedItems == null ? List.of() : List.copyOf(unmappedItems);
        }

        static Outcome accepted(UUID orderId, String number, List<String> unmapped) {
            return new Outcome(true, false, orderId, number, null, unmapped);
        }

        static Outcome duplicate(UUID orderId) {
            return new Outcome(true, true, orderId, null, null, List.of());
        }

        static Outcome rejected(RejectionCode code, UUID orderId) {
            return new Outcome(false, false, orderId, null, code, List.of());
        }
    }
}
