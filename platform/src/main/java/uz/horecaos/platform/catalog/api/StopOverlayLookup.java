package uz.horecaos.platform.catalog.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The stops in force at a branch, for the stop list and the New order picker
 * (ADR 0141 Decision 1: "the stop-list page ... call [the resolver]").
 *
 * <p>Declared here and implemented by inventory, the same direction as {@link
 * MenuAvailabilityLookup}, so catalog never learns what an {@code
 * availability_stops} row is. {@code CatalogAuthoringService} lays the answer
 * over the rows its own SQL read; without an implementation there are no stops
 * and the rows are exactly what that SQL says.
 */
public interface StopOverlayLookup {

    /** The stops in force now that touch any of these variants at this branch. */
    Map<UUID, List<StopFact>> stopsAtLocation(UUID tenantId, UUID brandId, UUID locationId, Set<UUID> variantIds);

    /**
     * Every variant a stop in force covers on all channels at this branch ({@code
     * LOCATION} or {@code BRAND} scope) — what the tab badges subtract from "available".
     */
    Set<UUID> variantsStoppedOnEveryChannel(UUID tenantId, UUID brandId, UUID locationId);

    /**
     * One stop, in the words the stop list shows. {@code scopeType} and {@code
     * source} are the stable names ({@code BRAND}, {@code POS}, ...), never
     * translated here.
     *
     * @param everyChannel whether it stops the dish on every channel here (the
     *     dish is simply on stop) or only on some (a partial stop the list marks as such)
     */
    record StopFact(
            UUID stopId,
            UUID variantId,
            String scopeType,
            String source,
            String reasonCode,
            @Nullable Instant endsAt,
            Instant createdAt,
            @Nullable UUID locationId,
            @Nullable UUID menuId,
            @Nullable UUID channelId,
            boolean everyChannel,
            int version) {}
}
