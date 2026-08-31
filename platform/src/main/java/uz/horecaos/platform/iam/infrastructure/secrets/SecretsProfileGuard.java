package uz.horecaos.platform.iam.infrastructure.secrets;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;

/**
 * Refuses to start a non-local profile on environment-variable secrets
 * (ADR 0028).
 *
 * <p>Local development deliberately uses a different mechanism with values that
 * are valid nowhere else. Without this guard, the difference between "we
 * configured the secrets manager" and "we forgot, and it silently read an empty
 * environment variable" is invisible until a provider call fails in production.
 */
@Component
public class SecretsProfileGuard implements ApplicationRunner {

    private static final Set<String> LOCAL_PROFILES = Set.of("local", "test", "default");

    private final Environment environment;
    private final SecretResolver resolver;

    public SecretsProfileGuard(Environment environment, SecretResolver resolver) {
        this.environment = environment;
        this.resolver = resolver;
    }

    @Override
    public void run(@Nullable ApplicationArguments args) {
        if (!(resolver instanceof EnvironmentSecretResolver)) {
            return;
        }
        List<String> active = List.of(environment.getActiveProfiles());
        boolean localOnly = active.isEmpty() || active.stream().allMatch(LOCAL_PROFILES::contains);

        if (!localOnly) {
            throw new IllegalStateException("""
                    Profile %s is running with environment-variable secrets (ADR 0028).
                    Configure the secrets manager for this environment, or run a local profile.""".formatted(active));
        }
    }
}
