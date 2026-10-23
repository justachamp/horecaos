package uz.horecaos.platform.pricing.api;

import java.time.Instant;
import java.util.List;
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
     * Every grant minted for this customer at this brand, newest first, for the case drawer's picker (ADR 0159,
     * Decision 9). It names what each grant is worth (its shape and value), when it lapses and the last
     * characters of its code, so an operator can tell two apart, and never the code itself.
     */
    List<GrantListing> forCustomer(UUID tenantId, UUID brandId, UUID customerAccountId);

    /**
     * @param benefitType {@code PERCENTAGE_OFF_ORDER}, {@code FIXED_AMOUNT_OFF_ORDER} or {@code FREE_DELIVERY}
     * @param value       basis points for a percentage, minor units for a fixed amount, zero for free delivery
     * @param currency    null for free delivery
     * @param codeHint    the last characters of the code, never the code
     */
    record GrantListing(
            UUID grantId,
            String benefitType,
            long value,
            @Nullable String currency,
            String status,
            String sourceType,
            @Nullable UUID sourceId,
            String codeHint,
            Instant createdAt,
            @Nullable Instant expiresAt) {}

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
