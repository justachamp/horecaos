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

    KeycloakStaffAccounts(RestClient client, String realm) {
        this.client = client;
        this.realm = realm;
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

    @Override
    public void completeSetup(String subjectId, String firstName, String lastName, String password) {
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
