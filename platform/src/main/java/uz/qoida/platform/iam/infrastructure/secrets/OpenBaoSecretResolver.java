package uz.qoida.platform.iam.infrastructure.secrets;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import uz.qoida.platform.iam.api.secrets.SecretReference;
import uz.qoida.platform.iam.api.secrets.SecretResolver;
import uz.qoida.platform.iam.api.secrets.SecretValue;

/**
 * Reads secrets from OpenBao, the phase-one manager chosen in ADR 0028.
 *
 * <p>Deliberately speaks plain HTTP to the KV v2 API rather than using a client
 * library. The API surface used here is two endpoints, and a library would put
 * provider vocabulary — leases, namespaces, auth backends — into code that ADR
 * 0034 phase two migrates onto AWS Secrets Manager. The port stays free of it.
 *
 * <p>A path is derived from the reference rather than stored, so no business row
 * holds a provider-shaped location that a migration would have to rewrite.
 */
public class OpenBaoSecretResolver implements SecretResolver {

    static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final RestClient client;
    private final String mount;
    private final Clock clock;
    private final Map<String, CachedSecret> cache = new ConcurrentHashMap<>();

    public OpenBaoSecretResolver(RestClient client, String mount, Clock clock) {
        this.client = client;
        this.mount = mount;
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
        KvResponse response = client.get()
                .uri("/v1/{mount}/data/{path}", mount, pathFor(reference))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, failure) -> {
                    // The reference is safe to name; the response body may echo
                    // request detail and is deliberately not propagated.
                    throw new SecretNotFoundException(reference);
                })
                .body(KvResponse.class);

        if (response == null || response.data() == null || response.data().data() == null) {
            throw new SecretNotFoundException(reference);
        }
        String raw = response.data().data().get("value");
        if (raw == null || raw.isBlank()) {
            throw new SecretNotFoundException(reference);
        }

        SecretValue value = SecretValue.of(raw);
        cache.put(reference.toString(), new CachedSecret(value, clock.instant().plus(CACHE_TTL)));
        return value;
    }

    /**
     * Maps a provider-neutral reference onto a KV path. The mapping lives here,
     * in the adapter, so the reference format never learns about OpenBao.
     */
    static String pathFor(SecretReference reference) {
        return "%s/%s/%s/%s".formatted(
                reference.environment(),
                reference.category().name().toLowerCase(Locale.ROOT),
                reference.ownerScope(),
                reference.opaqueId());
    }

    private record CachedSecret(SecretValue value, Instant expiresAt) { }

    /** The subset of the KV v2 response this adapter reads. */
    record KvResponse(Data data) {
        record Data(Map<String, String> data) { }
    }
}
