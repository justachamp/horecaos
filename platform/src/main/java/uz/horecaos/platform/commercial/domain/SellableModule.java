package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A module sold beside the plans (ADR 0087): its price, what it is billed per,
 * and the feature entitlements it switches on.
 *
 * <p>Drafted by one person and activated by another; immutable once active.
 * Retiring stops it being sold and leaves every tenant that has it as it is.
 */
public record SellableModule(
        UUID id,
        String code,
        String name,
        @Nullable String description,
        BillingUnit billingUnit,
        String currency,
        long unitPriceMinor,
        List<String> featureKeys,
        String status,
        String createdBy,
        @Nullable String approvedBy,
        @Nullable Instant activatedAt,
        @Nullable Instant retiredAt) {

    public static final String DRAFT = "DRAFT";
    public static final String ACTIVE = "ACTIVE";
    public static final String RETIRED = "RETIRED";

    public SellableModule {
        featureKeys = List.copyOf(featureKeys);
    }

    public boolean isActivated() {
        return activatedAt != null;
    }

    /** Whether a tenant may be given it now: activated and not retired. */
    public boolean isOnSale() {
        return ACTIVE.equals(status);
    }
}
