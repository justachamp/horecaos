package uz.horecaos.platform.payments.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;

/**
 * Reading a provider credential on a path an unauthenticated stranger can reach.
 *
 * <p>Both payment providers call HorecaOS on public endpoints, and both
 * authenticate with a shared secret that an operator may rotate at any moment.
 * That puts two requirements in direct tension.
 *
 * <p>ADR 0028 states the first: "On a provider authentication failure the adapter
 * calls {@code resolveFresh} exactly once before classifying the failure, so a
 * rotation that happened mid-cache does not become a false provider outage." The
 * resolvers cache for five minutes, so without the fresh read a rotation makes
 * the platform reject the provider for five minutes — and on Payme that rejection
 * is {@code -32504} answered to {@code PerformTransaction}, which is a definite
 * refusal returned <em>after the customer's card has been debited</em>. It is not
 * a lost response the provider will retry into; it is HorecaOS telling Payme the
 * money does not belong to an order it in fact belongs to.
 *
 * <p>The second requirement pulls the other way. A fresh read is a round trip to
 * the secrets manager, and the failure path is the path an unauthenticated caller
 * controls completely: anyone who can reach the callback URL can send a wrong
 * signature, and if every wrong signature triggers a fresh read then the public
 * endpoint is an amplifier pointed at the platform's own secrets manager. The
 * first requirement, implemented naively, hands out the second.
 *
 * <p>The resolution is that a rotation is rare and an attacker is fast. One fresh
 * read per reference per cooldown recovers from a rotation within seconds while
 * making the amplification factor a constant rather than a multiple of request
 * volume. A genuine rotation is picked up on the first failure after it; a
 * flood of forged signatures reads the secrets manager six times a minute
 * however hard it tries.
 */
@Component
public class RotationAwareSecrets {

    /**
     * Long enough that a flood cannot turn into load on the secrets manager,
     * short enough that a rotation is invisible to the provider. Payme retries a
     * failed callback, so a few seconds of {@code -32504} is recovered from
     * rather than lost.
     */
    private static final Duration COOLDOWN = Duration.ofSeconds(10);

    private final SecretResolver secrets;
    private final Clock clock;

    /**
     * Bounded by the number of provider bindings — one per legal entity per
     * provider — so this does not grow with traffic and needs no eviction.
     */
    private final ConcurrentMap<SecretReference, Instant> lastFreshRead = new ConcurrentHashMap<>();

    public RotationAwareSecrets(SecretResolver secrets, Clock clock) {
        this.secrets = secrets;
        this.clock = clock;
    }

    /** The ordinary cached read, for the path where the credential matches. */
    public SecretValue cached(SecretReference reference) {
        return secrets.resolve(reference);
    }

    /**
     * A fresh read, at most once per reference per {@link #COOLDOWN}.
     *
     * <p>Empty means "not now", not "no secret". A caller that gets an empty
     * optional must classify the request as unauthenticated, which is the correct
     * answer: the cached credential did not match and the platform has recently
     * confirmed the cached credential is current.
     */
    public Optional<SecretValue> fresh(SecretReference reference) {
        Instant now = clock.instant();

        // The claim is recorded by the winner inside the atomic block rather than
        // inferred afterwards by comparing the stored instant with `now`. Two
        // requests arriving in the same tick — certain under a fixed test clock,
        // possible under a coarse one — would compare equal and both believe they
        // had won, which is exactly the amplification this class exists to stop.
        AtomicBoolean claimed = new AtomicBoolean(false);
        lastFreshRead.compute(reference, (key, previous) -> {
            if (previous == null || !previous.plus(COOLDOWN).isAfter(now)) {
                claimed.set(true);
                return now;
            }
            return previous;
        });

        return claimed.get() ? Optional.of(secrets.resolveFresh(reference)) : Optional.empty();
    }
}
