package uz.horecaos.platform.ordering.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.domain.DeliveryDestination;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcBranchOverrideReasonStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartRow;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Operator-assisted order creation (ADR 0039): "an operator-placed order is
 * the same order, taken by a different hand."
 *
 * <p>{@link #place} is an orchestration over {@link CartService} and {@link
 * CheckoutService} — the identical path {@code StorefrontOrderingController}
 * and {@code CustomerBotOrderingAdapter} already take an order through, never
 * a second pricing, inventory or payment rule set of its own. What differs
 * from a customer's own checkout is attribution alone: the cart is opened for
 * the resolved customer's own account (so every ownership check {@link
 * CartService} already enforces still applies), and the {@link
 * CheckoutService.CheckoutCommand} names the operator as {@code USER} rather
 * than the account as {@code CUSTOMER}, which is what lands in {@code
 * ordering.orders.created_by_actor_type/id} (V0029).
 *
 * <p><strong>Payment is whatever the operator channel's own matrix offers,
 * not a hard-coded list.</strong> Wave P13 refused anything but {@code CASH}
 * here before writing a row; that check is gone, because {@link
 * CheckoutEligibilityGuard} already asks the identical question of every
 * other checkout — {@code tenant.channel_payment_methods} intersected with
 * {@code PaymentIntentPort#canAcceptPayment} — and a second, narrower rule in
 * front of it would just be a worse copy of the one rule that has to be right.
 * Today's channel matrix for the tenant's operator channel may still enable
 * cash alone, which is a configuration fact and not a rule this class states;
 * enabling Click or Payme on that channel is a Settings change, not a release.
 *
 * <p><strong>A promo code applies exactly as a customer's own does</strong>
 * (ADR 0072): {@link #place} calls {@link CartService#applyPromoCode} between
 * filling the basket and pricing it, so the same eligibility check — active,
 * in its window, not exhausted — runs on a phone order that runs on a
 * self-service one.
 *
 * <p>The phone lookup ADR 0039 describes beside this is {@code
 * OperatorCustomerLookupService} — a separate class with separate
 * collaborators (a PII port and an audit recorder rather than a cart and a
 * checkout transaction), kept apart so that testing one never needs a stand-in
 * for the other.
 *
 * <p><strong>A pre-order time is a requested instant, not a second pricing
 * pipeline</strong> (row 1.3d). {@link PlaceOrderCommand#requestedFor} passes
 * straight through to {@link CheckoutService.CheckoutCommand#requestedFor()},
 * where {@code CheckoutEligibilityGuard} validates it against the branch's
 * own hours before checkout ever commits and {@code CheckoutOrderWriter}
 * writes it as the order's promise under {@code PromiseBasis.SCHEDULED_SLOT}.
 * This is deliberately the narrow slice of ADR 0019's still-open scheduled-order
 * input — an operator-entered time, checked against today's hours — and not the
 * long-lead reprice/reservation/payment-timing policy that ADR still leaves
 * for a later decision.
 */
@Service
public class OperatorOrderingService {

    private final CartService carts;
    private final CheckoutService checkout;
    private final BranchOverrideReasonQueryService overrideReasons;
    private final BranchResolutionQueryService branchResolution;
    private final CustomerAddressBook addresses;
    private final AuditRecorder audit;
    private final Clock clock;

    /**
     * Recorded as the ADR 0027 fact against the one reveal {@link
     * #resolveProposedLocationId} makes of a DELIVERY destination's
     * coordinate — never the same line as {@code CartService}'s own capture
     * of the same address, because the two are different purposes even when
     * they read the identical row.
     */
    private static final String OVERRIDE_CHECK_PURPOSE = "OPERATOR_BRANCH_OVERRIDE_CHECK";

    public OperatorOrderingService(
            CartService carts,
            CheckoutService checkout,
            BranchOverrideReasonQueryService overrideReasons,
            BranchResolutionQueryService branchResolution,
            CustomerAddressBook addresses,
            AuditRecorder audit,
            Clock clock) {
        this.carts = carts;
        this.checkout = checkout;
        this.overrideReasons = overrideReasons;
        this.branchResolution = branchResolution;
        this.addresses = addresses;
        this.audit = audit;
        this.clock = clock;
    }

    /** One line an operator entered into the basket. */
    public record OrderLine(
            UUID variantId,
            BigDecimal quantity,
            List<UUID> modifierOptionIds,
            // Row 2.1b: the coded kitchen-instruction presets, same vocabulary
            // and the same offered-subset check a customer's own cart line
            // goes through in CartService.putLine.
            List<String> commentPresetCodes,
            @Nullable String customerNote,
            // ADR 0136: what the operator picked inside a combo and any second-level
            // modifier selections, through the same cart path a customer's go through.
            List<CartService.ComboPick> comboPicks,
            List<CartService.NestedModifier> nestedModifiers) {

        public OrderLine {
            comboPicks = comboPicks == null ? List.of() : List.copyOf(comboPicks);
            nestedModifiers = nestedModifiers == null ? List.of() : List.copyOf(nestedModifiers);
        }

        /** A line with no combo and no nested selection: every line that predates ADR 0136. */
        public OrderLine(
                UUID variantId,
                BigDecimal quantity,
                List<UUID> modifierOptionIds,
                List<String> commentPresetCodes,
                @Nullable String customerNote) {
            this(variantId, quantity, modifierOptionIds, commentPresetCodes, customerNote, List.of(), List.of());
        }

        /** A whole number of units, which is every line there was before ADR 0137. */
        public OrderLine(
                UUID variantId,
                int quantity,
                List<UUID> modifierOptionIds,
                List<String> commentPresetCodes,
                @Nullable String customerNote) {
            this(
                    variantId,
                    BigDecimal.valueOf(quantity),
                    modifierOptionIds,
                    commentPresetCodes,
                    customerNote,
                    List.of(),
                    List.of());
        }
    }

    /**
     * Where a delivery order is going — a saved address of the resolved
     * customer's, never one typed ad hoc: see {@link
     * CartService#setDestination}'s own doc for why. Null for a pickup or
     * dine-in order.
     */
    public record Destination(
            UUID customerAddressId,
            String recipientName,
            String recipientPhone,
            @Nullable String deliveryNote) {}

    /**
     * @param requestedFor       row 1.3d: the caller asked for this order for
     *                           later rather than now, or null for an ordinary
     *                           immediate order. Validated against the branch's
     *                           own hours inside {@code CheckoutService} and
     *                           written to {@code OrderPromise} as {@code
     *                           PromiseBasis.SCHEDULED_SLOT} — see that basis's
     *                           own doc
     * @param overrideOutOfHours whether the operator has already been warned the
     *                           branch is closed at {@code requestedFor} and
     *                           chose to place it anyway. Meaningless when {@code
     *                           requestedFor} is null
     * @param proposedLocationId row 1.3's cross-branch resolver, as the
     *                           caller last saw it — a display hint only.
     *                           {@link #place} never trusts this field for
     *                           whether an override happened or what to
     *                           audit: it re-resolves the branch itself
     *                           through {@link BranchResolutionQueryService}
     *                           from {@code fulfillmentMode}/{@code
     *                           destination}/{@code channelCode}, because a
     *                           client-supplied proposal is trivially
     *                           omitted or edited and would otherwise let a
     *                           cross-branch placement read back as an
     *                           ordinary one, with no audit fact at all
     * @param overrideReasonCode required, and only meaningful, when the
     *                           server's own freshly-resolved proposal
     *                           differs from {@code locationId}: one of
     *                           {@code BranchOverrideReasonQueryService}'s
     *                           curated codes, validated and audited before
     *                           the order is created
     * @param overrideNote       required exactly when {@code overrideReasonCode}
     *                           is {@code OTHER}; free text, so redacted like
     *                           every other note in this platform's audit trail
     * @param dineInSessionId    the live party (ADR 0047) whose bill a {@code
     *                           DINE_IN} order goes on, or null. Meaningful only
     *                           for {@code DINE_IN}; refused for any other mode.
     *                           When set the order is put on that session's bill
     *                           inside this transaction, after the order exists
     *                           and before anything commits, so it is on the bill
     *                           or it does not exist -- the operator no longer
     *                           places an order and then attaches it in a second
     *                           call that a closed party can fail
     */
    public record PlaceOrderCommand(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID customerAccountId,
            String channelCode,
            FulfillmentMode fulfillmentMode,
            List<OrderLine> lines,
            @Nullable Destination destination,
            String paymentMethodCode,
            @Nullable String promoCode,
            String idempotencyKey,
            String operatorSubject,
            @Nullable String correlationId,
            @Nullable Instant requestedFor,
            boolean overrideOutOfHours,
            @Nullable UUID proposedLocationId,
            @Nullable String overrideReasonCode,
            @Nullable String overrideNote,
            @Nullable UUID dineInSessionId) {

        /** An order that is not put on a table's bill: every mode but DINE_IN, and a DINE_IN one the caller attaches later. */
        @SuppressWarnings("checkstyle:ParameterNumber")
        public PlaceOrderCommand(
                UUID tenantId,
                UUID brandId,
                UUID locationId,
                UUID customerAccountId,
                String channelCode,
                FulfillmentMode fulfillmentMode,
                List<OrderLine> lines,
                @Nullable Destination destination,
                String paymentMethodCode,
                @Nullable String promoCode,
                String idempotencyKey,
                String operatorSubject,
                @Nullable String correlationId,
                @Nullable Instant requestedFor,
                boolean overrideOutOfHours,
                @Nullable UUID proposedLocationId,
                @Nullable String overrideReasonCode,
                @Nullable String overrideNote) {
            this(
                    tenantId,
                    brandId,
                    locationId,
                    customerAccountId,
                    channelCode,
                    fulfillmentMode,
                    lines,
                    destination,
                    paymentMethodCode,
                    promoCode,
                    idempotencyKey,
                    operatorSubject,
                    correlationId,
                    requestedFor,
                    overrideOutOfHours,
                    proposedLocationId,
                    overrideReasonCode,
                    overrideNote,
                    null);
        }
    }

    /**
     * Opens a cart for the resolved customer, fills it exactly as entered,
     * applies a promo code when one was given, prices it and checks it out
     * through {@link CheckoutService} — the same transaction, the same rules,
     * and the same order that a customer's own checkout would produce,
     * attributed to the operator who took the call.
     */
    @Transactional
    public CheckoutService.CheckoutResult place(PlaceOrderCommand command) {
        if (command.lines().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An order needs at least one line");
        }
        boolean delivery = command.fulfillmentMode() == FulfillmentMode.DELIVERY;
        if (delivery && command.destination() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A delivery order needs a destination");
        }
        if (!delivery && command.destination() != null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A " + command.fulfillmentMode() + " order has nowhere to deliver to");
        }
        UUID dineInSessionId = command.dineInSessionId();
        if (dineInSessionId != null) {
            if (command.fulfillmentMode() != FulfillmentMode.DINE_IN) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "A " + command.fulfillmentMode()
                                + " order is not eaten at a table, so it has no bill to go on");
            }
            // Before anything is created or priced. A party that left while the operator
            // built the basket is the ordinary way for this to fail, and it must cost
            // the operator a refusal and not a cooked order that is on no bill.
            carts.requireLiveSession(command.tenantId(), command.locationId(), dineInSessionId);
        }

        // Row 1.3: re-resolved here rather than trusted from the request body
        // (see PlaceOrderCommand#proposedLocationId's own doc) and validated
        // before anything is created, so a bad or missing reason code refuses
        // cleanly rather than leaving an orphaned cart behind.
        // BranchOverrideReasonQueryService#validateForDecision throws
        // ApiException-unwrapped exceptions the caller below translates the
        // same way CartService's own refusals already are — see the catch
        // blocks this method already carries.
        UUID resolvedProposedLocationId = resolveProposedLocationId(command);
        boolean isBranchOverride =
                resolvedProposedLocationId != null && !resolvedProposedLocationId.equals(command.locationId());
        JdbcBranchOverrideReasonStore.ReasonRow overrideReason = null;
        if (isBranchOverride) {
            if (command.overrideReasonCode() == null
                    || command.overrideReasonCode().isBlank()) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Placing at a branch other than the one the resolver proposed needs a reason");
            }
            try {
                overrideReason =
                        overrideReasons.validateForDecision(command.overrideReasonCode(), command.overrideNote());
            } catch (BranchOverrideReasonQueryService.UnknownBranchOverrideReasonException unknown) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, unknown.getMessage());
            } catch (IllegalArgumentException invalid) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
            }
        }

        CartRow cart = carts.create(
                command.tenantId(),
                command.brandId(),
                command.locationId(),
                command.channelCode(),
                command.fulfillmentMode(),
                command.customerAccountId(),
                null);

        int version = cart.version();
        int lineIndex = 0;
        for (OrderLine line : command.lines()) {
            var view = carts.putLine(
                    command.tenantId(),
                    command.brandId(),
                    command.customerAccountId(),
                    cart.cartId(),
                    version,
                    "op" + lineIndex++,
                    line.variantId(),
                    line.quantity(),
                    line.modifierOptionIds(),
                    line.commentPresetCodes(),
                    line.comboPicks(),
                    line.nestedModifiers(),
                    line.customerNote());
            version = view.cart().version();
        }

        if (delivery) {
            // Required and refused above when absent; NullAway cannot see that
            // cross-statement guarantee, so it is restated here rather than
            // silently re-typed away.
            Destination destination =
                    Objects.requireNonNull(command.destination(), "A delivery order needs a destination");
            var view = carts.setDestination(
                    command.tenantId(),
                    command.brandId(),
                    command.customerAccountId(),
                    cart.cartId(),
                    version,
                    new CartService.DestinationCommand(
                            destination.customerAddressId(),
                            destination.recipientName(),
                            destination.recipientPhone(),
                            destination.deliveryNote()));
            version = view.cart().version();
        }

        if (command.promoCode() != null && !command.promoCode().isBlank()) {
            // ADR 0072, threaded exactly as a customer's own
            // POST /carts/{cartId}/promo-code does: checked read-only against
            // live coupon state, stored, and re-checked independently on every
            // price that follows — this call does not decide anything the
            // quote below will not decide again.
            var view = carts.applyPromoCode(
                    command.tenantId(),
                    command.brandId(),
                    command.customerAccountId(),
                    cart.cartId(),
                    version,
                    command.promoCode());
            version = view.cart().version();
        }

        // ADR 0140: the method the order will be paid by is an input to its price when a
        // promotion reads it, and checkout refuses a method the cart was not priced with.
        // An operator names the method up front, so it goes on the cart before pricing.
        if (command.paymentMethodCode() != null && !command.paymentMethodCode().isBlank()) {
            try {
                var view = carts.setPaymentMethod(
                        command.tenantId(),
                        command.brandId(),
                        command.customerAccountId(),
                        cart.cartId(),
                        version,
                        command.paymentMethodCode());
                version = view.cart().version();
            } catch (CartService.CartRefusedException unavailable) {
                if (!"PAYMENT_METHOD_UNAVAILABLE".equals(unavailable.code())) {
                    throw unavailable;
                }
                // A method the channel does not offer is checkout's refusal to make, with its own
                // typed outcome; the cart simply carries no method and prices as it always did.
            }
        }

        var priced =
                carts.price(command.tenantId(), command.brandId(), command.customerAccountId(), cart.cartId(), version);

        CheckoutService.CheckoutResult result = checkout.checkout(new CheckoutService.CheckoutCommand(
                command.tenantId(),
                command.brandId(),
                cart.cartId(),
                priced.cartVersion(),
                priced.quote().quoteId(),
                priced.quote().contextHash(),
                command.idempotencyKey(),
                command.paymentMethodCode(),
                0L,
                "USER",
                command.operatorSubject(),
                command.correlationId(),
                command.requestedFor(),
                command.overrideOutOfHours()));

        // The order is on the party's bill before this transaction commits, or it does
        // not exist: a refusal here (the party closed in the seconds checkout took, a
        // currency the bill cannot mix) rolls the cart, the order and the stock it held
        // back with it. Not after a REJECTED outcome -- nothing was placed -- and safe on
        // a REPLAYED one, where the round is already on the bill and the write answers
        // with the sequence it has.
        if (dineInSessionId != null && result.outcome() != CheckoutService.CheckoutResult.Outcome.REJECTED) {
            carts.attachOrderToSession(
                    command.tenantId(),
                    command.locationId(),
                    dineInSessionId,
                    Objects.requireNonNull(result.orderId(), "a non-rejected checkout always names an order"),
                    command.operatorSubject());
        }

        // Audited only on the write that actually created the order — never on
        // a REJECTED outcome (nothing was placed to have a branch at all) and
        // never a second time on a REPLAYED retry of the same Idempotency-Key,
        // which would otherwise double the audit trail for one real override.
        if (overrideReason != null && result.outcome() == CheckoutService.CheckoutResult.Outcome.CREATED) {
            recordOverrideAudit(
                    command,
                    overrideReason,
                    Objects.requireNonNull(result.orderId()),
                    Objects.requireNonNull(resolvedProposedLocationId));
        }

        return result;
    }

    /**
     * The branch {@link BranchResolutionQueryService} proposes for this
     * command right now, re-derived server-side rather than read off {@link
     * PlaceOrderCommand#proposedLocationId} — a caller cannot make a
     * cross-branch placement look ordinary just by omitting or editing that
     * field. Null exactly when {@link BranchResolutionQueryService#resolve}
     * itself has nothing to propose: {@code DINE_IN} (always the current
     * branch, no cross-branch question — the same refusal {@code resolve}
     * itself would throw, avoided here rather than caught), or a
     * {@code DELIVERY} order whose destination cannot be resolved to a point
     * (an address that does not exist, is not this customer's, or carries no
     * coordinate — {@code CartService#setDestination} refuses the order for
     * the identical reason moments later, so there is nothing to override
     * here either).
     */
    private @Nullable UUID resolveProposedLocationId(PlaceOrderCommand command) {
        if (command.fulfillmentMode() == FulfillmentMode.DINE_IN) {
            return null;
        }
        GeoPoint point = null;
        if (command.fulfillmentMode() == FulfillmentMode.DELIVERY) {
            Destination destination = command.destination();
            if (destination == null) {
                return null;
            }
            CustomerAddressBook.SavedDestination saved = addresses
                    .destination(
                            command.tenantId(),
                            command.customerAccountId(),
                            destination.customerAddressId(),
                            OVERRIDE_CHECK_PURPOSE)
                    .orElse(null);
            if (saved == null || !saved.located()) {
                return null;
            }
            DeliveryDestination located =
                    Objects.requireNonNull(saved.destination(), "saved.located() guarantees this");
            point = new GeoPoint(located.latitude(), located.longitude());
        }
        return branchResolution
                .resolve(command.tenantId(), command.brandId(), command.fulfillmentMode(), point, command.channelCode())
                .proposedLocationId();
    }

    private void recordOverrideAudit(
            PlaceOrderCommand command,
            JdbcBranchOverrideReasonStore.ReasonRow reason,
            UUID orderId,
            UUID resolvedProposedLocationId) {
        audit.record(AuditFact.of("ordering.order.branch_overridden", AuditClass.BUSINESS)
                .by(ActorRef.user(command.operatorSubject(), null))
                .at(ResourceScope.location(command.tenantId(), command.brandId(), command.locationId()))
                .target("Order", orderId)
                .because("Operator overrode the resolver's proposed branch (%s)".formatted(reason.code()))
                .changed(ChangeDocuments.created(mapOf(
                        "proposedLocationId",
                        resolvedProposedLocationId.toString(),
                        "chosenLocationId",
                        command.locationId().toString(),
                        "reasonCode",
                        reason.code(),
                        "note",
                        command.overrideNote())))
                .correlatedBy(command.correlationId() == null ? orderId.toString() : command.correlationId())
                .occurredAt(clock.instant())
                .build());
    }

    /**
     * {@code Map.of} rejects a null value outright, and {@code note} is
     * legitimately null whenever the operator gave none — the same reason
     * {@code JdbcServiceZoneStore#commonDraftParams} reaches for a mutable map
     * instead.
     */
    private static Map<String, Object> mapOf(@Nullable Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1]);
        }
        return map;
    }
}
