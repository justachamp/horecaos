package uz.horecaos.platform.iam.infrastructure.secrets;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import uz.horecaos.platform.iam.api.secrets.SecretIngressGateway;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretWriter;

/**
 * Selects the ADR 0028 secrets manager for the running environment.
 *
 * <p>Two implementations behind one port, chosen by configuration. That is the
 * whole reason the port exists: ADR 0034 replaces OpenBao with a managed AWS
 * service in phase two, and the swap should be a property change rather than a
 * change at every call site.
 */
@Configuration(proxyBeanMethods = false)
public class SecretsConfiguration {

    /** Bounded so an unreachable manager fails fast instead of hanging a request. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    @ConditionalOnProperty(name = "horecaos.secrets.provider", havingValue = "openbao")
    SecretResolver openBaoSecretResolver(
            @Value("${horecaos.secrets.openbao.url}") String url,
            @Value("${horecaos.secrets.openbao.token}") String token,
            @Value("${horecaos.secrets.openbao.mount:horecaos}") String mount,
            Clock clock) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        RestClient client = RestClient.builder()
                .baseUrl(url)
                .defaultHeader("X-Vault-Token", token)
                .requestFactory(requestFactory)
                .build();

        return new OpenBaoSecretResolver(client, mount, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "horecaos.secrets.provider", havingValue = "environment", matchIfMissing = true)
    SecretResolver environmentSecretResolver(Environment environment, MutableSecretStore store, Clock clock) {
        // The door (ADR 0065) writes here first; the property source is the
        // fallback for everything compose.yaml or a test seeds the old way.
        // Checking the mutable store first, rather than only on a miss, is
        // deliberate: a test that both seeds a property AND writes through the
        // door for the same key must see what it just wrote, not stale
        // configuration.
        return new EnvironmentSecretResolver(
                name -> {
                    String written = store.get(name);
                    return written != null ? written : environment.getProperty(name);
                },
                clock);
    }

    /**
     * Shared by the resolver and the writer above so a value written through
     * the door is immediately visible to a resolve call, without either bean
     * depending on the other directly.
     */
    @Bean
    @ConditionalOnProperty(name = "horecaos.secrets.provider", havingValue = "environment", matchIfMissing = true)
    MutableSecretStore mutableSecretStore() {
        return new MutableSecretStore();
    }

    @Bean
    @ConditionalOnProperty(name = "horecaos.secrets.provider", havingValue = "openbao")
    SecretWriter openBaoSecretWriter(
            @Value("${horecaos.secrets.openbao.url}") String url,
            @Value("${horecaos.secrets.openbao.token}") String token,
            @Value("${horecaos.secrets.openbao.mount:horecaos}") String mount) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        RestClient client = RestClient.builder()
                .baseUrl(url)
                .defaultHeader("X-Vault-Token", token)
                .requestFactory(requestFactory)
                .build();

        return new OpenBaoSecretWriter(client, mount);
    }

    @Bean
    @ConditionalOnProperty(name = "horecaos.secrets.provider", havingValue = "environment", matchIfMissing = true)
    SecretWriter environmentSecretWriter(MutableSecretStore store) {
        return new EnvironmentSecretWriter(store);
    }

    /** ADR 0065's door, built on whichever {@link SecretWriter} the profile selected. */
    @Bean
    SecretIngressGateway secretIngressGateway(
            SecretWriter writer, @Value("${horecaos.environment:local}") String environment) {
        return new SecretIngressGateway(writer, environment);
    }
}
