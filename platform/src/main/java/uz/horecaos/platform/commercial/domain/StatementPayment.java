package uz.horecaos.platform.commercial.domain;

import java.util.UUID;

/**
 * What one issued statement has been paid, derived from the wallet ledger
 * (ADR 0095). {@code dueMinor} is what remains, collected by the tenant's
 * payment method. Issued statements are frozen rows (ADR 0088); nothing
 * here is stored on {@code commercial.statements} itself.
 */
public record StatementPayment(
        UUID statementId,
        String number,
        String periodKey,
        String currency,
        long totalMinor,
        long paidMinor,
        long dueMinor) {}
