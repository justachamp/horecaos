package uz.horecaos.platform.audit.api;

/**
 * The right to perform one approved action, once (ADR 0027).
 *
 * <p>An approved request used to be a fact a caller could read as often as it
 * liked: {@code requireApproval} answered {@code Approved} for the same
 * parameters until the twenty-four hour validity lapsed, and nothing marked the
 * request spent. One signature therefore authorised an unbounded number of
 * identical executions, which is the opposite of what both
 * {@link ApprovalOutcome.Approved} and the decide endpoint promise. A grant is
 * the answer: the approval is not a readable state, it is a thing you hold and
 * then hand back.
 *
 * <p><strong>Consume it inside the transaction that performs the action.</strong>
 * The spend is an ordinary write in the caller's transaction, so it commits with
 * the effect or rolls back with it. That is the whole reason the spend is not
 * done by {@code requireApproval} on the way out: a request marked spent by a
 * check, in a transaction of its own, would stay spent when the action it was
 * checked for failed, and an approval destroyed by a rolled-back action is a
 * worse control than one that could be replayed.
 *
 * <p>Forgetting is not a quiet failure. The implementation refuses to hand out a
 * grant outside a transaction, and a transaction that commits still holding one
 * is rolled back rather than allowed to keep an approval alive.
 */
@FunctionalInterface
public interface ApprovalGrant {

    /**
     * Spends the approval, in the caller's transaction.
     *
     * @throws RuntimeException if the approval was already spent — which is how
     *                          two concurrent executions under one signature
     *                          resolve to one effect: the loser's transaction
     *                          fails and takes its half-written action with it
     */
    void consume();
}
