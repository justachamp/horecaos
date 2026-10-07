package uz.horecaos.platform.storefrontapps.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.storefrontapps.api.StorefrontAppClientType;
import uz.horecaos.platform.storefrontapps.domain.AuthorisationStatus;
import uz.horecaos.platform.storefrontapps.domain.ConformanceStatus;
import uz.horecaos.platform.storefrontapps.domain.StorefrontAppStatus;

/**
 * What the registry and the authorisation screens may show of an app (ADR 0070).
 *
 * <p>The secret reference never appears in any of these. Whether one is configured
 * is a boolean and when it last changed is a time: the same posture every other
 * credential surface in this codebase takes (ADR 0028), so an operator can see that
 * a credential exists and was rotated without being able to read where it lives.
 */
public final class StorefrontAppViews {

    private StorefrontAppViews() {}

    public record StorefrontAppConformance(
            ConformanceStatus status,
            @Nullable String contractVersion,
            @Nullable Instant recordedAt,
            @Nullable String note) {}

    public record StorefrontAppView(
            UUID id,
            String name,
            String vendor,
            StorefrontAppClientType clientType,
            boolean firstParty,
            List<String> originAllowlist,
            boolean secretConfigured,
            @Nullable Instant secretRotatedAt,
            StorefrontAppStatus status,
            StorefrontAppConformance conformance,
            long version,
            Instant createdAt,
            Instant updatedAt) {}

    public record StorefrontAppSummaryView(StorefrontAppView app, long activeAuthorisations, long activeTenants) {}

    public record StorefrontAppAuthorisationView(
            UUID id,
            UUID tenantId,
            String tenantName,
            UUID brandId,
            String brandName,
            AuthorisationStatus status,
            String grantedBy,
            Instant grantedAt,
            @Nullable String revokedBy,
            @Nullable Instant revokedAt,
            long version) {}

    public record StorefrontAppDetailView(StorefrontAppView app, List<StorefrontAppAuthorisationView> authorisations) {}

    /** What a brand sees of one app, and what it has decided about it. */
    public record StorefrontAppCatalogueEntry(
            UUID appId,
            String name,
            String vendor,
            StorefrontAppClientType clientType,
            boolean firstParty,
            StorefrontAppStatus appStatus,
            StorefrontAppConformance conformance,
            StorefrontAppStanding standing,
            @Nullable Instant grantedAt,
            @Nullable String grantedBy,
            @Nullable Instant revokedAt,
            @Nullable Long authorisationVersion) {}

    /** A brand's decision about an app, with the state before it has made one spelled out. */
    public enum StorefrontAppStanding {
        NOT_AUTHORISED,
        AUTHORISED,
        REVOKED
    }

    /** One brand's authorisation, as the authorise and revoke calls answer it. */
    public record StorefrontAppBrandAuthorisationView(
            UUID appId,
            UUID tenantId,
            UUID brandId,
            StorefrontAppStanding standing,
            String grantedBy,
            Instant grantedAt,
            @Nullable String revokedBy,
            @Nullable Instant revokedAt,
            long version) {}
}
