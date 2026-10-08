package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One attempt to charge a tenant's card for money to hold in its wallet (ADR 0095). */
public record CardTopUp(
        UUID id,
        UUID tenantId,
        long amountMinor,
        String currency,
        String outcome,
        @Nullable String providerDetail,
        @Nullable UUID walletEntryId,
        String requestedBy,
        Instant requestedAt,
        @Nullable Instant settledAt) {

    public static final String PENDING = "PENDING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
}
