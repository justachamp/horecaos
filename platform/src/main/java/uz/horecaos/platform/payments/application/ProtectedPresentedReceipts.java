package uz.horecaos.platform.payments.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.fiscal.api.SentReceiptEvidence;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.payments.domain.FiscalReceiptLine;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore.PresentedReceiptRow;

/**
 * {@link PresentedReceipts} over {@link ReceiptLineBuilder} and the attempt's protected column (V0687).
 *
 * <p>The class is {@code FINANCIAL} (ADR 0029), the same as the lines a filed document keeps: a price, a
 * classifier and a quantity of something a named customer bought. The ciphertext is bound to the attempt it was
 * written for, so a copy moved to another row does not open. The shape is {@link SentReceiptEvidence.Lines},
 * the one document the evidence surface reads, so a line is described once.
 *
 * <p>No marking code is in it: Payme has no field for one and a marked basket is refused before this is
 * reached, so the builder never returns a code for a Payme subject.
 */
@Component
public class ProtectedPresentedReceipts implements PresentedReceipts {

    private static final Logger log = LoggerFactory.getLogger(ProtectedPresentedReceipts.class);

    /** The purpose the kept lines are opened under to carry them on a checkout or to file them. */
    static final String PURPOSE = "payme-checkout-receipt";

    private final ReceiptLineBuilder builder;
    private final PaymentFiscalService fiscal;
    private final FieldProtection protection;
    private final JdbcPaymentAttemptStore attempts;
    private final ObjectMapper json;
    private final java.time.Clock clock;

    public ProtectedPresentedReceipts(
            ReceiptLineBuilder builder,
            PaymentFiscalService fiscal,
            FieldProtection protection,
            JdbcPaymentAttemptStore attempts,
            ObjectMapper json,
            java.time.Clock clock) {
        this.builder = builder;
        this.fiscal = fiscal;
        this.protection = protection;
        this.attempts = attempts;
        this.json = json;
        this.clock = clock;
    }

    /** Where the protected lines sit, which is also part of what the ciphertext is bound to. */
    public static RecordRef recordOf(UUID attemptId) {
        return new RecordRef("payments.payment_attempts", "presented_receipt_reference", attemptId);
    }

    @Override
    public boolean carriedBy(PaymentProviderType provider) {
        return fiscal.linesTravelWithTheCheckout(provider);
    }

    @Override
    @Transactional
    public Prepared prepare(PaymentAttempt attempt, UUID orderId) {
        Optional<List<FiscalReceiptLine>> kept = lines(attempt.tenantId(), attempt.id());
        if (kept.isPresent()) {
            return new Prepared.Ready(kept.get());
        }

        // Payme's cashbox is the taxpayer: no line carries a TIN, because there is no field to carry one.
        ReceiptLineBuilder.Result built =
                builder.build(ReceiptLineBuilder.Subject.ofPresentedAttempt(attempt, orderId), null);
        return switch (built) {
            case ReceiptLineBuilder.Result.Lines lines -> {
                keep(attempt, lines.lines());
                yield new Prepared.Ready(lines.lines());
            }
            case ReceiptLineBuilder.Result.Blocked blocked ->
                new Prepared.Refused(blocked.obstacle() + ":" + blocked.detail());
            case ReceiptLineBuilder.Result.MarksIncomplete incomplete -> new Prepared.Refused("MARKS_INCOMPLETE");
            case ReceiptLineBuilder.Result.Unprojectable unprojectable -> new Prepared.Refused(unprojectable.reason());
        };
    }

    private void keep(PaymentAttempt attempt, List<FiscalReceiptLine> lines) {
        String currency = lines.get(0).unitPrice().currency();
        List<SentReceiptEvidence.Line> evidence = new ArrayList<>(lines.size());
        for (FiscalReceiptLine line : lines) {
            evidence.add(new SentReceiptEvidence.Line(
                    line.kind() == FiscalReceiptLine.Kind.DELIVERY_FEE
                            ? SentReceiptEvidence.KIND_DELIVERY_FEE
                            : SentReceiptEvidence.KIND_ITEM,
                    line.fiscalName(),
                    line.mxikCode(),
                    line.packageCode(),
                    line.unitCode(),
                    line.quantity(),
                    line.unitPrice().value(),
                    line.taxAmount().value(),
                    line.vatPercent(),
                    line.discount() == null ? null : line.discount().value(),
                    line.barcode(),
                    line.markingCodes().size(),
                    line.commissionTin()));
        }
        String plaintext = json.writeValueAsString(
                new SentReceiptEvidence.Lines(SentReceiptEvidence.VERSION, currency, false, evidence));
        String reference = protection
                .protect(attempt.tenantId(), DataClass.FINANCIAL, recordOf(attempt.id()), plaintext)
                .serialize();
        if (!attempts.recordPresentedReceipt(attempt.tenantId(), attempt.id(), reference, clock.instant())) {
            // Another presentation of the same attempt fixed the lines first. They stand, and prepare()
            // returned these; the next read opens the earlier ones. Identifiers only.
            log.info("Attempt {} already carried its receipt lines; the earlier ones stand", attempt.id());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<List<FiscalReceiptLine>> lines(UUID tenantId, UUID attemptId) {
        return attempts.presentedReceiptReference(tenantId, attemptId)
                .map(reference -> open(tenantId, attemptId, reference));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<List<FiscalReceiptLine>> linesOfIntent(UUID tenantId, UUID intentId) {
        return attempts.presentedReceiptOfIntent(tenantId, intentId)
                .map((PresentedReceiptRow row) -> open(tenantId, row.attemptId(), row.protectedReference()));
    }

    private List<FiscalReceiptLine> open(UUID tenantId, UUID attemptId, String reference) {
        String plaintext =
                protection.reveal(tenantId, ProtectedValue.deserialize(reference), recordOf(attemptId), PURPOSE);
        SentReceiptEvidence.Lines kept = json.readValue(plaintext, SentReceiptEvidence.Lines.class);
        List<FiscalReceiptLine> lines = new ArrayList<>(kept.lines().size());
        for (SentReceiptEvidence.Line line : kept.lines()) {
            lines.add(new FiscalReceiptLine(
                    line.name(),
                    line.ikpu(),
                    line.packageCode(),
                    line.unitCode(),
                    line.quantity(),
                    new SomAmount(line.unitPriceMinor(), kept.currency()),
                    new SomAmount(line.taxAmountMinor(), kept.currency()),
                    line.vatPercent(),
                    line.discountMinor() == null ? null : new SomAmount(line.discountMinor(), kept.currency()),
                    line.barcode(),
                    List.of(),
                    line.commissionTin(),
                    null,
                    SentReceiptEvidence.KIND_DELIVERY_FEE.equals(line.kind())
                            ? FiscalReceiptLine.Kind.DELIVERY_FEE
                            : FiscalReceiptLine.Kind.ITEM));
        }
        return List.copyOf(lines);
    }
}
