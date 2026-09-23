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
import org.jspecify.annotations.Nullable;
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
                    + "through one; the status says whether a new cart may use it. Each row also "
                    + "carries its branch count, enabled payment-method count and enabled "
                    + "fulfilment modes -- settings.md 10.4a's three previously absent columns.")
    public List<ChannelView> list(@PathVariable UUID tenantId) {
        return channels.listSummaries(tenantId).stream().map(ChannelView::of).toList();
    }

    @PutMapping("/{channelId}")
    @RequiresCapability(value = Capability.CHANNEL_MANAGE, mutating = true)
    @Operation(
            summary = "Correct a channel's own fields",
            description = "Everything but the code and the system type, which ADR 0036 fixes at "
                    + "creation because behaviour keys on the type.")
    public ChannelView update(
            @PathVariable UUID tenantId,
            @PathVariable UUID channelId,
            @Valid @RequestBody UpdateChannelRequest body,
            @RequestParam int expectedVersion) {
        return ChannelView.of(channels.update(
                tenantId,
                channelId,
                new SalesChannelService.UpdateChannelCommand(
                        body.displayName(),
                        body.pricePlaneChannelId(),
                        body.externallyPriced(),
                        body.guestOrdersAllowed(),
                        body.providerInstallationId(),
                        body.icon(),
                        body.brandColorPrimary(),
                        body.brandColorSecondary()),
                expectedVersion));
    }

    @PostMapping("/{channelId}/deactivate")
    @RequiresCapability(value = Capability.CHANNEL_MANAGE, mutating = true)
    @Operation(
            summary = "Suspend sales on an active channel",
            description = "Reversible with reactivate, unlike archive. Row 10.4a's ACTIVE→INACTIVE "
                    + "transition, declared since ADR 0036 and unreachable until now.")
    public ChannelView deactivate(
            @PathVariable UUID tenantId, @PathVariable UUID channelId, @RequestParam int expectedVersion) {
        return ChannelView.of(channels.deactivate(tenantId, channelId, expectedVersion));
    }

    @PostMapping("/{channelId}/reactivate")
    @RequiresCapability(value = Capability.CHANNEL_MANAGE, mutating = true)
    @Operation(summary = "Resume sales on a suspended channel")
    public ChannelView reactivate(
            @PathVariable UUID tenantId, @PathVariable UUID channelId, @RequestParam int expectedVersion) {
        return ChannelView.of(channels.reactivate(tenantId, channelId, expectedVersion));
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

    @PutMapping("/{channelId}/social-links")
    @RequiresCapability(value = Capability.CHANNEL_MANAGE, mutating = true)
    @Operation(
            summary = "Replace the channel's social links",
            description = "Whole set, never per-link -- the same discipline the payment-method, "
                    + "fulfilment-mode and location matrices already use. Key is the platform, "
                    + "a checked vocabulary (ADR 0036); value is the destination URL, https only. "
                    + "Entry order in the request body becomes display order.")
    public ResponseEntity<Void> replaceSocialLinks(
            @PathVariable UUID tenantId,
            @PathVariable UUID channelId,
            @RequestParam int expectedVersion,
            @RequestBody Map<String, String> links) {
        channels.replaceSocialLinks(tenantId, channelId, links, expectedVersion);
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

    record UpdateChannelRequest(
            @NotBlank @Size(max = 200) String displayName,
            UUID pricePlaneChannelId,
            boolean externallyPriced,
            boolean guestOrdersAllowed,
            UUID providerInstallationId,

            // Row 10.4a: a channel's own presentation. All three optional --
            // omitted or null clears the field, the same full-replace reading
            // every other field on this request already has.
            @Size(max = 64) String icon,
            @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String brandColorPrimary,
            @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String brandColorSecondary) {}

    /**
     * What a control-plane screen shows.
     *
     * @param locationCount              settings.md 10.4a "Филиалы" -- zero for a
     *                                   freshly created, updated or status-changed
     *                                   channel, since those mutations never touch
     *                                   the location matrix
     * @param enabledPaymentMethodCount  10.4a "Способы оплаты"
     * @param enabledFulfillmentModes    10.4a "Типы получения", enabled only
     * @param icon                       10.4a's own presentation field, null until set
     * @param brandColorPrimary          six-digit hex (#rrggbb), null until set
     * @param brandColorSecondary        six-digit hex (#rrggbb), null until set
     */
    public record ChannelView(
            UUID id,
            String code,
            String systemType,
            String displayName,
            String status,
            @Nullable UUID pricePlaneChannelId,
            boolean externallyPriced,
            boolean guestOrdersAllowed,
            @Nullable UUID providerInstallationId,
            int version,
            int locationCount,
            int enabledPaymentMethodCount,
            List<String> enabledFulfillmentModes,
            @Nullable String icon,
            @Nullable String brandColorPrimary,
            @Nullable String brandColorSecondary) {

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
                    channel.version(),
                    0,
                    0,
                    List.of(),
                    channel.icon(),
                    channel.brandColorPrimary(),
                    channel.brandColorSecondary());
        }

        static ChannelView of(SalesChannelService.ChannelRegistrySummary summary) {
            SalesChannel channel = summary.channel();
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
                    channel.version(),
                    summary.locationCount(),
                    summary.enabledPaymentMethodCount(),
                    summary.enabledFulfillmentModes().stream().map(Enum::name).toList(),
                    channel.icon(),
                    channel.brandColorPrimary(),
                    channel.brandColorSecondary());
        }
    }
}
