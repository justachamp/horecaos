package uz.horecaos.platform.commercial.api;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One answered question about one tenant's entitlement (ADR 0021).
 *
 * <p>Two boundaries, not one. {@link #boundary()} is what the platform actually
 * did; {@link #wouldBe()} is what full enforcement of the commercial terms would
 * have done. During a meter-only rollout the first is always allowed and the
 * second is the number that decides whether hard limits can safely be turned on:
 * "how many refusals would we have caused last month" becomes a query over
 * recorded checks instead of a guess, and the switch is thrown on evidence.
 *
 * <p>Nothing here throws. A check is an answer; refusing is
 * {@code EntitlementService.require}'s job, and only when {@link #boundary()}
 * says so.
 *
 * @param entitlementKey  the key that was asked about
 * @param tenantId        the tenant the question was about
 * @param limit           the resolved limit, or null when unlimited
 * @param consumed        what the ledger says has been used in this period
 * @param requested       the increase the caller is about to make
 * @param period          the window {@code consumed} was measured over
 * @param value           the resolved entitlement, with its provenance
 * @param boundary        the effective answer, after the enforcement ceiling
 * @param wouldBe         the answer the declared mode alone would have given
 * @param overageQuantity units beyond the limit after this request, never negative
 */
public record LimitCheck(
        String entitlementKey,
        UUID tenantId,
        @Nullable Long limit,
        long consumed,
        long requested,
        UsagePeriod period,
        EntitlementValue value,
        Boundary boundary,
        Boundary wouldBe,
        long overageQuantity) {

    public LimitCheck {
        Objects.requireNonNull(entitlementKey, "An entitlement key is required");
        Objects.requireNonNull(tenantId, "A tenant is required");
        Objects.requireNonNull(period, "A usage period is required");
        Objects.requireNonNull(value, "A resolved entitlement value is required");
        Objects.requireNonNull(boundary, "A boundary answer is required");
        Objects.requireNonNull(wouldBe, "A shadow boundary answer is required");
        if (overageQuantity < 0) {
            throw new IllegalArgumentException("Overage is never negative: " + entitlementKey);
        }
    }

    public boolean allowed() {
        return boundary.allowed();
    }

    /** Where the tenant stands after this request would be applied. */
    public long projectedConsumption() {
        return consumed + requested;
    }

    /** What is left before the limit, or null when unlimited. */
    public @Nullable Long remaining() {
        return limit == null ? null : Math.max(0, limit - consumed);
    }

    /**
     * The overage charge in minor units of {@link EntitlementValue#currency()},
     * or null when nothing is billable.
     *
     * <p>Integer arithmetic throughout. For UZS a minor unit is one whole som,
     * so 21 extra locations at 250 000 is 5 250 000, and any code that divides
     * this by a hundred is wrong.
     */
    public @Nullable Long overageChargeMinor() {
        if (overageQuantity == 0 || !value.billableOverage()) {
            return null;
        }
        // billableOverage() being true is exactly the invariant that guarantees
        // a unit price is set; this makes that guarantee explicit rather than
        // unboxing a value the checker cannot otherwise prove is present.
        Long unitPriceMinor = Objects.requireNonNull(
                value.overageUnitPriceMinor(), "A billable overage always carries a unit price");
        return Math.multiplyExact(overageQuantity, unitPriceMinor);
    }

    /**
     * Whether measurement and enforcement currently disagree — the platform
     * allowed something the commercial terms would have refused or charged for.
     * The count of these is the meter-only rollout's whole dashboard.
     */
    public boolean suppressedByCeiling() {
        return boundary != wouldBe;
    }
}
