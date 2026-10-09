package uz.horecaos.platform.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;

/**
 * An ADR 0030 policy store with no database, for suites that only need a document at one
 * scope and its version history.
 *
 * <p>Keeps every version, answers the latest from {@link #resolve} and an exact earlier one
 * from {@link #pinned}, and refuses a stale {@code expectedVersion} the way the real author
 * does, so a test of the optimistic check is a test of the contract and not of this class.
 * Scope is ignored beyond the key: the policies these suites read are {@code PLATFORM}-only.
 */
public final class InMemoryPlatformPolicies implements PolicyResolver, PolicyAuthor {

    private record Stored(UUID id, int version, ResourceScope.ScopeType scopeType, Object document) {}

    private final Map<String, List<Stored>> versions = new HashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
        List<Stored> history = versions.getOrDefault(key.code(), List.of());
        if (history.isEmpty()) {
            return Optional.empty();
        }
        Stored latest = history.get(history.size() - 1);
        return Optional.of(resolved(key, latest));
    }

    @Override
    public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
        return versions.getOrDefault(key.code(), List.of()).stream()
                .filter(stored -> stored.id().equals(policyId) && stored.version() == policyVersion)
                .findFirst()
                .map(stored -> resolved(key, stored));
    }

    @Override
    public <P> ResolvedPolicy<P> author(
            PolicyKey<P> key, ResourceScope scope, P document, ActorRef authoredBy, String reason) {
        return append(key, scope, document);
    }

    @Override
    public <P> ResolvedPolicy<P> author(
            PolicyKey<P> key,
            ResourceScope scope,
            P document,
            int expectedVersion,
            ActorRef authoredBy,
            String reason) {
        int latest = currentVersion(key, scope);
        if (expectedVersion != latest) {
            throw ApiException.staleVersion(expectedVersion, latest);
        }
        return append(key, scope, document);
    }

    @Override
    public int currentVersion(PolicyKey<?> key, ResourceScope scope) {
        return versions.getOrDefault(key.code(), List.of()).size();
    }

    /** Forgets a key, as a database where nothing has been authored. */
    public void clear(PolicyKey<?> key) {
        versions.remove(key.code());
    }

    private <P> ResolvedPolicy<P> append(PolicyKey<P> key, ResourceScope scope, P document) {
        List<Stored> history = versions.computeIfAbsent(key.code(), code -> new ArrayList<>());
        Stored stored = new Stored(UUID.randomUUID(), history.size() + 1, scope.type(), document);
        history.add(stored);
        return resolved(key, stored);
    }

    @SuppressWarnings("unchecked")
    private <P> ResolvedPolicy<P> resolved(PolicyKey<P> key, Stored stored) {
        return new ResolvedPolicy<>(
                key.code(),
                stored.id(),
                stored.version(),
                stored.scopeType(),
                Integer.toHexString(stored.document().hashCode()),
                (P) stored.document());
    }
}
