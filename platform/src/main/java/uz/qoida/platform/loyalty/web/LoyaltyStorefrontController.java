package uz.qoida.platform.loyalty.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.iam.api.ResourceScope.ScopeType;
import uz.qoida.platform.loyalty.application.LoyaltyQueryService;
import uz.qoida.platform.loyalty.application.LoyaltyQueryService.BalanceView;
import uz.qoida.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.EntryRow;
import uz.qoida.platform.web.api.ApiMoney;
import uz.qoida.platform.web.authorization.RequiresCapability;

/**
 * What a customer can see of their own points (ADR 0046).
 *
 * <p>Reads only, and the absences are the decision. There is no endpoint that
 * credits an account from a customer payment, and none that pays one out: the
 * platform holds no customer funds, so there is nothing to top up and nothing to
 * withdraw. That is part of ADR 0046's decision rather than a gap in this class.
 *
 * <p>The balance and the spendable amount are separate fields on purpose. They
 * differ by the earn delay, and a customer shown one number who is then refused
 * at a checkout reads that as a bug — which is the same reason the next expiry
 * is here rather than only in a notification.
 */
@RestController
@RequestMapping("/api/v1/storefront/loyalty")
@Tag(name = "Loyalty", description = "A customer's points balance and its movements")
public class LoyaltyStorefrontController {

    private final LoyaltyQueryService loyalty;

    public LoyaltyStorefrontController(LoyaltyQueryService loyalty) {
        this.loyalty = loyalty;
    }

    @GetMapping("/tenants/{tenantId}/accounts/{accountId}")
    @RequiresCapability(value = Capability.LOYALTY_READ, scope = ScopeType.TENANT)
    @Operation(summary = "One points balance",
            description = "The balance, what is spendable today, what an unfinished checkout is "
                    + "holding, and the next lot to expire. Points are not money: they cannot be "
                    + "withdrawn, cannot be transferred, and have no value outside the platform.")
    public ResponseEntity<BalanceResponse> balance(@PathVariable UUID tenantId,
            @PathVariable UUID accountId) {
        return ResponseEntity.ok(BalanceResponse.of(loyalty.balance(tenantId, accountId)));
    }

    @GetMapping("/tenants/{tenantId}/accounts/{accountId}/entries")
    @RequiresCapability(value = Capability.LOYALTY_READ, scope = ScopeType.TENANT)
    @Operation(summary = "The movements behind a balance",
            description = "Every entry that produced the balance, newest first, each with the "
                    + "balance it left behind. This is what a disputed figure is answered from.")
    public ResponseEntity<List<EntryResponse>> entries(@PathVariable UUID tenantId,
            @PathVariable UUID accountId) {
        return ResponseEntity.ok(loyalty.entries(tenantId, accountId).stream()
                .map(EntryResponse::of)
                .toList());
    }

    /**
     * @param brandId the brand that will honour these points. Named on every
     *                balance because a multi-brand customer holds several and
     *                cannot spend one at another
     */
    public record BalanceResponse(UUID accountId, UUID brandId, ApiMoney balance,
            ApiMoney spendable, ApiMoney held, Instant nextExpiryAt, ApiMoney nextExpiryAmount) {

        static BalanceResponse of(BalanceView view) {
            return new BalanceResponse(view.accountId(), view.brandId(),
                    ApiMoney.of(view.balanceMinor(), view.currency()),
                    ApiMoney.of(view.spendableMinor(), view.currency()),
                    ApiMoney.of(view.heldMinor(), view.currency()),
                    view.nextExpiryAt(),
                    ApiMoney.of(view.nextExpiryMinor(), view.currency()));
        }
    }

    public record EntryResponse(UUID id, String type, long amountMinor, long balanceAfterMinor,
            UUID orderId, String reasonCode, Instant occurredAt) {

        static EntryResponse of(EntryRow row) {
            return new EntryResponse(row.id(), row.entryType().name(), row.amountMinor(),
                    row.balanceAfterMinor(), row.orderId(), row.reasonCode(), row.occurredAt());
        }
    }
}
