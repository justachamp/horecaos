package uz.horecaos.platform.partner.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.partner.api.PartnerBound;
import uz.horecaos.platform.partner.api.PartnerPrincipal;
import uz.horecaos.platform.partner.application.MarketplaceAvailabilityPullService;
import uz.horecaos.platform.partner.application.MarketplaceAvailabilityPullService.AvailabilityItem;
import uz.horecaos.platform.partner.application.MarketplaceAvailabilityPullService.AvailabilityPage;
import uz.horecaos.platform.partner.application.PartnerAuthenticationService;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * The partner pull of a branch's availability (ADR 0141 Phase 4, ADR 0040): an aggregator that
 * would rather poll than be pushed to reads, per branch, which of its mapped dishes may be sold
 * right now.
 *
 * <p>A read, so no {@code Idempotency-Key} and no version (ADR 0031 asks for them on mutations).
 * Authorised by the aggregator's own client credential and the bindings its installation holds
 * ({@link PartnerBound}); the tenant in the path is matched against the credential's, and the
 * branch must be one the caller is bound to. The answer is the current truth from the same resolver
 * the storefront and the reconciler use, evaluated per call: a partner that polls every minute
 * learns of a 14:32 stop at 14:33, which is the property the push exists to improve on and the pull
 * must never undermine by being served from a cache.
 *
 * <p>Rate-limited per partner with a burst allowance, for the reason {@link PartnerOrderController}
 * gives: aggregators poll, and a limiter tuned for a human's click rate refuses a perfectly
 * ordinary integration.
 */
@RestController
@RequestMapping("/api/v1/partner/tenants/{tenantId}")
@Tag(name = "Partner API", description = "Inbound aggregator orders, shift events and availability reads (ADR 0040)")
public class PartnerAvailabilityController {

    /** 300 polls a minute per partner credential: a menu of a few hundred dishes in two or three pages, every few seconds. */
    private static final RateLimiter.Policy AVAILABILITY_PULL_LIMIT =
            new RateLimiter.Policy(300, Duration.ofMinutes(1), false);

    private final PartnerAuthenticationService authentication;
    private final MarketplaceAvailabilityPullService pull;
    private final PartnerTokenReader tokens;
    private final RateLimiter rateLimiter;

    public PartnerAvailabilityController(
            PartnerAuthenticationService authentication,
            MarketplaceAvailabilityPullService pull,
            PartnerTokenReader tokens,
            RateLimiter rateLimiter) {
        this.authentication = authentication;
        this.pull = pull;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/restaurants/{locationId}/availability")
    @PartnerBound(Capability.MARKETPLACE_AVAILABILITY_PULL)
    @Operation(
            summary = "Read a restaurant's current availability",
            description = "ADR 0141 Phase 4. For each dish this partner has mapped at the restaurant, whether it "
                    + "may be sold on the partner's channel right now, in the partner's own item-id order and "
                    + "in cursor pages (pass nextCursor back as cursor; limit 1 to 200, default 200). Identifiers "
                    + "and a boolean only. Answered from the platform's own resolver on every call, never from a "
                    + "cache. 404 when the restaurant is not one this credential is bound to; 409 "
                    + "CHANNEL_NOT_CONFIGURED when the integration has no single active channel yet.")
    public ResponseEntity<PartnerAvailabilityResponse> read(
            @PathVariable UUID tenantId,
            @PathVariable UUID locationId,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {

        PartnerPrincipal principal = authentication.authenticate(tokens.clientId(), tenantId);

        RateLimiter.Decision decision = rateLimiter.check(
                new RateLimiter.Key("partner.availability.pull", tenantId.toString(), principal.rateLimitSubject()),
                AVAILABILITY_PULL_LIMIT);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many availability reads on this partner credential",
                    Map.of("retryAfterSeconds", decision.retryAfter().toSeconds()));
        }

        AvailabilityPage page = pull.read(principal, locationId, cursor, limit);
        return ResponseEntity.ok()
                // The answer is only true as of the instant it carries.
                .cacheControl(CacheControl.noStore())
                .body(PartnerAvailabilityResponse.of(page));
    }

    /**
     * @param asOf the instant the availability was evaluated at
     * @param nextCursor null on the last page
     */
    public record PartnerAvailabilityResponse(
            UUID locationId,
            java.time.Instant asOf,
            List<AvailabilityItemResponse> items,
            @Nullable String nextCursor) {

        static PartnerAvailabilityResponse of(AvailabilityPage page) {
            return new PartnerAvailabilityResponse(
                    page.locationId(),
                    page.asOf(),
                    page.items().stream().map(AvailabilityItemResponse::of).toList(),
                    page.nextCursor());
        }
    }

    /** One mapped dish: the partner's identifier and whether it may be sold. */
    public record AvailabilityItemResponse(String externalItemId, boolean available) {

        static AvailabilityItemResponse of(AvailabilityItem item) {
            return new AvailabilityItemResponse(item.externalItemId(), item.available());
        }
    }
}
