package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import java.util.Objects;

import uz.horecaos.platform.commercial.api.EnforcementMode;

/**
 * A time-bounded departure from a tenant's plan (ADR 0021).
 *
 * <p>An override replaces the number and, optionally, the mode. It never
 * replaces the reset period or the overage rate: those describe the shape of the
 * commercial deal, and a support exception that quietly turned a monthly
 * allowance into a lifetime one would be indistinguishable from a plan change
 * nobody approved.
 */
public record EntitlementOverride(
        String entitlementKey,
        Long integerValue,
        Boolean booleanValue,
        EnforcementMode enforcementMode,
        Instant validFrom,
        Instant validUntil) {

    public EntitlementOverride {
        Objects.requireNonNull(entitlementKey, "An entitlement key is required");
        Objects.requireNonNull(validFrom, "A validity start is required");
        Objects.requireNonNull(validUntil, "A validity end is required");

        if ((integerValue == null) == (booleanValue == null)) {
            throw new IllegalArgumentException(
                    "An override carries exactly one typed value: " + entitlementKey);
        }
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("An override expires after it starts: " + entitlementKey);
        }
    }

    public boolean isLiveAt(Instant instant) {
        return !instant.isBefore(validFrom) && instant.isBefore(validUntil);
    }
}
