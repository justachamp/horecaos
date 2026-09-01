package uz.horecaos.platform.iam.infrastructure.keycloak;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;

/**
 * The ADR 0062 OAuth2 direct grant: the only place in this codebase a staff
 * password is handed to Keycloak, and the only place a staff refresh token is
 * exchanged or revoked.
 *
 * <p>Client authentication is a confidential client secret in the form body,
 * resolved from the ADR 0028 manager on every call rather than cached in this
 * class, exactly as {@link KeycloakConfiguration}'s {@code TokenSource}
 * resolves the provisioning and reader secrets — so a rotation takes effect on
 * the next sign-in without a restart. Unlike that client-credentials flow,
 * nothing here caches a token: every call mints a fresh one for a real staff
 * user, and there is nothing generic to reuse across callers.
 *
 * <p><strong>Never logs a password, a token, or a username.</strong> The
 * request body that carries them is built here and nowhere else, and Keycloak's
 * {@code error_description} — which can echo back a username — is inspected
 * for classification only, never logged verbatim.
 */
public final class StaffDirectGrantClient {

    private static final Logger log = LoggerFactory.getLogger(StaffDirectGrantClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    /**
     * What Keycloak's direct grant says when a required action is outstanding
     * (an unconfigured account, an expired temporary password, and — per
     * {@code infra/keycloak/README.md}'s Keycloak 26 regression note — a
     * profile still missing a required attribute). Every other {@code
     * invalid_grant} — wrong password, unknown username, brute-force lockout —
     * is deliberately folded into the same uniform failure the ADR requires;
     * this is the one outcome besides rate-limiting it lets the caller
     * distinguish.
     */
    private static final String ACCOUNT_NOT_FULLY_SET_UP = "not fully set up";

    /** `openid` for the subject; `offline_access` so the refresh token this endpoint proxies exists at all. */
    private static final String SCOPE = "openid offline_access";

    private final RestClient restClient;
    private final String realm;
    private final String clientId;
    private final SecretReference clientSecret;
    private final SecretResolver secrets;
    private final Clock clock;

    StaffDirectGrantClient(
            RestClient restClient,
            String realm,
            String clientId,
            SecretReference clientSecret,
            SecretResolver secrets,
            Clock clock) {
        this.restClient = restClient;
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.secrets = secrets;
        this.clock = clock;
    }

    /** Resource-owner password credentials: the one place a staff password reaches Keycloak. */
    public TokenOutcome signIn(String username, String password) {
        MultiValueMap<String, String> form = clientForm();
        form.add("grant_type", "password");
        form.add("username", username);
        form.add("password", password);
        form.add("scope", SCOPE);
        return exchange(form);
    }

    /** Proxies the refresh grant. The refresh token is the caller's whole credential here. */
    public TokenOutcome refresh(String refreshToken) {
        MultiValueMap<String, String> form = clientForm();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        return exchange(form);
    }

    /**
     * RFC 7009 revocation. Best-effort: Keycloak answers 200 for an unknown or
     * already-expired token by design (revocation must not become an oracle for
     * which tokens are live), and a transport failure here must not stop a
     * staff member from signing out locally — the frontend clears its own
     * tokens regardless of whether this call landed.
     */
    public void revoke(String refreshToken) {
        MultiValueMap<String, String> form = clientForm();
        form.add("token", refreshToken);
        form.add("token_type_hint", "refresh_token");
        try {
            restClient
                    .post()
                    .uri("/realms/{realm}/protocol/openid-connect/revoke", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException failure) {
            log.warn("Keycloak did not confirm a staff refresh-token revocation", failure);
        }
    }

    private TokenOutcome exchange(MultiValueMap<String, String> form) {
        try {
            Map<String, Object> body = restClient
                    .post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JSON_OBJECT);
            return issued(body);
        } catch (HttpClientErrorException refused) {
            return TokenOutcome.refused(classify(bodyOf(refused)));
        } catch (RestClientException upstream) {
            throw new KeycloakUnavailableException("Keycloak did not answer the staff token request", upstream);
        }
    }

    private MultiValueMap<String, String> clientForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", secrets.resolve(clientSecret).reveal());
        return form;
    }

    private TokenOutcome issued(@Nullable Map<String, Object> body) {
        if (body == null || body.get("access_token") == null || body.get("refresh_token") == null) {
            throw new KeycloakUnavailableException("Keycloak answered success with no usable token pair", null);
        }
        Instant now = clock.instant();
        return TokenOutcome.issued(new TokenOutcome.Issued(
                String.valueOf(body.get("access_token")),
                String.valueOf(body.get("refresh_token")),
                now.plusSeconds(seconds(body, "expires_in", 60)),
                refreshExpiry(body, now),
                String.valueOf(body.getOrDefault("token_type", "Bearer"))));
    }

    /**
     * {@code null} when Keycloak reports {@code refresh_expires_in: 0} —
     * verified live against the dev realm, this is what an {@code
     * offline_access}-scoped refresh token gets instead of a real duration.
     * See {@link TokenOutcome.Issued#refreshTokenExpiresAt()}.
     */
    private static @Nullable Instant refreshExpiry(Map<String, Object> body, Instant now) {
        long refreshSeconds = seconds(body, "refresh_expires_in", 0);
        return refreshSeconds > 0 ? now.plusSeconds(refreshSeconds) : null;
    }

    private static TokenOutcome.FailureReason classify(Map<String, Object> body) {
        String description =
                String.valueOf(body.getOrDefault("error_description", "")).toLowerCase(Locale.ROOT);
        if (description.contains(ACCOUNT_NOT_FULLY_SET_UP)) {
            return TokenOutcome.FailureReason.ACCOUNT_ACTION_REQUIRED;
        }
        // Every other invalid_grant/invalid_request/unauthorized_client answer
        // -- wrong password, unknown username, a brute-force lockout, a
        // disabled account -- is one uniform outcome. Distinguishing them here
        // would hand a caller exactly the enumeration oracle ADR 0062 forbids.
        return TokenOutcome.FailureReason.INVALID_CREDENTIALS;
    }

    private static Map<String, Object> bodyOf(HttpClientErrorException exception) {
        try {
            Map<String, Object> body = exception.getResponseBodyAs(JSON_OBJECT);
            return body == null ? Map.of() : body;
        } catch (RuntimeException unparsable) {
            // Not JSON, or not the shape expected. Refused either way; nothing
            // here can be classified further than the uniform failure.
            return Map.of();
        }
    }

    private static long seconds(Map<String, Object> body, String key, long fallback) {
        return body.get(key) instanceof Number number ? number.longValue() : fallback;
    }

    /** Thrown when Keycloak itself could not be reached or answered nonsense, never for a credential failure. */
    public static final class KeycloakUnavailableException extends RuntimeException {
        KeycloakUnavailableException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
