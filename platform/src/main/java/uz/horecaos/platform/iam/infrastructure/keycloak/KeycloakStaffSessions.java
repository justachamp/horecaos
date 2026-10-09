package uz.horecaos.platform.iam.infrastructure.keycloak;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import uz.horecaos.platform.iam.api.accounts.StaffSessions;

/**
 * {@link StaffSessions} over Keycloak's admin API (ADR 0176), on the provisioning credential.
 *
 * <p>Three calls, probed live on Keycloak 26.7.0 against that credential as it is
 * ({@code infra/keycloak/spikes/staff-sessions-probe.py}):
 * <ul>
 *   <li>{@code GET /users/{id}/offline-sessions/{clientUuid}}: the sessions the direct grant
 *       creates. It takes the client's <em>UUID</em>, not its {@code clientId}, and reading the
 *       UUID would need {@code view-clients}, which the credential deliberately lacks (the 403
 *       ADR 0098 recorded). So the UUID is a deployment property, {@code
 *       horecaos.keycloak.staff-login-client-uuid}, and no role is granted to avoid that;
 *   <li>{@code GET /users/{id}/sessions}: any online session, merged by id;
 *   <li>{@code DELETE /sessions/{id}?isOffline=true}: ends one, and the same call without the
 *       flag for an online one.
 * </ul>
 *
 * <p><strong>Keycloak's {@code ipAddress} is never copied out.</strong> It is present on the
 * representation and is not read: a {@link LiveSession} is an id and two times. Nothing in this
 * class logs a response body, because a body can hold that address.
 */
class KeycloakStaffSessions implements StaffSessions {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;
    private final String realm;
    private final @Nullable String staffLoginClientUuid;

    KeycloakStaffSessions(RestClient client, String realm, @Nullable String staffLoginClientUuid) {
        this.client = client;
        this.realm = realm;
        this.staffLoginClientUuid =
                staffLoginClientUuid == null || staffLoginClientUuid.isBlank() ? null : staffLoginClientUuid.strip();
    }

    @Override
    public List<LiveSession> list(String subjectId) {
        if (staffLoginClientUuid == null) {
            throw new SessionsUnavailableException("The sign-in client's id is not configured");
        }
        try {
            Map<String, LiveSession> merged = new LinkedHashMap<>();
            collect(merged, fetch("/admin/realms/{realm}/users/{id}/offline-sessions/{client}", subjectId, true));
            collect(merged, fetch("/admin/realms/{realm}/users/{id}/sessions", subjectId, false));
            return List.copyOf(merged.values());
        } catch (HttpClientErrorException.NotFound noSuchAccount) {
            return List.of();
        } catch (RestClientException | IllegalStateException failed) {
            throw new SessionsUnavailableException("Keycloak could not list the account's sessions", failed);
        }
    }

    private @Nullable List<Map<String, Object>> fetch(String path, String subjectId, boolean offline) {
        return offline
                ? client.get()
                        .uri(path, realm, subjectId, staffLoginClientUuid)
                        .retrieve()
                        .body(LIST)
                : client.get().uri(path, realm, subjectId).retrieve().body(LIST);
    }

    private static void collect(Map<String, LiveSession> into, @Nullable List<Map<String, Object>> representations) {
        if (representations == null) {
            return;
        }
        for (Map<String, Object> representation : representations) {
            Object id = representation.get("id");
            if (!(id instanceof String sessionId) || sessionId.isBlank()) {
                continue;
            }
            Instant started = instantOf(representation.get("start"));
            Instant lastAccess = instantOf(representation.get("lastAccess"));
            into.putIfAbsent(
                    sessionId,
                    new LiveSession(sessionId, started, lastAccess.isBefore(started) ? started : lastAccess));
        }
    }

    private static Instant instantOf(@Nullable Object epochMillis) {
        return epochMillis instanceof Number millis ? Instant.ofEpochMilli(millis.longValue()) : Instant.EPOCH;
    }

    @Override
    public boolean end(String sessionId) {
        try {
            if (delete(sessionId, true)) {
                return true;
            }
            return delete(sessionId, false);
        } catch (RestClientException failed) {
            throw new SessionsUnavailableException("Keycloak could not end the session", failed);
        }
    }

    /** @return false when Keycloak answers 404: it no longer holds that session */
    private boolean delete(String sessionId, boolean offline) {
        try {
            client.delete()
                    .uri("/admin/realms/{realm}/sessions/{id}?isOffline={offline}", realm, sessionId, offline)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound gone) {
            return false;
        }
    }
}
