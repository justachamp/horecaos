package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The one account an invoice tells a tenant to pay into (ADR 0095, decision 2).
 *
 * <p>{@code configured} is false while the row still holds the placeholder
 * migration V0504 seeds. A placeholder is displayed as what it is, never as an
 * account, and no invoice is issued from one.
 */
public record PlatformBillingSettings(
        String beneficiary,
        String bankName,
        String account,
        String mfo,
        String taxId,
        boolean configured,
        long version,
        String updatedBy,
        Instant updatedAt,
        @Nullable String approvedBy) {}
