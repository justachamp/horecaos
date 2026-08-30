package uz.qoida.platform.customers.infrastructure.security;

import java.util.List;
import java.util.Set;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a real environment that has a fixed one-time code configured
 * (ADR 0051, ADR 0028).
 *
 * <p>{@code SecretsProfileGuard} and {@code VerificationTransportGuard} are the
 * pattern, and this is the sharpest instance of it. Those two refuse to start when
 * something needed is <em>missing</em>. This one refuses to start when something
 * dangerous is <em>present</em>, which is the direction that matters here: a
 * preset code that reached a deployment would be a complete authentication bypass
 * for every customer of every tenant, exercisable by anybody who can type a phone
 * number.
 *
 * <p>{@link PresetVerificationCodeSource} already cannot be created outside a
 * local profile, so on its own the property would simply be ignored there — and
 * "silently ignored" is exactly the state in which somebody discovers, months
 * later, that a deployment has been carrying a bypass switch that happened not to
 * be wired. Refusing to boot converts that into a container that will not start
 * and a message naming the property, on the first deploy that carries it.
 *
 * <p>Registered unconditionally, on purpose. A guard that only existed when the
 * thing it guards existed would be absent in precisely the case it is for.
 */
@Component
public class PresetVerificationCodeGuard implements ApplicationRunner {

    /** The same set the other two guards use, and the same one {@code local-fixtures} is bound to. */
    private static final Set<String> LOCAL_PROFILES = Set.of("local", "test", "default");

    private final Environment environment;

    public PresetVerificationCodeGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        verify(environment);
    }

    /**
     * The whole check, as a function of an environment.
     *
     * <p>Separated from {@link #run} so it can be asserted against a
     * production-shaped profile without booting one. A test that had to start a
     * production-shaped context to check this would be slow enough not to be run,
     * which is how a guard stops being one.
     */
    public static void verify(Environment environment) {
        boolean configured = isSet(environment, PresetVerificationCodeSource.PHONE_PROPERTY)
                || isSet(environment, PresetVerificationCodeSource.CODE_PROPERTY);
        if (!configured) {
            return;
        }

        List<String> active = List.of(environment.getActiveProfiles());
        boolean localOnly = active.isEmpty() || active.stream().allMatch(LOCAL_PROFILES::contains);
        if (localOnly) {
            return;
        }

        throw new IllegalStateException("""
                Profile %s has a fixed customer verification code configured (%s / %s).
                That is a complete authentication bypass: one code would sign anybody in as
                the holder of that number, and the number is in configuration. It exists for
                local development only (ADR 0051).
                Unset both properties, or run a local profile."""
                .formatted(active, PresetVerificationCodeSource.PHONE_PROPERTY,
                        PresetVerificationCodeSource.CODE_PROPERTY));
    }

    private static boolean isSet(Environment environment, String property) {
        String value = environment.getProperty(property);
        return value != null && !value.isBlank();
    }
}
