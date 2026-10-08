package uz.horecaos.platform.pricing.api;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Describes one coded benefit grant for a module that needs to know whose it is (ADR 0159, Decision 9).
 *
 * <p>A service-recovery case links the apology code an operator minted from it; to accept the link the
 * case has to know that the grant belongs to the same customer and brand as the review it answers. It
 * learns that here and learns nothing else: not the code (which is stored only as a hash and shown once,
 * at the mint), not the discount's shape, not what it was spent on.
 *
 * <p>The edge is {@code reviews -> pricing}, safe because {@code pricing} imports nothing from {@code
 * reviews}; the opposite edge is not available ({@code reviews -> ordering -> pricing}).
 *
 * <p>Tenant-scoped in every implementation: a grant of another tenant is absent.
 */
public interface BenefitGrantDirectory {

    Optional<GrantSummary> summary(UUID tenantId, UUID grantId);

    /**
     * @param status       {@code ACTIVE}, {@code REDEEMED}, {@code EXPIRED} or {@code REVOKED}
     * @param sourceType   what asked for the grant: {@code OPERATOR_MANUAL}, {@code RECOVERY_CASE}, ...
     * @param sourceId     the case, when {@code sourceType} is {@code RECOVERY_CASE}
     */
    record GrantSummary(
            UUID grantId,
            UUID brandId,
            UUID customerAccountId,
            String status,
            String sourceType,
            @Nullable UUID sourceId) {}
}
