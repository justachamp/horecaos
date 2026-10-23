package uz.horecaos.platform.marketing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.marketing.api.AnonymousVisitorHandle;
import uz.horecaos.platform.marketing.application.AttributionLinkClickService;

/**
 * The public click on an attribution link (ADR 0167 Decision 4 tier 1, Decision 5; ADR 0044).
 *
 * <p>A storefront landing on a {@code ?ref=token} URL calls this once. It is unauthenticated by design,
 * the standing of {@code StorefrontAnalyticsConfigController}: there is nobody to authorise, so it declares
 * no capability, and it carries no {@code Idempotency-Key} because it is non-idempotent on purpose -- the
 * second open of a link is a second click. What protects it is that it can only add one to a counter,
 * answers every token alike, and is rate limited per token and visitor.
 *
 * <p>It replaces the staff {@code POST .../attribution-links/{linkId}/clicks}, which needed
 * {@code marketing-link.manage} and was called by no surface. A staff person incrementing a counter by
 * hand is a way to make the chart wrong, so that endpoint is gone and not merely unused.
 *
 * <p>The visitor's address becomes a keyed hash here ({@link AnonymousVisitorHandle}), with a key minted at
 * process start and never persisted, so the handle means nothing outside this process's sixty-second limiter
 * window and cannot be correlated across restarts or with any other table.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/attribution-links")
@Tag(name = "Attribution link clicks", description = "Anonymous click counting for ?ref= landings (ADR 0167)")
public class AttributionLinkClickController {

    private final AttributionLinkClickService clicks;

    public AttributionLinkClickController(AttributionLinkClickService clicks) {
        this.clicks = clicks;
    }

    @PostMapping("/{token}/clicks")
    @Operation(
            summary = "Count one open of an attribution link",
            description = "Public. Answers 202 whether or not the token names a live link of this brand "
                    + "(unknown, archived, expired and other-brand tokens count nothing and say nothing), "
                    + "so the endpoint cannot be used to probe which tokens exist. Records no address, "
                    + "user agent or cookie. Non-idempotent by design: each open is one click. Limited "
                    + "per token and visitor.")
    public ResponseEntity<Void> click(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable String token,
            HttpServletRequest request) {

        clicks.recordClick(tenantId, brandId, token, clientKey(request));
        return ResponseEntity.accepted().build();
    }

    /**
     * An opaque handle for the caller, for the ADR 0033 limiter's in-memory bucket and nothing else.
     * {@code getRemoteAddr} is the real client because {@code server.forward-headers-strategy: framework}
     * applies the proxy's {@code X-Forwarded-For} before a handler runs (see
     * {@code StorefrontCustomerIdentityController#callerKey}, whose reasoning this repeats).
     */
    private String clientKey(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        if (address == null || address.isBlank()) {
            // No source at all still gets a bucket rather than an exemption; sharing one is stricter.
            return "unattributed";
        }
        return AnonymousVisitorHandle.of("ip", address);
    }
}
