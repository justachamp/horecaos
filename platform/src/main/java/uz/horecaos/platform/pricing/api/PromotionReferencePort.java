package uz.horecaos.platform.pricing.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The one thing {@code marketing} asks pricing about a promotion it wants to point at
 * (ADR 0112, ADR 0044): that it exists, for this brand, and is not history.
 *
 * <p>A reference and never a window onto the rule. A marketing offer holds a promotion's
 * id and nothing of what it does; what the promotion is worth, whether it combines with
 * another and whether it is active remain pricing's, read through the promotions API.
 * Existence is all an offer has to check at authoring time, which is why this answers
 * with a name and a status and not the definition.
 */
public interface PromotionReferencePort {

    /** The promotion, or empty when no such promotion belongs to this brand of this tenant. */
    Optional<PromotionReference> find(UUID tenantId, UUID brandId, UUID promotionId);

    /**
     * @param status {@code DRAFT}, {@code VALIDATED}, {@code ACTIVE}, {@code SUSPENDED}
     *               or {@code ARCHIVED}. An offer may point at a promotion that is not
     *               active yet and a marketer may be preparing it; an archived one is
     *               refused by the caller
     */
    record PromotionReference(UUID promotionId, String code, String name, String status) {}
}
