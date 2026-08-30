package uz.qoida.platform.integration.camel.delivery.noor;

import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps Noor delivery stages onto Qoida's shipment states (ADR 0014).
 *
 * <p><b>This table is a verified subset, not the whole enum.</b> Noor publishes
 * roughly thirty stages; the ones below are those confirmed against the partner
 * collection and recorded in ADR 0014. Anything not listed maps to
 * {@code UNKNOWN} and is logged.
 *
 * <p>That is deliberate. Guessing at a stage name means a delivery that has
 * failed can be read as delivered, or a live one as cancelled — a silent wrong
 * answer in the part of the system that decides whether a customer gets their
 * food. {@code UNKNOWN} instead stops the state machine somewhere visible, and
 * the log line names the stage so the table can be completed from real traffic.
 * Fill this in from the partner collection before Noor carries production orders.
 */
final class NoorStage {

    private static final Logger log = LoggerFactory.getLogger(NoorStage.class);

    /** Keys are lower-cased: Noor sends PascalCase, and casing has drifted before. */
    private static final Map<String, String> CONFIRMED = Map.ofEntries(
            Map.entry("new", "CONFIRMED"),
            Map.entry("estimating", "CONFIRMED"),
            Map.entry("searching", "CONFIRMED"),
            Map.entry("performerfound", "CONFIRMED"),
            Map.entry("pickuparrived", "CONFIRMED"),
            Map.entry("pickuped", "IN_TRANSIT"),
            Map.entry("deliveryarrived", "IN_TRANSIT"),
            Map.entry("delivered", "DELIVERED"),
            Map.entry("canceled", "CANCELLED"),
            Map.entry("cancelled", "CANCELLED"),
            // Business rejections, not transport faults: ADR 0006 routes these to
            // a re-source decision rather than an infrastructure retry.
            Map.entry("performernotfound", "FAILED"),
            Map.entry("cancelledoutofzone", "FAILED"),
            Map.entry("cancelledoutofrange", "FAILED"),
            Map.entry("estimatingfailed", "FAILED"));

    /** Stages where a courier is committed, making cancellation potentially costly. */
    private static final Map<String, Boolean> LIVE = Map.of(
            "CONFIRMED", true,
            "IN_TRANSIT", true,
            "DELIVERED", true,
            "CANCELLED", false,
            "FAILED", false,
            "UNKNOWN", false);

    private NoorStage() {
    }

    static String toShipmentState(String stage) {
        if (stage == null) {
            return "UNKNOWN";
        }
        String mapped = CONFIRMED.get(stage.toLowerCase(Locale.ROOT));
        if (mapped == null) {
            log.warn("Unmapped Noor stage '{}' — treating as UNKNOWN. Add it to NoorStage.", stage);
            return "UNKNOWN";
        }
        return mapped;
    }

    /**
     * An unmapped stage reports not-live. A caller reading this to decide whether
     * cancelling is safe should be reading {@code state == UNKNOWN} first and
     * reconciling, which is why this is the conservative answer rather than the
     * cautious-sounding {@code true}.
     */
    static boolean isLive(String stage) {
        return LIVE.getOrDefault(toShipmentState(stage), false);
    }
}
