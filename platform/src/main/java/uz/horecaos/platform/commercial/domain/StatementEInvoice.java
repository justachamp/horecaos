package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.commercial.api.EInvoiceDocument;
import uz.horecaos.platform.commercial.api.EInvoiceOperatorState;

/**
 * One attempt to send one issued statement to an e-invoicing operator, and what
 * became of it (ADR 0096): what HorecaOS sent, the operator's document identifier
 * and the state the operator reports.
 *
 * <p>The sent document and its parties are frozen at the database; only the
 * delivery bookkeeping and the operator's reported state ever move. There is no
 * signature material on it, because none is ever received (ADR 0096, decision 3).
 */
public record StatementEInvoice(
        UUID id,
        UUID tenantId,
        UUID statementId,
        UUID installationId,
        String providerType,
        UUID legalEntityId,
        String buyerTaxpayerNumber,
        String buyerName,
        String sellerTaxpayerNumber,
        String documentNumber,
        LocalDate documentDate,
        String currency,
        long netMinor,
        long vatMinor,
        long totalMinor,
        boolean classificationProvisional,
        EInvoiceDocument sentDocument,
        EInvoiceDelivery delivery,
        @Nullable String failureCode,
        @Nullable String failureDetail,
        @Nullable String operatorDocumentId,
        @Nullable EInvoiceOperatorState operatorState,
        @Nullable String operatorStatus,
        @Nullable Instant stateCheckedAt,
        @Nullable Instant stateChangedAt,
        String sendReason,
        String sentBy,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    /** Whether this attempt is standing as its statement's invoice, which holds the statement against voiding and resending. */
    public boolean live() {
        return delivery.holdsStatement() && (operatorState == null || !operatorState.releasesTheStatement());
    }

    /** Whether asking the operator could still tell us something new. */
    public boolean open() {
        return (delivery == EInvoiceDelivery.SUBMITTED || delivery == EInvoiceDelivery.UNCERTAIN)
                && (operatorState == null || !operatorState.settled());
    }

    /** The client reference the send carried: thirty-two hex characters, the row id without its dashes. */
    public static String clientReferenceOf(UUID id) {
        return id.toString().replace("-", "");
    }
}
