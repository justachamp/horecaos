package uz.horecaos.platform.iam.infrastructure.keycloak;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;

/**
 * {@link StaffAccounts} over Keycloak's admin API (ADR 0097), on the
 * provisioning credential that already creates these accounts.
 *
 * <p>Setting up an account is two writes: the password first, because it is
 * the one the realm's policy can refuse and a refused password must leave the
 * account exactly as it was; then the name and the verified address, echoed
 * back on the representation Keycloak returned so that nothing else on the
 * account is dropped by the replacing {@code PUT}.
 */
class KeycloakStaffAccounts implements StaffAccounts {

    private static final ParameterizedTypeReference<Map<String, Object>> SINGLE = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;
    private final String realm;

    /**
     * The client ADR 0062's sign-in uses, and therefore the one whose offline
     * grants a password reset has to revoke -- see {@link #logoutEverywhere}.
     */
    private final String staffLoginClientId;

    KeycloakStaffAccounts(RestClient client, String realm, String staffLoginClientId) {
        this.client = client;
        this.realm = realm;
        this.staffLoginClientId = staffLoginClientId;
    }

    @Override
    public Optional<StaffAccount> find(String subjectId) {
        Map<String, Object> user;
        try {
            user = client.get()
                    .uri("/admin/realms/{realm}/users/{id}", realm, subjectId)
                    .retrieve()
                    .body(SINGLE);
        } catch (HttpClientErrorException.NotFound missing) {
            return Optional.empty();
        }
        if (user == null || user.get("email") == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> credentials = client.get()
                .uri("/admin/realms/{realm}/users/{id}/credentials", realm, subjectId)
                .retrieve()
                .body(LIST);
        boolean hasPassword = credentials != null
                && credentials.stream().anyMatch(credential -> "password".equals(credential.get("type")));
        return Optional.of(new StaffAccount(
                subjectId,
                String.valueOf(user.get("email")),
                Boolean.TRUE.equals(user.get("emailVerified")),
                hasPassword));
    }

    /**
     * Exact user name first, then exact email (ADR 0098).
     *
     * <p>{@code exact=true} on both, because Keycloak's default search is a
     * prefix match across several attributes: without it, "dil" would resolve
     * to somebody, and a reset link would go to an account the requester never
     * named. More than one match is treated as no match for the same reason --
     * the platform will not guess whose password is being reset.
     */
    @Override
    public Optional<StaffAccount> findByLogin(String usernameOrEmail) {
        String login = usernameOrEmail.strip();
        if (login.isEmpty()) {
            return Optional.empty();
        }
        return exactlyOne("username", login)
                .or(() -> exactlyOne("email", login))
                .flatMap(id -> find(id));
    }

    private Optional<String> exactlyOne(String attribute, String value) {
        List<Map<String, Object>> found = client.get()
                .uri(builder -> builder.path("/admin/realms/{realm}/users")
                        .queryParam(attribute, value)
                        .queryParam("exact", true)
                        .queryParam("max", 2)
                        .build(realm))
                .retrieve()
                .body(LIST);
        if (found == null || found.size() != 1) {
            return Optional.empty();
        }
        Object id = found.getFirst().get("id");
        return id == null ? Optional.empty() : Optional.of(String.valueOf(id));
    }

    @Override
    public void setPassword(String subjectId, String password) {
        resetPassword(subjectId, password);
    }

    /**
     * Ends every session the account holds -- which takes <em>two</em> admin
     * calls, not one (ADR 0098).
     *
     * <p>The obvious call, {@code POST /users/{id}/logout}, is not enough here
     * and answers {@code 204} while doing nothing that matters. ADR 0062's
     * direct grant asks for the {@code offline_access} scope (see {@code
     * StaffDirectGrantClient.SCOPE}, which needs it so the console survives a
     * browser restart), so a staff refresh token is an <em>offline</em> token,
     * and Keycloak's admin logout removes only regular user sessions. Probed
     * against the live 26.7 realm while this was written: sign in, refresh,
     * {@code POST .../logout} answers 204, refresh again -- still 200. A reset
     * that stopped there would report success and revoke nothing, which is the
     * one outcome worse than not offering the feature.
     *
     * <p>What does revoke them is deleting the account's consent for the
     * sign-in client, which Keycloak documents as removing the consent
     * <em>and</em> the offline tokens granted under it. Verified the same way,
     * on two devices at once: both refresh tokens answer 400 afterwards, and
     * the account can sign in again immediately with the new password.
     *
     * <p>Both calls are made, in this order, because they cover different
     * things: the logout ends live non-offline sessions (a browser mid-shift),
     * the revocation ends the offline grants (every device that stayed signed
     * in). A {@code 404} from the second is success, not failure -- Keycloak
     * answers "Consent nor offline token not found" for an account that has
     * none, which is exactly the state a reset is trying to reach.
     *
     * <p>What this cannot end is an access token already issued: it is a
     * self-contained JWT that no server-side state invalidates, so it stays
     * valid for its own short lifetime. ADR 0098 records that as an accepted
     * trade-off rather than a gap.
     */
    @Override
    public void logoutEverywhere(String subjectId) {
        client.post()
                .uri("/admin/realms/{realm}/users/{id}/logout", realm, subjectId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new IllegalStateException(
                            "Keycloak refused to end the account's sessions with " + response.getStatusCode());
                })
                .toBodilessEntity();

        try {
            client.delete()
                    .uri("/admin/realms/{realm}/users/{id}/consents/{client}", realm, subjectId, staffLoginClientId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound none) {
            // No consent and no offline token: already the state this is for.
        }
    }

    @Override
    public void completeSetup(String subjectId, String firstName, String lastName, String password) {
        resetPassword(subjectId, password);

        Map<String, Object> current = client.get()
                .uri("/admin/realms/{realm}/users/{id}", realm, subjectId)
                .retrieve()
                .body(SINGLE);
        if (current == null) {
            throw new IllegalStateException("The account was set up but cannot be read back");
        }
        Map<String, Object> updated = new LinkedHashMap<>(current);
        updated.put("firstName", firstName);
        updated.put("lastName", lastName);
        updated.put("emailVerified", true);
        client.put()
                .uri("/admin/realms/{realm}/users/{id}", realm, subjectId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(updated)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new IllegalStateException(
                            "Keycloak refused the account's name with " + response.getStatusCode());
                })
                .toBodilessEntity();
    }

    /**
     * The one write both password paths share: a permanent password, and a
     * refused one raised as {@link PasswordRejectedException} so the account is
     * left exactly as it was.
     */
    private void resetPassword(String subjectId, String password) {
        client.put()
                .uri("/admin/realms/{realm}/users/{id}/reset-password", realm, subjectId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "password", "value", password, "temporary", false))
                .retrieve()
                .onStatus(status -> status.value() == HttpStatus.BAD_REQUEST.value(), (request, response) -> {
                    throw new PasswordRejectedException(
                            policyOf(response.getBody().readAllBytes()));
                })
                .toBodilessEntity();
    }

    /**
     * Keycloak answers a refused password with {@code {"error": "invalidPasswordMinLengthMessage", ...}};
     * the key names the rule and carries no part of the password.
     */
    static String policyOf(byte[] body) {
        try {
            Object error =
                    JsonMapper.builder().build().readValue(body, Map.class).get("error");
            return error == null ? "unknown" : String.valueOf(error);
        } catch (RuntimeException unreadable) {
            return "unknown";
        }
    }
}
