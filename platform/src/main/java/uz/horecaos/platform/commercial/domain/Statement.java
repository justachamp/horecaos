package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One month of what a tenant owes under its plan and modules, before tax (ADR 0088).
 *
 * <p>A draft has no id and no number: it is computed each time it is asked
 * for. An issued statement is frozen with both, and is corrected only by
 * voiding it and issuing again.
 */
public record Statement(
        @Nullable UUID id,
        UUID tenantId,
        @Nullable String number,
        String periodKey,
        Instant periodStart,
        Instant periodEnd,
        @Nullable String currency,
        long totalMinor,
        @Nullable UUID subscriptionId,
        String status,
        @Nullable String issuedBy,
        @Nullable Instant issuedAt,
        @Nullable String issueReason,
        @Nullable String voidedBy,
        @Nullable Instant voidedAt,
        @Nullable String voidReason,
        List<StatementLine> lines) {

    public static final String DRAFT = "DRAFT";
    public static final String ISSUED = "ISSUED";
    public static final String VOID = "VOID";

    public Statement {
        lines = List.copyOf(lines);
    }
}
