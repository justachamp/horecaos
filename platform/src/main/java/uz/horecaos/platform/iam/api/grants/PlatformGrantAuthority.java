package uz.horecaos.platform.iam.api.grants;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code PLATFORM}-scope grant and revoke (ADR 0025, Gap A of the 2026-08-30
 * proving run) — the write half {@code audit.application.PlatformGrantService}
 * calls once ADR 0027 approval clears, and the read half a console lists
 * from.
 *
 * <p>Narrow on purpose, the same way {@link TenantOwnerAuthorityGrantor} is:
 * {@code PLATFORM} scope only, never a general "grant anything at any scope"
 * port. {@code GrantManagementService} already enforces every escalation
 * rule — a granter confers only what it already holds, at a scope it already
 * covers, and {@code platform.admin} is never grantable through this or any
 * other API — before either method writes a row.
 *
 * <p>This interface exists only so that {@code audit} — which already
 * depends on {@code iam.api} for {@link uz.horecaos.platform.iam.api.ResourceScope}
 * and {@link uz.horecaos.platform.iam.api.CurrentActor} — can reach that
 * enforcement to perform the write once its own ADR 0027 maker-checker
 * clears, without {@code iam} ever depending back on {@code audit} for the
 * approval gate. That reverse edge is exactly the cycle {@code
 * ModularArchitectureTests} exists to catch, and it is why the maker-checker
 * itself lives in {@code audit.application.PlatformGrantService} rather than
 * here.
 */
public interface PlatformGrantAuthority {

    /**
     * Grants a {@code PLATFORM}-scope role to a principal.
     *
     * @param validUntil null for an open-ended grant; set it for support access
     * @return the new grant's id
     */
    UUID grant(
            String principalSubject,
            String roleCode,
            String reason,
            @Nullable Instant validUntil,
            String granterSubject);

    /**
     * Revokes an active {@code PLATFORM}-scope grant.
     *
     * @return whether an active grant was found and revoked
     */
    boolean revoke(UUID grantId, String revokerSubject, String reason);

    /** Every active {@code PLATFORM}-scope grant. */
    List<PlatformGrantView> list();

    record PlatformGrantView(UUID id, String principalSubject, String roleCode, String status, String grantedBy) {}
}
