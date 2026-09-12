package uz.horecaos.platform.iam.api.accounts;

import org.jspecify.annotations.Nullable;

/**
 * A cached, read-time answer to "what is this subject's name" (Staff 9.3b).
 *
 * <p>Exists apart from {@link StaffAccounts#displayName} so a caller that only
 * wants a name to render — {@code AuditQueryService}, an order's {@code
 * createdByActorId} — never pays a live Keycloak admin-API round trip per row.
 * {@link StaffAccounts#displayName} stays the uncached source of truth
 * {@code completeSetup} accounts read back from directly.
 *
 * <p><strong>Deliberately not a directory lookup a caller can address on its
 * own.</strong> This interface answers "the name for a subject I already
 * hold", never "list every subject" or "find a subject by name" — a caller
 * resolves a name only for a subject its own capability-gated read already
 * returned, so nobody learns the name of a principal they could not otherwise
 * see. Callers must keep that shape: resolve only subjects already present in
 * an authorized result, not subjects taken from a caller-supplied search.
 */
public interface StaffDisplayNames {

    /** The subject's display name, or {@code null} when none is on file. */
    @Nullable
    String displayName(String subjectId);
}
