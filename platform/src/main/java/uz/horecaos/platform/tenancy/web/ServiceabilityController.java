package uz.horecaos.platform.tenancy.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.api.Serviceability;
import uz.horecaos.platform.tenancy.api.ServiceabilityReason;
import uz.horecaos.platform.tenancy.api.ServiceabilityResolver;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * "Can I order from here right now" (ADR 0036).
 *
 * <p>Unauthenticated, like the menu it accompanies: this is what a customer sees
 * before they have an account.
 *
 * <p>Browse may cache the answer for at most 30 seconds under ADR 0033, which is
 * the {@code Cache-Control} below. ADR 0019's checkout does not read this endpoint
 * and never reads a cached resolution — it re-resolves from PostgreSQL inside its
 * transaction, because a manual close that takes five minutes to appear is worse
 * than no manual close.
 */
@RestController
@RequestMapping("/api/v1/storefront")
@Tag(name = "Serviceability", description = "Whether a location serves this channel and mode now")
public class ServiceabilityController {

    private final ServiceabilityResolver resolver;
    private final SalesChannelLookup channels;
    private final Clock clock;

    public ServiceabilityController(ServiceabilityResolver resolver, SalesChannelLookup channels,
            Clock clock) {
        this.resolver = resolver;
        this.channels = channels;
        this.clock = clock;
    }

    @GetMapping("/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/serviceability")
    @Operation(summary = "Resolve serviceability for a channel and fulfilment mode",
            description = "One resolver, one stable reason code, and a next-available instant "
                    + "where one is computable. \"Closed now\" and \"cannot pre-order\" are "
                    + "different facts and are reported separately.")
    public ResponseEntity<ServiceabilityView> resolve(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId,
            @RequestParam String channel, @RequestParam FulfillmentMode mode,
            @RequestParam(required = false) Instant at) {

        // A channel code naming no row is CHANNEL_NOT_ENABLED rather than a 404: a
        // customer following a stale link should be told the route is unavailable,
        // not shown an error page about a resource they have never heard of.
        UUID channelId = channels.byCode(tenantId, channel)
                .map(uz.horecaos.platform.tenancy.api.SalesChannel::id)
                .orElse(null);
        if (channelId == null) {
            return cached(new ServiceabilityView(false,
                    ServiceabilityReason.CHANNEL_NOT_ENABLED.name(), null, false, null));
        }

        try {
            Serviceability answer = resolver.resolve(tenantId, brandId, locationId, channelId,
                    mode, at == null ? clock.instant() : at);
            return cached(ServiceabilityView.of(answer));
        } catch (uz.horecaos.platform.tenancy.application.TenantResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    private ResponseEntity<ServiceabilityView> cached(ServiceabilityView body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(body);
    }

    /** The wire shape. The storefront maps {@code reason} to wording and never renders it. */
    public record ServiceabilityView(boolean available, String reason, Instant nextAvailableAt,
            boolean acceptsScheduledOrders, Integer preparationMinutes) {

        static ServiceabilityView of(Serviceability answer) {
            return new ServiceabilityView(answer.available(),
                    answer.reason() == null ? null : answer.reason().name(),
                    answer.nextAvailableAt(), answer.acceptsScheduledOrders(),
                    answer.preparationMinutes());
        }
    }
}
