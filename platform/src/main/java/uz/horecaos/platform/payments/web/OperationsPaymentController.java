package uz.horecaos.platform.payments.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.payments.application.PaymentCheckoutService;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PresentationFailure;
import uz.horecaos.platform.payments.domain.PresentationKind;
import uz.horecaos.platform.payments.domain.PresentationRequest;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentTransactionStore;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore.MethodRow;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore.TenderRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * What Finance sees about one order's payment, and the one thing it may do
 * about it that is not a remedy: send the checkout surface again (ADR 0013,
 * ADR 0031, operations-spec/finance.md &sect;8.1).
 *
 * <p><strong>{@code payment} is the IA's {@code payment[]} array.</strong> An
 * order pays through exactly one {@link PaymentIntent} (a single provider
 * request), but it <em>settles</em> through an ordered set of tenders —
 * {@code OrderSettlementService}'s own subject, ADR 0046 — and a
 * cash-plus-points order writes two rows to {@code payments.tenders} at
 * checkout today. {@link OrderPaymentResponse#payment} names every one of a
 * settlement's tenders in their settlement sequence, each with its own status
 * and how much of it has been refunded so far — the operator-facing half of
 * split tender that was missing, since the tenders themselves were already
 * being written. A <strong>deposit</strong> tender is not among the methods a
 * settlement can ever name: ADR 0046 withdrew stored value outright, and
 * {@code CASH}, {@code CLICK}, {@code PAYME}, {@code TELEGRAM} and
 * {@code MARKETPLACE} — plus the code-only {@code LOYALTY_POINTS} balance leg
 * — are the whole of {@link uz.horecaos.platform.payments.domain.PaymentMethod}.
 *
 * <p><strong>Re-presentation is the operator side of a customer action that
 * already existed.</strong> {@code StorefrontPaymentController} opens or
 * re-presents a checkout for the customer's own order, under their own token
 * and no capability at all. An operator taking a call from someone who never
 * received their Click push, or who wants it pushed to a different phone, has
 * no order to own and no token to prove it with — {@link
 * Capability#PAYMENT_INITIATE} is the capability that stands in for that
 * ownership check, exactly as that controller's own Javadoc predicted before
 * this one existed.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/orders/{orderId}/payment")
@Tag(
        name = "Order payments",
        description = "One order's payment intent, its settlement tenders and attempts, and "
                + "re-presenting its checkout surface")
public class OperationsPaymentController {

    private static final Logger log = LoggerFactory.getLogger(OperationsPaymentController.class);

    private final JdbcPaymentIntentStore intents;
    private final JdbcPaymentAttemptStore attempts;
    private final JdbcPaymentTransactionStore transactions;
    private final JdbcSettlementStore settlements;
    private final OrderDirectory orders;
    private final PaymentCheckoutService checkout;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public OperationsPaymentController(
            JdbcPaymentIntentStore intents,
            JdbcPaymentAttemptStore attempts,
            JdbcPaymentTransactionStore transactions,
            JdbcSettlementStore settlements,
            OrderDirectory orders,
            PaymentCheckoutService checkout,
            AuditRecorder audit,
            CurrentActor currentActor,
            Clock clock) {
        this.intents = intents;
        this.attempts = attempts;
        this.transactions = transactions;
        this.settlements = settlements;
        this.orders = orders;
        this.checkout = checkout;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @GetMapping
    @RequiresCapability(value = Capability.PAYMENT_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "One order's payment: its live intent, its settlement tenders, every "
                    + "attempt, and what has settled",
            description = "The order is read through OrderDirectory rather than the ordering "
                    + "module's own detail endpoint, so this stays a payments-side read: the "
                    + "public order number and total are enough to confirm this is the right "
                    + "order, without reaching into lines, notes or the encrypted fields that "
                    + "belong to a different capability. A null intent is not an error -- it is "
                    + "an order that was never checked out for payment, or one whose intent has "
                    + "gone terminal (cancelled, expired, failed) and is no longer the order's "
                    + "live obligation. `payment` is the IA's payment[] array -- every tender of "
                    + "the order's settlement (ADR 0046), in the sequence it settles, empty for "
                    + "an order with no settlement at all.")
    public ResponseEntity<OrderPaymentResponse> forOrder(@PathVariable UUID tenantId, @PathVariable UUID orderId) {
        OrderDirectory.OrderSummary order = orders.summary(tenantId, orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));

        Optional<PaymentIntent> intent = intents.findLiveForOrder(tenantId, orderId);

        List<PaymentAttemptResponse> attemptRows = intent
                .map(PaymentIntent::id)
                .map(intentId -> attempts.listForIntent(tenantId, intentId))
                .orElseGet(List::of)
                .stream()
                .map(PaymentAttemptResponse::of)
                .toList();

        List<TenderResponse> tenderRows = tendersOf(tenantId, orderId);

        ApiMoney captured = null;
        ApiMoney returned = null;
        if (intent.isPresent()) {
            String currency = intent.get().amount().currency();
            captured = ApiMoney.of(
                    transactions.capturedMinor(tenantId, intent.get().id()), currency);
            returned = ApiMoney.of(
                    transactions.returnedMinor(tenantId, intent.get().id()), currency);
        }

        return ResponseEntity.ok(new OrderPaymentResponse(
                order.orderId(),
                order.publicOrderNumber(),
                order.status(),
                ApiMoney.of(order.totalMinor(), order.currency()),
                intent.map(PaymentIntentResponse::of).orElse(null),
                tenderRows,
                attemptRows,
                captured,
                returned));
    }

    /**
     * The order's settlement, as the IA's {@code payment[]} array: every tender
     * in the sequence {@code OrderSettlementService#plan} gave it, each with its
     * own status and how much of it has been refunded so far.
     *
     * <p>Empty rather than an error for an order with no settlement at all --
     * placed before this seam existed, or naming a method this build cannot
     * tender against ({@code CheckoutSettlementPlanner}'s own case) -- the same
     * "not found is not an error" reading {@code intent} already gives a
     * terminal or absent intent.
     */
    private List<TenderResponse> tendersOf(UUID tenantId, UUID orderId) {
        return settlements
                .findSettlement(tenantId, orderId)
                .map(settlement -> settlements.tendersOf(tenantId, settlement.id()))
                .orElseGet(List::of)
                .stream()
                .map(tender -> TenderResponse.of(tender, settlements.findMethod(tenantId, tender.paymentMethodId())))
                .toList();
    }

    @PostMapping("/re-presentations")
    @RequiresCapability(value = Capability.PAYMENT_INITIATE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Re-issue the checkout surface -- a link, or an invoice pushed to a phone",
            description = "The same open-or-re-present rule the storefront uses: an abandoned "
                    + "checkout is handed back its own attempt, never a second one. A push is "
                    + "never repeated automatically -- Click's invoice/create has no idempotency "
                    + "key -- so pushing again on a customer's request is exactly this endpoint's "
                    + "reason to exist rather than something the storefront could do for itself.")
    public ResponseEntity<PaymentSessionResponse> rePresent(
            @PathVariable UUID tenantId, @PathVariable UUID orderId, @Valid @RequestBody RePresentationRequest body) {

        PresentationRequest request;
        try {
            request = body.toDomain();
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, malformed.getMessage());
        }

        OrderDirectory.OrderSummary order = orders.summary(tenantId, orderId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No such order", Map.of("reason", "ORDER_NOT_FOUND")));

        // Before the checkout, not after it: opening one is an external effect no
        // transaction can take back (a payable link, or an invoice on a customer's
        // phone), so "an audit failure fails the action" (ADR 0027) is kept by making
        // the first write the gate. A store that is down stops the issue here, with
        // nothing sent and the Idempotency-Key released for the retry.
        recordReissueRequested(tenantId, order, request, body.reason());

        try {
            var session = checkout.openOrRePresent(tenantId, orderId, null, request);
            recordReissue(tenantId, order, request, session, body.reason());
            return ResponseEntity.ok(PaymentSessionResponse.of(session));

        } catch (PaymentCheckoutService.CheckoutRefusedException refused) {
            throw new ApiException(
                    errorCodeFor(refused.code()), refused.getMessage(), Map.of("reason", refused.code()));

        } catch (PresentationFailure.Uncertain lost) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The payment provider did not answer and the outcome is unknown. The "
                            + "platform is resolving it; do not re-present this order again.",
                    Map.of("reason", "PAYMENT_OUTCOME_UNCERTAIN", "failureCode", lost.failureCode()));

        } catch (PresentationFailure.Refused refused) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, refused.getMessage(), Map.of("reason", refused.failureCode()));
        }
    }

    /**
     * The audit fact for an operator's request to hand a customer a checkout surface
     * again (ADR 0027; gap map row {@code 1.1e}, «Выставить счёт»), written
     * <em>before</em> the checkout is opened.
     *
     * <p>Opening the checkout is an external effect, so it cannot share a
     * transaction with its own evidence. Writing this first is what keeps the rule
     * that an audit failure fails the action: if the store is down the exception
     * leaves the request here, before anything was sent, and the retry is served
     * once it is back. It records <em>that the operator asked</em> and for which
     * presentation, so a request the checkout then refuses stays on the trail --
     * who tried to re-issue what, and was told no -- with no {@code
     * payment.checkout_reissued} beside it.
     *
     * <p>Nothing about the phone an invoice would be pushed to: only that a
     * recipient was named (ADR 0029).
     */
    private void recordReissueRequested(
            UUID tenantId, OrderDirectory.OrderSummary order, PresentationRequest request, @Nullable String reason) {
        var after = new LinkedHashMap<String, Object>();
        after.put("requestedPresentation", request.preferredKind().name());
        after.put("pushedToRecipient", request.pushRecipient() != null);
        audit.record(reissueFact("payment.checkout_reissue_requested", tenantId, order, reason, after));
    }

    /**
     * The audit fact for a checkout surface handed out on an operator's request
     * (ADR 0027; gap map row {@code 1.1e}, «Выставить счёт»). Written only after
     * the surface exists: a refused or uncertain presentation handed nobody
     * anything, and its refusal is already the response.
     *
     * <p>Scoped to the order's own branch, so a branch manager's activity log
     * shows the invoices sent for their orders. The fact records <em>that</em> a
     * payable link or a push was issued, through which provider and whether it was
     * an abandoned attempt handed back — never the phone the invoice was pushed
     * to (ADR 0029: personal data reaches no audit document, only the fact that
     * a push recipient was named) and never the link itself, which is a bearer of
     * the payment.
     *
     * <p>Not allowed to fail the response. By here the link exists and the
     * customer can pay it, and {@link #recordReissueRequested} has already put the
     * action on the trail; answering 500 for it would tell the operator it does not
     * exist and release the Idempotency-Key on a payment surface that is live. So a
     * failure to write the detail is logged at {@code ERROR} -- the order and the
     * attempt, never the recipient -- and the session is returned.
     */
    private void recordReissue(
            UUID tenantId,
            OrderDirectory.OrderSummary order,
            PresentationRequest request,
            PaymentCheckoutService.PaymentSession session,
            @Nullable String reason) {
        var after = new LinkedHashMap<String, Object>();
        after.put("attemptId", session.attemptId().toString());
        after.put("provider", session.providerType().name());
        after.put("presentation", session.presentationKind().name());
        after.put("rePresented", session.rePresented());
        after.put("presentationCount", session.presentationCount());
        after.put("pushedToRecipient", request.pushRecipient() != null);

        try {
            audit.record(reissueFact("payment.checkout_reissued", tenantId, order, reason, after));
        } catch (RuntimeException unwritten) {
            log.error(
                    "The audit fact for the checkout issued for order {} (attempt {}) could not be written; "
                            + "the request fact stands",
                    order.orderId(),
                    session.attemptId(),
                    unwritten);
        }
    }

    private AuditFact reissueFact(
            String actionCode,
            UUID tenantId,
            OrderDirectory.OrderSummary order,
            @Nullable String reason,
            Map<String, Object> after) {
        return AuditFact.of(actionCode, AuditClass.BUSINESS)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.location(tenantId, order.brandId(), order.locationId()))
                .target("ordering.order", order.orderId())
                .because(reason == null || reason.isBlank() ? DEFAULT_REISSUE_REASON : reason.strip())
                .changed(ChangeDocuments.created(after))
                .usingCapability(Capability.PAYMENT_INITIATE.code())
                .correlatedBy(order.orderId().toString())
                .occurredAt(clock.instant())
                .build();
    }

    /** Recorded when the operator gave no reason of their own — the action is the reason, and it must not be blank. */
    private static final String DEFAULT_REISSUE_REASON = "Operator re-issued the order's payment checkout";

    private static ErrorCode errorCodeFor(String code) {
        return switch (code) {
            case "ORDER_NOT_FOUND", "NO_PAYMENT_INTENT" -> ErrorCode.RESOURCE_NOT_FOUND;
            default -> ErrorCode.RESOURCE_CONFLICT;
        };
    }

    // ----------------------------------------------------------- payloads

    /**
     * What an operator asks for. Mirrors {@code StorefrontPaymentController}'s
     * own request shape; the field this endpoint exists for is
     * {@code pushRecipient}, which here may be any phone the operator was given
     * on the call, not only the one on the order.
     *
     * @param pushRecipient the phone to push an invoice to, on Click only.
     *                      Personal data under ADR 0029: never stored on the
     *                      attempt, never logged, never published in an event
     * @param reason        why the operator is issuing it (optional): recorded with
     *                      the audit fact, defaulted when absent so the console's
     *                      one-click «Выставить счёт» stays one click
     */
    public record RePresentationRequest(
            @Size(max = 24) String presentation,
            @Size(max = 2) String language,

            @Size(max = 12) @Pattern(regexp = "^998\\d{9}$", message = "must be 998 followed by nine digits")
            String pushRecipient,

            @Size(max = 255) @Nullable String reason) {

        PresentationRequest toDomain() {
            PresentationKind kind = presentation == null || presentation.isBlank()
                    ? PresentationKind.PAYMENT_LINK
                    : PresentationKind.valueOf(presentation.strip().toUpperCase(java.util.Locale.ROOT));
            return new PresentationRequest(kind, null, language, pushRecipient);
        }
    }

    /** Mirrors {@code StorefrontPaymentController.PaymentSessionResponse} field for field. */
    public record PaymentSessionResponse(
            UUID attemptId,
            String merchantTransId,
            String provider,
            String presentation,
            @Nullable String checkoutUrl,
            @Nullable String qrPayload,
            @Nullable Instant expiresAt,
            long amountMinor,
            String currency,
            boolean rePresented,
            int presentationCount) {

        static PaymentSessionResponse of(PaymentCheckoutService.PaymentSession session) {
            return new PaymentSessionResponse(
                    session.attemptId(),
                    session.merchantTransId(),
                    session.providerType().name(),
                    session.presentationKind().name(),
                    session.checkoutUrl(),
                    session.qrPayload(),
                    session.expiresAt(),
                    session.amountMinor(),
                    session.currency(),
                    session.rePresented(),
                    session.presentationCount());
        }
    }

    /**
     * One order's payment picture.
     *
     * @param intent   the order's live payment intent, or null when the order has
     *                 none -- not checked out for payment yet, or its intent has
     *                 gone terminal
     * @param payment  the IA's {@code payment[]} array: every tender of the
     *                 order's settlement (ADR 0046), in settlement sequence.
     *                 Empty for an order with no settlement -- not the same
     *                 condition as a null {@code intent}, since a settlement
     *                 outlives the intent that was created against its money leg
     * @param captured what a payment transaction has recorded as captured
     *                 against the intent, or null alongside a null intent
     * @param returned what a payment transaction has recorded as returned
     *                 (a provider-side reversal, distinct from an ADR 0048
     *                 remedy, which is recorded by {@code OperationsRemedyController})
     */
    public record OrderPaymentResponse(
            UUID orderId,
            String publicOrderNumber,
            String orderStatus,
            ApiMoney orderTotal,
            @Nullable PaymentIntentResponse intent,
            List<TenderResponse> payment,
            List<PaymentAttemptResponse> attempts,
            @Nullable ApiMoney captured,
            @Nullable ApiMoney returned) {}

    /**
     * One tender of a settlement (ADR 0046), as an operator sees it: the
     * registry method it names, its own status and how much of it has been
     * given back.
     *
     * <p>{@code methodCode} is never {@code DEPOSIT} or any spelling of it --
     * stored value was withdrawn outright and no settlement can plan a tender
     * against a method that does not exist in {@link
     * uz.horecaos.platform.payments.domain.PaymentMethod} or the code-only
     * loyalty balance leg {@code CheckoutSettlementPlanner} registers.
     *
     * @param sequence          the settlement order: the balance tender first,
     *                          external tenders last (releasing a points hold is
     *                          a local write; reversing a captured payment is a
     *                          provider refund with an uncertainty window)
     * @param settlesFromBalance true for the one balance-backed leg a
     *                          settlement may carry, today only the loyalty
     *                          points redemption
     * @param status            {@code TenderStatus}'s own name: {@code PLANNED},
     *                          {@code RESERVED}, {@code SETTLED},
     *                          {@code RELEASED}, {@code REVERSED} or
     *                          {@code FAILED}
     * @param refunded          how much of this tender has been given back so
     *                          far -- never more than {@code amount}, and never
     *                          the source of a refund that exceeds what the
     *                          tender itself settled
     */
    public record TenderResponse(
            UUID tenderId,
            int sequence,
            String methodCode,
            String methodDisplayName,
            boolean settlesFromBalance,
            ApiMoney amount,
            String status,
            ApiMoney refunded) {

        static TenderResponse of(TenderRow tender, Optional<MethodRow> method) {
            String code = method.map(MethodRow::code).orElse("UNKNOWN");
            String displayName = method.map(MethodRow::displayName).orElse(code);
            return new TenderResponse(
                    tender.id(),
                    tender.sequence(),
                    code,
                    displayName,
                    tender.settlesFromBalance(),
                    ApiMoney.of(tender.amountMinor(), tender.currency()),
                    tender.status().name(),
                    ApiMoney.of(tender.refundedMinor(), tender.currency()));
        }
    }

    public record PaymentIntentResponse(
            UUID intentId,
            String tender,
            String method,
            @Nullable String providerType,
            ApiMoney amount,
            String status,
            Instant createdAt,
            @Nullable Instant settledAt) {

        static PaymentIntentResponse of(PaymentIntent intent) {
            return new PaymentIntentResponse(
                    intent.id(),
                    intent.tender().name(),
                    intent.method().name(),
                    intent.providerType() == null ? null : intent.providerType().name(),
                    ApiMoney.of(intent.amount().value(), intent.amount().currency()),
                    intent.status().name(),
                    intent.createdAt(),
                    intent.settledAt());
        }
    }

    public record PaymentAttemptResponse(
            UUID attemptId,
            String providerType,
            String status,
            @Nullable String presentationKind,
            ApiMoney amount,
            boolean live,
            Instant createdAt,
            @Nullable Instant settledAt) {

        static PaymentAttemptResponse of(PaymentAttempt attempt) {
            return new PaymentAttemptResponse(
                    attempt.id(),
                    attempt.providerType().name(),
                    attempt.status().name(),
                    attempt.presentationKind() == null
                            ? null
                            : attempt.presentationKind().name(),
                    ApiMoney.of(attempt.amount().value(), attempt.amount().currency()),
                    attempt.status().blocksFurtherAttempts(),
                    attempt.createdAt(),
                    attempt.settledAt());
        }
    }
}
