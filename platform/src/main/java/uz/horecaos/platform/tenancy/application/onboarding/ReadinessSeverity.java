package uz.horecaos.platform.tenancy.application.onboarding;

/**
 * How much a readiness finding matters, in the order the settings home lists
 * them (settings.md §10.0: «blocking (0) → expiring (1) → advisory (2)»).
 *
 * <ul>
 *   <li>{@link #BLOCKING} — the restaurant cannot trade, or a customer's order
 *       fails, until it is fixed. The only tier that turns {@code allPassed}
 *       false.
 *   <li>{@link #EXPIRING} — nothing is broken today, but a clock is running and
 *       on a known date it becomes {@link #BLOCKING} (a fiscal assignment that
 *       ends and has no successor). It is worth a person's attention before the
 *       date and does not stop trade now.
 *   <li>{@link #ADVISORY} — worth an operator's attention, with no date attached
 *       and no stop.
 * </ul>
 */
public enum ReadinessSeverity {
    BLOCKING,
    EXPIRING,
    ADVISORY;

    /** Whether a finding of this tier is left out of {@code ValidationOutcome#allPassed()}. */
    public boolean advisory() {
        return this != BLOCKING;
    }
}
