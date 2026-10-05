package uz.horecaos.platform.partner.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.inventory.api.ChannelAvailabilityPort;
import uz.horecaos.platform.inventory.api.ChannelAvailabilityPort.ChannelAvailability;
import uz.horecaos.platform.partner.api.PartnerPrincipal;
import uz.horecaos.platform.partner.infrastructure.persistence.JdbcPartnerStore;
import uz.horecaos.platform.partner.infrastructure.persistence.JdbcPartnerStore.MappedMenuItem;
import uz.horecaos.platform.partner.infrastructure.persistence.JdbcPartnerStore.PullBinding;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * What an aggregator reads when it polls a branch's availability (ADR 0141 Phase 4, ADR 0040's
 * {@code GET .../restaurants/{locationId}/availability}).
 *
 * <p>The pull is the second consumer of the one resolver, beside the reconciler's push: it asks
 * {@link ChannelAvailabilityPort} the same question at the same call's own instant, so what a
 * partner is told by polling can never be a second notion of "available" that drifts from what the
 * storefront refuses and what the reconciler pushes. Nothing is cached between polls and nothing is
 * read from the reconciler's own rows: those hold what was <em>last pushed</em>, which is the thing
 * a partner that polls is trying to avoid depending on.
 *
 * <h2>Reach</h2>
 *
 * <p>The branch in the path is reachable only through a binding the caller's own credential holds
 * ({@link PartnerPrincipal#covers}); a branch that exists but belongs to another partner's
 * installation answers exactly what an unknown one does, "not found", so the response never tells a
 * partner which of its guesses was nearest. The channel behind the binding is the {@code
 * tenant.sales_channels} row whose {@code provider_installation_id} is the binding's installation
 * (ADR 0036). Where that is not exactly one active channel the answer is a conflict rather than an
 * empty or an all-available list: a partner that read "everything is on sale" off an unfinished
 * configuration would sell dishes the kitchen has stopped.
 *
 * <h2>What it carries</h2>
 *
 * <p>The partner's own item identifier and one boolean per mapped dish, in the partner's identifier
 * order, in cursor pages. No dish name, no reason for a stop, no quantity and nothing about a
 * customer: availability is binary (ADR 0040) and the reason an operator stopped a dish is the
 * tenant's own business (ADR 0029).
 */
@Service
public class MarketplaceAvailabilityPullService {

    /** Items per page when the partner names no limit: a typical menu in two or three calls. */
    public static final int DEFAULT_LIMIT = 200;

    /** The most a partner may ask for in one page. */
    public static final int MAXIMUM_LIMIT = 200;

    private final JdbcPartnerStore store;
    private final ChannelAvailabilityPort inventory;
    private final SalesChannelLookup channels;
    private final Clock clock;

    public MarketplaceAvailabilityPullService(
            JdbcPartnerStore store, ChannelAvailabilityPort inventory, SalesChannelLookup channels, Clock clock) {
        this.store = store;
        this.inventory = inventory;
        this.channels = channels;
        this.clock = clock;
    }

    /** One page of a branch's availability as of {@code asOf}. */
    public record AvailabilityPage(
            UUID locationId,
            Instant asOf,
            List<AvailabilityItem> items,
            @Nullable String nextCursor) {}

    /** One mapped dish: the partner's identifier and whether it may be sold on this channel here now. */
    public record AvailabilityItem(String externalItemId, boolean available) {}

    /**
     * @param cursor the {@code nextCursor} of the previous page, or null for the first
     * @param limit the page size, 1 to {@link #MAXIMUM_LIMIT}; null for {@link #DEFAULT_LIMIT}
     * @throws ApiException {@code RESOURCE_NOT_FOUND} when the branch is not one of the caller's
     *     bound branches; {@code RESOURCE_CONFLICT} ({@code CHANNEL_NOT_CONFIGURED}) when no single
     *     active channel backs the binding; {@code INVALID_REQUEST} for a limit or cursor that is not
     *     one this service handed out
     */
    public AvailabilityPage read(
            PartnerPrincipal principal, UUID locationId, @Nullable String cursor, @Nullable Integer limit) {
        int pageSize = pageSize(limit);
        String after = decode(cursor);
        Instant now = clock.instant();

        PullBinding binding = store.findBindingAtLocation(principal.tenantId(), principal.bindingIds(), locationId, now)
                .filter(found -> principal.covers(found.bindingId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such restaurant"));

        SalesChannel channel = channels.byProviderInstallation(principal.tenantId(), binding.installationId())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "This restaurant has no single active channel for the integration yet",
                        Map.of("conflict", "CHANNEL_NOT_CONFIGURED")));

        // One more than the page, to know whether there is a next one without a count (ADR 0031).
        List<MappedMenuItem> fetched =
                store.mappedMenuItemsAfter(principal.tenantId(), binding.bindingId(), after, pageSize + 1);
        boolean more = fetched.size() > pageSize;
        List<MappedMenuItem> page = more ? fetched.subList(0, pageSize) : fetched;

        Set<UUID> variants = new HashSet<>();
        page.forEach(item -> variants.add(item.variantId()));
        Map<UUID, ChannelAvailability> resolved = variants.isEmpty()
                ? Map.of()
                : inventory.resolve(principal.tenantId(), binding.brandId(), locationId, channel.id(), variants, now);

        List<AvailabilityItem> items = page.stream()
                .map(item -> {
                    ChannelAvailability availability = resolved.get(item.variantId());
                    // An item the resolver says nothing about is not sellable: fail toward
                    // under-selling, as the reconciler does.
                    return new AvailabilityItem(item.externalItemId(), availability != null && availability.sellable());
                })
                .toList();
        String next = more ? encode(page.get(page.size() - 1).externalItemId()) : null;
        return new AvailabilityPage(locationId, now, items, next);
    }

    private static int pageSize(@Nullable Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "limit must be at least 1");
        }
        return Math.min(limit, MAXIMUM_LIMIT);
    }

    /** Opaque to the partner: it is only ever handed back. */
    private static String encode(String lastExternalItemId) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(lastExternalItemId.getBytes(StandardCharsets.UTF_8));
    }

    private static @Nullable String decode(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "cursor is not one this endpoint returned");
        }
    }
}
