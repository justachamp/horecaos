package uz.horecaos.platform.payments.web;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerOwned;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.payments.application.PaymentCheckoutService;
import uz.horecaos.platform.payments.domain.PresentationFailure;
import uz.horecaos.platform.payments.domain.PresentationKind;
import uz.horecaos.platform.payments.domain.PresentationRequest;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * The customer's side of paying for an order (ADR 0013, ADR 0031).
 *
 * <p>One endpoint, and it is the one thing that was missing between checkout and
 * both inbound provider surfaces: it opens the attempt whose
 * {@code merchant_trans_id} Click's callback will name and Payme's
 * {@code account.order_id} will carry, and it hands back the link that gets the
 * customer to a payment page. Without it a payment-first order waited in
 * {@code PAYMENT_AUTHORIZING} for a callback that could not arrive.
 *
 * <p>Under the same path shape as {@code StorefrontOrderingController}, because
 * the idempotency interceptor namespaces a key by the path variables naming the
 * resource, and a storefront path without a tenant and a brand is one where a
 * customer's key could be answered with another customer's payment link.
 *
 * <p>Unlike the two provider-facing controllers in this module, this one is
 * ordinary ADR 0031: an {@code Idempotency-Key}, a JSON body, and Problem Details
 * on refusal. The provider endpoints are exempt because the wire format is
 * Click's and Payme's; this one's caller is HorecaOS's own storefront.
 *
 * <p>The customer account is never taken from the request body. It comes from the
 * caller's own verified token, and the order must be that account's — because a
 * client that could name an account could produce payment links for somebody
 * else's orders.
 *
 * <p>That ownership check is the authorization decision, and there is no ADR 0025
 * capability declared. {@code PAYMENT_INITIATE} is delegated staff authority over
 * somebody else's payment; no customer principal holds it or is meant to, so
 * declaring it here refused every customer this endpoint exists for. What must
 * not go with it is the {@code Idempotency-Key}, which used to be implied by
 * {@code mutating = true} — a payment endpoint without one turns a double-tapped
 * button into a second attempt against the same order. {@link Idempotent} carries
 * it now.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}")
@Tag(name = "Storefront payments",
        description = "Opening a payment attempt and getting the checkout surface for it")
public class StorefrontPaymentController {

    private final PaymentCheckoutService checkout;
    private final OrderDirectory orders;
    private final CurrentCustomer currentCustomer;

    public StorefrontPaymentController(PaymentCheckoutService checkout, OrderDirectory orders,
            CurrentCustomer currentCustomer) {
        this.checkout = checkout;
        this.orders = orders;
        this.currentCustomer = currentCustomer;
    }

    @PostMapping("/orders/{orderId}/payment-sessions")
    @CustomerOwned
    @Idempotent
    @Operation(summary = "Open a payment attempt and get its checkout surface",
            description = "Returns a Click payment link or a Payme base64 checkout URL, and the "
                    + "attempt identity the provider's callback will carry. A customer who "
                    + "abandons the checkout and comes back is given the same attempt: a second "
                    + "one is refused by a unique index, because two payable links against one "
                    + "order is a double charge waiting for somebody to open both. Nothing here "
                    + "credits anything — both surfaces are unsigned, so the amount the platform "
                    + "enforces is the one it checks when the provider calls back.")
    public ResponseEntity<PaymentSessionResponse> openPaymentSession(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody PaymentSessionRequest body) {

        PresentationRequest request;
        try {
            request = body.toDomain();
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, malformed.getMessage());
        }

        UUID accountId = accountId(tenantId, brandId);
        requireOwnOrder(tenantId, brandId, orderId, accountId);

        try {
            var session = checkout.openOrRePresent(tenantId, orderId, accountId, request);
            return ResponseEntity.ok(PaymentSessionResponse.of(session));

        } catch (PaymentCheckoutService.CheckoutRefusedException refused) {
            throw new ApiException(errorCodeFor(refused.code()), refused.getMessage(),
                    Map.of("reason", refused.code()));

        } catch (PresentationFailure.Uncertain lost) {
            // ADR 0031 requires uncertain to be a response the caller handles and
            // not an error it retries. A mutating provider call was made and its
            // answer is gone; the attempt now carries a resolver and a deadline, and
            // a second call would be a second charge with nothing to key it on.
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "The payment provider did not answer and the outcome is unknown. The "
                            + "platform is resolving it; do not present this order again.",
                    Map.of("reason", "PAYMENT_OUTCOME_UNCERTAIN",
                            "failureCode", lost.failureCode()));

        } catch (PresentationFailure.Refused refused) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, refused.getMessage(),
                    Map.of("reason", refused.failureCode()));
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The caller's account, from their own token.
     *
     * <p>Same shape as the storefront ordering controller's, and for the same
     * reason: a guest reference this endpoint invented would have no path to
     * becoming an account, and ADR 0015's guest claim is outside this slice.
     */
    private UUID accountId(UUID tenantId, UUID brandId) {
        return currentCustomer.account(tenantId, brandId)
                .map(CustomerAccountRef::accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "This principal has no customer account for this brand"));
    }

    /**
     * The order must be this customer's, and the brand in the path must be its own.
     *
     * <p>{@code PaymentCheckoutService} compares the two accounts only when the
     * order has one, which is right for its other caller — an operator settling a
     * counter order acts on an order with no customer account at all. On this
     * surface that same rule is a hole: an order placed by a phone agent has a
     * null account, so "no account to disagree with" would let any signed-in
     * customer of the brand open a payment link against a stranger's ticket. Here
     * the caller must be the order's own account, and an order with none is
     * nobody's.
     *
     * <p>The brand is checked for the same reason the ordering controller checks
     * it: the path names one, the order knows which one it belongs to, and letting
     * them differ would make a brand a customer holds an account at a lens onto
     * every other brand of the tenant.
     *
     * <p>Not found rather than forbidden, and the same words for every failure. A
     * refusal that distinguishes "not yours" from "does not exist" confirms the
     * order id to whoever guessed it.
     */
    private void requireOwnOrder(UUID tenantId, UUID brandId, UUID orderId, UUID accountId) {
        boolean own = orders.summary(tenantId, orderId)
                .filter(order -> brandId.equals(order.brandId()))
                .filter(order -> accountId.equals(order.customerAccountId()))
                .isPresent();
        if (!own) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order");
        }
    }

    private static ErrorCode errorCodeFor(String code) {
        return switch (code) {
            case "ORDER_NOT_FOUND", "NO_PAYMENT_INTENT" -> ErrorCode.RESOURCE_NOT_FOUND;
            // Everything else is a well-formed request against a world that says no:
            // already paid, no merchant account, an outcome in doubt. A conflict,
            // never a validation failure, because nothing about the request is wrong.
            default -> ErrorCode.RESOURCE_CONFLICT;
        };
    }

    // ------------------------------------------------------------------ payloads

    /**
     * @param presentation  which surface to produce. Defaults to a payment link,
     *                      which is the only one both providers can build
     * @param returnUrl     where Click sends the browser afterwards. Honoured on
     *                      Click only; Payme takes it from the {@code Referer},
     *                      because putting it in the base64 payload changes the
     *                      encoding in a way the provider docs do not settle.
     *                      Either way the return is a browser event that proves
     *                      nothing — only the provider's callback does
     * @param pushRecipient the phone an invoice is pushed to, on Click only.
     *                      Personal data under ADR 0029: it is passed into the
     *                      provider call and is never stored on the attempt, never
     *                      logged, and never published in an event. It is the number
     *                      the customer typed for this payment rather than one the
     *                      platform revealed from its own encrypted record
     */
    public record PaymentSessionRequest(
            @Size(max = 24) String presentation,
            @Size(max = 512) @Pattern(regexp = "^https://.*",
                    message = "a return URL must be https") String returnUrl,
            @Size(max = 2) String language,
            @Size(max = 12) String pushRecipient) {

        PresentationRequest toDomain() {
            PresentationKind kind = presentation == null || presentation.isBlank()
                    ? PresentationKind.PAYMENT_LINK
                    : PresentationKind.valueOf(presentation.strip().toUpperCase(
                            java.util.Locale.ROOT));
            return new PresentationRequest(kind, returnUrl, language, pushRecipient);
        }
    }

    /**
     * @param merchantTransId the identifier the provider's callback will carry —
     *                        Click's {@code merchant_trans_id}, Payme's
     *                        {@code account.order_id}. Opaque and non-sequential,
     *                        so it reveals nothing and cannot be walked
     * @param checkoutUrl     where to send the browser, or null for a push
     * @param amountMinor     whole som (ADR 0018). Not divided by anything: a
     *                        formatter that asks ISO 4217 how many decimal places
     *                        UZS has divides by 100 and shows a customer a price a
     *                        hundred times too small
     */
    public record PaymentSessionResponse(
            UUID attemptId,
            String merchantTransId,
            String provider,
            String presentation,
            String checkoutUrl,
            String qrPayload,
            Instant expiresAt,
            long amountMinor,
            String currency,
            boolean rePresented,
            int presentationCount) {

        static PaymentSessionResponse of(PaymentCheckoutService.PaymentSession session) {
            return new PaymentSessionResponse(
                    session.attemptId(), session.merchantTransId(),
                    session.providerType().name(), session.presentationKind().name(),
                    session.checkoutUrl(), session.qrPayload(), session.expiresAt(),
                    session.amountMinor(), session.currency(),
                    session.rePresented(), session.presentationCount());
        }
    }
}
