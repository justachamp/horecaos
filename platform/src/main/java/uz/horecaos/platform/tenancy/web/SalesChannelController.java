package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.application.SalesChannelService;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The tenant-managed channel registry (ADR 0036).
 *
 * <p>Every endpoint sits at {@code TENANT} scope, which is the narrowest the
 * paths support and also the truth: a channel is a tenant-level object that spans
 * brands, so a brand-scoped grant is not enough to create one.
 *
 * <p>Matrix writes are whole-matrix {@code PUT} with an expected version. ADR
 * 0036 sketches the location matrix as {@code PUT .../locations/{locationId}},
 * one cell at a time; that is written here as a whole-matrix PUT instead, both
 * because the ADR's own rule against per-cell patches applies equally to it, and
 * because a path naming {@code {locationId}} would have to declare
 * {@code LOCATION} scope — which needs a brand in the path that a tenant-level
 * object does not have.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/sales-channels")
@Tag(name = "Sales channels", description = "The tenant-owned registry of routes to market")
public class SalesChannelController {

    private final SalesChannelService channels;

    public SalesChannelController(SalesChannelService channels) {
        this.channels = channels;
    }

    @PostMapping
    @RequiresCapability(value = Capability.CHANNEL_MANAGE, mutating = true)
    @Operation(
            summary = "Register a sales channel",
            description = "The system type comes from a closed, code-owned list. A tenant may run "
                    + "several channels of one type — Uzum Tezkor and Yandex Eda are both "
                    + "AGGREGATOR and must differ in price plane, payment mix, and reporting.")
    public ResponseEntity<ChannelView> create(
            @PathVariable UUID tenantId, @Valid @RequestBody CreateChannelRequest body) {

        SalesChannel channel = channels.create(
                tenantId,
                new SalesChannelService.CreateChannelCommand(
                        body.code(),
                        body.systemType(),
                        body.displayName(),
                        body.pricePlaneChannelId(),
                        body.externallyPriced(),
                        body.guestOrdersAllowed(),
                        body.providerInstallationId()));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{channelId}")
                .buildAndExpand(channel.id())
                .toUri();
        return ResponseEntity.created(location).body(ChannelView.of(channel));
    }

    @GetMapping
    @RequiresCapability(Capability.CHANNEL_READ)
    @Operation(
            summary = "List a tenant's sales channels, archived ones included",
            description = "Archived channels are listed because a historical order still renders "
                    + "through one; the status says whether a new cart may use it.")
    public List<ChannelView> list(@PathVariable UUID tenantId) {
        return channels.list(tenantId).stream().map(ChannelView::of).toList();
    }

    @GetMapping("/{channelId}/matrices")
    @RequiresCapability(Capability.CHANNEL_READ)
    @Operation(summary = "The channel's payment, fulfilment, and location matrices")
    public SalesChannelService.ChannelMatrices matrices(@PathVariable UUID tenantId, @PathVariable UUID channelId) {
        return channels.matrices(tenantId, channelId);
    }

    @PutMapping("/{channelId}/payment-methods")
    @RequiresCapability(value = Capability.CHANNEL_MANAGE, mutating = true)
    @Operation(
            summary = "Replace the channel's payment-method matrix",
            description = "Whole matrix, never per cell: a matrix edited cell by cell from two "
                    + "tabs produces a combination neither operator chose.")
    public ResponseEntity<Void> replacePaymentMethods(
            @PathVariable UUID tenantId,
            @PathVariable UUID channelId,
            @RequestParam int expectedVersion,
            @Valid @RequestBody Map<String, Boolean> matrix) {
        channels.replacePaymentMethods(tenantId, channelId, matrix, expectedVersion);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{channelId}/fulfillment-modes")
    @RequiresCapability(value = Capability.CHANNEL_MANAGE, mutating = true)
    @Operation(
            summary = "Replace the channel's fulfilment-mode matrix",
            description = "Dine-in is a mode here and never a channel: a QR-table order and a "
                    + "waiter-entered order are both DINE_IN on different channels.")
    public ResponseEntity<Void> replaceFulfillmentModes(
            @PathVariable UUID tenantId,
            @PathVariable UUID channelId,
            @RequestParam int expectedVersion,
            @Valid @RequestBody Map<FulfillmentMode, Boolean> matrix) {
        channels.replaceFulfillmentModes(tenantId, channelId, matrix, expectedVersion);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{channelId}/locations")
    @RequiresCapability(value = Capability.CHANNEL_MANAGE, mutating = true)
    @Operation(
            summary = "Replace the set of locations selling on this channel",
            description = "A location absent from the set is not served by the channel; the "
                    + "resolver returns CHANNEL_NOT_ENABLED rather than falling through to hours.")
    public ResponseEntity<Void> replaceLocations(
            @PathVariable UUID tenantId,
            @PathVariable UUID channelId,
            @RequestParam int expectedVersion,
            @Valid @RequestBody LocationSetRequest body) {
        channels.replaceLocations(tenantId, channelId, body.locationIds(), expectedVersion);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{channelId}/archive")
    @RequiresCapability(value = Capability.CHANNEL_MANAGE, mutating = true)
    @Operation(
            summary = "Archive a channel",
            description = "Channels archive and never delete. Every order carries its channel "
                    + "forever, and a deleted row makes that order unattributable in every report.")
    public ChannelView archive(
            @PathVariable UUID tenantId, @PathVariable UUID channelId, @RequestParam int expectedVersion) {
        return ChannelView.of(channels.archive(tenantId, channelId, expectedVersion));
    }

    record CreateChannelRequest(
            @NotBlank @Size(max = 32) @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,31}")
            String code,

            @NotBlank @Size(max = 16) String systemType,
            @NotBlank @Size(max = 200) String displayName,
            UUID pricePlaneChannelId,
            boolean externallyPriced,
            boolean guestOrdersAllowed,
            UUID providerInstallationId) {}

    record LocationSetRequest(@NotNull List<UUID> locationIds) {}

    /** What a control-plane screen shows. */
    public record ChannelView(
            UUID id,
            String code,
            String systemType,
            String displayName,
            String status,
            UUID pricePlaneChannelId,
            boolean externallyPriced,
            boolean guestOrdersAllowed,
            UUID providerInstallationId,
            int version) {

        static ChannelView of(SalesChannel channel) {
            return new ChannelView(
                    channel.id(),
                    channel.code(),
                    channel.systemType().name(),
                    channel.displayName(),
                    channel.status().name(),
                    channel.pricePlaneChannelId(),
                    channel.externallyPriced(),
                    channel.guestOrdersAllowed(),
                    channel.providerInstallationId(),
                    channel.version());
        }
    }
}
