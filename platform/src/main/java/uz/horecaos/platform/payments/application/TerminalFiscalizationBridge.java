package uz.horecaos.platform.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.fiscal.api.FiscalTerminalDirectory;
import uz.horecaos.platform.fiscal.api.FiscalTerminalDirectory.CapableTerminal;
import uz.horecaos.platform.fiscal.api.FiscalTerminalPort;
import uz.horecaos.platform.fiscal.api.FiscalTerminalPort.IssueOutcome;
import uz.horecaos.platform.fiscal.api.PartnerFiscalizationPort.Outcome;
import uz.horecaos.platform.fiscal.api.SentReceiptEvidence;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalReceiptLine;
import uz.horecaos.platform.payments.domain.FiscalStatus;
import uz.horecaos.platform.payments.domain.FiscalSubmission;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.SentReceipt;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;

/**
 * The {@code TERMINAL} path: a cash sale's receipt, requested from the tenant's own equipment and recorded
 * (ADR 0166 «Providers» and Decision 1, ADR 0038 stage 5, ADR 0007).
 *
 * <p>The tenant's legal entity is the taxpayer, its terminal is the issuer, and this is HorecaOS asking and
 * keeping what the terminal returns. It never issues on its own account and never invents a receipt.
 *
 * <p><strong>The order of events is the whole safety property</strong>, and it is Click's rule applied to
 * a device: <em>read before write, and never ask twice to issue.</em>
 *
 * <ol>
 *   <li>The key is derived from the document, so a document has one receipt however often anything asks.</li>
 *   <li>The terminal is looked up first. A receipt already there (an earlier attempt whose reply was lost) is
 *       recorded and nothing is issued; an answer that is itself uncertain stops the pass.</li>
 *   <li>Only a terminal that holds nothing for the key is asked to issue. An answer that is lost, late or
 *       unreadable is {@code UNCERTAIN}: the document stays {@code SUBMITTED}, nothing is sent again, and the
 *       next pass finds the receipt by looking.</li>
 *   <li>A terminal that could not be reached, when nothing left, is {@code TERMINAL_UNREACHABLE}: the
 *       document is blocked for the one reason that names a device, and an operator or the retry button asks again.</li>
 * </ol>
 *
 * <p>The lines are the receipt of the order built exactly as for a provider (the same classification, VAT and
 * reconciliation rules, no placeholder), kept as sent behind ADR 0029 protection, with the whole amount in the
 * evidence's cash share. A marked good is not issuable here: the port carries no marking code (ADR 0161), so the
 * builder refuses it and nothing is issued.
 */
@Service
public class TerminalFiscalizationBridge implements TerminalReceiptIssuer {

    private static final Logger log = LoggerFactory.getLogger(TerminalFiscalizationBridge.class);

    private final PaymentFiscalService fiscalDocuments;
    private final JdbcPaymentIntentStore intents;
    private final ReceiptLineBuilder lines;
    private final FiscalTerminalDirectory terminals;
    private final ObjectProvider<FiscalTerminalPort> ports;
    private final Clock clock;

    public TerminalFiscalizationBridge(
            PaymentFiscalService fiscalDocuments,
            JdbcPaymentIntentStore intents,
            ReceiptLineBuilder lines,
            FiscalTerminalDirectory terminals,
            ObjectProvider<FiscalTerminalPort> ports,
            Clock clock) {
        this.fiscalDocuments = fiscalDocuments;
        this.intents = intents;
        this.lines = lines;
        this.terminals = terminals;
        this.ports = ports;
        this.clock = clock;
    }

    /** The key every attempt at this document carries to the terminal: one document, one receipt. */
    static String keyOf(UUID documentId) {
        return "terminal-issue:" + documentId;
    }

    @Override
    public Outcome issue(FiscalDocument document) {
        if (document.status() == FiscalStatus.ISSUED) {
            return Outcome.ALREADY_ISSUED;
        }
        UUID tenantId = document.tenantId();
        UUID legalEntityId = document.legalEntityId();
        Optional<PaymentIntent> intent = document.paymentIntentId() == null
                ? Optional.empty()
                : intents.find(tenantId, document.paymentIntentId());
        if (legalEntityId == null || intent.isEmpty()) {
            // Without the seller or the place there is no terminal to name, and guessing either is how a
            // receipt is issued on another company's equipment.
            return Outcome.NO_PROVIDER_PATH;
        }

        Optional<CapableTerminal> terminal =
                terminals.capableTerminal(tenantId, intent.get().locationId(), legalEntityId);
        if (terminal.isEmpty()) {
            return Outcome.NO_TERMINAL;
        }
        FiscalTerminalPort port = ports.getIfAvailable();
        if (port == null) {
            log.warn("Fiscal document {} was not sent: no FiscalTerminalPort is wired.", document.id());
            return Outcome.NOT_WIRED;
        }

        String key = keyOf(document.id());
        Instant now = clock.instant();

        // Read before write.
        IssueOutcome known = port.lookup(tenantId, terminal.get().reference(), key);
        switch (known) {
            case FiscalTerminalPort.Issued issued -> {
                // An earlier attempt reached the terminal and its reply was lost. The receipt exists; it is
                // recorded, and nothing is issued.
                fiscalDocuments.recordTerminalSubmission(
                        document, terminal.get().terminalId(), FiscalSubmission.issued(evidenceOf(issued), now), now);
                return Outcome.ALREADY_ISSUED;
            }
            case FiscalTerminalPort.Uncertain uncertain -> {
                return Outcome.UNCERTAIN;
            }
            case FiscalTerminalPort.Unreachable unreachable -> {
                return Outcome.TERMINAL_UNREACHABLE;
            }
            case FiscalTerminalPort.NotFound notFound -> {}
            case FiscalTerminalPort.Rejected rejected -> {}
        }

        Optional<String> filedUnder = fiscalDocuments.sellerTaxpayerNumber(tenantId, document.id());
        if (filedUnder.isEmpty()) {
            // The same rule as a provider receipt (ADR 0166 Decision 3): a document with no seller snapshot has
            // no taxpayer to name, and nothing is issued under a TIN of zeros.
            log.warn(
                    "Fiscal document {} for order {} was not sent: it holds no seller snapshot",
                    document.id(),
                    document.orderId());
            return Outcome.REJECTED;
        }
        List<FiscalReceiptLine> built;
        switch (lines.build(ReceiptLineBuilder.Subject.of(document), null)) {
            case ReceiptLineBuilder.Result.Lines ready -> built = ready.lines();
            case ReceiptLineBuilder.Result.MarksIncomplete incomplete -> {
                return Outcome.BLOCKED_MARKS;
            }
            case ReceiptLineBuilder.Result.Unprojectable unprojectable -> {
                log.warn(
                        "Fiscal document {} for order {} was not sent to the terminal: {}",
                        document.id(),
                        document.orderId(),
                        unprojectable.reason());
                return Outcome.REJECTED;
            }
            case ReceiptLineBuilder.Result.Blocked blocked -> {
                return switch (blocked.obstacle()) {
                    case CLASSIFICATION -> Outcome.BLOCKED_CLASSIFICATION;
                    case TAX -> Outcome.BLOCKED_TAX;
                    case RECONCILIATION -> Outcome.BLOCKED_RECONCILIATION;
                };
            }
        }

        String currency = intent.get().amount().currency();
        long paid = built.stream().mapToLong(line -> line.netTotal().value()).sum();
        List<SentReceiptEvidence.Line> evidenceLines = new ArrayList<>(built.size());
        for (FiscalReceiptLine line : built) {
            evidenceLines.add(ProtectedSentEvidenceRecorder.evidenceLineOf(line));
        }
        // The lines go with the document from here on: what was sent is kept whatever the terminal answers.
        FiscalDocument sentDocument = withLines(document, built);
        SentReceipt wire = new SentReceipt(
                built, new SomAmount(0, currency), new SomAmount(paid, currency), new SomAmount(0, currency));

        IssueOutcome answer = port.issue(new FiscalTerminalPort.IssueRequest(
                tenantId,
                document.id(),
                document.orderId(),
                legalEntityId,
                terminal.get().reference(),
                key,
                currency,
                evidenceLines,
                paid));

        switch (answer) {
            case FiscalTerminalPort.Issued issued -> {
                fiscalDocuments.recordTerminalSubmission(
                        sentDocument,
                        terminal.get().terminalId(),
                        FiscalSubmission.issued(evidenceOf(issued), now).withSent(wire),
                        now);
                return Outcome.ISSUED;
            }
            case FiscalTerminalPort.Rejected rejected -> {
                fiscalDocuments.recordTerminalSubmission(
                        sentDocument,
                        terminal.get().terminalId(),
                        FiscalSubmission.rejected(rejected.code(), "The fiscal terminal refused the receipt", now)
                                .withSent(wire),
                        now);
                return Outcome.REJECTED;
            }
            case FiscalTerminalPort.Unreachable unreachable -> {
                // Nothing left, so nothing is recorded as sent.
                return Outcome.TERMINAL_UNREACHABLE;
            }
            case FiscalTerminalPort.Uncertain uncertain -> {
                fiscalDocuments.recordTerminalSubmission(
                        sentDocument,
                        terminal.get().terminalId(),
                        FiscalSubmission.uncertain(uncertain.code(), now).withSent(wire),
                        now);
                return Outcome.UNCERTAIN;
            }
            case FiscalTerminalPort.NotFound notFound -> {
                // Not a defined answer to issuing. The request left; it is uncertain, never issued again.
                fiscalDocuments.recordTerminalSubmission(
                        sentDocument,
                        terminal.get().terminalId(),
                        FiscalSubmission.uncertain("NOT_FOUND_AFTER_ISSUE", now).withSent(wire),
                        now);
                return Outcome.UNCERTAIN;
            }
        }
    }

    private static FiscalDocument.FiscalEvidence evidenceOf(FiscalTerminalPort.Issued issued) {
        return new FiscalDocument.FiscalEvidence(
                issued.receiptNumber(),
                issued.fiscalSign(),
                issued.terminalId(),
                issued.receiptNumber(),
                issued.registeredAt(),
                issued.receiptUrl(),
                null,
                null);
    }

    private static FiscalDocument withLines(FiscalDocument document, List<FiscalReceiptLine> lines) {
        return new FiscalDocument(
                document.id(),
                document.tenantId(),
                document.orderId(),
                document.legalEntityId(),
                document.paymentIntentId(),
                document.paymentTransactionId(),
                document.providerType(),
                document.documentType(),
                document.correctsDocumentId(),
                document.status(),
                document.reasonCode(),
                document.reasonNote(),
                lines,
                document.evidence(),
                document.version(),
                document.createdAt());
    }
}
