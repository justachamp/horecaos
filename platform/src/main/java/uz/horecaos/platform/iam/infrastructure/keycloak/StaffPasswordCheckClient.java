package uz.horecaos.platform.iam.infrastructure.keycloak;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffDirectGrantClient.KeycloakUnavailableException;

/**
 * The password-only second client of ADR 0148, asked first on every staff sign-in.
 *
 * <p>Its Keycloak flow has a username and a password step and no OTP step, so it
 * answers one question and only that one: <em>is this password right for this
 * account?</em> A direct grant to {@code horecaos-staff-login} cannot answer it,
 * because Keycloak refuses a missing code and a wrong password alike with the
 * same {@code invalid_grant}.
 *
 * <p><strong>Nothing that signs anything leaves this class.</strong> Keycloak
 * does answer success with an access token and a refresh token; the access
 * token is read for its {@code sub} claim and dropped, the session it opened is
 * ended on the spot, and what the caller receives is {@link PasswordVerified},
 * a value whose only component is the subject id. There is no token field to
 * misuse, and {@code StaffPasswordCheckClientTests} asserts that by reflection
 * so a later edit cannot add one quietly. The probe is therefore a second route
 * to "password verified" and never a route to a session.
 *
 * <p>Asked first and not after a refusal, for the reason ADR 0148's Context
 * gives: a wrong password followed at once by a failing probe is two failures
 * milliseconds apart, which trips the realm's quick-login check and disables the
 * account for a minute on the first typo. Asked first, a wrong password is one
 * failure, as it was before the probe existed.
 *
 * <p>The probe's success clears Keycloak's failure count for the account
 * (verified by {@code infra/keycloak/spikes/mfa-lockout-probe.py}), which is why
 * Keycloak's lockout is not the control for the code step: the platform's own
 * budget is, and it is charged only after this class has said the password is
 * right.
 */
public final class StaffPasswordCheckClient {

    private static final Logger log = LoggerFactory.getLogger(StaffPasswordCheckClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient restClient;
    private final String realm;
    private final String clientId;
    private final SecretReference clientSecret;
    private final SecretResolver secrets;

    StaffPasswordCheckClient(
            RestClient restClient,
            String realm,
            String clientId,
            SecretReference clientSecret,
            SecretResolver secrets) {
        this.restClient = restClient;
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.secrets = secrets;
    }

    /**
     * Asks whether {@code password} is right for {@code username}.
     *
     * @throws KeycloakUnavailableException when Keycloak cannot be reached, answers nonsense, or
     *     does not know this client at all (the realm step of ADR 0148 has not been run): never a
     *     reason to tell a person their password is wrong
     */
    public PasswordCheck verify(String username, String password) {
        MultiValueMap<String, String> form = clientForm();
        form.add("grant_type", "password");
        form.add("username", username);
        form.add("password", password);
        try {
            Map<String, Object> body = restClient
                    .post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JSON_OBJECT);
            return verified(body);
        } catch (HttpClientErrorException refused) {
            Map<String, Object> answer = StaffDirectGrantClient.bodyOf(refused);
            Object error = answer.get("error");
            if ("invalid_client".equals(error) || "unauthorized_client".equals(error)) {
                // The realm does not carry the client, or its secret no longer matches. Reading
                // that as a wrong password would turn a missed deployment step into a total
                // sign-in outage that looks like everybody mistyping.
                throw new KeycloakUnavailableException(
                        "Keycloak does not accept the staff password-check client", null);
            }
            return new PasswordCheck.Refused(StaffDirectGrantClient.classify(answer));
        } catch (RestClientException upstream) {
            throw new KeycloakUnavailableException("Keycloak did not answer the staff password check", upstream);
        }
    }

    private PasswordCheck verified(@Nullable Map<String, Object> body) {
        if (body == null || !(body.get("access_token") instanceof String accessToken)) {
            throw new KeycloakUnavailableException("Keycloak answered success with no access token", null);
        }
        String refreshToken = body.get("refresh_token") instanceof String text ? text : null;
        String subject = subjectOf(accessToken);
        // The token is dropped here: only the subject id leaves. The session the grant opened is
        // ended now rather than left to lapse, so the realm keeps no sessions of this client.
        endSession(refreshToken);
        return new PasswordCheck.Verified(new PasswordVerified(subject));
    }

    private static String subjectOf(String accessToken) {
        String[] parts = accessToken.split("\\.");
        if (parts.length < 2) {
            throw new KeycloakUnavailableException("Keycloak answered with an access token that is not a JWT", null);
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            Map<?, ?> claims = JSON.readValue(new String(payload, StandardCharsets.UTF_8), Map.class);
            if (claims.get("sub") instanceof String subject && !subject.isBlank()) {
                return subject;
            }
        } catch (RuntimeException unreadable) {
            // Falls through to the single refusal below; the payload is never logged.
        }
        throw new KeycloakUnavailableException("Keycloak's access token carries no subject", null);
    }

    /** Best effort: a failure here leaves a session that lapses on the client's own idle timeout. */
    private void endSession(@Nullable String refreshToken) {
        if (refreshToken == null) {
            return;
        }
        MultiValueMap<String, String> form = clientForm();
        form.add("refresh_token", refreshToken);
        try {
            restClient
                    .post()
                    .uri("/realms/{realm}/protocol/openid-connect/logout", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException failure) {
            log.warn("Keycloak did not confirm the end of a staff password-check session", failure);
        }
    }

    private MultiValueMap<String, String> clientForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", secrets.resolve(clientSecret).reveal());
        return form;
    }

    /** What the probe learned: the password is right (and whose it is), or why Keycloak refused. */
    public sealed interface PasswordCheck {

        record Verified(PasswordVerified account) implements PasswordCheck {}

        record Refused(TokenOutcome.FailureReason reason) implements PasswordCheck {}
    }

    /**
     * The one fact the probe releases. Deliberately a single-component record with no token, no
     * username and no expiry: the account's Keycloak subject id, and nothing a caller could sign
     * or authenticate with.
     */
    public record PasswordVerified(String subjectId) {}
}
