package uz.horecaos.platform.support;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.ResolutionTrace;
import uz.horecaos.platform.tenancy.api.Resolved;

/**
 * A {@link ConfigurationResolver} test double that answers every key's own
 * code default unless a test has specifically overridden it.
 *
 * <p>Most fixtures across the suite construct a service that now depends on
 * ADR 0030 resolution (ADR 0030, the 2026-09-10 wiring of {@code
 * pricing.quote_ttl_seconds}, {@code ordering.cart_expiry_minutes}, and
 * {@code inventory.reservation_ttl_seconds}) but have nothing to say about
 * what is configured. For those, the code default is exactly today's
 * previously-hardcoded behaviour — {@code new FakeConfigurationResolver()}
 * changes nothing they assert. A test that cares what a specific key resolves
 * to at a given scope supplies an override map instead of reaching for the
 * real {@code JdbcConfigurationResolver} and a database it does not otherwise
 * need.
 *
 * <p>Ignores scope entirely: an override applies at every scope alike. Real
 * precedence across platform/tenant/brand/location is {@code ScopeResolution}'s
 * job and is exhaustively tested where that class lives; this double exists to
 * let a service-level test say "this key resolves to X here" without
 * re-proving precedence itself.
 */
public final class FakeConfigurationResolver implements ConfigurationResolver {

    private final Map<String, Object> overrides;

    public FakeConfigurationResolver() {
        this(Map.of());
    }

    public FakeConfigurationResolver(Map<String, Object> overrides) {
        this.overrides = Map.copyOf(overrides);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Resolved<T> resolve(ConfigurationKey<T> key, ResourceScope scope) {
        if (overrides.containsKey(key.code())) {
            return new Resolved<>(
                    (T) overrides.get(key.code()), trace(key, ResolutionTrace.Source.SCOPED_VALUE, scope.type()));
        }
        return new Resolved<>(key.defaultValue(), trace(key, ResolutionTrace.Source.CODE_DEFAULT, null));
    }

    @Override
    public ResolutionTrace explain(ConfigurationKey<?> key, ResourceScope scope) {
        return resolve(key, scope).trace();
    }

    private static ResolutionTrace trace(
            ConfigurationKey<?> key, ResolutionTrace.Source source, @Nullable ScopeType winner) {
        return new ResolutionTrace(key.code(), source, winner, List.of());
    }
}
