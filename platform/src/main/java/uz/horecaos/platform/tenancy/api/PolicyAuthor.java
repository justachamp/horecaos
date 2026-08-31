package uz.horecaos.platform.tenancy.api;

import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * Publishes the next version of an ADR 0030 policy document (Gap D of the
 * 2026-08-30 proving run).
 *
 * <p>Before this existed, {@code tenant.policies} had a resolver ({@link
 * PolicyResolver}) and no writer anywhere in the platform — every test and
 * {@code tools/proving-run} that needed a policy in force wrote the row (and
 * its {@code tenant.policy_current} pointer) directly, citing that absence.
 *
 * <p><strong>A policy is versioned, never edited in place.</strong> {@link
 * #author} always inserts a new, immutable {@code tenant.policies} row and
 * only ever moves the {@code tenant.policy_current} pointer for its {@code
 * (keyCode, scope)}; the version it replaces is never touched, so {@link
 * PolicyResolver#pinned} keeps answering with the exact document a past
 * decision resolved, forever — the same guarantee {@code
 * ApprovalPolicyService} gives {@code audit.approval_policies} for the
 * identical reason.
 */
public interface PolicyAuthor {

    /**
     * Publishes the next version of a policy document at a scope.
     *
     * @param key      identifies the policy and, via {@link PolicyKey#settableScopes()},
     *                 which scope levels may hold a version of it at all
     * @param scope    where this version applies; must be one of {@code
     *                 key.settableScopes()}
     * @param document the new version's content
     * @param authoredBy who published it, for the audit trail
     * @param reason   why, for the audit trail
     * @return the resolved identity of the version just published — the same
     *         shape {@link PolicyResolver#resolve} returns, so a caller that
     *         must snapshot a policy identity onto a business fact can do so
     *         from either call
     */
    <P> ResolvedPolicy<P> author(PolicyKey<P> key, ResourceScope scope, P document, ActorRef authoredBy, String reason);
}
