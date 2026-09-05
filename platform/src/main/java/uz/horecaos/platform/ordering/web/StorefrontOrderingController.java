package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerOwned;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.ordering.application.CartPaymentOptions;
import uz.horecaos.platform.ordering.application.CartService;
import uz.horecaos.platform.ordering.application.CheckoutService;
import uz.horecaos.platform.ordering.application.OrderQueryService;
import uz.horecaos.platform.ordering.application.OrderStateService;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * The customer's side of ordering (ADR 0019).
 *
 * <p>Paths sit under {@code /api/v1/storefront/tenants/{tenantId}/brands/{brandId}}
 * rather than ADR 0019's shorter {@code /api/v1/storefront/carts}. The ADR's form
 * has nowhere to put the tenant, and the idempotency interceptor namespaces a key
 * by the path variables naming the resource — a storefront path without them
 * would let one key answer a request about another customer's order.
 * {@code StorefrontCatalogController} already sets this shape.
 *
 * <p>The customer account is never taken from the request body. It is resolved
 * from the caller's own verified token, because a client that could name an
 * account could read and place orders against somebody else's.
 *
 * <p>That resolved account scopes every cart operation as well as every order
 * one. A cart id is a UUID in a URL, and the tenant and brand that used to be its
 * only company are in the same URL — so scoping to them alone made the id a
 * bearer token for reading, editing, repricing and checking out a stranger's
 * basket.
 *
 * <p>That ownership check is the whole authorization decision here, and nothing
 * on these handlers declares an ADR 0025 capability. Capabilities are delegated
 * staff authority — a manager may refund because a grant says so — and a customer
 * buying lunch is not exercising delegated authority over anything. There is no
 * grant row per customer and there is not meant to be one, so
 * {@code ORDER_PLACE} here refused every caller these endpoints exist for. The
 * operator equivalents on {@code OperationsOrderController} keep theirs, because
 * an agent acting on somebody else's order is exactly what a capability is for.
 *
 * <p>Losing the declaration must not lose the ADR 0031 replay protection that
 * used to ride on {@code mutating = true}, so every mutating handler below
 * declares {@link Idempotent}. On the checkout path that is not bookkeeping: a
 * retried request that ran twice is a second order and a second charge.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}")
@Tag(name = "Storefront ordering", description = "Carts, checkout, and a customer's own orders")
public class StorefrontOrderingController {

    private final CartService carts;
    private final CheckoutService checkout;
    private final CartPaymentOptions paymentOptions;
    private final OrderQueryService orderQuery;
    private final OrderStateService orderState;
    private final CurrentCustomer currentCustomer;
    private final CurrentActor currentActor;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public StorefrontOrderingController(
            CartService carts,
            CheckoutService checkout,
            CartPaymentOptions paymentOptions,
            OrderQueryService orderQuery,
            OrderStateService orderState,
            CurrentCustomer currentCustomer,
            CurrentActor currentActor) {
        this.carts = carts;
        this.checkout = checkout;
        this.paymentOptions = paymentOptions;
        this.orderQuery = orderQuery;
        this.orderState = orderState;
        this.currentCustomer = currentCustomer;
        this.currentActor = currentActor;
    }

    @PostMapping("/carts")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Open a cart at one location",
            description = "The cart is bound to this location and channel for its whole life. "
                    + "Moving location rebuilds it, because catalog, availability, tax, fee and "
                    + "promise all change with the branch.")
    public ResponseEntity<CartResponse> createCart(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody CreateCartRequest body) {
        try {
            UUID accountId = accountId(tenantId, brandId);
            var cart = carts.create(
                    tenantId, brandId, body.locationId(), body.channel(), body.fulfillmentMode(), accountId, null);
            return ResponseEntity.ok(CartResponse.of(
                    carts.view(tenantId, brandId, accountId, cart.cartId()).orElseThrow()));
        } catch (CartService.CartRefusedException refused) {
            throw refusal(refused);
        }
    }

    @GetMapping("/carts/{cartId}")
    @CustomerOwned
    @Operation(
            summary = "Read a cart",
            description = "Scoped to the caller's own account. A cart id is not a bearer token: "
                    + "knowing one does not read the basket it belongs to.")
    public ResponseEntity<CartResponse> readCart(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID cartId) {
        var view = carts.view(tenantId, brandId, accountId(tenantId, brandId), cartId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such cart"));
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(view.cart().version()))
                .body(CartResponse.of(view));
    }

    @PutMapping("/carts/{cartId}/lines/{lineKey}")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Add or change a line",
            description = "Clears any attached quote in the same statement that bumps the cart "
                    + "version. A cart holding a price for contents it no longer has is the "
                    + "shortest path to charging for a basket nobody agreed to.")
    public ResponseEntity<CartResponse> putLine(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID cartId,
            @PathVariable String lineKey,
            @Valid @RequestBody PutLineRequest body,
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            long expected = AggregateVersion.requireIfMatch(request);
            var view = carts.putLine(
                    tenantId,
                    brandId,
                    accountId(tenantId, brandId),
                    cartId,
                    (int) expected,
                    lineKey,
                    body.variantId(),
                    body.quantity(),
                    body.modifierOptionIds(),
                    body.customerNote());
            return ResponseEntity.ok(CartResponse.of(view));
        } catch (CartService.StaleCartException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (CartService.CartRefusedException refused) {
            throw refusal(refused);
        }
    }

    @DeleteMapping("/carts/{cartId}/lines/{lineKey}")
    @CustomerOwned
    @Idempotent
    @Operation(summary = "Remove a line")
    public ResponseEntity<CartResponse> removeLine(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID cartId,
            @PathVariable String lineKey,
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            long expected = AggregateVersion.requireIfMatch(request);
            return ResponseEntity.ok(CartResponse.of(carts.removeLine(
                    tenantId, brandId, accountId(tenantId, brandId), cartId, (int) expected, lineKey)));
        } catch (CartService.StaleCartException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (CartService.CartRefusedException refused) {
            throw refusal(refused);
        }
    }

    @PostMapping("/carts/{cartId}/location")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Rebuild this cart at another location",
            description = "Returns a new cart with the same lines and no price. The old cart is "
                    + "abandoned: ADR 0019 never carries a cart across locations, because the "
                    + "prices shown would be prices that do not apply.")
    public ResponseEntity<CartResponse> moveLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID cartId,
            @Valid @RequestBody MoveLocationRequest body,
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            long expected = AggregateVersion.requireIfMatch(request);
            return ResponseEntity.ok(CartResponse.of(carts.rebuildAtLocation(
                    tenantId, brandId, accountId(tenantId, brandId), cartId, (int) expected, body.locationId())));
        } catch (CartService.StaleCartException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (CartService.CartRefusedException refused) {
            throw refusal(refused);
        }
    }

    @PutMapping("/carts/{cartId}/destination")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Say where a delivery cart is going",
            description = "Names one of the caller's own saved addresses, never an address typed "
                    + "into this request: ADR 0015 owns what an address is, including whether it "
                    + "has a coordinate and why. The chosen address is copied on to the cart, so "
                    + "editing or archiving it afterwards cannot change where an order in flight "
                    + "goes. Clears any attached quote, because ADR 0037 prices delivery from the "
                    + "destination and a basket priced to one door is not priced to another.")
    public ResponseEntity<CartResponse> setDestination(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID cartId,
            @Valid @RequestBody DestinationRequest body,
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            long expected = AggregateVersion.requireIfMatch(request);
            var view = carts.setDestination(
                    tenantId,
                    brandId,
                    accountId(tenantId, brandId),
                    cartId,
                    (int) expected,
                    new CartService.DestinationCommand(
                            body.addressId(), body.recipientName(), body.recipientPhone(), body.deliveryNote()));
            return ResponseEntity.ok(CartResponse.of(view));
        } catch (CartService.StaleCartException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (CartService.CartRefusedException refused) {
            throw refusal(refused);
        }
    }

    @GetMapping("/carts/{cartId}/destination")
    @CustomerOwned
    @Operation(
            summary = "Which saved address this cart is going to",
            description = "Answers with the caller's own address id and never with the address. "
                    + "The doorstep is personal data and is read from ADR 0015's own endpoint, "
                    + "where the decrypt is recorded against a purpose. A sub-resource rather "
                    + "than a field on the cart, so reading a basket stays one query and a cart "
                    + "response can never grow a field that is somebody's home.")
    public ResponseEntity<DestinationResponse> readDestination(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID cartId) {
        UUID accountId = accountId(tenantId, brandId);
        // Not found covers both "no such cart of yours" and "this cart has no
        // destination". A cart id must not become probeable through the answer to
        // a question about it.
        UUID addressId = carts.destinationAddressId(tenantId, brandId, accountId, cartId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "This cart has no destination"));
        return ResponseEntity.ok(new DestinationResponse(cartId, addressId));
    }

    @GetMapping("/carts/{cartId}/payment-methods")
    @CustomerOwned
    @Operation(
            summary = "What this cart may be paid with",
            description = "Only methods that would actually work: the channel offers them here, "
                    + "this build implements them, a customer may choose them, and a merchant "
                    + "account resolves for this branch today. A method whose provider binding "
                    + "is suspended is absent rather than listed and refused, because an "
                    + "unusable method offered to a customer is a checkout that fails at its "
                    + "last step. The codes map to customer wording in the storefront, the way "
                    + "a serviceability reason does.")
    public ResponseEntity<PaymentMethodsResponse> paymentMethods(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID cartId) {

        var options = paymentOptions
                .forCart(tenantId, brandId, accountId(tenantId, brandId), cartId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such cart"));
        return ResponseEntity.ok(PaymentMethodsResponse.of(options));
    }

    @PostMapping("/carts/{cartId}/pricing")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Price the cart and bind the quote to it",
            description = "Returns the total and a context hash valid for 15 minutes. Checkout "
                    + "accepts only this quote for this cart, so a client cannot present a quote "
                    + "priced for a different, cheaper basket.")
    public ResponseEntity<PricedCartResponse> price(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID cartId,
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            long expected = AggregateVersion.requireIfMatch(request);
            var priced = carts.price(tenantId, brandId, accountId(tenantId, brandId), cartId, (int) expected);
            return ResponseEntity.ok(new PricedCartResponse(
                    priced.cartId(),
                    priced.cartVersion(),
                    priced.quote().quoteId(),
                    priced.quote().contextHash(),
                    priced.quote().currency(),
                    priced.quote().subtotalMinor(),
                    priced.quote().taxMinor(),
                    priced.quote().discountMinor(),
                    priced.quote().totalMinor(),
                    priced.quote().expiresAt()));
        } catch (CartService.StaleCartException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (CartService.CartRefusedException refused) {
            throw refusal(refused);
        } catch (uz.horecaos.platform.pricing.api.CartPricingPort.PricingRefusedException unpriced) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    unpriced.getMessage(),
                    java.util.Map.of("reason", unpriced.code(), "subjectId", String.valueOf(unpriced.subjectId())));
        }
    }

    @PostMapping("/carts/{cartId}/promo-code")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Apply a promo code to the cart",
            description = "ADR 0072. Checked read-only against live coupon state before it is "
                    + "stored — active, in its window, and not exhausted — and checked again, "
                    + "independently, on every subsequent price. A cart already carrying a code "
                    + "has it replaced rather than refused: this platform supports at most one "
                    + "applied code per cart. Clears any attached quote, because a code changes "
                    + "what the total will be.")
    public ResponseEntity<CartResponse> applyPromoCode(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID cartId,
            @Valid @RequestBody ApplyPromoCodeRequest body,
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            long expected = AggregateVersion.requireIfMatch(request);
            var view = carts.applyPromoCode(
                    tenantId, brandId, accountId(tenantId, brandId), cartId, (int) expected, body.code());
            return ResponseEntity.ok(CartResponse.of(view));
        } catch (CartService.StaleCartException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (CartService.CartRefusedException refused) {
            throw refusal(refused);
        }
    }

    @DeleteMapping("/carts/{cartId}/promo-code")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Remove the cart's applied promo code",
            description =
                    "Does nothing when none is applied. Clears any attached quote, the same " + "as applying one does.")
    public ResponseEntity<CartResponse> removePromoCode(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID cartId,
            jakarta.servlet.http.HttpServletRequest request) {
        try {
            long expected = AggregateVersion.requireIfMatch(request);
            var view = carts.removePromoCode(tenantId, brandId, accountId(tenantId, brandId), cartId, (int) expected);
            return ResponseEntity.ok(CartResponse.of(view));
        } catch (CartService.StaleCartException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (CartService.CartRefusedException refused) {
            throw refusal(refused);
        }
    }

    @PostMapping("/checkouts")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Turn a priced cart into an order",
            description = "Idempotent under the tenant-scoped Idempotency-Key. Repeating the "
                    + "request returns the same order; a settled business rejection returns the "
                    + "same rejection rather than running again against a changed cart.")
    public ResponseEntity<CheckoutResponse> checkout(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody CheckoutRequest body) {

        // Checkout takes the cart by id and scopes it to the tenant and the brand,
        // both of which are in the path the caller controls. Reading the cart as
        // its owner first is what stops a customer paying for — and taking
        // delivery of — a basket somebody else assembled: the read answers empty
        // for a cart that is not theirs, and a 404 rather than a 403 so the id
        // cannot be probed. The check belongs inside CheckoutService's own
        // transaction and is here because the command has nowhere to carry an
        // owner yet.
        UUID accountId = accountId(tenantId, brandId);
        if (carts.view(tenantId, brandId, accountId, body.cartId()).isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such cart");
        }

        var result = checkout.checkout(new CheckoutService.CheckoutCommand(
                tenantId,
                brandId,
                body.cartId(),
                body.cartVersion(),
                body.quoteId(),
                body.contextHash(),
                idempotencyKey,
                body.paymentMethodCode(),
                body.redeemFromBalanceMinor() == null ? 0L : body.redeemFromBalanceMinor(),
                "CUSTOMER",
                currentActor.get().subject(),
                null));

        if (result.outcome() == CheckoutService.CheckoutResult.Outcome.REJECTED) {
            // A settled business answer, and a conflict rather than a fault: the
            // request was well-formed and the world moved underneath it. A
            // rejected result always carries its code and detail (or falls back to
            // the code); NullAway cannot see that outcome-conditioned guarantee.
            String rejectionCode = Objects.requireNonNull(result.rejectionCode(), "a rejection always names a code");
            throw new ApiException(
                    errorCodeFor(rejectionCode),
                    result.rejectionDetail() == null ? rejectionCode : result.rejectionDetail(),
                    java.util.Map.of(
                            "reason", rejectionCode,
                            "unavailableItems", result.unavailableItems(),
                            "warnings", result.warnings()));
        }
        // CREATED and REPLAYED both name a real order; only REJECTED, handled
        // above, leaves these null.
        return ResponseEntity.ok(new CheckoutResponse(
                Objects.requireNonNull(result.orderId(), "a non-rejected checkout always names an order"),
                Objects.requireNonNull(result.publicOrderNumber(), "a non-rejected checkout always names an order"),
                Objects.requireNonNull(result.status(), "a non-rejected checkout always has a status")
                        .name(),
                result.orderVersion(),
                result.outcome().name(),
                result.warnings()));
    }

    @GetMapping("/orders/{orderId}")
    @CustomerOwned
    @Operation(
            summary = "Read one of the caller's own orders",
            description = "Scoped to the caller's account inside the query. A customer cannot "
                    + "read another customer's order by knowing its id.")
    public ResponseEntity<OrderResponse> readOrder(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID orderId) {
        UUID accountId = accountId(tenantId, brandId);
        var detail = orderQuery
                .detailForCustomer(tenantId, orderId, accountId, null)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(detail.order().version()))
                .body(OrderResponse.of(detail));
    }

    @GetMapping("/orders")
    @CustomerOwned
    @Operation(
            summary = "The caller's own orders at this brand, newest first",
            description = "Cursor-paginated per ADR 0031: pass the previous page's nextCursor, "
                    + "and a null nextCursor is the end. Every row is the caller's own — the "
                    + "account is a predicate of the query and there is no parameter that widens "
                    + "it — so this enumerates a customer's history and never a brand's. Carries "
                    + "what a list shows and nothing beneath it: no lines, no modifiers, no "
                    + "notes, no destination. Open one order to read those.")
    public Page<OrderSummaryResponse> listOrders(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) @Schema(description = "The nextCursor of the previous page") UUID cursor,
            @RequestParam(required = false) Integer limit) {

        UUID accountId = accountId(tenantId, brandId);
        int pageSize = Page.limitOrDefault(limit);

        List<JdbcOrderStore.CustomerOrderRow> rows;
        try {
            rows = orderQuery.forCustomer(tenantId, brandId, accountId, cursor, pageSize);
        } catch (OrderQueryService.UnknownCursorException unusable) {
            // A cursor naming somebody else's order and a cursor naming nothing at
            // all answer identically, and both are the caller's mistake rather than
            // a missing resource: nothing was asked for by name. Answering
            // not-found for the second and something else for the first would make
            // the cursor a probe for whether an order id exists.
            throw new ApiException(ErrorCode.INVALID_REQUEST, unusable.getMessage());
        }

        // A short page is the end of the collection. A full one may or may not be,
        // and answering "maybe" with a cursor costs the caller one empty request,
        // where answering "no" wrongly loses them every order after this page.
        String nextCursor = rows.size() < pageSize
                ? null
                : rows.get(rows.size() - 1).orderId().toString();

        return new Page<>(rows.stream().map(OrderSummaryResponse::of).toList(), nextCursor);
    }

    @PostMapping("/orders/{orderId}/cancellations")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Cancel an order before it is confirmed",
            description = "Refused once the order is confirmed: the payment, fiscal, POS and "
                    + "fulfilment consequences of that are owned by ADR 0039, and half-performing "
                    + "them would be worse than refusing.")
    public ResponseEntity<OrderStateResponse> cancel(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID orderId,
            @Valid @RequestBody CancelRequest body,
            jakarta.servlet.http.HttpServletRequest request) {

        UUID accountId = accountId(tenantId, brandId);
        if (orderQuery.detailForCustomer(tenantId, orderId, accountId, null).isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order");
        }

        try {
            long expected = AggregateVersion.requireIfMatch(request);
            var result = orderState.cancel(
                    tenantId,
                    orderId,
                    (int) expected,
                    body.reasonCode(),
                    "CUSTOMER",
                    currentActor.get().subject(),
                    null);
            return ResponseEntity.ok(
                    new OrderStateResponse(orderId, result.status().name(), result.orderVersion(), result.applied()));
        } catch (OrderStateService.StaleOrderException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        } catch (OrderStateService.CancellationNotPermittedException refused) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, refused.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The caller's account, from their own token.
     *
     * <p>Not found rather than forbidden when the principal has no account here.
     * The two are the same fact to a caller who is entitled to nothing, and only
     * the forbidden answer tells somebody probing a brand id that the brand is
     * real.
     *
     * <p>Guest carts are accepted by the model and not by this controller: ADR
     * 0015's guest claim is outside the first cutover slice, and a guest reference
     * this endpoint invented would have no path to becoming an account later.
     */
    private UUID accountId(UUID tenantId, UUID brandId) {
        return currentCustomer
                .account(tenantId, brandId)
                .map(CustomerAccountRef::accountId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This principal has no customer account for this brand"));
    }

    private static ApiException refusal(CartService.CartRefusedException refused) {
        ErrorCode code =
                switch (refused.code()) {
                    case "CART_NOT_FOUND", "LINE_NOT_FOUND", "TENANT_NOT_FOUND", "CHANNEL_NOT_REGISTERED" ->
                        ErrorCode.RESOURCE_NOT_FOUND;
                    case "ADDRESS_NOT_FOUND", "CODE_NOT_FOUND" -> ErrorCode.RESOURCE_NOT_FOUND;
                    case "CART_NOT_EDITABLE",
                            "CART_EXPIRED",
                            "NOT_SERVICEABLE",
                            "CHANNEL_NOT_SELLABLE",
                            "GUEST_ORDERS_NOT_ALLOWED",
                            "CUSTOMER_BLACKLISTED",
                            "DESTINATION_NOT_APPLICABLE",
                            "DESTINATION_NOT_LOCATED",
                            // ADR 0072: a well-formed request against a code whose current
                            // state refuses it — nothing in the body is wrong, the coupon is
                            // just not usable right now, the same class of answer
                            // CUSTOMER_BLACKLISTED and NOT_SERVICEABLE already give.
                            "CODE_NOT_ACTIVE",
                            "CODE_NOT_YET_ACTIVE",
                            "CODE_EXPIRED",
                            "REDEMPTION_LIMIT_REACHED",
                            "PER_CUSTOMER_LIMIT_REACHED" -> ErrorCode.RESOURCE_CONFLICT;
                    default -> ErrorCode.VALIDATION_FAILED;
                };
        return new ApiException(code, refused.getMessage(), java.util.Map.of("reason", refused.code()));
    }

    private static ErrorCode errorCodeFor(String rejectionCode) {
        return switch (rejectionCode) {
            case "PRICE_CHANGED" -> ErrorCode.PRICE_CHANGED;
            case "IDEMPOTENCY_KEY_REUSED" -> ErrorCode.IDEMPOTENCY_KEY_REUSED;
            case "CART_VERSION_STALE" -> ErrorCode.STALE_VERSION;
            case "CART_NOT_FOUND", "QUOTE_NOT_FOUND" -> ErrorCode.RESOURCE_NOT_FOUND;
            // A well-formed request against a cart that has not been told where it
            // is going. A conflict rather than a validation failure: nothing in the
            // body is wrong, a step is missing.
            case "DELIVERY_DESTINATION_REQUIRED" -> ErrorCode.RESOURCE_CONFLICT;
            // A well-formed request against an account this checkout will never
            // accept. A conflict for the same reason GUEST_ORDERS_NOT_ALLOWED and
            // NOT_SERVICEABLE are (below, by way of the default): nothing in the
            // request is wrong, the account's own standing is what refuses it, and
            // that standing can change (a lift, an expiry) without the caller
            // changing anything.
            case "CUSTOMER_BLACKLISTED" -> ErrorCode.RESOURCE_CONFLICT;
            // The body asked for something the request itself forbids — a
            // redemption on a checkout with no account behind it, or one that would
            // leave the order with no money tender. Nothing moved underneath the
            // caller, so a conflict would be the wrong word.
            // Same shape: the body is missing something the request needs, and
            // nothing moved underneath the caller. Unreachable through this
            // controller — @NotBlank refuses it at binding — and mapped anyway,
            // because the service's refusal is the one that must hold for every
            // surface and a default of CONFLICT would misdescribe it.
            case "GUEST_CANNOT_REDEEM", "REDEMPTION_INVALID", "REDEMPTION_EXCEEDS_ORDER", "PAYMENT_METHOD_REQUIRED" ->
                ErrorCode.VALIDATION_FAILED;
            default -> ErrorCode.RESOURCE_CONFLICT;
        };
    }

    // ------------------------------------------------------------------ payloads

    public record CreateCartRequest(
            @NotNull UUID locationId,
            @NotBlank @Size(max = 32) String channel,
            @NotNull FulfillmentMode fulfillmentMode) {}

    public record PutLineRequest(
            @NotNull UUID variantId,
            @Positive @Max(999) int quantity,
            @Size(max = 20) List<UUID> modifierOptionIds,
            @Size(max = 500) String customerNote) {}

    public record MoveLocationRequest(@NotNull UUID locationId) {}

    /** ADR 0072. Normalized (trimmed, upper-cased) by {@code CartService} before lookup. */
    public record ApplyPromoCodeRequest(
            @NotBlank @Size(min = 1, max = 32) String code) {}

    /**
     * Where a delivery order goes.
     *
     * <p>{@code addressId} rather than an address. A customer names one of their
     * own saved addresses and the server copies it; there is deliberately no field
     * here for a street, a flat or a coordinate, because ADR 0015 owns what an
     * address is — including V0021's record of why a point is present or absent —
     * and a second writer of that concept in ordering would drift from it and
     * would put a home address outside the erasure and geocoding paths that run
     * over {@code customer.addresses}.
     *
     * <p>The recipient is asked for rather than inferred from the account: an
     * order is often for somebody else, and a courier who cannot telephone the
     * person at the door does not deliver.
     *
     * @param deliveryNote this order's note for the courier, or absent to keep the
     *                     standing instruction saved with the address
     */
    public record DestinationRequest(
            @NotNull UUID addressId,
            @NotBlank @Size(max = 120) String recipientName,
            @NotBlank @Size(max = 32) String recipientPhone,
            @Size(max = 500) String deliveryNote) {

        /**
         * Prints the address id and nothing personal.
         *
         * <p>A request record's generated {@code toString} is one binding failure
         * away from a log line carrying a customer's name and telephone number.
         */
        @Override
        public String toString() {
            return "DestinationRequest[address=%s]".formatted(addressId);
        }
    }

    /**
     * A request to check out one cart.
     *
     * @param cartVersion ADR 0031's expected version, so a checkout cannot be
     *                    built on a basket edited on another device
     * @param paymentMethodCode how this order will be paid (ADR 0013, ADR 0046).
     *                          <strong>Required.</strong> It was optional, and the
     *                          consequence was not that the order went unpaid — it
     *                          was that step 7b, gated on the field being present,
     *                          planned no settlement, and the checkout went on to
     *                          create a real, confirmable, completable order with
     *                          no tenders behind it. Every refund, delivery-fee
     *                          reimbursement and courier cash figure on such an
     *                          order answers "the order has no settlement", and
     *                          the operator finds out with a customer on the
     *                          phone. A storefront customer always chooses how
     *                          they are paying, so there is no case here to keep
     *                          the field optional for; an order that is placed
     *                          through another surface and genuinely has no
     *                          tender needs a settlement shape of its own before
     *                          it may exist, not silence
     * @param redeemFromBalanceMinor how much of the total to settle from the
     *                               caller's points balance, in whole som (ADR 0018,
     *                               ADR 0046). Absent and zero mean the same thing.
     *                               Never the whole total: an order settles at least
     *                               partly with money, and a request that would not
     *                               is refused rather than trimmed
     */
    public record CheckoutRequest(
            @NotNull UUID cartId,
            @Positive int cartVersion,
            @NotNull UUID quoteId,
            @NotBlank @Size(max = 64) String contextHash,
            @NotBlank @Size(max = 32) String paymentMethodCode,
            @PositiveOrZero Long redeemFromBalanceMinor) {}

    public record CancelRequest(@NotBlank @Size(max = 64) String reasonCode) {}

    /**
     * The cart as it stands right now.
     *
     * @param fulfillmentMode how the order leaves the branch. On the response
     *        because it is the server's fact and the client was guessing it: a
     *        cart is bound to its mode for its whole life, and a client that
     *        reopens a remembered basket had no way to learn which mode it was
     *        bound to. It defaulted to DELIVERY, so a customer who chose pickup
     *        and closed the app came back to a basket the client believed was a
     *        delivery, asked them for an address, and was refused
     *        DESTINATION_NOT_APPLICABLE by this very controller.
     */
    public record CartResponse(
            UUID cartId,
            UUID locationId,
            String status,
            String currency,
            String fulfillmentMode,
            int version,
            @Nullable UUID quoteId,
            @Nullable String contextHash,
            Instant expiresAt,
            List<CartLineResponse> lines,
            @Nullable String appliedPromoCode) {

        // `currency` then `fulfillmentMode`, in that order, and it is worth
        // saying why a line this dull carries a comment. The two arguments were
        // the wrong way round: every cart this platform has ever returned
        // answered `"currency": "DELIVERY", "fulfillmentMode": "UZS"`. Both
        // components are `String`, so nothing in the compiler, the schema or the
        // suite had anything to object to — a positional constructor call is
        // exactly where two same-typed neighbours can trade places in silence.
        // The storefront reads `currency` to format money and `fulfillmentMode`
        // to decide whether to ask for an address, so it got neither.
        static CartResponse of(CartService.CartView view) {
            return new CartResponse(
                    view.cart().cartId(),
                    view.cart().locationId(),
                    view.cart().status().name(),
                    view.cart().currency(),
                    view.cart().fulfillmentMode().name(),
                    view.cart().version(),
                    view.cart().pricingQuoteId(),
                    view.cart().pricingContextHash(),
                    view.cart().expiresAt(),
                    view.lines().stream()
                            .map(line -> new CartLineResponse(
                                    line.lineKey(),
                                    line.variantId(),
                                    line.quantity(),
                                    line.customerNoteEncrypted() != null))
                            .toList(),
                    view.cart().appliedCouponCode());
        }
    }

    /**
     * One cart line.
     *
     * @param hasCustomerNote whether a note exists, never the note itself. The
     *                        text is personal data and is revealed only through
     *                        the endpoint that records a purpose for it
     */
    public record CartLineResponse(String lineKey, UUID variantId, int quantity, boolean hasCustomerNote) {}

    /**
     * The destination just set on this cart.
     *
     * @param addressId the customer's own saved address id, never the address
     */
    public record DestinationResponse(UUID cartId, UUID addressId) {}

    /**
     * The methods this cart may be paid with.
     *
     * @param methodCodes the offerable codes only. There is deliberately no
     *                    unavailable list and no per-method reason beside it: a
     *                    client that has to filter is a client that will one day
     *                    forget to, and the place that surfaces is a customer at
     *                    the last step of a checkout
     * @param warnings    platform gaps that apply to this answer. An assembly with
     *                    no payments module cannot check a merchant account, so the
     *                    list degrades to the channel's matrix — and says so here
     *                    rather than silently
     */
    public record PaymentMethodsResponse(
            UUID cartId,
            UUID locationId,
            UUID channelId,
            String fulfillmentMode,
            String currency,
            List<String> methodCodes,
            List<String> warnings) {

        static PaymentMethodsResponse of(CartPaymentOptions.PaymentOptions options) {
            return new PaymentMethodsResponse(
                    options.cartId(),
                    options.locationId(),
                    options.channelId(),
                    options.fulfillmentMode().name(),
                    options.currency(),
                    options.methodCodes(),
                    options.warnings());
        }
    }

    public record PricedCartResponse(
            UUID cartId,
            int cartVersion,
            UUID quoteId,
            String contextHash,
            String currency,
            long subtotalMinor,
            long taxMinor,
            long discountMinor,
            long totalMinor,
            Instant expiresAt) {}

    /**
     * The result of a checkout attempt.
     *
     * @param warnings platform gaps that apply to this order, such as an unwired payments port
     */
    public record CheckoutResponse(
            UUID orderId,
            String publicOrderNumber,
            String status,
            int version,
            String outcome,
            List<String> warnings) {}

    public record OrderResponse(
            UUID orderId,
            String publicOrderNumber,
            String status,
            String currency,
            long subtotalMinor,
            long taxMinor,
            long totalMinor,
            int version,
            Instant createdAt,
            @Nullable Instant confirmedAt,
            List<OrderLineResponse> lines,
            List<String> warnings) {

        static OrderResponse of(OrderQueryService.OrderDetail detail) {
            var order = detail.order();
            return new OrderResponse(
                    order.orderId(),
                    order.publicOrderNumber(),
                    order.status().name(),
                    order.currency(),
                    order.subtotalMinor(),
                    order.taxMinor(),
                    order.totalMinor(),
                    order.version(),
                    order.createdAt(),
                    order.confirmedAt(),
                    detail.lines().stream()
                            .map(line -> new OrderLineResponse(
                                    line.line().lineNumber(),
                                    line.line().productName(),
                                    line.line().variantName(),
                                    line.line().quantity(),
                                    line.line().unitAmountMinor(),
                                    line.line().finalAmountMinor(),
                                    line.modifiers().stream()
                                            .map(m -> m.optionName())
                                            .toList()))
                            .toList(),
                    detail.warnings());
        }
    }

    public record OrderLineResponse(
            int lineNumber,
            String productName,
            String variantName,
            int quantity,
            long unitAmountMinor,
            long finalAmountMinor,
            List<String> modifiers) {}

    /**
     * One row of the caller's own order history.
     *
     * <p>Not an {@link OrderResponse} without its lines. This is its own shape
     * because the two answer different questions and will drift: a list shows what
     * a customer needs to recognise an order and decide whether to open it — the
     * number they say out loud, the state, the money, when, and where from.
     *
     * <p>What is absent is the point. No lines and therefore no line notes, which
     * are ADR 0029 personal data with their own purposed reveal. No destination,
     * which is a doorstep. No acceptance policy, approval deadline or actor id,
     * which describe how the restaurant decides rather than what the customer
     * bought.
     *
     * @param totalMinor whole som for UZS (ADR 0018). Not divided by anything: a
     *                   formatter that asks ISO 4217 how many decimal places UZS
     *                   has shows a price a hundred times too small
     * @param version    echoed so a client can present it as an {@code If-Match}
     *                   when it cancels, without a second read
     */
    public record OrderSummaryResponse(
            UUID orderId,
            String publicOrderNumber,
            UUID locationId,
            String fulfillmentMode,
            String status,
            String paymentStatus,
            String fulfillmentStatus,
            String currency,
            long totalMinor,
            @Nullable Instant promisedAt,
            int version,
            Instant placedAt) {

        static OrderSummaryResponse of(JdbcOrderStore.CustomerOrderRow row) {
            return new OrderSummaryResponse(
                    row.orderId(),
                    row.publicOrderNumber(),
                    row.locationId(),
                    row.fulfillmentMode().name(),
                    row.status().name(),
                    row.paymentStatusProjection(),
                    row.fulfillmentStatusProjection(),
                    row.currency(),
                    row.totalMinor(),
                    row.promisedAt(),
                    row.version(),
                    row.createdAt());
        }
    }

    public record OrderStateResponse(UUID orderId, String status, int version, boolean applied) {

        static OrderStateResponse of(UUID orderId, OrderStatus status, int version) {
            return new OrderStateResponse(orderId, status.name(), version, true);
        }
    }
}
