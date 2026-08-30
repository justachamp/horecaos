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
import uz.horecaos.platform.iam.api.secrets.SecretResolver;

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
    SecretResolver environmentSecretResolver(Environment environment, Clock clock) {
        return new EnvironmentSecretResolver(environment, clock);
    }
}
