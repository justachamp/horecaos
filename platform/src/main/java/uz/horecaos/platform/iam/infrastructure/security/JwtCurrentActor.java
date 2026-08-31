package uz.horecaos.platform.iam.infrastructure.security;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.NonStaffPrincipal;

@Component
public class JwtCurrentActor implements CurrentActor {

    private static final String ORGANIZATION_CLAIM = "organization";

    private final String resourceServerClientId;

    public JwtCurrentActor(@Value("${horecaos.security.oauth2.client-id:horecaos-api}") String resourceServerClientId) {
        this.resourceServerClientId = resourceServerClientId;
    }

    @Override
    public AuthenticatedActor get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // ADR 0049 and ADR 0051: not every authenticated caller holds a realm
        // token. A customer signed in with a platform-issued session is a
        // principal with a subject and nothing else, and the cross-cutting
        // machinery that asks this question — ADR 0031 idempotency scoping, above
        // all — wants exactly that handle. Answering with an access-denied error
        // instead is how every @Idempotent storefront mutation came to refuse the
        // caller it was written for, inside an interceptor, with a 403 describing
        // a problem the customer does not have.
        //
        // The actor built here carries no roles of either kind, so it satisfies no
        // ADR 0003 tenant rule and holds no ADR 0025 capability. The narrowing is
        // in the construction rather than in a later check.
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof NonStaffPrincipal nonStaff) {
            return new AuthenticatedActor(nonStaff.subject(), Set.of(), Map.of());
        }

        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("An authenticated Keycloak access token is required");
        }

        Jwt jwt = jwtAuthentication.getToken();
        String subject = jwt.getSubject();
        if (subject == null) {
            // A Keycloak access token with no "sub" claim is not a shape this
            // resource server accepts. Refused here, as the same 403 every other
            // authentication failure in this method produces, rather than left to
            // surface as whatever AuthenticatedActor's own constructor guard
            // throws several frames away.
            throw new AccessDeniedException("An authenticated Keycloak access token is required");
        }
        return new AuthenticatedActor(
                subject,
                clientRoles(jwt.getClaim("resource_access")),
                organizationRoles(jwt.getClaim(ORGANIZATION_CLAIM)));
    }

    private Map<String, Set<String>> organizationRoles(@Nullable Object claim) {
        if (!(claim instanceof Map<?, ?> organizations)) {
            return Map.of();
        }

        Map<String, Set<String>> result = new LinkedHashMap<>();
        organizations.values().forEach(rawOrganization -> {
            if (!(rawOrganization instanceof Map<?, ?> organization)) {
                return;
            }
            Object rawId = organization.get("id");
            if (!(rawId instanceof String organizationId) || organizationId.isBlank()) {
                return;
            }
            result.put(organizationId.strip(), clientRoles(organization.get("resource_access")));
        });
        return result;
    }

    private Set<String> clientRoles(@Nullable Object rawResourceAccess) {
        if (!(rawResourceAccess instanceof Map<?, ?> resourceAccess)) {
            return Set.of();
        }
        Object rawClient = resourceAccess.get(resourceServerClientId);
        if (!(rawClient instanceof Map<?, ?> clientAccess)) {
            return Set.of();
        }
        Object rawRoles = clientAccess.get("roles");
        if (!(rawRoles instanceof Collection<?> roles)) {
            return Set.of();
        }

        Set<String> result = new LinkedHashSet<>();
        roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(role -> !role.isBlank())
                .map(String::strip)
                .forEach(result::add);
        return Set.copyOf(result);
    }
}
