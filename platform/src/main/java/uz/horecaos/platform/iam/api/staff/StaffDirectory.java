package uz.horecaos.platform.iam.api.staff;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The one tenant-scoped answer to "who is this?" for every staff screen, every
 * audit actor and every order attribution (ADR 0139). It replaces the
 * Keycloak-only {@code StaffDisplayNames}, which keyed a ten-minute cache by
 * subject alone and so could not tell two tenants' views of one person apart.
 *
 * <p><strong>Deliberately not a directory a caller can search or enumerate.</strong>
 * It answers three questions and no others: the display name for a subject in a
 * tenant, the same for a batch of subjects in one call, and the member id for a
 * subject in a tenant. A caller resolves only subjects it already holds -- ones
 * a capability-gated read of its own returned -- so nobody learns the name of a
 * principal they could not otherwise see. What changed from the interim port is
 * that the tenant is now in every signature, so the rule that a lookup never
 * crosses a tenant is structure and no longer convention: a subject who works in
 * two tenants has two rows, and this port reads the one it is asked about.
 *
 * <p>A subject with no member row -- a device principal (ADR 0079), a partner
 * client (ADR 0049), a courier (a different record), a HorecaOS support session
 * (ADR 0081) -- is answered with nothing, so callers keep their existing
 * labelled rendering. Until the backfill has run everywhere, a staff subject
 * with no row is answered from the identity provider's interim copy of the name
 * ({@code StaffAccounts#displayName}); the fallback is removed once every
 * environment reports zero unbacked active subjects.
 *
 * <p>The name is a person's name (ADR 0029 PERSONAL). It is handed to a caller
 * to put in a response its own capability already authorised, and goes nowhere
 * else: not a log line, not an event payload, not a metric tag.
 */
public interface StaffDirectory {

    /**
     * The display name of {@code subject} in {@code tenantId}: "First Last",
     * or the member's {@code display_reference} when the member has no name on
     * file yet. {@code null} when this tenant has no such member and the
     * identity provider has no name either.
     */
    @Nullable
    String nameOf(UUID tenantId, String subject);

    /**
     * {@link #nameOf} for a batch, in one database read, so a list of fifty
     * rows costs one query and not fifty. A subject with no answer is absent
     * from the result, never mapped to null.
     */
    Map<String, String> namesOf(UUID tenantId, Collection<String> subjects);

    /** The member id of {@code subject} in {@code tenantId}, or empty when this tenant has none. */
    Optional<UUID> memberIdOf(UUID tenantId, String subject);
}
