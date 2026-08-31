package uz.horecaos.platform.iam.infrastructure.authorization;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.iam.api.PlatformRole;

/**
 * Ensures every configured platform-admin subject holds the platform-admin
 * grant, on every startup (ADR 0025, Gap A of the 2026-08-30 proving run).
 *
 * <p>Before this existed, the only way a fresh deployment's first grant could
 * exist at all was a human clicking through Keycloak and then a SQL statement
 * written by hand into {@code iam.grants} — every test fixture needing an
 * operator did exactly that, and {@code tools/proving-run} did the identical
 * thing against a live stack, both citing the absence of a
 * {@code PLATFORM}-scope grant endpoint. {@code
 * uz.horecaos.platform.audit.web.PlatformGrantController} closes that
 * endpoint gap for every grant <em>after</em> the first; this closes it for the first
 * one, the same way {@link RoleRegistrySynchronizer} closes the equivalent gap
 * for the role catalogue itself — a reconciler that runs on every startup
 * rather than a migration that runs once, so a role or grant that regresses
 * (a capability bundle changes, an operator's grant is dropped in a database
 * restore) is repaired the next time the application starts rather than
 * silently staying wrong.
 *
 * <p><strong>This reconciler only ever creates the grant. It never revokes
 * one.</strong> That asymmetry is deliberate and load-bearing, not an
 * oversight to be "fixed" later:
 *
 * <ul>
 *   <li>Config absence is not a decision. An operator who removes a subject
 *       from {@code horecaos.iam.bootstrap-platform-admins} — or who simply
 *       typos it, or deploys with an unset environment variable, or rolls back
 *       a release that changed it — has not necessarily decided that subject
 *       should lose platform administration. Revoking on that signal would
 *       make a configuration typo indistinguishable from a deliberate offboarding,
 *       and the failure mode of getting that wrong is a fresh deployment where
 *       <em>nobody</em> can reach the platform-admin-only bootstrap any more —
 *       the exact hole this reconciler exists to close, reopened by the thing
 *       meant to close it.
 *   <li>Revocation already has an honest, audited path: {@link
 *       uz.horecaos.platform.iam.application.GrantManagementService#revoke}, a
 *       human decision with a reason attached, not a side effect of a config
 *       file changing shape.
 *   <li>This mirrors {@link RoleRegistrySynchronizer}'s own asymmetry between
 *       roles and grants: that class deletes and rewrites {@code
 *       iam.role_capabilities} on every run — because a capability bundle is
 *       entirely code-owned and has no other writer to conflict with — but it
 *       has never once revoked a grant, because a grant is a fact about what a
 *       specific person was given and only a person revokes that.
 * </ul>
 */
@Component
public class PlatformAdminBootstrapReconciler implements ApplicationRunner {

    /**
     * The actor recorded on {@code iam.grants.granted_by} for every grant this
     * reconciler creates. Never a Keycloak subject, so the audit trail can tell
     * a platform-owned bootstrap action from an administrator's, the same
     * distinction {@link uz.horecaos.platform.iam.application.GrantManagementService#grantSystemInitiated}
     * draws for onboarding's own system-initiated grants.
     */
    static final String SYSTEM_ACTOR = "platform-admin-bootstrap-config";

    private static final String REASON = "Configuration bootstrap: horecaos.iam.bootstrap-platform-admins (ADR 0025)";

    private final JdbcClient jdbc;
    private final IamBootstrapProperties properties;
    private final RoleRegistrySynchronizer roles;
    private final Clock clock;

    public PlatformAdminBootstrapReconciler(
            JdbcClient jdbc, IamBootstrapProperties properties, RoleRegistrySynchronizer roles, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.roles = roles;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        reconcile();
    }

    /**
     * Idempotent: a subject already holding an active platform-admin grant is
     * left untouched, so running this on every restart never produces a second
     * row and never bumps {@code updated_at} on one that has not changed.
     *
     * <p>Calls {@link RoleRegistrySynchronizer#synchronize()} first rather than
     * relying on bean startup order, because the grant this writes carries a
     * foreign key onto {@code iam.roles} and nothing guarantees this runner
     * observes the roles after that one has seeded them. Synchronising twice is
     * cheap and idempotent; a foreign-key failure on a fresh database is not a
     * good first impression.
     */
    @Transactional
    public void reconcile() {
        if (properties.bootstrapPlatformAdmins().isEmpty()) {
            return;
        }
        roles.synchronize();

        UUID platformAdminRoleId = RoleRegistrySynchronizer.platformRoleId(PlatformRole.PLATFORM_ADMIN);
        for (String subject : properties.bootstrapPlatformAdmins()) {
            ensureGrant(subject, platformAdminRoleId);
        }
    }

    private void ensureGrant(String subject, UUID roleId) {
        boolean alreadyGranted = Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM iam.grants
                     WHERE principal_subject = :subject AND role_id = :roleId
                       AND scope_type = 'PLATFORM' AND status = 'ACTIVE'
                )
                """)
                .param("subject", subject)
                .param("roleId", roleId)
                .query(Boolean.class)
                .single());
        if (alreadyGranted) {
            return;
        }

        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, NULL, :subject, :roleId, true, 'PLATFORM', NULL,
                        'ACTIVE', :grantedBy, :reason, :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("subject", subject)
                .param("roleId", roleId)
                .param("grantedBy", SYSTEM_ACTOR)
                .param("reason", REASON)
                .param("validFrom", at(clock))
                .update();
    }

    private static OffsetDateTime at(Clock clock) {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }
}
