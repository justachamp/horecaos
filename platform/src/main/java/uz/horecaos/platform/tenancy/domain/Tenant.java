package uz.horecaos.platform.tenancy.domain;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.tenancy.api.TenantId;

public final class Tenant {

    private final TenantId id;
    private final Slug slug;
    private final Currency defaultCurrency;
    private final ZoneId defaultTimezone;
    private @Nullable String keycloakOrganizationId;
    private String legalName;
    private String displayName;
    private TenantStatus status;

    private Tenant(
            TenantId id,
            Slug slug,
            String legalName,
            String displayName,
            Currency defaultCurrency,
            ZoneId defaultTimezone,
            @Nullable String keycloakOrganizationId,
            TenantStatus status) {
        this.id = Objects.requireNonNull(id, "Tenant ID is required");
        this.slug = Objects.requireNonNull(slug, "Tenant slug is required");
        this.legalName = requiredName(legalName, "Legal name");
        this.displayName = requiredName(displayName, "Display name");
        this.defaultCurrency = Objects.requireNonNull(defaultCurrency, "Default currency is required");
        this.defaultTimezone = Objects.requireNonNull(defaultTimezone, "Default timezone is required");
        this.keycloakOrganizationId = normalizedOrganizationId(keycloakOrganizationId);
        this.status = Objects.requireNonNull(status, "Tenant status is required");
    }

    public static Tenant provision(
            TenantId id,
            Slug slug,
            String legalName,
            String displayName,
            Currency defaultCurrency,
            ZoneId defaultTimezone) {
        return new Tenant(
                id, slug, legalName, displayName, defaultCurrency, defaultTimezone, null, TenantStatus.PROVISIONING);
    }

    public static Tenant reconstitute(
            TenantId id,
            Slug slug,
            String legalName,
            String displayName,
            Currency defaultCurrency,
            ZoneId defaultTimezone,
            @Nullable String keycloakOrganizationId,
            TenantStatus status) {
        return new Tenant(
                id, slug, legalName, displayName, defaultCurrency, defaultTimezone, keycloakOrganizationId, status);
    }

    public void linkKeycloakOrganization(String organizationId) {
        String normalized = Objects.requireNonNull(
                normalizedOrganizationId(organizationId), "Keycloak organization ID is required");
        if (keycloakOrganizationId != null && !keycloakOrganizationId.equals(normalized)) {
            throw new IllegalStateException("A tenant cannot be linked to another Keycloak organization");
        }
        keycloakOrganizationId = normalized;
    }

    public void activate() {
        requireStatus(TenantStatus.PROVISIONING, TenantStatus.SUSPENDED);
        status = TenantStatus.ACTIVE;
    }

    public void suspend() {
        requireStatus(TenantStatus.ACTIVE);
        status = TenantStatus.SUSPENDED;
    }

    public void archive() {
        requireStatus(TenantStatus.PROVISIONING, TenantStatus.SUSPENDED);
        status = TenantStatus.ARCHIVED;
    }

    public void rename(String legalName, String displayName) {
        if (status == TenantStatus.ARCHIVED) {
            throw new IllegalStateException("An archived tenant cannot be renamed");
        }
        this.legalName = requiredName(legalName, "Legal name");
        this.displayName = requiredName(displayName, "Display name");
    }

    public TenantId id() {
        return id;
    }

    public Slug slug() {
        return slug;
    }

    public String legalName() {
        return legalName;
    }

    public String displayName() {
        return displayName;
    }

    public Currency defaultCurrency() {
        return defaultCurrency;
    }

    public ZoneId defaultTimezone() {
        return defaultTimezone;
    }

    public Optional<String> keycloakOrganizationId() {
        return Optional.ofNullable(keycloakOrganizationId);
    }

    public TenantStatus status() {
        return status;
    }

    private void requireStatus(TenantStatus... allowed) {
        for (TenantStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException("Tenant cannot transition from " + status);
    }

    private static String requiredName(String value, String field) {
        Objects.requireNonNull(value, field + " is required");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw new IllegalArgumentException(field + " must contain 1-200 characters");
        }
        return normalized;
    }

    private static @Nullable String normalizedOrganizationId(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw new IllegalArgumentException("Keycloak organization ID must contain 1-64 characters");
        }
        return normalized;
    }
}
