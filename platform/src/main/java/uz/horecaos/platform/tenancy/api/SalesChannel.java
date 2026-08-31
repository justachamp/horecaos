package uz.horecaos.platform.tenancy.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A registered route to market (ADR 0036).
 *
 * <p>Tenant-owned data carrying a code-owned {@link SalesChannelSystemType}. A
 * channel gates exactly four things — payment methods, fulfilment modes, catalog
 * visibility, and the price plane — and nothing else keys on it. In particular a
 * channel is <em>not</em> a fifth level of the ADR 0030 scope chain: that chain
 * is ancestry and channel is orthogonal to it, so inserting it would produce a
 * lattice in which a location override and a channel override have no defined
 * winner.
 */
public record SalesChannel(
        UUID id,
        UUID tenantId,
        String code,
        SalesChannelSystemType systemType,
        String displayName,
        Status status,
        @Nullable UUID pricePlaneChannelId,
        boolean externallyPriced,
        boolean guestOrdersAllowed,
        @Nullable UUID providerInstallationId,
        int version) {

    /** Channels archive, never delete: every order carries its channel forever. */
    public enum Status {
        ACTIVE,
        INACTIVE,
        ARCHIVED
    }

    public SalesChannel {
        Objects.requireNonNull(id, "A channel id is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(code, "A channel code is required");
        Objects.requireNonNull(systemType, "A channel system type is required");
        Objects.requireNonNull(status, "A channel status is required");
    }

    /** Whether a new cart may be opened on this channel. */
    public boolean sellable() {
        return status == Status.ACTIVE;
    }

    /**
     * The channel whose price-book assignments price this one.
     *
     * <p>Exactly one hop and never a chain: "for QR and kiosk take the hall's
     * prices" is one column, and a chain would let a cycle of two channels each
     * pointing at the other hang the pricing path. {@code SalesChannelService}
     * refuses to point a plane at a channel that itself has one, so the single
     * hop here is complete rather than approximate.
     */
    public UUID pricingChannelId() {
        return pricePlaneChannelId == null ? id : pricePlaneChannelId;
    }

    public Optional<UUID> providerInstallation() {
        return Optional.ofNullable(providerInstallationId);
    }
}
