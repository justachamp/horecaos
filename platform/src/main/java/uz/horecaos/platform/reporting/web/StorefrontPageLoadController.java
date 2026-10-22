package uz.horecaos.platform.reporting.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.marketing.api.AnonymousVisitorHandle;
import uz.horecaos.platform.reporting.application.PageLoadCountService;

/**
 * The page-load count a storefront reports (ADR 0183 D18; ADR 0167 amended): dark behind
 * {@code reporting.page_load_count_enabled}.
 *
 * <p>{@code GET} tells a storefront whether to send the ping at all, so that while the flag is off no storefront makes a
 * measurement request: the answer is the same for every tenant and visitor, names nobody, and is cached for five minutes.
 * {@code POST} adds one to the day's counter. It is public by design, the standing of the attribution-link click: there is
 * nobody to authorise, it can only add one to a counter, it records no address, user agent or cookie, it takes no
 * {@code Idempotency-Key} (a retried ping counting twice is inside what a client-reported counter already accepts), and it is
 * limited per visitor in the ADR 0033 limiter. It answers 202 whether or not anything was counted, so it says nothing about
 * whether the flag is on, the brand is real or the channel exists.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/page-loads")
@Tag(
        name = "Storefront page loads",
        description = "An anonymous page-load count, dark until counsel answers (ADR 0183)")
public class StorefrontPageLoadController {

    private static final Duration CACHE_FOR = Duration.ofMinutes(5);

    private final PageLoadCountService pageLoads;

    public StorefrontPageLoadController(PageLoadCountService pageLoads) {
        this.pageLoads = pageLoads;
    }

    @GetMapping
    @Operation(
            summary = "Whether the platform counts page loads",
            description = "False until the platform owner switches it on after counsel answers. A storefront sends the "
                    + "ping only when this is true.")
    public ResponseEntity<EnabledView> pageLoadCountingEnabled(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_FOR).cachePublic())
                .body(new EnabledView(pageLoads.enabled()));
    }

    @PostMapping
    @Operation(
            summary = "Count one page load (one per browser tab session)",
            description = "Public. Answers 202 whether or not anything was counted. Reads no cookie and stores no "
                    + "address, user agent, session or path; a page load is not a person. Limited per visitor.")
    public ResponseEntity<Void> countPageLoad(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @Valid @RequestBody PingRequest body,
            HttpServletRequest request) {
        pageLoads.record(tenantId, brandId, body.channel(), visitor(request));
        return ResponseEntity.accepted().build();
    }

    /**
     * An opaque handle for the caller, for the ADR 0033 limiter and nothing else; the same reasoning as the attribution-link
     * click ({@code getRemoteAddr} is the real client because the proxy's forwarding header is applied before a handler runs).
     */
    private static String visitor(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "unattributed" : AnonymousVisitorHandle.of("ip", address);
    }

    public record EnabledView(boolean enabled) {}

    /** @param channel the sales channel code this storefront reports on carts ({@code AppConfig.channel}) */
    public record PingRequest(@Nullable @Size(max = 32) String channel) {}
}
