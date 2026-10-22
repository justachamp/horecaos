package uz.horecaos.platform.configuration;

import org.jspecify.annotations.Nullable;

/**
 * What one retention setting means for one tenant right now (Settings 10.11, ADR 0109).
 *
 * <p>A value a tenant types into a retention field and the number a job acts on are not the same thing, and the gap used to
 * be invisible: the sweeps took the longer of the platform default and the largest value any tenant had stored, so a tenant that
 * asked for thirty days got ninety and was told nothing (ADR 0109's own negative consequence). This is the answer to "what
 * will actually happen", read from the job that does it, so the card can show it beside the field.
 *
 * @param code            the ADR 0030 key the tenant edits
 * @param unit            what the numbers count
 * @param platformDefault what applies when nobody configured anything
 * @param floor           the smallest value the platform will act on; a value below it is raised to it
 * @param configured      the value this tenant stored itself, or null when it inherits
 * @param effective       the window the job uses for this tenant's data today
 * @param scope           whether {@code effective} is this tenant's alone or shared with every tenant
 * @param enforcedBy      the job that applies it, by simple class name, for a reader who has to find it
 */
public record RetentionWindow(
        String code,
        Unit unit,
        int platformDefault,
        int floor,
        @Nullable Integer configured,
        int effective,
        Scope scope,
        String enforcedBy) {

    /** What the numbers count. */
    public enum Unit {
        DAYS,
        MONTHS
    }

    /** Whose data the effective window governs. */
    public enum Scope {
        /** The job sweeps each tenant on its own window, so a shorter value is honoured down to the floor. */
        PER_TENANT,

        /**
         * The data of every tenant shares one physical unit (a daily partition), so the job can only act on the longest
         * window anybody needs, and a shorter value cannot shorten what the others keep.
         */
        ALL_TENANTS
    }
}
