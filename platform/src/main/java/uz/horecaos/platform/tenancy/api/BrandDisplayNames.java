package uz.horecaos.platform.tenancy.api;

import java.util.Optional;
import java.util.UUID;

/**
 * A brand's display name, for the one consumer that needs it by id (ADR 0182): a campaign email is
 * sent in the brand's name, over the platform's one sender address.
 *
 * <p>The tenant is in the predicate, never only the brand: a brand id from another tenant answers
 * as if it did not exist, so one tenant's mail can never be sent in another's name.
 */
public interface BrandDisplayNames {

    /** The brand's {@code display_name}, or empty when this tenant has no such brand. */
    Optional<String> displayName(UUID tenantId, UUID brandId);
}
