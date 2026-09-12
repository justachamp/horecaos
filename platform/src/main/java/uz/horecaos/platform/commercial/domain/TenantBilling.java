package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** How a tenant is collected, and who last changed it (ADR 0095). */
public record TenantBilling(
        UUID tenantId,
        PaymentMethod paymentMethod,
        @Nullable String cardTokenReference,
        String updatedBy,
        Instant updatedAt) {

    /**
     * Says whether a token is on file, never which one.
     *
     * <p>A record's generated {@code toString} prints every component, and this
     * one is held across the settlement pass and handed to {@code CardCharger},
     * which is where a real card adapter will one day be wired. One diagnostic
     * log line or one wrapped exception interpolating the record would put the
     * vault reference into the application log, and the reference belongs in
     * PostgreSQL and nowhere else (ADR 0028). Same override, for the same
     * reason, as {@code ProviderBinding} and {@code MerchantBinding}.
     *
     * <p>"On file" rather than the value keeps the one diagnostic that matters:
     * a CARD tenant with no token at all is the case {@code CardCharger}
     * documents an adapter has to refuse.
     */
    @Override
    public String toString() {
        return "TenantBilling[tenant=%s method=%s token=%s updatedBy=%s]"
                .formatted(tenantId, paymentMethod, cardTokenReference == null ? "none" : "on-file", updatedBy);
    }
}
