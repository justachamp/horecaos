package uz.horecaos.platform.integration.provider;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * ADR 0026 provider environment reference data: platform-owned, never
 * tenant-writable, and changed only by a deployment migration adding or
 * editing a row in {@code integration.provider_environments} — exactly what
 * ADR 0033's {@code integration.environments} cache is registered for.
 *
 * <p>Deliberately narrower than {@link JdbcProviderInstallationLookup}'s own
 * per-tenant installation state (status, secret reference): those can change
 * at any moment through an operator action or a provider event, and {@link
 * JdbcProviderInstallationLookup#installation} feeds the outbound payment,
 * delivery, POS, notification, and SMS gateways, so serving a suspended or
 * retired installation's stale status for the registry's one-hour reference-data
 * TTL would be exactly the correctness-on-a-cache mistake ADR 0033 forbids.
 * Only the environment's own {@code base_url} — the part ADR 0026 itself calls
 * "platform-owned reference data, not tenant-writable" — is cached here; a
 * separate query, always fresh, answers everything about a specific
 * installation.
 */
@Repository
public class JdbcProviderEnvironmentLookup {

    private final JdbcClient jdbc;

    public JdbcProviderEnvironmentLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Deliberately not cached, and {@code integration.environments} is no longer
     * a registry entry (ADR 0033).
     *
     * <p>The entry declared its invalidation source as "deployment", which is
     * not something the application can perform: nothing in the platform can
     * notice a {@code base_url} change and evict. ADR 0033's own rule is that a
     * cache whose declared invalidation never fires is worse than no cache, and
     * this one proved it — a base URL cached for an hour with no way to drop it
     * turned a secret rotation into a 422, because the gateway had moved and the
     * cache had not.
     *
     * <p>The read it replaces is a single indexed lookup on a table with a
     * handful of rows, so the accelerator was never worth much. The class stays
     * as the seam that keeps the per-tenant half — {@code status} and
     * {@code secret_reference}, which change at any moment — visibly separate
     * from the platform-owned half.
     */
    public Optional<String> baseUrlOf(String environmentCode) {
        return jdbc.sql("SELECT base_url FROM integration.provider_environments WHERE code = :code")
                .param("code", environmentCode)
                .query(String.class)
                .optional();
    }
}
