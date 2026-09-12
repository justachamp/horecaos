package uz.horecaos.platform.iam.infrastructure.keycloak;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
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

    /** The account a login resolves to: {@link #findSubjectIdByLogin}, then read. */
    @Override
    public Optional<StaffAccount> findByLogin(String usernameOrEmail) {
        return findSubjectIdByLogin(usernameOrEmail).flatMap(id -> find(id));
    }

    /**
     * Exact user name and exact email, both searched, the user name preferred
     * (ADR 0098).
     *
     * <p>{@code exact=true} on both, because Keycloak's default search is a
     * prefix match across several attributes: without it, "dil" would resolve
     * to somebody, and a reset link would go to an account the requester never
     * named. More than one match is treated as no match for the same reason --
     * the platform will not guess whose password is being reset.
     *
     * <p>Both searches run even when the first one hits. The {@code or(...)}
     * that short-circuited made a login matching a user name cost one admin
     * search and one matching nothing cost two, on an endpoint that exists to
     * answer identically for both; two searches either way is a difference
     * nobody outside can measure.
     */
    @Override
    public Optional<String> findSubjectIdByLogin(String usernameOrEmail) {
        String login = usernameOrEmail.strip();
        if (login.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> byUsername = exactlyOne("username", login);
        Optional<String> byEmail = exactlyOne("email", login);
        return byUsername.or(() -> byEmail);
    }

    /**
     * The login goes out as a URI variable, never as a literal.
     *
     * <p>{@code queryParam(attribute, value)} leaves a {@code +} raw -- it is
     * an allowed sub-delimiter in a query component -- and Keycloak's Vert.x
     * query parsing decodes a raw {@code +} as a space, so a plus-addressed
     * account ({@code ops+kassa@acme.uz}, which is also its user name) is
     * searched for as {@code ops kassa@acme.uz} and resolves nobody. On an
     * endpoint that answers 202 to everyone, that account could never recover
     * and nobody would ever be told why. The variable form percent-encodes it.
     * {@code UriUtils.encodeQueryParam} does not help here: it leaves {@code +}
     * alone, and pre-encoding then passing a literal double-encodes.
     */
    private Optional<String> exactlyOne(String attribute, String value) {
        List<Map<String, Object>> found = client.get()
                .uri(builder -> builder.path("/admin/realms/{realm}/users")
                        .queryParam(attribute, "{login}")
                        .queryParam("exact", true)
                        .queryParam("max", 2)
                        .build(realm, value))
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
     * Reads back exactly what {@link #completeSetup} wrote — {@code
     * firstName}/{@code lastName} — and nothing {@link #find} already
     * exposes. A missing account or a name nobody ever set are the same
     * "nothing to show" answer to the caller (Staff 9.3b): the audit screen
     * falls back to the raw subject id either way.
     */
    @Override
    public Optional<String> displayName(String subjectId) {
        Map<String, Object> user;
        try {
            user = client.get()
                    .uri("/admin/realms/{realm}/users/{id}", realm, subjectId)
                    .retrieve()
                    .body(SINGLE);
        } catch (HttpClientErrorException.NotFound missing) {
            return Optional.empty();
        }
        if (user == null) {
            return Optional.empty();
        }
        String full = Stream.of(user.get("firstName"), user.get("lastName"))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.joining(" "));
        return full.isEmpty() ? Optional.empty() : Optional.of(full);
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
     *
     * <p>The second narrow exception is {@link ProviderUnreachableException},
     * and it is raised for exactly one thing: a request that never left this
     * process. A password reset spends its link before this call, so the caller
     * has to decide afterwards whether the link may come back, and it may only
     * when the write provably did not happen. A refused connection or a host
     * that does not resolve prove that; a read timeout or a 5xx prove nothing
     * and are deliberately left to propagate as themselves.
     */
    private void resetPassword(String subjectId, String password) {
        try {
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
        } catch (ResourceAccessException unreachable) {
            if (neverLeft(unreachable.getCause())) {
                throw new ProviderUnreachableException("The password write never reached Keycloak", unreachable);
            }
            throw unreachable;
        }
    }

    /**
     * Whether the transport failure proves the request was never delivered.
     *
     * <p>Only these three. A {@link java.net.SocketTimeoutException} is
     * deliberately absent even though most of them are connect timeouts: the
     * same type is thrown when a response never arrives, and a write whose
     * response was lost is a write that may have landed. The conservative
     * reading costs somebody a link; the permissive one leaves a spent link
     * live.
     */
    private static boolean neverLeft(@Nullable Throwable cause) {
        return cause instanceof ConnectException
                || cause instanceof UnknownHostException
                || cause instanceof NoRouteToHostException;
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
