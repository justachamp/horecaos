package uz.horecaos.platform.tenancy.application;

/**
 * The caller's {@code If-Match} names a version that is no longer current
 * (ADR 0031), with both versions so the response can report them.
 */
public final class TenantResourceStaleException extends RuntimeException {

    private final long expected;
    private final long actual;

    public TenantResourceStaleException(long expected, long actual) {
        super("The resource has changed since version %d was read".formatted(expected));
        this.expected = expected;
        this.actual = actual;
    }

    public long expected() {
        return expected;
    }

    public long actual() {
        return actual;
    }
}
