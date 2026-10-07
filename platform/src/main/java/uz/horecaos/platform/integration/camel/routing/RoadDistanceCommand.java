package uz.horecaos.platform.integration.camel.routing;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * One question for the road-distance route (ADR 0147): two points and the tariff version's
 * routing installation.
 *
 * <p>Both points are personal data once one of them is a customer's delivery address (ADR
 * 0029: a precise location is as identifying as the address beside it). Camel writes
 * exchange bodies into route logs and into the messages of the exceptions it wraps, so the
 * generated {@code toString} of a record that held them would put a customer's location in
 * a log line on the first routing failure. It prints neither.
 */
public record RoadDistanceCommand(
        GeoPoint origin, GeoPoint destination, @Nullable UUID installationId) {

    @Override
    public String toString() {
        return "RoadDistanceCommand[REDACTED]";
    }
}
