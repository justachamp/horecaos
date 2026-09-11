package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One append-only entry in a tenant's wallet (ADR 0095).
 *
 * <p>{@code moneyKind} is {@link #PAID} (money the tenant paid HorecaOS) or
 * {@link #BONUS} (money HorecaOS granted). {@code amountMinor} is signed:
 * positive for money arriving, negative for money leaving, and either sign
 * for {@link #ADJUSTMENT}. A balance is the SUM of a tenant's entries of one
 * kind; none is stored beside them, and no entry is ever changed once
 * written — the database refuses both at the trigger that backs this type.
 */
public record WalletEntry(
        UUID id,
        UUID tenantId,
        String moneyKind,
        String entryType,
        long amountMinor,
        String currency,
        @Nullable UUID statementId,
        @Nullable UUID grantId,
        @Nullable Instant expiresAt,
        @Nullable String externalReference,
        String reason,
        String recordedBy,
        @Nullable String approvedBy,
        @Nullable UUID approvalRequestId,
        Instant createdAt) {

    public static final String PAID = "PAID";
    public static final String BONUS = "BONUS";

    public static final String TOP_UP = "TOP_UP";
    public static final String DEPOSIT = "DEPOSIT";
    public static final String BONUS_GRANT = "BONUS_GRANT";
    public static final String BONUS_EXPIRY = "BONUS_EXPIRY";
    public static final String STATEMENT_PAYMENT = "STATEMENT_PAYMENT";

    /**
     * What a voided statement gives back (ADR 0088 voids one and issues
     * again). The ledger is never reopened, so a draw is undone by the
     * opposite entry naming the same statement — and the same grant, when the
     * draw was bonus money — not by removing the one that made it.
     */
    public static final String STATEMENT_REVERSAL = "STATEMENT_REVERSAL";

    public static final String ADJUSTMENT = "ADJUSTMENT";
    public static final String REFUND = "REFUND";

    /**
     * What an approved reversal of a deposit recorded in error gives back
     * (ADR 0095, item 6). A deposit recorded against the wrong tenant leaves
     * two things behind — the money in that tenant's ledger, and its
     * subscription's deposit marked paid — and a plain downward correction
     * mends only the first, leaving the real deposit uncollectable because
     * nothing is due any more. This entry takes the money back and re-arms
     * the obligation in the same locked transaction. It names the reference
     * the deposit was recorded under, so the two rows read as one act.
     */
    public static final String DEPOSIT_REVERSAL = "DEPOSIT_REVERSAL";
}
