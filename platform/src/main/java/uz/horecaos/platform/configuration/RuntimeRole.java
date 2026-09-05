package uz.horecaos.platform.configuration;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Which half of the ADR 0023 {@code app}/{@code worker} split this process plays.
 *
 * <p>Resolved once, from {@code horecaos.runtime.role}. This is deployment-time process
 * topology — which container an operator started, not a fact about a tenant — so it is
 * plain Spring configuration rather than an ADR 0030 {@code PolicyResolver} key: ADR
 * 0030's own alternatives table already draws this line ("Spring {@code
 * @ConfigurationProperties} and profiles... correct for deployment-time platform
 * configuration... never for tenant-scoped values"), and a role is exactly the
 * deployment-time case it names.
 *
 * <p><b>Unset is {@link #BOTH}</b>, which is today's only behaviour: every {@code
 * @Scheduled} job and the inbox Kafka listener run, exactly as they do without this
 * class existing. That is deliberate — {@code make run}, every test, and the single
 * production container today all leave this property unset, and none of them may
 * change behaviour because this record was added.
 */
public enum RuntimeRole {

    /** Serves HTTP, Camel provider routes, and the two payment callback roots. Runs no background work. */
    APP,

    /** Runs background work only — {@code @Scheduled} jobs and the inbox listener. Carries no proxied traffic. */
    WORKER,

    /** Both halves in one process. Today's only deployed shape, and the default. */
    BOTH;

    static final String PROPERTY = "horecaos.runtime.role";

    /**
     * Whether background work — every {@code @Scheduled} method on the platform and the
     * ADR 0006 inbox Kafka listener — is allowed to run under this role.
     */
    public boolean runsWorkerWork() {
        return this != APP;
    }

    /**
     * Whether this role serves HTTP and the payment callback roots.
     *
     * <p>Named for symmetry with {@link #runsWorkerWork()}. Nothing in this build gates
     * on it yet: the split this record delivers only ever removes background work from
     * {@code app}, never HTTP from {@code worker} — routing traffic away from a
     * {@code worker} container is the reverse proxy's job, not this process's own, per
     * ADR 0023's runtime shape.
     */
    public boolean runsAppWork() {
        return this != WORKER;
    }

    /**
     * Parses {@code horecaos.runtime.role}. Blank or absent resolves to {@link #BOTH}.
     *
     * @throws IllegalStateException if set to anything other than {@code app}, {@code
     *     worker}, or {@code both} (case-insensitive) — failing at startup rather than
     *     silently running every job, or none of them, under a misspelled value
     */
    public static RuntimeRole fromProperty(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return BOTH;
        }
        try {
            return RuntimeRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notARole) {
            throw new IllegalStateException(
                    PROPERTY + " must be one of \"app\", \"worker\", \"both\"; was \"" + value + "\"", notARole);
        }
    }
}
