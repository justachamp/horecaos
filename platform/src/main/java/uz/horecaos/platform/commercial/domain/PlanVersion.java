package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A priced set of terms (ADR 0021).
 *
 * <p>{@code priceMinor} is integer minor units of {@code currency}. For UZS a
 * minor unit is one whole som, so nothing here may be divided by a hundred on
 * the way to a screen.
 */
public record PlanVersion(
        UUID id,
        UUID planId,
        String planCode,
        int versionNumber,
        String currency,
        long priceMinor,
        String billingPeriod,
        String status,
        String termsReference,
        String createdBy,
        String approvedBy,
        @Nullable Instant activatedAt) {

    public PlanVersion {
        Objects.requireNonNull(id, "A plan version id is required");
        Objects.requireNonNull(planId, "A plan id is required");
        Objects.requireNonNull(currency, "A currency is required");
        Objects.requireNonNull(status, "A status is required");
    }

    public boolean isActivated() {
        return activatedAt != null;
    }

    /** How many months this version's billing period spans, zero when it does not bill. */
    public int billingMonths() {
        return switch (billingPeriod) {
            case "MONTHLY" -> 1;
            case "QUARTERLY" -> 3;
            case "YEARLY" -> 12;
            default -> 0;
        };
    }
}
