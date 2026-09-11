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
        Instant updatedAt) {}
