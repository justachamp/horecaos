package uz.horecaos.platform.payments.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
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
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * What Finance sees about one order's payment, and the one thing it may do
 * about it that is not a remedy: send the checkout surface again (ADR 0013,
 * ADR 0031, operations-spec/finance.md &sect;8.1).
 *
 * <p><strong>The read is honest about what this build does not have.</strong>
 * {@code PaymentIntent.tenderId}'s own Javadoc says split tender has not
 * shipped — every order pays through exactly one intent and one method, never a
 * mix of cash, cashback and deposit in the same settlement (ADR 0046 is the
 * open decision). So {@link OrderPaymentResponse} names a single {@code intent}
 * rather than an array, and a caller expecting the IA's {@code payment[]}
 * should read this as that array's first and, for now, only element.
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
        description = "One order's payment intent and attempts, and re-presenting its checkout surface")
public class OperationsPaymentController {

    private final JdbcPaymentIntentStore intents;
    private final JdbcPaymentAttemptStore attempts;
    private final JdbcPaymentTransactionStore transactions;
    private final OrderDirectory orders;
    private final PaymentCheckoutService checkout;

    public OperationsPaymentController(
            JdbcPaymentIntentStore intents,
            JdbcPaymentAttemptStore attempts,
            JdbcPaymentTransactionStore transactions,
            OrderDirectory orders,
            PaymentCheckoutService checkout) {
        this.intents = intents;
        this.attempts = attempts;
        this.transactions = transactions;
        this.orders = orders;
        this.checkout = checkout;
    }

    @GetMapping
    @RequiresCapability(value = Capability.PAYMENT_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "One order's payment: its live intent, every attempt, and what has settled",
            description = "The order is read through OrderDirectory rather than the ordering "
                    + "module's own detail endpoint, so this stays a payments-side read: the "
                    + "public order number and total are enough to confirm this is the right "
                    + "order, without reaching into lines, notes or the encrypted fields that "
                    + "belong to a different capability. A null intent is not an error -- it is "
                    + "an order that was never checked out for payment, or one whose intent has "
                    + "gone terminal (cancelled, expired, failed) and is no longer the order's "
                    + "live obligation.")
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
                attemptRows,
                captured,
                returned));
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

        try {
            var session = checkout.openOrRePresent(tenantId, orderId, null, request);
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
     */
    public record RePresentationRequest(
            @Size(max = 24) String presentation,
            @Size(max = 2) String language,

            @Size(max = 12) @Pattern(regexp = "^998\\d{9}$", message = "must be 998 followed by nine digits")
            String pushRecipient) {

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
            List<PaymentAttemptResponse> attempts,
            @Nullable ApiMoney captured,
            @Nullable ApiMoney returned) {}

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
