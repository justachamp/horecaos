package uz.horecaos.platform.partner.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.catalog.api.ChannelMenuPort;
import uz.horecaos.platform.catalog.api.ChannelMenuPort.ChannelMenu;
import uz.horecaos.platform.partner.api.PartnerPrincipal;
import uz.horecaos.platform.partner.infrastructure.persistence.JdbcPartnerStore;
import uz.horecaos.platform.partner.infrastructure.persistence.JdbcPartnerStore.PullBinding;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * What an aggregator reads when it pulls a branch's menu (ADR 0040 "Menu is pull-first", ADR 0138
 * "push consumes the same projection"): {@code GET .../restaurants} and {@code GET
 * .../restaurants/{locationId}/menu}.
 *
 * <p>The menu is the channel's own assembled menu -- {@link ChannelMenuPort}, which is the storefront's
 * read for the partner's channel -- so what a partner is told is what a customer on that channel is shown
 * and what the preview drew, never a separate export that can drift. Each variant is identified by the
 * platform's id and, where the partner has mapped it ({@code MENU_ITEM}, ADR 0026), by the partner's own
 * item id as well, so a partner that keeps its own catalogue can join the two without a lookup table of
 * its own.
 *
 * <h2>Reach</h2>
 *
 * <p>Exactly the availability pull's (see {@link MarketplaceAvailabilityPullService}): a branch is reachable
 * only through a binding the caller's own credential holds, a branch that belongs to another partner's
 * installation answers what an unknown one does, and where no single active channel backs the binding the
 * answer is a conflict and never an empty menu a partner could read as "this restaurant sells nothing".
 */
@Service
public class MarketplaceMenuPullService {

    private final JdbcPartnerStore store;
    private final ChannelMenuPort menus;
    private final SalesChannelLookup channels;
    private final Clock clock;

    public MarketplaceMenuPullService(
            JdbcPartnerStore store, ChannelMenuPort menus, SalesChannelLookup channels, Clock clock) {
        this.store = store;
        this.menus = menus;
        this.channels = channels;
        this.clock = clock;
    }

    /** One branch the partner is bound to. */
    public record Restaurant(UUID locationId, String name, boolean channelConfigured) {}

    /**
     * A branch's menu and the partner's own ids for its variants.
     *
     * @param partnerItemIds the partner's identifier for a variant, for each variant that has a mapping
     */
    public record PulledMenu(UUID locationId, ChannelMenu menu, Map<UUID, String> partnerItemIds) {}

    /** The branches this credential is bound to, by name. */
    public List<Restaurant> restaurants(PartnerPrincipal principal) {
        Instant now = clock.instant();
        return store.boundRestaurants(principal.tenantId(), principal.bindingIds(), now).stream()
                .filter(restaurant -> principal.covers(restaurant.bindingId()))
                .map(restaurant -> new Restaurant(
                        restaurant.locationId(),
                        restaurant.name(),
                        channels.byProviderInstallation(principal.tenantId(), restaurant.installationId())
                                .isPresent()))
                .sorted(java.util.Comparator.comparing(Restaurant::name).thenComparing(Restaurant::locationId))
                .toList();
    }

    /**
     * @param locale the wording language, or null for the brand's own default
     * @throws ApiException {@code RESOURCE_NOT_FOUND} when the branch is not one of the caller's bound
     *     branches; {@code RESOURCE_CONFLICT} ({@code CHANNEL_NOT_CONFIGURED}) when no single active channel
     *     backs the binding, or ({@code MENU_NOT_PUBLISHED}) when nothing has been published to the channel
     */
    public PulledMenu menu(PartnerPrincipal principal, UUID locationId, @Nullable String locale) {
        Instant now = clock.instant();
        PullBinding binding = store.findBindingAtLocation(principal.tenantId(), principal.bindingIds(), locationId, now)
                .filter(found -> principal.covers(found.bindingId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such restaurant"));

        SalesChannel channel = channels.byProviderInstallation(principal.tenantId(), binding.installationId())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "This restaurant has no single active channel for the integration yet",
                        Map.of("conflict", "CHANNEL_NOT_CONFIGURED")));

        ChannelMenu menu = menus.liveMenu(principal.tenantId(), binding.brandId(), locationId, channel.code(), locale)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "No menu has been published to this channel yet",
                        Map.of("conflict", "MENU_NOT_PUBLISHED")));
        return new PulledMenu(
                locationId, menu, store.mappedItemIdsByVariant(principal.tenantId(), binding.bindingId()));
    }
}
