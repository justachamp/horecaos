package uz.horecaos.platform.tenancy.application.port;

import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * Drops the cached answer to "what does this key resolve to here" (ADR
 * 0033's {@code tenant.configuration}).
 *
 * <p>The same one-method-port shape as {@link PolicyCurrentCache}, for the
 * same reason: a caller that does not care about caching can pass {@code
 * (code, scope) -> {}} instead of a database client. The production
 * implementation is {@code tenancy.infrastructure.persistence.JdbcConfigurationResolver},
 * and the writer that must call it is {@code
 * tenancy.infrastructure.persistence.JdbcConfigurationValueAuthor} — without
 * this, a value an operator just set would keep resolving to whatever was
 * cached before it, for up to the registry's sixty-second TTL.
 */
@FunctionalInterface
public interface ConfigurationValueCache {

    void evict(String keyCode, ResourceScope scope);
}
