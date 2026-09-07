package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A sales channel became sellable (ADR 0036).
 *
 * <p>Fired once, from {@code SalesChannelService.create}: a channel this build
 * creates is {@code ACTIVE} from its first row, and there is no separate
 * "activate a draft channel" step yet. A future reactivation path (ADR 0021's
 * plan enforcement moving a channel back from {@code INACTIVE}) fires this
 * event again rather than inventing a second one — "this channel may now sell"
 * is the same fact either time.
 */
public record SalesChannelActivated(
        UUID eventId,
        TenantId tenantId,
        UUID channelId,
        Instant occurredAt,
        String code,
        String systemType,
        int version)
        implements TenancyEvent {

    public SalesChannelActivated {
        Objects.requireNonNull(eventId, "Event ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(channelId, "Channel ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        Objects.requireNonNull(code, "Channel code is required");
        Objects.requireNonNull(systemType, "Channel system type is required");
    }

    @Override
    public String eventType() {
        return "SalesChannelActivated";
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
        return new Payload(channelId, code, systemType, version);
    }

    public record Payload(UUID channelId, String code, String systemType, int version) {}
}
