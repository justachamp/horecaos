package uz.horecaos.platform.kitchen.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The {@code kitchen.paid_hold} policy document (ADR 0168 Decision 8, ADR 0030): whether a buffered
 * ticket is held while its order's payment is required before the kitchen and has not arrived.
 *
 * <p><strong>The built-in default is {@link Mode#SHADOW}</strong> with ten minutes: the sweep and the
 * amendment listener evaluate every ticket exactly as they would under {@link Mode#HOLD_UNTIL_PAID} and
 * count what they would have held, and a ticket is <em>never</em> held in shadow. The owner moves the
 * platform by publishing at the platform scope after a fortnight on the pilot tenants; a tenant, brand or
 * location that wants its own mode publishes at its own scope and keeps it whatever the platform value
 * becomes (resolution is most-specific-wins and replaces, never merges).
 *
 * @param schema               the document version, always 1 today
 * @param mode                 what the platform does with a ticket whose payment is required and absent
 * @param overdueAfterMinutes  minutes past a blocked ticket's fire instant before the board treats it as
 *                             stuck, one to a day
 */
public record KitchenPaidHoldPolicy(int schema, Mode mode, int overdueAfterMinutes) {

    public static final int SCHEMA = 1;
    public static final int DEFAULT_OVERDUE_AFTER_MINUTES = 10;
    public static final int MIN_OVERDUE_AFTER_MINUTES = 1;
    public static final int MAX_OVERDUE_AFTER_MINUTES = 1440;

    /** What the platform does with such a ticket. */
    public enum Mode {

        /** Evaluates and counts what it would hold, and holds nothing. The platform default. */
        SHADOW,

        /** Holds the ticket until the money is held, or a named person overrides. */
        HOLD_UNTIL_PAID,

        /** Neither holds nor counts. The per-scope off switch. */
        OFF
    }

    /** The document in force when nothing is published anywhere in the chain. */
    public static final KitchenPaidHoldPolicy BUILT_IN =
            new KitchenPaidHoldPolicy(SCHEMA, Mode.SHADOW, DEFAULT_OVERDUE_AFTER_MINUTES);

    public KitchenPaidHoldPolicy {
        Objects.requireNonNull(mode, "A paid-hold policy names its mode");
    }

    /** Why this document may not be published, as sentences; empty when it may. */
    public List<String> violations() {
        List<String> found = new ArrayList<>();
        if (schema != SCHEMA) {
            found.add("schema must be %d".formatted(SCHEMA));
        }
        if (overdueAfterMinutes < MIN_OVERDUE_AFTER_MINUTES || overdueAfterMinutes > MAX_OVERDUE_AFTER_MINUTES) {
            found.add("overdueAfterMinutes must be between %d and %d"
                    .formatted(MIN_OVERDUE_AFTER_MINUTES, MAX_OVERDUE_AFTER_MINUTES));
        }
        return List.copyOf(found);
    }
}
