package uz.qoida.platform.iam.infrastructure.secrets;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.core.env.Environment;

import uz.qoida.platform.iam.api.secrets.SecretReference;
import uz.qoida.platform.iam.api.secrets.SecretResolver;
import uz.qoida.platform.iam.api.secrets.SecretValue;

/**
 * Resolves secrets from the process environment, with the ADR 0028 caching and
 * rotation contract already in place.
 *
 * <p>This is the phase-one local and CI implementation. ADR 0034 hosts on a
 * local provider first with no managed secrets service, so OpenBao is the
 * production adapter and AWS Secrets Manager follows in phase two. Both slot in
 * behind {@link SecretResolver} without touching a call site, which is the
 * reason the port exists.
 *
 * <p>A startup guard refuses to let this implementation serve a non-local
 * profile, so environment-variable secrets cannot reach production by accident.
 */
public class EnvironmentSecretResolver implements SecretResolver {

    /**
     * Bounded so a rotation takes effect without a restart. Short enough that a
     * revoked credential stops working quickly, long enough that the manager is
     * not on the hot path of every provider call.
     */
    static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final Function<String, String> lookup;
    private final Clock clock;
    private final Map<String, CachedSecret> cache = new ConcurrentHashMap<>();

    public EnvironmentSecretResolver(Environment environment, Clock clock) {
        this(environment::getProperty, clock);
    }

    /** Visible for tests and for adapters that supply their own lookup source. */
    public EnvironmentSecretResolver(Function<String, String> lookup, Clock clock) {
        this.lookup = lookup;
        this.clock = clock;
    }

    @Override
    public SecretValue resolve(SecretReference reference) {
        CachedSecret cached = cache.get(reference.toString());
        if (cached != null && cached.expiresAt().isAfter(clock.instant())) {
            return cached.value();
        }
        return resolveFresh(reference);
    }

    @Override
    public SecretValue resolveFresh(SecretReference reference) {
        String raw = lookup.apply(propertyNameFor(reference));
        if (raw == null || raw.isBlank()) {
            throw new SecretNotFoundException(reference);
        }
        SecretValue value = SecretValue.of(raw);
        cache.put(reference.toString(), new CachedSecret(value, clock.instant().plus(CACHE_TTL)));
        return value;
    }

    /**
     * Maps a reference onto a property name, so nothing about the reference
     * format leaks into configuration keys beyond a predictable transform.
     */
    static String propertyNameFor(SecretReference reference) {
        return "qoida.secrets.%s.%s.%s".formatted(
                reference.category().name().toLowerCase(Locale.ROOT),
                reference.ownerScope(),
                reference.opaqueId());
    }

    private record CachedSecret(SecretValue value, Instant expiresAt) { }
}
