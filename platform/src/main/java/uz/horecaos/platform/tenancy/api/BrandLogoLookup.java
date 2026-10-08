package uz.horecaos.platform.tenancy.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Which media asset is a brand's logo (row 10.1, {@code tenant.brand_media}), for the one consumer that
 * needs it by id and not by URL: a printed receipt thresholds the logo to one bit (ADR 0154).
 *
 * <p>The tenant is in the predicate, never only the brand: a brand id from another tenant answers as if it
 * had no logo.
 */
public interface BrandLogoLookup {

    /** The brand's {@code LOGO} asset id, or empty when it has none. */
    Optional<UUID> logoAssetId(UUID tenantId, UUID brandId);
}
