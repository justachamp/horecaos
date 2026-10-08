package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A request for payment of money to be held in a tenant's wallet, before tax
 * (ADR 0095). Frozen at issue, bank details included; whether it was paid is the
 * sum of the entries that name it, carried here as {@code paidMinor}.
 */
public record PrepaymentInvoice(
        UUID id,
        UUID tenantId,
        String number,
        long amountMinor,
        String currency,
        Instant validUntil,
        String bankBeneficiary,
        String bankName,
        String bankAccount,
        String bankMfo,
        String bankTaxId,
        String issuedBy,
        Instant issuedAt,
        @Nullable String cancelledBy,
        @Nullable Instant cancelledAt,
        @Nullable String cancelReason,
        long paidMinor) {

    public static final String OPEN = "OPEN";
    public static final String PARTIALLY_PAID = "PARTIALLY_PAID";
    public static final String PAID = "PAID";
    public static final String EXPIRED = "EXPIRED";
    public static final String CANCELLED = "CANCELLED";

    /**
     * Where it stands at {@code now}. Derived every time, never stored: a
     * cancelled invoice is cancelled whatever else is true, a paid one stays
     * paid after it would have expired (the money is in the ledger either way),
     * and one nothing has paid expires.
     */
    public String statusAt(Instant now) {
        if (cancelledAt != null) {
            return CANCELLED;
        }
        if (paidMinor >= amountMinor) {
            return PAID;
        }
        if (paidMinor > 0) {
            return PARTIALLY_PAID;
        }
        return validUntil.isAfter(now) ? OPEN : EXPIRED;
    }

    /** What is still unpaid; never negative, because an overpayment is the tenant's credit and not a debt of HorecaOS. */
    public long dueMinor() {
        return Math.max(0, amountMinor - paidMinor);
    }
}
