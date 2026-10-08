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

    /** Whether the subject holds an active, in-force grant of this role at any scope in the tenant. */
    boolean holdsRoleInTenant(String subject, UUID tenantId, String roleCode);

    /**
     * The interface language the person chose on any of their staff member records, most recently
     * changed first; empty when none has one (a platform account has no member record at all).
     */
    java.util.Optional<String> uiLocale(String subject);
}
