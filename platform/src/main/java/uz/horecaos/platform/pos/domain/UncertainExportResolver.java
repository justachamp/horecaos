package uz.horecaos.platform.pos.domain;

import java.util.List;
import uz.horecaos.platform.pos.api.CapabilitySnapshot.IdempotencyBehaviour;

/**
 * Decides what a recovery read established, and refuses to decide more than it
 * did (ADR 0011).
 *
 * <p>Pure, and unit-tested on its own, because this is the function that decides
 * whether a restaurant cooks a dinner twice.
 *
 * <p>The rule is one sentence: <strong>an automatic resolution requires the
 * provider to have handed back our own reference.</strong> Everything else — one
 * candidate, no candidates, five candidates — goes to a person.
 *
 * <p>That is stricter than the obvious design, and the two rejected alternatives
 * are worth naming.
 *
 * <ul>
 *   <li><em>Exactly one heuristic match means it landed.</em> The heuristic is
 *       venue, customer phone, creation time and line composition. A customer who
 *       orders the same basket twice ninety seconds apart produces exactly one
 *       such match for each of our exports, and the provider offers no field that
 *       separates them. Reading one match as proof is a coin toss dressed as
 *       reconciliation, and it fails silently in the direction that costs food.</li>
 *   <li><em>No candidates means it did not land, so send it again.</em> Absence
 *       from a read is not absence at the provider. The read is a paged list over
 *       a table the restaurant is editing, taken seconds after a call that timed
 *       out; a clerk who accepted the order in that window moves it out of the
 *       status we filtered on, and an offset page that shifted skips it outright.
 *       Auto-resending on a negative read is the same duplicate as auto-resending
 *       on a timeout, arrived at more slowly.</li>
 * </ul>
 *
 * <p>Whether the echo works is an empirical question with a five-minute answer
 * against a real brand, and until somebody runs it every uncertain export costs
 * an operator a decision. That is the honest price of the provider's missing
 * idempotency key, and it belongs in the operations budget rather than hidden
 * inside a guess.
 */
public final class UncertainExportResolver {

    private UncertainExportResolver() {}

    /**
     * @param idempotency what the provider guarantees about a repeated export.
     *                    A provider that deduplicates on a key we supply does not
     *                    need any of this: the safe move there is to re-send under
     *                    the same key, and this resolver says so rather than
     *                    sending an operator work it does not need to do
     */
    public static Decision decide(List<ExportCandidate> candidates, IdempotencyBehaviour idempotency) {
        if (idempotency == IdempotencyBehaviour.KEYED) {
            // The provider will collapse the repeat itself. Nothing to resolve.
            return new Decision(
                    Outcome.RETRY_UNDER_KEY, null, "The provider deduplicates on the key this export already carries");
        }

        List<ExportCandidate> echoed =
                candidates.stream().filter(ExportCandidate::correlationEchoed).toList();

        if (echoed.size() == 1) {
            return new Decision(
                    Outcome.LANDED,
                    echoed.getFirst().externalOrderId(),
                    "The provider returned this export's own correlation reference");
        }

        if (echoed.size() > 1) {
            // Two provider orders carrying one reference of ours means either the
            // reference is not unique at the provider or we genuinely sent twice.
            // Both are things a person must look at, and neither is a retry.
            return new Decision(
                    Outcome.OPERATOR,
                    null,
                    "More than one provider order carries this export's correlation reference, "
                            + "which means the reference is not unique there or the order was sent twice");
        }

        if (candidates.isEmpty()) {
            return new Decision(
                    Outcome.OPERATOR,
                    null,
                    "No provider order resembles this export. Absence from a paged read taken "
                            + "seconds after a timeout is not evidence the order is absent at the provider");
        }

        return new Decision(
                Outcome.OPERATOR,
                null,
                "%d provider order(s) resemble this export on phone, time and line composition, "
                                .formatted(candidates.size())
                        + "which is also what a customer ordering the same basket twice looks like");
    }

    /** What the read established. */
    public enum Outcome {

        /** The provider named the order as ours. Move to RESOLVED_LANDED. */
        LANDED,

        /** Nothing here decides. Move to AWAITING_OPERATOR and show the evidence. */
        OPERATOR,

        /**
         * The provider deduplicates, so the original command may simply be sent
         * again. Reachable only on a provider that says it does; no adapter in
         * this build does.
         */
        RETRY_UNDER_KEY
    }

    /**
     * @param externalOrderId set only on {@link Outcome#LANDED}, because that is
     *                        the only outcome that identified an order
     * @param reason          shown to the operator and stored on the export. It
     *                        explains what could not be established, not merely
     *                        that something could not be
     */
    public record Decision(Outcome outcome, String externalOrderId, String reason) {}
}
