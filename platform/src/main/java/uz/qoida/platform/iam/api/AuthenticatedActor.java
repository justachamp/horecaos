package uz.qoida.platform.iam.api;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record AuthenticatedActor(
        String subject,
        Set<String> globalRoles,
        Map<String, Set<String>> organizationRoles) {

    public AuthenticatedActor {
        subject = required(subject, "Keycloak subject");
        globalRoles = normalizedRoles(globalRoles);
        Objects.requireNonNull(organizationRoles, "Organization roles are required");

        Map<String, Set<String>> normalizedOrganizations = new LinkedHashMap<>();
        organizationRoles.forEach((organizationId, roles) -> normalizedOrganizations.put(
                required(organizationId, "Keycloak organization ID"), normalizedRoles(roles)));
        organizationRoles = Map.copyOf(normalizedOrganizations);
    }

    public boolean hasGlobalRole(String role) {
        return globalRoles.contains(normalizedRole(role));
    }

    public boolean belongsToOrganization(String organizationId) {
        return organizationRoles.containsKey(required(organizationId, "Keycloak organization ID"));
    }

    public boolean hasOrganizationRole(String organizationId, String role) {
        return organizationRoles
                .getOrDefault(required(organizationId, "Keycloak organization ID"), Set.of())
                .contains(normalizedRole(role));
    }

    private static Set<String> normalizedRoles(Set<String> roles) {
        Objects.requireNonNull(roles, "Roles are required");
        return roles.stream().map(AuthenticatedActor::normalizedRole).collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizedRole(String role) {
        return required(role, "Role").toLowerCase(Locale.ROOT);
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
