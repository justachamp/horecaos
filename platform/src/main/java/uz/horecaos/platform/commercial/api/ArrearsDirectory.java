package uz.horecaos.platform.commercial.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The subscriptions that have been past due since before a given moment (ADR 0089).
 *
 * <p>For the sweeper that asks a person to review a tenant in arrears. It
 * answers identifiers and a time, nothing about the tenant's people.
 */
public interface ArrearsDirectory {

    /** Past-due subscriptions whose status last moved before {@code before}, longest first. */
    List<Arrear> pastDueSince(Instant before, int limit);

    /** One subscription in arrears, and since when. */
    record Arrear(UUID tenantId, UUID subscriptionId, Instant since) {}
}
