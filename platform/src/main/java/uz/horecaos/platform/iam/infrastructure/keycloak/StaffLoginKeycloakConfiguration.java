package uz.horecaos.platform.iam.infrastructure.keycloak;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;

/**
 * Wires the ADR 0062 direct-grant adapter.
 *
 * <p>A plain {@link RestClient} rather than {@link KeycloakConfiguration}'s
 * bearer-authenticated one: {@code horecaos-staff-login} authenticates itself
 * with {@code client_id}/{@code client_secret} in each request's form body,
 * the way every OAuth2 token endpoint call does, not with a bearer token of
 * its own. There is nothing to log in ahead of time.
 */
@Configuration(proxyBeanMethods = false)
public class StaffLoginKeycloakConfiguration {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /**
     * Longer than {@link KeycloakConfiguration}'s admin-call timeout: this is
     * on a person's own sign-in, not a background reconciliation job, and a
     * password hash under load is allowed to be slower than a token lookup.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    StaffDirectGrantClient staffDirectGrantClient(
            SecretResolver secrets,
            Clock clock,
            @Value("${horecaos.keycloak.base-url:http://localhost:8081}") String baseUrl,
            @Value("${horecaos.keycloak.realm:horecaos}") String realm,
            @Value("${horecaos.keycloak.staff-login-client-id:horecaos-staff-login}") String clientId,
            @Value("${horecaos.environment:local}") String environment) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        RestClient client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();

        SecretReference clientSecret =
                new SecretReference(environment, SecretCategory.IDENTITY_ADMIN, "keycloak", "staff-login-secret");

        return new StaffDirectGrantClient(client, realm, clientId, clientSecret, secrets, clock);
    }
}
