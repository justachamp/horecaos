package uz.horecaos.platform.tenancy.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * The tenant-facing channel registry (ADR 0036).
 *
 * <p>Registering a channel is tenant CRUD by design: a tenant signs a marketplace
 * on Monday and sells on it without waiting for a release. What the tenant cannot
 * do is invent a {@link SalesChannelSystemType}, because behaviour keys on the
 * type.
 */
@Service
public class SalesChannelService {

    private final JdbcSalesChannelStore store;
    private final Clock clock;

    public SalesChannelService(JdbcSalesChannelStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional
    public SalesChannel create(UUID tenantId, CreateChannelCommand command) {
        SalesChannelSystemType systemType = SalesChannelSystemType.require(command.systemType());
        UUID pricePlaneChannelId = validatedPricePlane(tenantId, command.pricePlaneChannelId());

        SalesChannel channel = new SalesChannel(
                UUID.randomUUID(), tenantId, command.code(), systemType, command.displayName(),
                SalesChannel.Status.ACTIVE, pricePlaneChannelId,
                command.externallyPriced(), command.guestOrdersAllowed(),
                command.providerInstallationId(), 1);
        try {
            store.insert(channel, clock.instant());
        } catch (DataIntegrityViolationException violation) {
            throw JdbcSalesChannelStore.explain(violation);
        }
        return channel;
    }

    @Transactional(readOnly = true)
    public List<SalesChannel> list(UUID tenantId) {
        return store.listForTenant(tenantId);
    }

    @Transactional(readOnly = true)
    public SalesChannel require(UUID tenantId, UUID channelId) {
        return store.byId(tenantId, channelId)
                .orElseThrow(() -> new TenantResourceNotFoundException(
                        "No sales channel %s for this tenant".formatted(channelId)));
    }

    /**
     * Retires a channel without deleting it.
     *
     * <p>Refused while another channel prices through this one, because archiving
     * the price plane would leave the dependent channel silently falling back to
     * brand prices — a price change nobody made, visible only on the receipt.
     */
    @Transactional
    public SalesChannel archive(UUID tenantId, UUID channelId, int expectedVersion) {
        SalesChannel channel = require(tenantId, channelId);
        if (store.isPricePlaneForAnother(tenantId, channelId)) {
            throw new TenantResourceConflictException(
                    "Another channel takes its prices from this one; repoint it before archiving");
        }
        if (!store.updateStatus(tenantId, channelId, SalesChannel.Status.ARCHIVED,
                expectedVersion, clock.instant())) {
            throw new TenantResourceConflictException("The channel changed since it was read");
        }
        return new SalesChannel(channel.id(), channel.tenantId(), channel.code(),
                channel.systemType(), channel.displayName(), SalesChannel.Status.ARCHIVED,
                channel.pricePlaneChannelId(), channel.externallyPriced(),
                channel.guestOrdersAllowed(), channel.providerInstallationId(),
                channel.version() + 1);
    }

    /**
     * Replaces the payment matrix wholesale.
     *
     * <p>Whole-matrix and never per-cell: a matrix edited cell by cell from two
     * tabs produces a combination neither operator chose. The expected version is
     * what makes the second writer lose visibly.
     */
    @Transactional
    public void replacePaymentMethods(UUID tenantId, UUID channelId,
            Map<String, Boolean> matrix, int expectedVersion) {
        require(tenantId, channelId);
        if (!store.replacePaymentMethods(tenantId, channelId, matrix, expectedVersion, clock.instant())) {
            throw new TenantResourceConflictException("The channel changed since it was read");
        }
    }

    @Transactional
    public void replaceFulfillmentModes(UUID tenantId, UUID channelId,
            Map<FulfillmentMode, Boolean> matrix, int expectedVersion) {
        require(tenantId, channelId);
        if (!store.replaceFulfillmentModes(tenantId, channelId, matrix, expectedVersion, clock.instant())) {
            throw new TenantResourceConflictException("The channel changed since it was read");
        }
    }

    @Transactional
    public void replaceLocations(UUID tenantId, UUID channelId, List<UUID> locationIds,
            int expectedVersion) {
        require(tenantId, channelId);
        try {
            if (!store.replaceLocations(tenantId, channelId, locationIds, expectedVersion,
                    clock.instant())) {
                throw new TenantResourceConflictException("The channel changed since it was read");
            }
        } catch (DataIntegrityViolationException violation) {
            throw JdbcSalesChannelStore.explain(violation);
        }
    }

    @Transactional(readOnly = true)
    public ChannelMatrices matrices(UUID tenantId, UUID channelId) {
        require(tenantId, channelId);
        return new ChannelMatrices(
                store.paymentMethods(tenantId, channelId),
                store.fulfillmentModes(tenantId, channelId),
                store.locations(tenantId, channelId));
    }

    /**
     * A price plane must exist, belong to this tenant, and not itself have one.
     *
     * <p>One hop, never a chain. The resolver reads
     * {@code COALESCE(price_plane_channel_id, id)} and does not recurse, so a chain
     * would resolve to the middle of it — and a cycle of two channels each pointing
     * at the other would be a configuration nobody could reason about.
     */
    private UUID validatedPricePlane(UUID tenantId, UUID pricePlaneChannelId) {
        if (pricePlaneChannelId == null) {
            return null;
        }
        SalesChannel plane = store.byId(tenantId, pricePlaneChannelId)
                .orElseThrow(() -> new TenantResourceNotFoundException(
                        "No sales channel %s for this tenant".formatted(pricePlaneChannelId)));
        if (plane.pricePlaneChannelId() != null) {
            throw new TenantResourceConflictException(
                    "A price plane may not itself take prices from another channel");
        }
        return plane.id();
    }

    public record CreateChannelCommand(
            String code,
            String systemType,
            String displayName,
            UUID pricePlaneChannelId,
            boolean externallyPriced,
            boolean guestOrdersAllowed,
            UUID providerInstallationId) { }

    public record ChannelMatrices(
            Map<String, Boolean> paymentMethods,
            Map<FulfillmentMode, Boolean> fulfillmentModes,
            List<UUID> locationIds) { }
}
