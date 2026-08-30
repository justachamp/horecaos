package uz.horecaos.platform.iam.api.secrets;

/**
 * Resolves the value behind a secret reference (ADR 0028).
 *
 * <p>Callers ask for a reference they already legitimately hold; there is
 * deliberately no way to enumerate secrets.
 */
public interface SecretResolver {

    /**
     * Resolves through a bounded cache.
     *
     * <p>The returned value is owned by the resolver and shared with other
     * callers. Read it with {@code reveal()} and let it go; do <em>not</em> call
     * {@code dispose()} on it, which would clear the cached credential underneath
     * every other caller. Disposal is the resolver's to do at eviction.
     */
    SecretValue resolve(SecretReference reference);

    /**
     * Bypasses the cache after a provider authentication failure, so a rotation
     * that happened mid-cache does not present as a provider outage. Adapters
     * call this exactly once before classifying an authentication failure.
     */
    SecretValue resolveFresh(SecretReference reference);

    /** Thrown when a reference has no value in the configured manager. */
    class SecretNotFoundException extends RuntimeException {
        public SecretNotFoundException(SecretReference reference) {
            super("No secret is configured for " + reference);
        }
    }
}
