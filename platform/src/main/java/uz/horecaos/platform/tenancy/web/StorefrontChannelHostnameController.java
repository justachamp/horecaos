package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.tenancy.application.ChannelSetupService;
import uz.horecaos.platform.tenancy.domain.channel.ChannelHostname;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Row 10.5: which tenant and channel a hostname belongs to.
 *
 * <p>This is the read an edge layer generating or choosing a storefront
 * deployment's own {@code /config.json} would call — {@code
 * ChannelSetupService}'s own doc explains at length what this does and does
 * not replace. The storefront application itself keeps reading its bundled
 * {@code /config.json} exactly as before; nothing changes there.
 *
 * <p>Deliberately not under {@code /tenants/{tenantId}/...}: resolving the
 * tenant from a hostname is the one storefront read that cannot be scoped by
 * a tenant id, because the caller does not have one yet. Unauthenticated for
 * the same reason the menu is, and answers nothing personal — a tenant id, a
 * channel id, and a boolean.
 */
@RestController
@RequestMapping("/api/v1/storefront/channel-hostnames")
@Tag(
        name = "Storefront channel hostname lookup",
        description = "Row 10.5: resolve a Host header to a tenant and channel")
public class StorefrontChannelHostnameController {

    private static final Duration CACHE_FOR = Duration.ofMinutes(5);

    private final ChannelSetupService setup;

    public StorefrontChannelHostnameController(ChannelSetupService setup) {
        this.setup = setup;
    }

    @GetMapping("/{hostname}")
    @Operation(
            summary = "The tenant and channel this hostname answers on",
            description = "Not-found for an unclaimed hostname and for a claimed-but-unverified "
                    + "one alike -- ChannelSetupService#resolveHostname's own doc explains why an "
                    + "unverified custom domain must not resolve.")
    public ResponseEntity<HostnameLookupView> resolve(@PathVariable String hostname) {
        ChannelHostname resolved = setup.resolveHostname(hostname)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No channel answers on this hostname"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_FOR).cachePublic())
                .body(HostnameLookupView.of(resolved));
    }

    record HostnameLookupView(UUID tenantId, UUID channelId, boolean verified) {
        static HostnameLookupView of(ChannelHostname hostname) {
            return new HostnameLookupView(hostname.tenantId(), hostname.channelId(), hostname.verified());
        }
    }
}
