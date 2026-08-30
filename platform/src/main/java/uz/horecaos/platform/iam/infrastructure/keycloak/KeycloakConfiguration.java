package uz.horecaos.platform.iam.infrastructure.keycloak;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import uz.horecaos.platform.iam.api.organizations.OrganizationDirectory;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;

/**
 * Wires the ADR 0009 Keycloak adapter.
 *
 * <p>The client secret is resolved from the ADR 0028 manager at call time rather
 * than injected at startup, so rotation takes effect without a restart.
 *
 * <p>Access tokens are cached with a safety margin and refreshed on demand. A
 * token fetched per request would put an avoidable round trip on every
 * onboarding step; a token cached to its exact expiry would fail intermittently
 * on clock skew.
 */
@Configuration(proxyBeanMethods = false)
public class KeycloakConfiguration {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** Refresh this far before expiry, so skew never produces a rejected token. */
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);

    @Bean
    OrganizationProvisioner organizationProvisioner(
            SecretResolver secrets,
            Clock clock,
            @Value("${horecaos.keycloak.base-url:http://localhost:8081}") String baseUrl,
            @Value("${horecaos.keycloak.realm:horecaos}") String realm,
            @Value("${horecaos.keycloak.provisioning-client-id:horecaos-provisioning}") String clientId,
            @Value("${horecaos.environment:local}") String environment) {

        RestClient client = authenticatedClient(
                secrets, clock, baseUrl, realm, clientId, "provisioning-secret", environment);

        // Its own directory instance, on the provisioning credential. The read
        // path is shared code, never a shared token: a provisioner that borrowed
        // the reader's client would be unable to read back what it just wrote,
        // and a reader that borrowed this one would hold `manage-organizations`
        // on a timer.
        return new KeycloakOrganizationProvisioner(
                client, new KeycloakOrganizationDirectory(client, realm), realm);
    }

    /**
     * The drift report's credential (ADR 0009).
     *
     * <p>{@code horecaos-identity-reader} holds view and query roles only, verified
     * against Keycloak 26.7: creating an organization with it returns 403. The
     * report runs unattended, and that is exactly when a credential should not
     * carry write capability it never uses.
     */
    @Bean
    OrganizationDirectory organizationDirectory(
            SecretResolver secrets,
            Clock clock,
            @Value("${horecaos.keycloak.base-url:http://localhost:8081}") String baseUrl,
            @Value("${horecaos.keycloak.realm:horecaos}") String realm,
            @Value("${horecaos.keycloak.identity-reader-client-id:horecaos-identity-reader}") String clientId,
            @Value("${horecaos.environment:local}") String environment) {

        return new KeycloakOrganizationDirectory(
                authenticatedClient(secrets, clock, baseUrl, realm, clientId, "reader-secret", environment),
                realm);
    }

    private static RestClient authenticatedClient(
            SecretResolver secrets, Clock clock, String baseUrl, String realm,
            String clientId, String secretName, String environment) {

        SecretReference clientSecret = new SecretReference(
                environment, SecretCategory.IDENTITY_ADMIN, "keycloak", secretName);
        TokenSource tokens = new TokenSource(baseUrl, realm, clientId, clientSecret, secrets, clock);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory())
                .requestInitializer(request -> request.getHeaders().setBearerAuth(tokens.current()))
                .build();
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /** Client-credentials tokens with caching, kept out of the adapter itself. */
    private static final class TokenSource {

        private final String baseUrl;
        private final String realm;
        private final String clientId;
        private final SecretReference clientSecret;
        private final SecretResolver secrets;
        private final Clock clock;
        private final AtomicReference<CachedToken> cached = new AtomicReference<>();

        private TokenSource(
                String baseUrl, String realm, String clientId, SecretReference clientSecret,
                SecretResolver secrets, Clock clock) {
            this.baseUrl = baseUrl;
            this.realm = realm;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
            this.secrets = secrets;
            this.clock = clock;
        }

        String current() {
            CachedToken token = cached.get();
            if (token != null && token.expiresAt().isAfter(clock.instant())) {
                return token.value();
            }
            return fetch();
        }

        private String fetch() {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", secrets.resolve(clientSecret).reveal());

            Map<String, Object> response = RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(requestFactory())
                    .build()
                    .post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });

            if (response == null || response.get("access_token") == null) {
                throw new IllegalStateException("Keycloak did not return an access token");
            }
            String value = String.valueOf(response.get("access_token"));
            long expiresIn = response.get("expires_in") instanceof Number seconds
                    ? seconds.longValue() : 60L;

            cached.set(new CachedToken(
                    value, clock.instant().plusSeconds(expiresIn).minus(REFRESH_MARGIN)));
            return value;
        }
    }

    private record CachedToken(String value, Instant expiresAt) { }
}
