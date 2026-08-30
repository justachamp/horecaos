package uz.qoida.platform.tenancy.api;

import java.util.Objects;
import java.util.UUID;

/**
 * The company that sells, as distinct from the brand it sells under (ADR 0038).
 *
 * <p>A separate identifier from {@link BrandId} because a company and a trade
 * name are orthogonal: one company routinely runs three brands, and one brand is
 * routinely split across two companies for tax or franchise reasons. Collapsing
 * the two is how a receipt comes to name the wrong taxpayer.
 */
public record LegalEntityId(UUID value) {

    public LegalEntityId {
        Objects.requireNonNull(value, "Legal entity ID is required");
    }
}
