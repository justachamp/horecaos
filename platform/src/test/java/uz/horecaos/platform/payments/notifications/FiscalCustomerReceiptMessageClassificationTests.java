package uz.horecaos.platform.payments.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.protection.ClassificationScanner;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalDocumentType;
import uz.horecaos.platform.payments.domain.FiscalReason;
import uz.horecaos.platform.payments.domain.FiscalStatus;
import uz.horecaos.platform.payments.domain.PaymentProviderType;

/**
 * {@link FiscalCustomerReceiptTrigger}'s own vocabulary, classified —
 * deliberately, not automatically, because {@link
 * TelegramOperationsMessageClassificationTests}'s {@code assertClean} genre
 * (see also this package's own {@code PaymentOperationsMessageClassificationTests})
 * asserts the opposite rule from the one that applies here. Every one of
 * those siblings is about a <em>group</em> message, where ADR 0058 forbids a
 * customer's own data outright: "no phone, no address, no note in an
 * operations or control-plane message". This trigger's message goes to the
 * customer's own 1:1 chat, where the same record says the opposite: "a
 * customer's own 1:1 chat may carry their own data" — a receipt URL naming
 * this exact customer's own transaction is precisely the kind of content
 * that sentence exists to permit.
 *
 * <p>"Deliberate" means two things, both asserted below: the vocabulary is
 * named exactly, not merely scanned, so a third variable appearing here is a
 * reviewed decision rather than a silent addition; and {@link
 * ClassificationScanner#isProtectedName} is checked and its answer recorded
 * as a fact about {@code receiptUrl}, not skipped — this test would need to
 * change, not merely stay green, if that answer ever became {@code true}.
 */
class FiscalCustomerReceiptMessageClassificationTests {

    @Test
    void theReceiptVariablesAreExactlyTheOfdLinkAndNothingElse() {
        FiscalDocument issued = issuedDocument("https://ofd.soliq.uz/epi?t=1&r=2&c=3&s=4");

        Map<String, String> variables = FiscalCustomerReceiptTrigger.receiptVariables(issued);

        // Named explicitly, the discipline every sibling in this genre
        // applies: an amount, a seller name, or anything about the order
        // beyond the receipt link itself appearing here is exactly the
        // drift this test exists to catch.
        assertThat(variables.keySet()).containsExactly("receiptUrl");
        assertThat(variables).containsEntry("receiptUrl", "https://ofd.soliq.uz/epi?t=1&r=2&c=3&s=4");
    }

    @Test
    void aDocumentWithNoReceiptUrlRendersAnEmptyStringRatherThanFailing() {
        // Payme's evidence can legitimately carry named fields without a URL
        // (see FiscalDocument.FiscalEvidence's own javadoc); the template
        // still has to render something rather than throw mid-send.
        Map<String, String> variables = FiscalCustomerReceiptTrigger.receiptVariables(issuedDocument(null));

        assertThat(variables).containsEntry("receiptUrl", "");
    }

    @Test
    void receiptUrlIsNotFlaggedByTheOperationsProtectedNameScanner() {
        // Recorded as a fact, not assumed: this is what makes it safe for
        // FiscalCustomerReceiptTrigger to skip the assertClean() call every
        // operations-audience sibling in this genre makes. If a future
        // rename ever made this true, the right fix is not to keep sending
        // it to the customer's own chat as though nothing changed — ADR 0029
        // classification and ADR 0058's channel-appropriateness are
        // independent questions, and this test only answers the first.
        assertThat(ClassificationScanner.isProtectedName("receiptUrl")).isFalse();
    }

    private static FiscalDocument issuedDocument(@Nullable String receiptUrl) {
        return new FiscalDocument(
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                null,
                null,
                null,
                PaymentProviderType.CLICK,
                FiscalDocumentType.SALE,
                null,
                FiscalStatus.ISSUED,
                FiscalReason.PARTNER_FISCALIZED,
                "test",
                List.of(),
                new FiscalDocument.FiscalEvidence(
                        "REC-1", "SIGN-1", "T-1", "R-1", java.time.Instant.now(), receiptUrl, "0", "OK"),
                1,
                java.time.Instant.now());
    }
}
