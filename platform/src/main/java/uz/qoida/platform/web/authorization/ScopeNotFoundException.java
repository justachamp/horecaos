package uz.qoida.platform.web.authorization;

import uz.qoida.platform.iam.api.ResourceScope;

/**
 * A request named a tenant, brand or location hierarchy that does not exist.
 *
 * <p>Carries the scope type but not the identifiers, because this is rendered
 * to the caller: the identifiers came from their own URL, and repeating which
 * level failed would tell them exactly how far up their guess was correct.
 */
public final class ScopeNotFoundException extends RuntimeException {

    private final ResourceScope.ScopeType scopeType;

    public ScopeNotFoundException(ResourceScope scope) {
        super("No such %s".formatted(scope.type().name().toLowerCase()));
        this.scopeType = scope.type();
    }

    public ResourceScope.ScopeType scopeType() {
        return scopeType;
    }
}
