package uz.horecaos.platform.ordering.domain;

import java.util.UUID;

/**
 * The single terminal fact recorded for an order (ADR 0039).
 *
 * <p>Assembled before the transition and written in the same transaction as it,
 * so an order can never be closed with nobody having said what closing it meant.
 * Reports read this; they never read a status string.
 *
 * @param reasonId         the tenant's reason, or null when the platform decided
 *                         — an approval that lapsed has no reason an operator
 *                         picked, and inventing one would put a tenant's name on
 *                         a fact the tenant had no part in
 * @param reasonVersion    the version the reason carried when it was cited
 * @param reasonSnapshot   the whole reason row as JSON. Deliberate duplication:
 *                         renaming a reason next year must not rewrite last
 *                         year's funnel
 * @param disposition      what was actually decided about the stock, which is
 *                         not always what the reason says — before the hold is
 *                         committed a cancellation always releases
 * @param reservationCommitted which of the two situations applied, recorded
 *                         rather than left to be re-derived later from a
 *                         timestamp somebody may reinterpret
 * @param noteEncrypted    the operator's own words, encrypted, because nothing
 *                         stops one typing a customer's phone number into a
 *                         free-text box
 */
public record OrderOutcome(
        TerminalOutcomeKind kind,
        OutcomeSystemCategory systemCategory,
        UUID reasonId,
        Integer reasonVersion,
        String reasonSnapshot,
        StockDisposition disposition,
        LiabilityParty liabilityParty,
        CustomerRefund customerRefund,
        boolean reservationCommitted,
        String noteEncrypted) {

    public OrderOutcome {
        if (kind == null) {
            throw new IllegalArgumentException("A terminal outcome needs a kind");
        }
        if (systemCategory == null) {
            throw new IllegalArgumentException("A terminal outcome needs a system category");
        }
        if (disposition == null) {
            throw new IllegalArgumentException("A terminal outcome needs a stock disposition");
        }
        if ((reasonId == null) != (reasonVersion == null)) {
            throw new IllegalArgumentException(
                    "A cited reason travels with the version it was cited at");
        }
        if ((reasonId == null) != (reasonSnapshot == null)) {
            throw new IllegalArgumentException(
                    "A cited reason travels with a snapshot of the row it was");
        }
        // The category belongs to the kind. A completion categorised as
        // ITEM_UNAVAILABLE would sit in the cancellation funnel for ever.
        if (!agrees(kind, systemCategory)) {
            throw new IllegalArgumentException(
                    "%s is not a category a %s outcome can carry".formatted(systemCategory, kind));
        }
        // A completed order took the stock it was always going to take at
        // confirmation. Nothing further moves, and nobody is out of pocket.
        if (kind == TerminalOutcomeKind.COMPLETED
                && (disposition != StockDisposition.NO_EFFECT || liabilityParty != null
                        || customerRefund != null)) {
            throw new IllegalArgumentException(
                    "A completed order moves no stock and costs nobody anything");
        }
        // ADR 0017: a cancellation never reopens a committed reservation, so a
        // disposition that moves stock can only follow one that was committed.
        if (!reservationCommitted && disposition != StockDisposition.RELEASE) {
            throw new IllegalArgumentException(
                    "An uncommitted reservation is released; the reason's disposition "
                            + "decides nothing until the hold has been turned into a sale");
        }
    }

    private static boolean agrees(TerminalOutcomeKind kind, OutcomeSystemCategory category) {
        return switch (kind) {
            case COMPLETED -> category.availableFor(OutcomeReasonKind.COMPLETION);
            case EXPIRED -> category == OutcomeSystemCategory.APPROVAL_DEADLINE_LAPSED;
            case PAYMENT_FAILED -> category == OutcomeSystemCategory.PAYMENT_NOT_RECEIVED;
            case CANCELLED, REJECTED -> category.availableFor(OutcomeReasonKind.CANCELLATION);
        };
    }
}
