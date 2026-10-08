package uz.horecaos.platform.loyalty.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The one thing {@code marketing} asks loyalty about an accrual rule it wants to point
 * at (ADR 0112, ADR 0046): that it exists, for this brand, and is not retired.
 *
 * <p>A reference and never a way to mint. Pointing at a rule is how an offer says "a
 * bonus multiplier applies here"; the points themselves are loyalty's ledger's to write,
 * and nothing in this port can write one.
 */
public interface AccrualRuleReferencePort {

    Optional<AccrualRuleReference> find(UUID tenantId, UUID brandId, UUID accrualRuleId);

    /** @param status {@code DRAFT}, {@code ACTIVE} or {@code RETIRED} */
    record AccrualRuleReference(UUID accrualRuleId, String status) {}
}
