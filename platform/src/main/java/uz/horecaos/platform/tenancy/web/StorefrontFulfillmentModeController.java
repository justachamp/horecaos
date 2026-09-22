package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.api.Serviceability;
import uz.horecaos.platform.tenancy.api.ServiceabilityReason;
import uz.horecaos.platform.tenancy.api.ServiceabilityResolver;
import uz.horecaos.platform.tenancy.application.TenantResourceNotFoundException;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * "What can this channel sell here at all" (ADR 0036), for every fulfilment
 * mode in one call.
 *
 * <p>{@link ServiceabilityController} answers one mode at a time and conflates
 * two different facts under its single {@code available} flag: a channel that
 * never carries pickup and a channel that carries it but is outside its hours
 * right now both come back {@code available: false}, and the caller has to
 * know that {@code FULFILMENT_MODE_UNAVAILABLE} means the first and every
 * other reason means the second. A storefront choosing which mode tab to show
 * by default, and whether to show it at all, needs exactly that distinction
 * up front for every mode — not a reason code to reverse-engineer after
 * guessing which modes exist.
 *
 * <p>Public and unauthenticated, like the menu and serviceability endpoints it
 * sits beside: choosing a fulfilment mode must not demand a customer account.
 * Carries no coordinate and no address — this answers what the channel sells
 * structurally, not where anyone is standing.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/locations/{locationId}")
@Tag(name = "Serviceability", description = "Whether a location serves this channel and mode now")
public class StorefrontFulfillmentModeController {

    private final ServiceabilityResolver resolver;
    private final SalesChannelLookup channels;
    private final Clock clock;

    public StorefrontFulfillmentModeController(
            ServiceabilityResolver resolver, SalesChannelLookup channels, Clock clock) {
        this.resolver = resolver;
        this.channels = channels;
        this.clock = clock;
    }

    @GetMapping("/fulfillment-modes")
    @Operation(
            summary = "Which fulfilment modes a channel sells at a location, and which are usable now",
            description = "One row per uz.horecaos.platform.tenancy.api.FulfillmentMode value. "
                    + "\"sold\" is whether the channel carries the mode at all, structurally, "
                    + "independent of the clock; \"serviceable\" is whether an order may be placed "
                    + "for it right now. A mode can be sold and not serviceable (outside hours, "
                    + "manually closed, at capacity, ...); it is never serviceable without being "
                    + "sold. \"reason\" is absent exactly when serviceable is true.")
    public ResponseEntity<FulfillmentModesResponse> fulfillmentModes(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam String channel) {

        // A channel code naming no row sells nothing anywhere, the same reading
        // ServiceabilityController gives a stale link: a conflict a customer can
        // be told about, never a 404 for a resource they never named.
        UUID channelId =
                channels.byCode(tenantId, channel).map(SalesChannel::id).orElse(null);
        if (channelId == null) {
            return cached(new FulfillmentModesResponse(Arrays.stream(FulfillmentMode.values())
                    .map(mode -> new FulfillmentModeAvailability(
                            mode, false, false, ServiceabilityReason.CHANNEL_NOT_ENABLED.name()))
                    .toList()));
        }

        try {
            List<FulfillmentModeAvailability> modes = Arrays.stream(FulfillmentMode.values())
                    .map(mode -> {
                        Serviceability answer =
                                resolver.resolve(tenantId, brandId, locationId, channelId, mode, clock.instant());
                        return FulfillmentModeAvailability.of(mode, answer);
                    })
                    .toList();
            return cached(new FulfillmentModesResponse(modes));
        } catch (TenantResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    private ResponseEntity<FulfillmentModesResponse> cached(FulfillmentModesResponse body) {
        // Matches ServiceabilityController's own ADR 0033 ceiling: this answer is
        // exactly as time-sensitive as that one and must not outlive it in a
        // shared cache.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(body);
    }

    public record FulfillmentModesResponse(List<FulfillmentModeAvailability> modes) {}

    /**
     * @param sold        whether the channel carries this mode at this location
     *                    at all — false only for {@link
     *                    ServiceabilityReason#CHANNEL_NOT_ENABLED} or {@link
     *                    ServiceabilityReason#FULFILMENT_MODE_UNAVAILABLE}, the
     *                    two reasons that mean "not on offer" rather than "not
     *                    right now"
     * @param serviceable whether an order for this mode may be placed this
     *                    instant; implies {@code sold}
     * @param reason      the {@code ServiceabilityReason} name, present exactly
     *                    when {@code serviceable} is false. The storefront maps
     *                    this to wording and never renders it
     */
    public record FulfillmentModeAvailability(
            FulfillmentMode mode,
            boolean sold,
            boolean serviceable,
            @Nullable String reason) {

        private static final Set<ServiceabilityReason> NOT_SOLD =
                Set.of(ServiceabilityReason.CHANNEL_NOT_ENABLED, ServiceabilityReason.FULFILMENT_MODE_UNAVAILABLE);

        static FulfillmentModeAvailability of(FulfillmentMode mode, Serviceability answer) {
            if (answer.available()) {
                return new FulfillmentModeAvailability(mode, true, true, null);
            }
            ServiceabilityReason reason = answer.reason();
            boolean sold = reason == null || !NOT_SOLD.contains(reason);
            return new FulfillmentModeAvailability(mode, sold, false, reason == null ? null : reason.name());
        }
    }
}
