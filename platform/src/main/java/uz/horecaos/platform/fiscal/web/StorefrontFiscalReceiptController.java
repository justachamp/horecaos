package uz.horecaos.platform.fiscal.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerOwned;
import uz.horecaos.platform.fiscal.application.FiscalEvidenceService;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The receipt links on a customer's own order page (ADR 0166, ADR 0058).
 *
 * <p>The customer is sent the link to the tax authority's page when a receipt is issued, as a
 * message their own preference cannot switch off, because a receipt is a legal artifact and not a
 * courtesy. A customer who has lost that message, or who opens the order a week later, should find
 * the same link where they look for everything else about the order, and this is the read that
 * serves it.
 *
 * <p>It answers the link, the kind of document and when it was registered, and nothing a staff
 * member would reveal for a stated purpose: no fiscal sign, no terminal, no INN, no lines and no
 * tender split. The ownership check is the authorization decision and no ADR 0025 capability is
 * declared, for the reason {@code StorefrontPaymentController} gives: a customer holds no staff
 * capability and is not meant to. The order must be the caller's own account's, at the brand in
 * the path, and an order with no account is nobody's (a phone order has none), so a stranger and a
 * missing order are answered with the same words.
 *
 * <p>Never cached: the link is the customer's and must not outlive their session in a shared proxy
 * or a browser cache.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}")
@Tag(name = "Storefront receipts", description = "The links to the receipts filed for a customer's own order")
public class StorefrontFiscalReceiptController {

    private final FiscalEvidenceService evidence;
    private final OrderDirectory orders;
    private final CurrentCustomer currentCustomer;

    public StorefrontFiscalReceiptController(
            FiscalEvidenceService evidence, OrderDirectory orders, CurrentCustomer currentCustomer) {
        this.evidence = evidence;
        this.orders = orders;
        this.currentCustomer = currentCustomer;
    }

    @GetMapping("/orders/{orderId}/receipts")
    @CustomerOwned
    @Operation(
            summary = "The links to the receipts filed for one of the caller's own orders",
            description = "A list, possibly empty: an order paid in cash has no receipt from a payment "
                    + "provider, and a card payment has none until the provider reports it. A Payme "
                    + "refund is a second receipt beside the first, and a split payment can settle on "
                    + "two paths, so the answer is always a list. Only receipts still in force are "
                    + "listed (a receipt that has been corrected or voided is replaced by the document "
                    + "that corrected it), and only an https link. The link is the one the customer was "
                    + "sent when the receipt was issued; nothing else about the receipt is returned, "
                    + "and the customer's account is a predicate of the check, so another customer's "
                    + "order and a missing one are both 404.")
    public ResponseEntity<ReceiptsResponse> receipts(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID orderId) {

        UUID accountId = accountId(tenantId, brandId);
        requireOwnOrder(tenantId, brandId, orderId, accountId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ReceiptsResponse(evidence.customerReceipts(tenantId, orderId).stream()
                        .map(ReceiptResponse::of)
                        .toList()));
    }

    /** The caller's account, from their own token and never from the request. */
    private UUID accountId(UUID tenantId, UUID brandId) {
        return currentCustomer
                .account(tenantId, brandId)
                .map(CustomerAccountRef::accountId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This principal has no customer account for this brand"));
    }

    /**
     * The order must be this customer's and the brand in the path its own. Not found rather than
     * forbidden, in the same words for every failure: a refusal that told "not yours" from "does not
     * exist" would confirm an order id to whoever guessed it.
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

    /**
     * @param kind {@code SALE}, {@code REFUND} or {@code CORRECTION}
     * @param url the tax authority's page for this receipt, an https link
     * @param registeredAt when the receipt was registered, when the provider said
     */
    public record ReceiptResponse(
            String kind, String url, @Nullable Instant registeredAt) {

        static ReceiptResponse of(FiscalEvidenceService.CustomerReceipt receipt) {
            return new ReceiptResponse(receipt.documentType(), receipt.receiptUrl(), receipt.registeredAt());
        }
    }

    public record ReceiptsResponse(List<ReceiptResponse> receipts) {}
}
