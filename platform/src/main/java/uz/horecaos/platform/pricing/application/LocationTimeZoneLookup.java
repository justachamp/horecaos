package uz.horecaos.platform.pricing.application;

import java.time.ZoneId;
import java.util.UUID;

/**
 * The IANA zone a location keeps time in (ADR 0140).
 *
 * <p>A "lunch 12:00 to 15:00" promotion means lunchtime where the branch is, and
 * the engine may read neither a clock nor a zone, so pricing resolves the local day
 * and minute from this zone before the engine runs. The previous resolution used
 * UTC, which would have fired a Tashkent (UTC+5) lunch rule five hours early.
 */
public interface LocationTimeZoneLookup {

    /** The location's zone, or UTC when it has none that resolves (loudly wrong is better than a crash on the pricing path). */
    ZoneId zoneOf(UUID tenantId, UUID locationId);
}
