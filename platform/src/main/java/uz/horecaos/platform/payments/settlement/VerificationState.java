package uz.horecaos.platform.payments.settlement;

/**
 * Whether an attested money movement has since been corroborated by something
 * other than the person who asserted it.
 *
 * <p>Deliberately not a nullable {@code verified_at}. A null reads as "unknown",
 * and the state of every attested remedy in this build is known precisely: it is
 * unverified, because nothing that could verify it exists yet. The ADR 0013
 * settlement import that would close the loop is not built, so
 * {@link #UNVERIFIED} is not a transitional value waiting on an operator — it is
 * the resting state of the reconciliation gap, and it is queryable.
 */
public enum VerificationState {

    /**
     * Recorded on an operator's word alone.
     *
     * <p>The default, and today the only state a money remedy is written in.
     */
    UNVERIFIED,

    /**
     * A source outside HorecaOS agreed the money moved — a provider settlement line,
     * an inbound Payme {@code CancelTransaction}, a bank statement.
     */
    CONFIRMED,

    /**
     * A source outside HorecaOS disagreed, or produced a different amount.
     *
     * <p>Not deleted and not corrected in place: the assertion was made, and what
     * a reconciliation found is a second fact about it rather than a reason to
     * lose the first.
     */
    DISPUTED
}
