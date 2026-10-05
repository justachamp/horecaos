package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Which ADR 0026 installation a sales channel is backed by, or whether that channel is
 * active, has just changed (ADR 0036, ADR 0141 Decision 7).
 *
 * <p>The marketplace reconciler resolves a binding's channel as "the active {@code
 * tenant.sales_channels} row whose {@code provider_installation_id} is the binding's
 * installation", so pointing a channel at an installation, away from one, or pausing or
 * retiring it changes what every binding of that installation resolves its stops and menu
 * against.
 *
 * <p>An in-process fact, not an outbox event, in the genre of {@code
 * catalog.api.ChannelAssortmentChanged}: it carries identifiers and nothing a consumer
 * could act on except "recompute". The reconciler listens only to ask for an early sweep —
 * an accelerator; the resync sweep recomputes the channel of every binding at its own
 * {@code now} whether or not this was heard — so it is published after every write that
 * can move that mapping and never needs to be complete to be safe.
 *
 * @param installationIds every installation the change touches: the one the channel is now
 *     backed by and, when it moved, the one it used to be; empty only for a channel with no
 *     installation before or after, which nothing needs to hear
 */
public record SalesChannelInstallationChanged(
        UUID tenantId, UUID channelId, Set<UUID> installationIds, Instant occurredAt) {

    public SalesChannelInstallationChanged {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(channelId, "Channel ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        installationIds = Set.copyOf(Objects.requireNonNull(installationIds, "Installation IDs are required"));
    }

    /** The two installations a channel straddles when it is repointed; either may be absent. */
    public static SalesChannelInstallationChanged of(
            UUID tenantId,
            UUID channelId,
            @Nullable UUID previousInstallationId,
            @Nullable UUID currentInstallationId,
            Instant occurredAt) {
        Set<UUID> touched = new LinkedHashSet<>();
        if (previousInstallationId != null) {
            touched.add(previousInstallationId);
        }
        if (currentInstallationId != null) {
            touched.add(currentInstallationId);
        }
        return new SalesChannelInstallationChanged(tenantId, channelId, touched, occurredAt);
    }
}
