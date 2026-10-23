package uz.horecaos.platform.fiscal.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The tenant's own fiscal-capable equipment, asked to issue a receipt (ADR 0166 «Providers», ADR 0038 stage 5,
 * ADR 0007).
 *
 * <p>The {@code TERMINAL} responsibility: a cash sale, a courier terminal, a kiosk, a POS settlement. The legal
 * entity the location is assigned to on the business date is the taxpayer, its own equipment is the issuer, and
 * HorecaOS is a requester that records what the equipment returns. This is the outbound half; the registry of
 * which terminal belongs to which location is {@link FiscalTerminalDirectory}. HorecaOS never issues on its own
 * INN and never composes a receipt the equipment did not issue.
 *
 * <p><strong>Four rules bind every implementation</strong>, and they are the ADR 0007 rules for a call that has a
 * legal effect and no way to be withdrawn:
 *
 * <ol>
 *   <li><em>Idempotent on the key.</em> Asking twice with the same {@link IssueRequest#idempotencyKey()} issues
 *       once; the second answer is the first receipt. The key is derived from the document, so a document has at
 *       most one receipt however many times anything asks.</li>
 *   <li><em>A lost reply is uncertain, never retried.</em> A request that left and was not answered (a read
 *       timeout, a reset, an unparseable body) may well have been issued. The answer is {@link Uncertain}, the
 *       caller never asks to issue again, and {@link #lookup} is the only thing that settles it.</li>
 *   <li><em>Nothing sent is {@link Unreachable}.</em> A refused connection or a terminal that reports itself
 *       offline before the request left is safe to ask again later, and says so.</li>
 *   <li><em>The domain never sees a vendor code.</em> A refusal carries a stable HorecaOS code; the vendor's own
 *       vocabulary stays in the adapter.</li>
 * </ol>
 *
 * <p>Money is whole som in HorecaOS's own minor unit (ADR 0018); the adapter converts at its boundary. No marking
 * code travels here (ADR 0161: a code is returned by no endpoint and sent only through Click), and the receipt
 * identifiers an answer carries are ADR 0029 {@code FINANCIAL} evidence the caller keeps behind its protection.
 */
public interface FiscalTerminalPort {

    /** Asks the terminal to issue a sale receipt. Idempotent on {@link IssueRequest#idempotencyKey()}. */
    IssueOutcome issue(IssueRequest request);

    /**
     * Reads back what the terminal did for this key, and asks it to do nothing.
     *
     * <p>The resolver of an {@link Uncertain} answer, and what the caller does before it ever issues: it answers
     * {@link Issued} when a receipt exists for the key, {@link NotFound} when the terminal holds none, and
     * {@link Uncertain} or {@link Unreachable} when it cannot say. It never creates a receipt.
     */
    IssueOutcome lookup(UUID tenantId, String terminalReference, String idempotencyKey);

    /**
     * @param terminalReference the registry's reference for the terminal (fiscal.fiscal_terminals), which the
     *     adapter maps to its own device identity
     * @param idempotencyKey stable for the document
     * @param lines the receipt lines as the neutral evidence shape; a marked line carries only a count
     * @param paidMinor what the customer paid at the terminal, in cash for a cash sale, which the lines add up to
     */
    record IssueRequest(
            UUID tenantId,
            UUID documentId,
            UUID orderId,
            UUID legalEntityId,
            String terminalReference,
            String idempotencyKey,
            String currency,
            List<SentReceiptEvidence.Line> lines,
            long paidMinor) {

        public IssueRequest {
            lines = List.copyOf(lines);
        }
    }

    /** What asking the terminal produced. */
    sealed interface IssueOutcome permits Issued, Rejected, Unreachable, Uncertain, NotFound {}

    /**
     * The terminal issued the receipt.
     *
     * @param fiscalSign the fiscal sign
     * @param receiptNumber the terminal's receipt number
     * @param terminalId the fiscal module / terminal identifier printed on the receipt
     * @param receiptUrl the tax authority's page for the receipt, when the terminal returns one
     */
    record Issued(
            String fiscalSign,
            String receiptNumber,
            String terminalId,
            @Nullable String receiptUrl,
            Instant registeredAt)
            implements IssueOutcome {

        /** Never includes the identifiers: they are evidence and a record's generated text would log them. */
        @Override
        public String toString() {
            return "Issued[registeredAt=" + registeredAt + "]";
        }
    }

    /**
     * The terminal refused, and asking again with the same lines will be refused again (a closed shift, a line it
     * cannot express). Permanent for this request.
     *
     * @param code a stable HorecaOS code, never a vendor's
     */
    record Rejected(String code) implements IssueOutcome {}

    /** Nothing left: the terminal could not be reached. Safe to ask again later. */
    record Unreachable(String code) implements IssueOutcome {}

    /**
     * The request left and the answer is lost or unreadable. The receipt may exist. Never issue again; look up.
     */
    record Uncertain(String code) implements IssueOutcome {}

    /** {@link #lookup} only: the terminal holds no receipt for the key. */
    record NotFound() implements IssueOutcome {}
}
