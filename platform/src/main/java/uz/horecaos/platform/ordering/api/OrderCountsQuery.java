package uz.horecaos.platform.ordering.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Live, unfiltered order counts for a scope, the read model a 15-minute
 * supervisor digest polls (ADR 0058).
 *
 * <p>Deliberately not point-in-time: {@code ordering.orders.status} is the
 * present tense, so a count of it is current as of the call rather than as of
 * any stored fact, which is exactly what a live pulse needs and what the
 * day-grain ADR 0043 facts cannot give before a business day closes.
 */
public interface OrderCountsQuery {

    /**
     * The live counts for one scope.
     *
     * @param locationId the location to scope to, or null for every location in
     *                   the brand — a flat operations group has no single
     *                   location to ask about
     */
    OrderCounts liveCounts(UUID tenantId, UUID brandId, @Nullable UUID locationId);
}
