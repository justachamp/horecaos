package uz.horecaos.platform.tenancy.api;

import java.util.Objects;
import java.util.UUID;

/**
 * The entity itself, named directly rather than resolved through a location
 * (ADR 0038).
 *
 * <p>{@link FiscalSeller} answers "who sold this order" by location and business
 * date, which is the only question a fiscal document or a checkout ever asks. A
 * merchant binding is different: it names its legal entity directly in the
 * request that registers it, so the caller holding that id needs to know whether
 * it is real and whether it may be named as a seller <em>before</em> any location
 * or order exists to resolve it from. This is that narrower answer, and it
 * carries nothing {@link FiscalSeller} does not already carry other than the two
 * facts registration needs.
 */
public record LegalEntitySummary(UUID id, UUID tenantId, String code, boolean active) {

    public LegalEntitySummary {
        Objects.requireNonNull(id, "A legal entity ID is required");
        Objects.requireNonNull(tenantId, "A tenant ID is required");
        Objects.requireNonNull(code, "A legal entity code is required");
    }
}
