package uz.horecaos.platform.commercial.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.commercial.api.EInvoiceOperatorState;
import uz.horecaos.platform.commercial.application.EInvoicingService;
import uz.horecaos.platform.commercial.domain.EInvoiceDelivery;
import uz.horecaos.platform.commercial.domain.StatementEInvoice;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * What the merchant can see of the e-invoice of an issued statement (ADR 0096, IA 8.6 «invoices»).
 *
 * <p>Every other e-invoice read is a staff read: sending, refreshing and releasing are HorecaOS's, and so are
 * the attempts that failed and the reasons they did. The merchant's question is smaller and it is theirs: was my
 * statement sent to an operator, and is it waiting for me to sign? That is the buyer's half of the document's
 * life (the buyer signs or refuses it in the operator's own product), and until now the only way to learn it was
 * to be told.
 *
 * <p><strong>The state is the operator's word, not ours.</strong> It is shown as the operator reports it and the
 * operator's own status text is kept beside it, because a status this build cannot read is still visible and a wrong
 * mapping stays fixable. Nothing here is signature material (none is ever received), and nothing here is a
 * failure detail, a staff name, an operator document identifier, a send reason or a taxpayer number: those stay
 * with the staff reads.
 *
 * <p>Attempts that never reached the operator, and the one being sent right now, are not shown. A failed attempt
 * holds nothing at the operator, so for the merchant there is no invoice; an attempt that may have reached it but
 * has no answer is shown, as sent and not yet confirmed.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/commercial")
@Tag(name = "Commercial", description = "The merchant's own plan, entitlements, and usage")
public class CommercialOperationsEInvoiceController {

    /** The state of an invoice the operator may hold but has not yet said anything about. */
    static final String UNCONFIRMED = "UNCONFIRMED";

    private final EInvoicingService einvoicing;

    public CommercialOperationsEInvoiceController(EInvoicingService einvoicing) {
        this.einvoicing = einvoicing;
    }

    @GetMapping("/einvoices")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Where the e-invoice of each issued statement stands",
            description = "Newest first. state is the operator's word: DRAFT (handed over, not yet signed and sent by "
                    + "HorecaOS), SENT (signed by HorecaOS, waiting for you to sign or refuse it), SIGNED (you "
                    + "signed it: this is the invoice), REFUSED, CANCELLED, UNKNOWN (a status this platform does "
                    + "not recognise, shown as the operator wrote it in operatorStatus) or UNCONFIRMED (sent, and "
                    + "the operator has not said what it holds yet). current marks the one standing as the "
                    + "statement's invoice; an older refused or cancelled one stays for history. Nothing here is "
                    + "signature material.")
    public ResponseEntity<List<StatementEInvoiceView>> einvoices(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(einvoicing.forTenant(tenantId).stream()
                .filter(CommercialOperationsEInvoiceController::reachedTheOperator)
                .map(StatementEInvoiceView::of)
                .toList());
    }

    private static boolean reachedTheOperator(StatementEInvoice row) {
        return row.delivery() == EInvoiceDelivery.SUBMITTED || row.delivery() == EInvoiceDelivery.UNCERTAIN;
    }

    /**
     * One statement's invoice as its buyer may see it.
     *
     * @param provider           the operator, by its code (DIDOX or FAKTURA_UZ)
     * @param awaitingYourSignature the operator holds it signed by HorecaOS and you have not yet signed or refused it
     */
    public record StatementEInvoiceView(
            UUID statementId,
            String documentNumber,
            String documentDate,
            String provider,
            String state,
            boolean awaitingYourSignature,
            @Nullable String operatorStatus,
            @Nullable String stateCheckedAt,
            @Nullable String stateChangedAt,
            boolean current,
            ApiMoney total,
            String sentAt) {

        static StatementEInvoiceView of(StatementEInvoice row) {
            EInvoiceOperatorState state = row.operatorState();
            return new StatementEInvoiceView(
                    row.statementId(),
                    row.documentNumber(),
                    row.documentDate().toString(),
                    row.providerType(),
                    state == null ? UNCONFIRMED : state.name(),
                    state == EInvoiceOperatorState.SENT,
                    row.operatorStatus(),
                    text(row.stateCheckedAt()),
                    text(row.stateChangedAt()),
                    row.live(),
                    ApiMoney.of(row.totalMinor(), row.currency()),
                    row.createdAt().toString());
        }

        private static @Nullable String text(@Nullable Instant instant) {
            return instant == null ? null : instant.toString();
        }
    }
}
