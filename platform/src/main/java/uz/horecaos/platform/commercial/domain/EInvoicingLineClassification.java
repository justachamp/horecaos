package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What one kind of statement line is invoiced as (ADR 0096): the product
 * classification code and unit of measure the operator's document carries for it,
 * the Russian line label, and the VAT rate added on top of the statement's
 * before-tax amount.
 *
 * <p>Configuration, not code, because it is finance's to state and to change
 * (ADR 0088 leaves tax on a subscription to finance). Every row is seeded
 * {@link #provisional()}: chosen by engineering so a document can be built, never
 * confirmed by finance. A row stops being provisional only when somebody with
 * {@code commercial.einvoicing.manage} confirms it, which records who and when.
 */
public record EInvoicingLineClassification(
        String lineKind,
        String itemLabel,
        String catalogCode,
        String catalogName,
        String packageCode,
        String packageName,
        int vatRateBp,
        boolean provisional,
        @Nullable String confirmedBy,
        @Nullable Instant confirmedAt,
        long version,
        String updatedBy,
        Instant updatedAt) {

    /** Every kind a statement line can have, so a send never meets a line it has no classification for. */
    public static final List<String> KINDS = List.of(
            StatementLine.PLAN,
            StatementLine.MODULE,
            StatementLine.OVERAGE,
            StatementLine.EARLY_EXIT,
            StatementLine.DEPOSIT);
}
