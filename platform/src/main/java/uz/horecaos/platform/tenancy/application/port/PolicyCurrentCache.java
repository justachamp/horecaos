package uz.horecaos.platform.tenancy.application.port;

import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * Drops the cached answer to "what policy is current here" (ADR 0033's {@code
 * tenant.policy_current}).
 *
 * <p>A one-method port rather than a direct dependency on the JDBC resolver,
 * for the same reason {@link TenantStatusCache} is one: a caller that does not
 * care about caching can pass {@code (code, scope) -> {}} instead of a
 * database client. The production implementation is {@code
 * tenancy.infrastructure.persistence.JdbcPolicyResolver}, and the writer that
 * must call it is {@code tenancy.infrastructure.persistence.JdbcPolicyAuthor}
 * — without this, a policy an operator just published would keep resolving to
 * the version it replaced for up to the registry's sixty-second TTL.
 */
@FunctionalInterface
public interface PolicyCurrentCache {

    void evict(String keyCode, ResourceScope scope);
}
