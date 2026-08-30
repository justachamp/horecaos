package uz.qoida.platform.pos.infrastructure.clopos;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import uz.qoida.platform.integration.api.pos.PosApiCall;
import uz.qoida.platform.integration.api.pos.PosApiTransport;
import uz.qoida.platform.integration.api.provider.ProviderOutcome;
import uz.qoida.platform.pos.application.port.PosAdapter.PosContext;

/**
 * Holds a Clopos session token for as long as it is worth holding.
 *
 * <p>Clopos issues a bearer-shaped JWT with a one-hour life and <em>no refresh
 * grant</em>: refreshing means calling {@code POST /auth} again with the same
 * four credentials. So the client secret has to remain available for the life of
 * the installation rather than being exchanged once at setup, which is the fact
 * that makes ADR 0028's rotation story matter here — and makes it uncomfortable,
 * because Clopos cannot rotate a client secret from the back office at all. It is
 * an email to their support address with a human turnaround.
 *
 * <p>Three details from the vendor that each look like a small thing and are not.
 *
 * <ul>
 *   <li>The transport header is {@code x-token} and the value is the bare token,
 *       despite the response saying {@code token_type: Bearer}. Sending
 *       {@code Authorization: Bearer} earns a 401 reading "Headers are missing",
 *       which looks like a completely different bug.</li>
 *   <li>{@code expires_in} is documented as "typically" 3600 seconds, so the
 *       expiry is taken from {@code expires_at} and the constant is not trusted.</li>
 *   <li>{@code expires_at} is a Unix second count on the success response and an
 *       ISO 8601 string inside the {@code Token expired} error body. Same field
 *       name, two types, one API. Only the first is parsed here.</li>
 * </ul>
 *
 * <p><strong>The cache is in-process, and that is a stated limitation rather than
 * a design.</strong> ADR 0033's shared runtime state is where this belongs: the
 * first Clopos rate-limit tier is sixty {@code /auth} calls a minute keyed on
 * client IP, and a fleet of pods each minting its own token walks into it. In
 * process it is correct but wasteful; the interface below is deliberately narrow
 * so that moving it to shared state is a new implementation of one method rather
 * than a change to the adapter.
 */
@Component
public class CloposSession {

    /**
     * Re-authenticate five minutes early. A token that expires between our check
     * and Clopos's read produces a 401 on an order export, and an order export is
     * the one call where an avoidable failure is expensive.
     */
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private final PosApiTransport transport;
    private final Clock clock;
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    public CloposSession(PosApiTransport transport, Clock clock) {
        this.transport = transport;
        this.clock = clock;
    }

    /**
     * @return the token to put in {@code x-token}, or a failure outcome. Never
     *         throws: an authentication failure is one of the four canonical
     *         outcomes like anything else, and the caller has to be able to tell
     *         "the restaurant disabled us" from "Clopos is down"
     */
    public Token token(PosContext context) {
        String key = context.installationId().toString();
        CachedToken cached = tokens.get(key);
        Instant now = clock.instant();
        if (cached != null && cached.usableAt(now)) {
            return new Token(ProviderOutcome.success(Map.of(), null), cached.value());
        }

        ProviderOutcome outcome = authenticate(context);
        if (outcome.status() != ProviderOutcome.Status.SUCCESS) {
            return new Token(outcome, null);
        }

        String value = CloposEnvelope.string(outcome.normalized(), "token");
        if (value == null || value.isBlank()) {
            // Clopos answered 200 with success true and no token. Rejected rather
            // than retryable: whatever this is, sending the same request again
            // produces the same nothing.
            return new Token(ProviderOutcome.rejected("CLOPOS_NO_TOKEN",
                    "Authentication succeeded without returning a token"), null);
        }

        tokens.put(key, new CachedToken(value, expiryOf(outcome.normalized(), now)));
        return new Token(outcome, value);
    }

    /** Drops a token Clopos has rejected, so the next call mints a fresh one. */
    public void invalidate(PosContext context) {
        tokens.remove(context.installationId().toString());
    }

    private ProviderOutcome authenticate(PosContext context) {
        String brand = context.config(CloposConfig.BRAND, null);
        String clientId = context.config(CloposConfig.CLIENT_ID, null);
        String integratorId = context.config(CloposConfig.INTEGRATOR_ID, null);

        if (brand == null || clientId == null || integratorId == null) {
            // Refused here rather than sent, because Clopos answers a missing
            // field with a 400 that reads like our bug — which it is, but three
            // network hops later.
            return ProviderOutcome.rejected("CLOPOS_CONFIG_INCOMPLETE",
                    "The installation must carry brand, client id, and integrator id");
        }

        PosApiCall call = new PosApiCall(
                context.tenantId(), context.installationId(), CloposAdapter.PROVIDER_TYPE,
                "auth", "POST", "/auth",
                // The client secret enters here and nowhere else. It is applied
                // inside the gateway, at send time, so it never rests on a record
                // this module holds.
                secret -> Map.of(
                        "client_id", clientId,
                        "client_secret", secret,
                        "brand", brand,
                        "integrator_id", integratorId),
                // Minting a token twice costs a token, not a side effect.
                PosApiCall.Effect.IDEMPOTENT_WRITE,
                PosApiCall.fixedHeaders(Map.of()),
                context.correlationId(),
                null);

        return CloposEnvelope.read(transport.exchange(call), PosApiCall.Effect.IDEMPOTENT_WRITE);
    }

    private Instant expiryOf(Map<String, Object> body, Instant now) {
        Object expiresAt = body.get("expires_at");
        if (expiresAt instanceof Number seconds) {
            return Instant.ofEpochSecond(seconds.longValue());
        }
        Object expiresIn = body.get("expires_in");
        if (expiresIn instanceof Number seconds) {
            return now.plusSeconds(seconds.longValue());
        }
        // Neither field parsed. An hour is the documented life; taking it means
        // at worst one avoidable 401 rather than a token cached forever.
        return now.plus(Duration.ofHours(1));
    }

    /** @param value never logged and never returned in an API response */
    private record CachedToken(String value, Instant expiresAt) {

        boolean usableAt(Instant now) {
            return now.isBefore(expiresAt.minus(REFRESH_MARGIN));
        }
    }

    /** @param value null unless the outcome succeeded */
    public record Token(ProviderOutcome outcome, String value) {

        public boolean usable() {
            return value != null && outcome.status() == ProviderOutcome.Status.SUCCESS;
        }

        public Optional<String> optional() {
            return Optional.ofNullable(value);
        }

        /** Never renders the token. */
        @Override
        public String toString() {
            return "Token[" + outcome.status() + "]";
        }
    }
}
