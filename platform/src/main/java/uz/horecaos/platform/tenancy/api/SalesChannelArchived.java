package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A sales channel was retired (ADR 0036).
 *
 * <p>Channels archive, never delete: every order carries its channel forever,
 * and a deleted row would make that order unattributable in every report. This
 * is the fact a consumer needs to stop treating the channel as sellable — the
 * price plane, catalog publication, and cart-opening paths all refuse an
 * archived channel independently, and this event lets a cache or a projection
 * catch up without polling.
 */
public record SalesChannelArchived(
        UUID eventId, TenantId tenantId, UUID channelId, Instant occurredAt, String code, int version)
        implements TenancyEvent {

    public SalesChannelArchived {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(channelId, "Channel ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(code, "Channel code is required");
    }

    @Override
    public String eventType() {
        return "SalesChannelArchived";
    }

    @Override
    public int eventVersion() {
        return 1;
    }

    @Override
    public String aggregateType() {
        return "SalesChannel";
    }

    @Override
    public UUID aggregateId() {
        return channelId;
    }

    @Override
    public Object payload() {
        return new Payload(channelId, code, version);
    }

    public record Payload(UUID channelId, String code, int version) {}
}
