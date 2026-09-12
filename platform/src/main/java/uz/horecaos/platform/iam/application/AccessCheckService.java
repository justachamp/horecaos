package uz.horecaos.platform.iam.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.EntitlementGate;
import uz.horecaos.platform.iam.api.EntitlementGate.Answer;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.application.GrantManagementService.GrantView;

/**
 * "Can she do this, and why" (Staff 9.5, ADR 0109): the tenant-facing sibling
 * of {@code GrantController.debugAccess}, which already computes exactly this
 * shape of answer but is gated {@code PLATFORM_ADMIN} because it can reveal
 * <em>any</em> principal's grants across every tenant — the wrong guard for a
 * manager asking about her own branch.
 *
 * <p>Enforces its own scope containment on top of {@link
 * uz.horecaos.platform.web.authorization.RequiresCapability}'s ordinary
 * {@code IAM_GRANT_MANAGE} check. That annotation resolves its scope from a
 * path variable, so on a route carrying only {@code tenantId} it can only ever
 * check TENANT-scope coverage — today's only holders of {@code
 * iam.grant.manage} ({@code TENANT_OWNER}/{@code TENANT_ADMIN}), which
 * trivially covers every scope below it. {@link #requireScopeContainment} is
 * the check that still matters once a narrower bundle carries this capability:
 * it asks {@link AuthorizationService#has} the real question — does the
 * caller's own grant cover <em>this specific target scope</em>, not merely
 * "somewhere in the tenant" — so a brand manager holding {@code
 * iam.grant.manage} only at her own brand could never probe a sibling
 * brand's grants through this endpoint, exactly the cross-brand leak {@code
 * debugAccess}'s own {@code PLATFORM_ADMIN} guard exists to prevent.
 *
 * <p>The yes/no answer itself is {@link AuthorizationService#has}, unchanged —
 * the same call a real request's own enforcement makes, so this console can
 * never disagree with what actually happens. {@link
 * GrantManagementService#grantsCarrying} supplies the reason chain on top:
 * every active grant the subject holds that carries this capability, whatever
 * scope it is at, so a negative answer can point at the grant that almost
 * worked (staff-and-access.md §6's own worked example — "she has this job,
 * but only at Chilonzor branch") instead of a bare no.
 *
 * <p>Three answers, never two: {@link Verdict#ALLOWED}, {@link
 * Verdict#INSUFFICIENT_CAPABILITY}, and {@link Verdict#ENTITLEMENT_REQUIRED} —
 * the distinction {@code GrantController.debugAccess} does not draw, because
 * entitlement is a separate, independent check from capability ({@link
 * AuthorizationService}'s own doc). Entitlement is checked only once the
 * capability answer is {@code ALLOWED}, matching how a real request is
 * actually enforced: the capability check runs first and short-circuits, so
 * this answer never claims an entitlement gap a caller without the capability
 * would never have reached anyway.
 */
@Service
public class AccessCheckService {

    private final AuthorizationService authorization;
    private final GrantManagementService grants;
    private final List<EntitlementGate> entitlementGates;

    public AccessCheckService(
            AuthorizationService authorization, GrantManagementService grants, List<EntitlementGate> entitlementGates) {
        this.authorization = authorization;
        this.grants = grants;
        this.entitlementGates = entitlementGates;
    }

    /**
     * @param callerSubject      whoever is asking (holds {@code
     *                           iam.grant.manage}); used only for {@link
     *                           #requireScopeContainment}
     * @param subject            the principal the question is about — may be
     *                           the caller themselves, or a colleague
     * @param capability         the action in question
     * @param scope              where the action would be taken — the scope
     *                           bar's own selection, never trusted from a
     *                           header, and also what the caller's own grant
     *                           must cover
     * @param entitlementKeyCode optional: a {@code commercial.api.EntitlementKeys}
     *                           code, checked only once the capability answer
     *                           is {@code ALLOWED}
     * @throws AuthorizationService.AccessDeniedException when the caller's own
     *         grant does not cover {@code scope} — scope containment, checked
     *         before anything about {@code subject} is read
     */
    public AccessCheckAnswer check(
            String callerSubject,
            String subject,
            Capability capability,
            ResourceScope scope,
            @Nullable String entitlementKeyCode) {

        requireScopeContainment(callerSubject, scope);

        // Callers are refused a PLATFORM-scope question before this point
        // (GrantController.askableScopeOf), so every other scope type carries
        // a tenant id — asserted rather than trusted, so a future caller that
        // skips that refusal fails loudly here instead of NPEing deeper in.
        UUID tenantId = Objects.requireNonNull(
                scope.tenantId(), "An access-check scope must name a tenant; PLATFORM is refused upstream");

        List<GrantView> heldElsewhere = grants.grantsCarrying(tenantId, subject, capability);
        boolean covered = authorization.has(subject, capability, scope);

        if (!covered) {
            return AccessCheckAnswer.insufficientCapability(capability, scope, heldElsewhere);
        }

        if (entitlementKeyCode != null && !entitlementKeyCode.isBlank()) {
            for (EntitlementGate gate : entitlementGates) {
                var answer = gate.checkFeature(tenantId, entitlementKeyCode);
                if (answer.isPresent() && !answer.get().entitled()) {
                    return AccessCheckAnswer.entitlementRequired(capability, scope, answer.get());
                }
            }
        }

        return AccessCheckAnswer.allowed(capability, scope, heldElsewhere);
    }

    /**
     * The caller must hold {@code iam.grant.manage} at a scope covering the
     * one they are asking about — not merely somewhere in the tenant, which is
     * all the controller's own annotation can check from a route carrying only
     * {@code tenantId}. Reuses {@link AuthorizationService#has} rather than
     * re-deriving scope coverage from grant rows a second way, so this check
     * can never disagree with what the same call decides for a real request.
     */
    private void requireScopeContainment(String callerSubject, ResourceScope target) {
        if (!authorization.has(callerSubject, Capability.IAM_GRANT_MANAGE, target)) {
            throw new AuthorizationService.AccessDeniedException(Capability.IAM_GRANT_MANAGE, target);
        }
    }

    /** Which of the three answers this is. */
    public enum Verdict {
        ALLOWED,
        INSUFFICIENT_CAPABILITY,
        ENTITLEMENT_REQUIRED
    }

    /**
     * The answer, and the reason chain behind it.
     *
     * @param heldElsewhere every active grant {@code subject} holds that
     *                      carries {@code capability}, whatever scope it is
     *                      at — populated for both {@code ALLOWED} (so the
     *                      console can say which grant is the one working)
     *                      and {@code INSUFFICIENT_CAPABILITY} (so a negative
     *                      answer can point at the grant that almost worked)
     * @param entitlement   present only for {@code ENTITLEMENT_REQUIRED}
     */
    public record AccessCheckAnswer(
            Verdict verdict,
            Capability capability,
            ResourceScope scope,
            List<GrantView> heldElsewhere,
            @Nullable Answer entitlement) {

        static AccessCheckAnswer allowed(Capability capability, ResourceScope scope, List<GrantView> held) {
            return new AccessCheckAnswer(Verdict.ALLOWED, capability, scope, held, null);
        }

        static AccessCheckAnswer insufficientCapability(
                Capability capability, ResourceScope scope, List<GrantView> heldElsewhere) {
            return new AccessCheckAnswer(Verdict.INSUFFICIENT_CAPABILITY, capability, scope, heldElsewhere, null);
        }

        static AccessCheckAnswer entitlementRequired(Capability capability, ResourceScope scope, Answer entitlement) {
            return new AccessCheckAnswer(Verdict.ENTITLEMENT_REQUIRED, capability, scope, List.of(), entitlement);
        }
    }
}
