package uz.qoida.platform.customers.infrastructure.messaging;

import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import uz.qoida.platform.customers.spi.VerificationCodeTransport;

/**
 * Refuses to start a real environment with no way to send a verification code
 * (ADR 0015, ADR 0020).
 *
 * <p>Modelled on {@code SecretsProfileGuard}, and for the same reason. Without it
 * the difference between "the SMS gateway is wired" and "nobody wired it" is
 * invisible until a customer stands at a checkout waiting for a code that is never
 * coming — and the symptom, a 500 on sign-up, points at the wrong thing entirely.
 *
 * <p>Local and test profiles start without a transport on purpose. There is
 * nothing to wire them to, and the alternatives are both worse than failing at the
 * point of use: a stand-in that accepted the message and dropped it would make an
 * unconfigured deployment look identical to a working one, and one that printed
 * the code would put a live credential into a log file, which ADR 0028 and
 * ADR 0029 forbid in as many words.
 */
@Component
public class VerificationTransportGuard implements ApplicationRunner {

    private static final Set<String> LOCAL_PROFILES = Set.of("local", "test", "default");

    private final Environment environment;
    private final ObjectProvider<VerificationCodeTransport> transports;

    public VerificationTransportGuard(Environment environment,
            ObjectProvider<VerificationCodeTransport> transports) {
        this.environment = environment;
        this.transports = transports;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (transports.getIfAvailable() != null) {
            return;
        }
        List<String> active = List.of(environment.getActiveProfiles());
        boolean localOnly = active.isEmpty() || active.stream().allMatch(LOCAL_PROFILES::contains);

        if (!localOnly) {
            throw new IllegalStateException("""
                    Profile %s has no VerificationCodeTransport, so no customer can be issued a
                    one-time code and nobody can register (ADR 0015).
                    Wire an SMS adapter implementing uz.qoida.platform.customers.spi
                    .VerificationCodeTransport, or run a local profile."""
                    .formatted(active));
        }
    }
}
