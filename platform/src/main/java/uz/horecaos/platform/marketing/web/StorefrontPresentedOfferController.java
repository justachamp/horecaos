package uz.horecaos.platform.marketing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerOwned;
import uz.horecaos.platform.marketing.application.PresentedOfferService;
import uz.horecaos.platform.marketing.application.PresentedOfferService.Banner;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * The banners a scenario's in-app steps make a guest eligible to see (ADR 0112).
 *
 * <p>Authorised by account ownership, as every customer self-service read is: a customer
 * asking what offers they have is exercising their own account. The storefront and the
 * Telegram mini-app poll it when a screen opens, and asking is showing, so each banner
 * returned counts against {@code marketing.in_app.show_cap_per_day} for that guest today.
 * Each item is the shape the storefront's existing {@code OfferItem} wants (an id, a name, an
 * image, a priority), so the existing frontend type is served rather than a second one.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/presented-offers")
@Tag(name = "Presented offers", description = "In-app offers a scenario has made a guest eligible to see (ADR 0112)")
public class StorefrontPresentedOfferController {

    private final PresentedOfferService presented;
    private final CurrentCustomer currentCustomer;

    public StorefrontPresentedOfferController(PresentedOfferService presented, CurrentCustomer currentCustomer) {
        this.presented = presented;
        this.currentCustomer = currentCustomer;
    }

    @GetMapping
    @CustomerOwned
    @Operation(
            summary = "The banners to show this customer now",
            description = "Counts each returned banner against today's cap. A banner the customer closed, "
                    + "one past the daily cap, and one whose offer has left its validity window are not returned.")
    public ResponseEntity<List<Banner>> poll(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(defaultValue = PresentedOfferService.STOREFRONT) String surface) {
        if (!PresentedOfferService.STOREFRONT.equals(surface)
                && !PresentedOfferService.TELEGRAM_MINI_APP.equals(surface)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, surface + " is not a surface");
        }
        return ResponseEntity.ok(presented.poll(tenantId, brandId, accountId(tenantId, brandId), surface));
    }

    @PostMapping("/{presentedOfferId}/dismissals")
    @CustomerOwned
    @Idempotent
    @Operation(summary = "The customer closed a banner; it is not shown again")
    public ResponseEntity<Void> dismiss(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID presentedOfferId) {
        if (!presented.dismiss(tenantId, accountId(tenantId, brandId), presentedOfferId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such banner is open for this customer");
        }
        return ResponseEntity.noContent().build();
    }

    private UUID accountId(UUID tenantId, UUID brandId) {
        return currentCustomer
                .account(tenantId, brandId)
                .map(CustomerAccountRef::accountId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This principal has no customer account for this brand"));
    }
}
