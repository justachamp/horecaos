package uz.horecaos.platform.loyalty.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
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
import uz.horecaos.platform.loyalty.application.LoyaltyQueryService;
import uz.horecaos.platform.loyalty.application.LoyaltyQueryService.BalanceView;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A customer reads their own points balance (ADR 0046, ADR 0185).
 *
 * <p>The staff balance reads under {@code /api/v1/storefront/loyalty} need {@code LOYALTY_READ}, a capability no
 * customer principal holds, so until this there was no customer-facing balance at all. This one is authorised by
 * ownership, the pattern the receipts read uses: the account is found from the caller's own token and the brand
 * in the path, never from an identifier in the request, so a customer cannot read a balance by naming it, and a
 * brand they hold no points at answers an empty balance rather than a 404 (they have simply earned nothing).
 *
 * <p>The balance and the spendable amount are separate fields on purpose, for the reason the staff read gives:
 * they differ by the earn delay, and a customer shown one number who is then refused at a checkout reads that as
 * a bug. Points are not money; the read names the brand that will honour them because a multi-brand customer
 * holds several and cannot spend one at another. Never cached: it is the customer's and must not outlive their
 * session in a shared proxy.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/loyalty")
@Tag(name = "Loyalty", description = "A customer's points balance and its movements")
public class LoyaltyCustomerController {

    private final LoyaltyQueryService loyalty;
    private final CurrentCustomer currentCustomer;

    public LoyaltyCustomerController(LoyaltyQueryService loyalty, CurrentCustomer currentCustomer) {
        this.loyalty = loyalty;
        this.currentCustomer = currentCustomer;
    }

    @GetMapping("/balance")
    @CustomerOwned
    @Operation(
            summary = "The caller's own points balance at this brand",
            description = "The balance, what is spendable today, what an unfinished checkout is holding, and the "
                    + "next lot to expire. A customer who has earned nothing at this brand reads accountId null "
                    + "and no amounts, not a 404. Points are not money: they cannot be withdrawn, cannot be transferred and have "
                    + "no value outside the platform.")
    public ResponseEntity<CustomerBalanceResponse> balance(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        UUID customerAccountId = currentCustomer
                .account(tenantId, brandId)
                .map(CustomerAccountRef::accountId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This principal has no customer account for this brand"));
        CustomerBalanceResponse body = loyalty.balanceOfCustomer(tenantId, brandId, customerAccountId)
                .map(CustomerBalanceResponse::of)
                .orElseGet(() -> CustomerBalanceResponse.none(brandId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /**
     * @param accountId the points account, or null for a customer who holds none at this brand yet, in which case
     *                  every amount is null too: there is no currency to state for points that were never earned
     */
    public record CustomerBalanceResponse(
            @Nullable UUID accountId,
            UUID brandId,
            @Nullable ApiMoney balance,
            @Nullable ApiMoney spendable,
            @Nullable ApiMoney held,
            @Nullable Instant nextExpiryAt,
            @Nullable ApiMoney nextExpiryAmount) {

        static CustomerBalanceResponse of(BalanceView view) {
            return new CustomerBalanceResponse(
                    view.accountId(),
                    view.brandId(),
                    ApiMoney.of(view.balanceMinor(), view.currency()),
                    ApiMoney.of(view.spendableMinor(), view.currency()),
                    ApiMoney.of(view.heldMinor(), view.currency()),
                    view.nextExpiryAt(),
                    ApiMoney.of(view.nextExpiryMinor(), view.currency()));
        }

        /** Nothing earned yet. */
        static CustomerBalanceResponse none(UUID brandId) {
            return new CustomerBalanceResponse(null, brandId, null, null, null, null, null);
        }
    }
}
