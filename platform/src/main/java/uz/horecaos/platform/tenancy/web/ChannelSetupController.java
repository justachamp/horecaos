package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.tenancy.application.ChannelSetupService;
import uz.horecaos.platform.tenancy.domain.channel.ChannelHostname;
import uz.horecaos.platform.tenancy.domain.channel.ChannelPresentation;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Row 10.5's channel setup hub — the hostname and SEO facets — from the
 * operations console.
 *
 * <p>Nested under {@code SalesChannelController}'s own path, the same
 * cross-surface control-plane placement {@code sales-channels-page.ts}'s own
 * doc explains: this is one more thing a channel has, not a resource of its
 * own. {@code TENANT} scope throughout, matching every sibling endpoint on
 * {@code SalesChannelController}.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/sales-channels/{channelId}/setup")
@Tag(name = "Channel setup", description = "Row 10.5: a channel's hostname and SEO presentation")
public class ChannelSetupController {

    private final ChannelSetupService setup;

    public ChannelSetupController(ChannelSetupService setup) {
        this.setup = setup;
    }

    // ------------------------------------------------------------ hostname

    @GetMapping("/hostname")
    @RequiresCapability(Capability.CHANNEL_READ)
    @Operation(
            summary = "This channel's own hostname, if it has claimed one",
            description = "configured: false with no hostname when the channel has not set one yet "
                    + "-- the console shows 'not configured' rather than treating an unset facet "
                    + "as an error.")
    public HostnameView hostname(@PathVariable UUID tenantId, @PathVariable UUID channelId) {
        return setup.hostname(tenantId, channelId)
                .map(hostname -> HostnameView.of(hostname, setup.baseDomain()))
                .orElseGet(() -> HostnameView.unset(setup.baseDomain()));
    }

    @PutMapping("/hostname/subdomain")
    @RequiresCapability(value = Capability.CHANNEL_MANAGE, mutating = true)
    @Operation(
            summary = "Claim a platform-issued subdomain",
            description = "slug becomes <slug>.<the platform's base domain>, verified immediately "
                    + "-- the platform's own DNS already answers for it. Refused if the slug is "
                    + "malformed or on the reserved list (ReservedSubdomains).")
    public HostnameView setSubdomain(
            @PathVariable UUID tenantId,
            @PathVariable UUID channelId,
            @Valid @RequestBody SubdomainRequest body,
            @RequestParam int expectedVersion) {
        return HostnameView.of(
                setup.setSubdomain(tenantId, channelId, body.slug(), expectedVersion), setup.baseDomain());
    }

    @PutMapping("/hostname/custom")
    @RequiresCapability(value = Capability.CHANNEL_MANAGE, mutating = true)
    @Operation(
            summary = "Claim a tenant's own custom domain",
            description = "Stored unverified. The automated DNS-TXT challenge is not built -- see "
                    + "ChannelSetupService's own doc -- so an operator confirms ownership out of "
                    + "band and a capability-holder marks it verified through the /verify action.")
    public HostnameView setCustomHostname(
            @PathVariable UUID tenantId,
            @PathVariable UUID channelId,
            @Valid @RequestBody CustomHostnameRequest body,
            @RequestParam int expectedVersion) {
        return HostnameView.of(
                setup.setCustomHostname(tenantId, channelId, body.hostname(), expectedVersion), setup.baseDomain());
    }

    @PostMapping("/hostname/verify")
    @RequiresCapability(value = Capability.CHANNEL_MANAGE, mutating = true)
    @Operation(summary = "Mark the channel's current hostname verified")
    public HostnameView verify(
            @PathVariable UUID tenantId, @PathVariable UUID channelId, @RequestParam int expectedVersion) {
        return HostnameView.of(setup.verifyCustomHostname(tenantId, channelId, expectedVersion), setup.baseDomain());
    }

    @DeleteMapping("/hostname")
    @RequiresCapability(value = Capability.CHANNEL_MANAGE, mutating = true)
    @Operation(summary = "Release this channel's hostname")
    public ResponseEntity<Void> clearHostname(
            @PathVariable UUID tenantId, @PathVariable UUID channelId, @RequestParam int expectedVersion) {
        setup.clearHostname(tenantId, channelId, expectedVersion);
        return ResponseEntity.noContent().build();
    }

    // --------------------------------------------------------- presentation

    @GetMapping("/presentation")
    @RequiresCapability(Capability.CHANNEL_READ)
    @Operation(summary = "This channel's SEO title/description and OG image")
    public PresentationView presentation(@PathVariable UUID tenantId, @PathVariable UUID channelId) {
        return PresentationView.of(setup.presentation(tenantId, channelId));
    }

    @PutMapping("/presentation")
    @RequiresCapability(value = Capability.CHANNEL_MANAGE, mutating = true)
    @Operation(
            summary = "Replace this channel's SEO presentation",
            description = "Full replace, like update() on the channel itself -- omitted or null "
                    + "clears a field rather than leaving it unchanged.")
    public PresentationView setPresentation(
            @PathVariable UUID tenantId,
            @PathVariable UUID channelId,
            @Valid @RequestBody PresentationRequest body,
            @RequestParam int expectedVersion) {
        return PresentationView.of(setup.setPresentation(
                tenantId, channelId, body.seoTitle(), body.seoDescription(), body.ogImageAssetId(), expectedVersion));
    }

    record SubdomainRequest(@NotBlank @Size(max = 63) String slug) {}

    record CustomHostnameRequest(@NotBlank @Size(max = 253) String hostname) {}

    record PresentationRequest(
            @Size(max = 200) String seoTitle,
            @Size(max = 500) String seoDescription,
            UUID ogImageAssetId) {}

    /** @param baseDomain the platform's own base domain, for the console to show alongside a subdomain field -- the authoritative value, not a copy the frontend has to keep in sync. */
    record HostnameView(boolean configured, @Nullable String hostname, boolean verified, String baseDomain) {
        static HostnameView of(ChannelHostname hostname, String baseDomain) {
            return new HostnameView(true, hostname.hostname(), hostname.verified(), baseDomain);
        }

        static HostnameView unset(String baseDomain) {
            return new HostnameView(false, null, false, baseDomain);
        }
    }

    record PresentationView(
            @Nullable String seoTitle,
            @Nullable String seoDescription,
            @Nullable UUID ogImageAssetId) {
        static PresentationView of(ChannelPresentation presentation) {
            return new PresentationView(
                    presentation.seoTitle(), presentation.seoDescription(), presentation.ogImageAssetId());
        }
    }
}
