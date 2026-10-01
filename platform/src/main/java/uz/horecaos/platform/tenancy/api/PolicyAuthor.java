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

    /**
     * {@link #author(PolicyKey, ResourceScope, Object, ActorRef, String)}, refused
     * unless the caller has seen the version now in force at exactly this scope
     * (wave 16, gap map row {@code X.39}: the first policy editor whose form is
     * open long enough for a second operator to publish underneath it).
     *
     * <p>The append-only versioning above means every write technically
     * succeeds, so without this check two operators editing the same scope from
     * two open tabs would each publish a full document built from the state they
     * last read, and the second would silently discard the first's changes. This
     * is the same {@code STALE_VERSION} contract {@link ConfigurationValueAuthor}
     * gives a setting, over the version {@link #currentVersion} reports.
     *
     * @param expectedVersion the latest version authored at exactly {@code scope},
     *                        or 0 when nothing has been authored there yet (the
     *                        scope resolves an ancestor's document or the platform
     *                        default); anything else is refused with {@code
     *                        STALE_VERSION} naming both versions
     */
    <P> ResolvedPolicy<P> author(
            PolicyKey<P> key, ResourceScope scope, P document, int expectedVersion, ActorRef authoredBy, String reason);

    /**
     * The latest version authored at exactly {@code scope} — not the ancestor's
     * that the scope may inherit — or 0 when nothing has been authored there.
     * Read straight from the table rather than through {@link PolicyResolver}'s
     * cache, because it is the value an {@code expectedVersion} is compared with
     * and a stale answer would defeat the comparison.
     */
    int currentVersion(PolicyKey<?> key, ResourceScope scope);
}
