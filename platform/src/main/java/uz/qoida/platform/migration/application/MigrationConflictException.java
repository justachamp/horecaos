package uz.qoida.platform.migration.application;

/**
 * The caller's view of the control plane is out of date, or something else
 * already holds the claim they asked for (ADR 0031).
 *
 * <p>Carries the version it expected and the version it found where the failure
 * was an optimistic-concurrency one, because the console's answer to a losing
 * operator is to re-read at the version that actually won rather than to retry
 * blindly. Both are {@code null} for a conflict that is not about a version — a
 * second scope claiming a capability another scope already claims, a second run
 * of a type that already has a live one.
 */
public class MigrationConflictException extends RuntimeException {

    private final Integer expectedVersion;
    private final Integer actualVersion;

    public MigrationConflictException(String message) {
        this(message, null, null);
    }

    public MigrationConflictException(String message, Integer expectedVersion, Integer actualVersion) {
        super(message);
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    /** The version the caller believed it was acting on, or null. */
    public Integer expectedVersion() {
        return expectedVersion;
    }

    /** The version actually stored, or null. */
    public Integer actualVersion() {
        return actualVersion;
    }

    static MigrationConflictException staleVersion(String subject, int expected, int actual) {
        return new MigrationConflictException(
                "The %s has changed since version %d was read".formatted(subject, expected),
                expected, actual);
    }
}
