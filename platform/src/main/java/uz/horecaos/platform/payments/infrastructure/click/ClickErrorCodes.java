package uz.horecaos.platform.payments.infrastructure.click;

import org.jspecify.annotations.Nullable;

/**
 * What can honestly be said about a MERCHANT API {@code error_code} (ADR 0013).
 *
 * <p><strong>The enumeration is not published.</strong> Click's own errors page
 * documents HTTP statuses only, and every JSON example in every section of the
 * documentation shows {@code error_code: 0}. There is no table anywhere reachable
 * saying what "insufficient funds", "card blocked", "wrong OTP", "invoice expired"
 * or "service disabled" look like. <strong>This is an open question with CLICK</strong>
 * — the first of the twelve in {@code docs/providers/click-merchant-api.md} — and
 * it must be answered before the first real transaction, because without it a
 * direction-B failure cannot be told from a direction-B rejection.
 *
 * <p>So exactly one thing is asserted here: {@code error_code == 0} means the call
 * succeeded. Both official reference implementations branch on precisely that and
 * on nothing else.
 *
 * <p><strong>An absent code is not a zero, except on a read.</strong> Several
 * documented responses carry no {@code error_code}, so a read that insisted on one
 * would call a successful fiscal read-back a rejection. A mutating call is the
 * opposite case: a 2xx whose body is empty or unparsed says nothing about whether
 * Click moved money, and reading that silence as success is how a reversal Click
 * never performed gets recorded as a refund the cardholder received. Hence two
 * predicates rather than one, and hence the caller must know which kind of call it
 * made.
 *
 * <p>Everything else is <em>unclassified</em>, and unclassified resolves to
 * {@link uz.horecaos.platform.payments.domain.ProviderOutcome.Classification#UNCERTAIN}
 * on a mutating call rather than to a rejection. The temptation is to map the
 * codes the reference implementations return — PHP's {@code -31300 "Payment in
 * processing"}, Django's {@code -1000}, {@code -5001}, {@code -5002} and
 * {@code -1 * http_status}. None of those are Click codes: they are the sample
 * applications' own inventions, and putting them in a table here would produce an
 * adapter that confidently mis-classifies real failures it has never seen.
 *
 * <p>The practical cost of the honest answer is one extra {@code status_by_mti}
 * query per unclassified failure. The cost of the dishonest one is a second charge
 * on a customer's card the first time an invented code is read as "definitely did
 * not happen".
 */
public final class ClickErrorCodes {

    /** The only value with a documented meaning. */
    public static final long SUCCESS = 0L;

    /** HorecaOS's own failure code for the gap above. Never one of Click's. */
    public static final String UNCLASSIFIED = "CLICK_ERROR_CODE_UNCLASSIFIED";

    private ClickErrorCodes() {}

    /**
     * The read predicate under its old name.
     *
     * <p>Kept only so the existing call site keeps compiling while it is still
     * one predicate for every call. {@code ClickResponse.successful()} must learn
     * which of the two applies — it already knows, because
     * {@code MerchantApiCall.mutating()} is set one line away — and this method
     * goes when it does. Nothing new should call it: the name does not say which
     * question it answers, and that is the whole defect.
     */
    public static boolean successful(@Nullable Object rawErrorCode) {
        return successfulRead(rawErrorCode);
    }

    /**
     * Whether a <strong>read</strong> succeeded, where an absent code counts as
     * success.
     *
     * <p>Only for the calls the documentation shows answering without an
     * {@code error_code} — the {@code ofd_data} GET is the one HorecaOS makes — and
     * only because a read cannot move money: the worst a wrong answer here can do
     * is read the same receipt again.
     *
     * <p>Never for a mutating call. See {@link #successfulMutation}.
     */
    public static boolean successfulRead(@Nullable Object rawErrorCode) {
        return asLong(rawErrorCode) == SUCCESS;
    }

    /**
     * Whether a <strong>mutating</strong> call succeeded, which requires Click to
     * have said so.
     *
     * <p>The asymmetry with {@link #successfulRead} is the point.
     * {@code payment/reversal} and {@code ofd_data/submit_items} answer 2xx with a
     * body, and the body is the only statement anyone has that Click acted. A 2xx
     * whose body is empty, unparsed, or missing the field is not that statement,
     * and reading it as success records a REVERSE for the full amount and an
     * attempt marked REVERSED — asserting money went back to a cardholder when
     * Click may have done nothing at all.
     *
     * <p>False here does not mean "did not happen". Everything that is not an
     * explicit zero is {@link #uncertainMutation} territory: resolve it by
     * querying {@code status_by_mti}, never by sending the call again.
     */
    public static boolean successfulMutation(@Nullable Object rawErrorCode) {
        return present(rawErrorCode) && asLong(rawErrorCode) == SUCCESS;
    }

    /**
     * Whether a mutating call left the question open.
     *
     * <p>True exactly when Click sent no code at all. A code that is present and
     * non-zero is Click saying the call failed — unclassified, because the
     * enumeration is not published, but a statement nonetheless. No code is not a
     * statement, and the two must not settle the same way: one is a failure to
     * report, the other is a payment nobody can account for.
     */
    public static boolean uncertainMutation(@Nullable Object rawErrorCode) {
        return !present(rawErrorCode);
    }

    /** Whether Click sent an {@code error_code} at all. */
    public static boolean present(@Nullable Object rawErrorCode) {
        return rawErrorCode != null;
    }

    /**
     * The code as a number, or {@link #SUCCESS} when the field is absent.
     *
     * <p>Absent reads as {@code 0} here because several documented responses — the
     * {@code ofd_data} GET among them — carry no {@code error_code} at all, and
     * treating a missing field as a failure would make a successful fiscal
     * read-back look like a rejection. That substitution is safe for a read and is
     * not safe for anything else, which is why the predicates above and not this
     * method are what a caller branches on.
     */
    public static long asLong(@Nullable Object rawErrorCode) {
        if (rawErrorCode == null) {
            return SUCCESS;
        }
        if (rawErrorCode instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(rawErrorCode.toString().strip());
        } catch (NumberFormatException unparseable) {
            // A non-numeric error_code is not something Click documents either.
            // Reported as a non-zero code so it lands in the unclassified bucket
            // rather than being read as success.
            return Long.MIN_VALUE;
        }
    }

    /**
     * A short, safe description for an operator.
     *
     * <p>Carries the numeric code so that a human can quote it to Click support,
     * and the note only when Click sent one. Provider error notes have been known
     * to echo request content back, so this is what reaches a log line and the raw
     * body is not.
     */
    public static String describe(@Nullable Object rawErrorCode, @Nullable Object rawErrorNote) {
        String note = rawErrorNote == null ? "" : rawErrorNote.toString();
        if (note.length() > 200) {
            note = note.substring(0, 200);
        }
        return "error_code=" + asLong(rawErrorCode) + (note.isBlank() ? "" : " (" + note + ")");
    }
}
