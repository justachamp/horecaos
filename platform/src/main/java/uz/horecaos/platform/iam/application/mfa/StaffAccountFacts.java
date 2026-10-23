package uz.horecaos.platform.iam.application.mfa;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the platform's own tables say about a staff account, for ADR 0148's requirement, for the
 * one rule about who may reset whom and for the language of the emails it sends. Read fresh: a
 * requirement decided from a cached grant is a factor skipped for as long as the cache lives.
 */
public interface StaffAccountFacts {

    /** An active, in-force {@code PLATFORM}-scope grant. */
    boolean holdsPlatformGrant(String subject);

    /**
     * The roles the subject holds in each tenant, by role code, counting grants at the tenant, a
     * brand or a location of it: a branch manager is in the tenant for this purpose.
     */
    Map<UUID, Set<String>> rolesByTenant(String subject);

    /**
     * The capability codes each of these roles carries, for the ones that are the tenant's own jobs
     * (ADR 0186); a code-owned role, or a code the tenant has no job by, is absent from the answer.
     * A job that exists and carries nothing is present with an empty set, which a caller must not
     * read as "carries nothing sensitive".
     */
    Map<String, Set<String>> tenantJobCapabilities(UUID tenantId, Set<String> roleCodes);

    /** Whether the subject holds an active, in-force grant of this role at any scope in the tenant. */
    boolean holdsRoleInTenant(String subject, UUID tenantId, String roleCode);

    /**
     * The interface language the person chose on any of their staff member records, most recently
     * changed first; empty when none has one (a platform account has no member record at all).
     */
    java.util.Optional<String> uiLocale(String subject);
}
