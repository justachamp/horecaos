package uz.horecaos.platform.commercial.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.commercial.api.EInvoiceDocument;
import uz.horecaos.platform.commercial.application.EInvoicingService;
import uz.horecaos.platform.commercial.application.EInvoicingService.InstallationDetail;
import uz.horecaos.platform.commercial.domain.EInvoicingInstallation;
import uz.horecaos.platform.commercial.domain.EInvoicingLineClassification;
import uz.horecaos.platform.commercial.domain.StatementEInvoice;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Sending an issued statement to an e-invoicing operator, and HorecaOS's own accounts with
 * the operators (ADR 0096).
 *
 * <p>HorecaOS staff only. HorecaOS is the seller on every invoice, so nothing here is a
 * tenant's to do: sending and asking the operator are {@code commercial.einvoice.send},
 * connecting an account and confirming a tax treatment are {@code
 * commercial.einvoicing.manage}, and reading is {@code commercial.usage.read}, like the
 * statements themselves. No response here carries a credential, a secret reference or any
 * signature material: an account says only whether it is bound.
 */
@RestController
@Tag(
        name = "Commercial e-invoicing",
        description = "Sending issued statements to Didox and Faktura.uz as electronic invoices")
public class CommercialEInvoicingController {

    private static final String ACCOUNTS = "/api/v1/platform-admin/commercial/einvoicing/installations";
    private static final String CLASSIFICATIONS = "/api/v1/platform-admin/commercial/einvoicing/line-classifications";

    private final EInvoicingService einvoicing;
    private final CurrentActor currentActor;

    public CommercialEInvoicingController(EInvoicingService einvoicing, CurrentActor currentActor) {
        this.einvoicing = einvoicing;
        this.currentActor = currentActor;
    }

    // ------------------------------------------------------------------ reads

    @GetMapping("/api/v1/control-plane/einvoicing/installations")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "HorecaOS's own accounts with the e-invoicing operators",
            description = "One row per operator account: whether it is connected, and what it still needs "
                    + "while it is not. Never a credential or a secret reference.")
    public ResponseEntity<List<InstallationView>> installations() {
        return ResponseEntity.ok(
                einvoicing.installations().stream().map(InstallationView::of).toList());
    }

    @GetMapping("/api/v1/control-plane/einvoicing/line-classifications")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "What each kind of statement line is invoiced as",
            description = "The classification code, unit and VAT rate per line kind, and whether finance "
                    + "has confirmed it (a provisional row has not).")
    public ResponseEntity<List<ClassificationView>> classifications() {
        return ResponseEntity.ok(einvoicing.classifications().stream()
                .map(ClassificationView::of)
                .toList());
    }

    @GetMapping("/api/v1/control-plane/tenants/{tenantId}/einvoices")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Every attempt to send one of the tenant's statements to an operator",
            description = "Newest first, failed attempts included.")
    public ResponseEntity<List<EInvoiceView>> forTenant(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(einvoicing.forTenant(tenantId).stream()
                .map(EInvoiceView::summary)
                .toList());
    }

    @GetMapping("/api/v1/control-plane/tenants/{tenantId}/statements/{statementId}/einvoices")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_READ, scope = ScopeType.TENANT)
    @Operation(summary = "The attempts to send one statement to an operator, newest first")
    public ResponseEntity<List<EInvoiceView>> forStatement(
            @PathVariable UUID tenantId, @PathVariable UUID statementId) {
        return ResponseEntity.ok(einvoicing.forStatement(tenantId, statementId).stream()
                .map(EInvoiceView::summary)
                .toList());
    }

    @GetMapping("/api/v1/control-plane/tenants/{tenantId}/einvoices/{einvoiceId}")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "One e-invoice with the document that was sent",
            description = "The provider-neutral document as it was sent: its parties and lines.")
    public ResponseEntity<EInvoiceView> one(@PathVariable UUID tenantId, @PathVariable UUID einvoiceId) {
        return ResponseEntity.ok(EInvoiceView.detail(einvoicing.find(tenantId, einvoiceId)));
    }

    // ------------------------------------------------------------- the send

    @PostMapping("/api/v1/platform-admin/commercial/tenants/{tenantId}/statements/{statementId}/einvoices")
    @RequiresCapability(value = Capability.COMMERCIAL_EINVOICE_SEND, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Send an issued statement to an e-invoicing operator",
            description = "HorecaOS is the seller and the tenant's legal entity the buyer. The document goes "
                    + "out as a draft invoice; the answer is the attempt as it stands (SUBMITTED, FAILED "
                    + "or UNCERTAIN). 422 OPERATOR_NOT_CONNECTED while HorecaOS has no connected account "
                    + "with that operator. A statement has at most one live invoice across both operators.")
    public ResponseEntity<EInvoiceView> send(
            @PathVariable UUID tenantId, @PathVariable UUID statementId, @Valid @RequestBody SendRequest body) {
        StatementEInvoice sent = einvoicing.send(
                new EInvoicingService.SendRequest(
                        tenantId, statementId, body.provider(), body.legalEntityId(), body.reason()),
                actor(),
                correlationId());
        return ResponseEntity.ok(EInvoiceView.summary(sent));
    }

    @PostMapping("/api/v1/platform-admin/commercial/tenants/{tenantId}/einvoices/{einvoiceId}/state-refresh")
    @RequiresCapability(value = Capability.COMMERCIAL_EINVOICE_SEND, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Ask the operator what became of a sent invoice",
            description = "Records the state the operator reports (sent, signed by the buyer, refused, "
                    + "cancelled). Also how an invoice whose send had no answer is resolved: by asking, "
                    + "never by sending again.")
    public ResponseEntity<RefreshView> refresh(@PathVariable UUID tenantId, @PathVariable UUID einvoiceId) {
        EInvoicingService.Refreshed refreshed = einvoicing.refresh(tenantId, einvoiceId, actor(), correlationId());
        return ResponseEntity.ok(
                new RefreshView(EInvoiceView.summary(refreshed.einvoice()), refreshed.unavailableCode()));
    }

    // ------------------------------------------------- the accounts (finance)

    @PutMapping(ACCOUNTS + "/{installationId}")
    @RequiresCapability(value = Capability.COMMERCIAL_EINVOICING_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Set up HorecaOS's account with an operator",
            description = "The reference to the login in the secrets manager (the value is put there with "
                    + "`bao kv put`, never sent here) and the seller's public identity. A missing setting is "
                    + "cleared. 409 STALE_VERSION when the account moved since expectedVersion was read.")
    public ResponseEntity<InstallationView> update(
            @PathVariable UUID installationId,
            @RequestParam long expectedVersion,
            @Valid @RequestBody InstallationUpdate body) {
        EInvoicingInstallation updated = einvoicing.updateInstallation(
                installationId,
                expectedVersion,
                body.displayName(),
                body.secretReference(),
                body.config() == null ? Map.of() : body.config(),
                actor(),
                body.reason(),
                correlationId());
        return ResponseEntity.ok(InstallationView.of(einvoicing.installationDetail(updated.id())));
    }

    @PostMapping(ACCOUNTS + "/{installationId}/activation")
    @RequiresCapability(value = Capability.COMMERCIAL_EINVOICING_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Connect an operator account",
            description = "422 OPERATOR_NOT_CONNECTED, naming what is missing, while the account has no "
                    + "secret reference or no seller identity.")
    public ResponseEntity<InstallationView> activate(
            @PathVariable UUID installationId,
            @RequestParam long expectedVersion,
            @Valid @RequestBody ReasonRequest body) {
        einvoicing.activateInstallation(installationId, expectedVersion, actor(), body.reason(), correlationId());
        return ResponseEntity.ok(InstallationView.of(einvoicing.installationDetail(installationId)));
    }

    @PostMapping(ACCOUNTS + "/{installationId}/suspension")
    @RequiresCapability(value = Capability.COMMERCIAL_EINVOICING_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Stop sending through an operator account",
            description = "The rollback ADR 0096 names: nothing more is sent through the account. Documents "
                    + "already sent keep their last known state.")
    public ResponseEntity<InstallationView> suspend(
            @PathVariable UUID installationId,
            @RequestParam long expectedVersion,
            @Valid @RequestBody ReasonRequest body) {
        einvoicing.suspendInstallation(installationId, expectedVersion, actor(), body.reason(), correlationId());
        return ResponseEntity.ok(InstallationView.of(einvoicing.installationDetail(installationId)));
    }

    @PutMapping(CLASSIFICATIONS + "/{lineKind}")
    @RequiresCapability(value = Capability.COMMERCIAL_EINVOICING_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "State what a kind of statement line is invoiced as",
            description = "The classification code, unit and VAT rate. confirmed=true records that finance "
                    + "has stated it, who and when, and clears the provisional flag.")
    public ResponseEntity<ClassificationView> classify(
            @PathVariable String lineKind,
            @RequestParam long expectedVersion,
            @Valid @RequestBody ClassificationUpdate body) {
        return ResponseEntity.ok(ClassificationView.of(einvoicing.updateClassification(
                lineKind,
                expectedVersion,
                body.itemLabel(),
                body.catalogCode(),
                body.catalogName(),
                body.packageCode(),
                body.packageName(),
                body.vatRateBp(),
                Boolean.TRUE.equals(body.confirmed()),
                actor(),
                body.reason(),
                correlationId())));
    }

    // --------------------------------------------------------------- helpers

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private static String correlationId() {
        String correlationId = org.slf4j.MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }

    private static @Nullable String text(@Nullable Instant instant) {
        return instant == null ? null : instant.toString();
    }

    // ----------------------------------------------------------- wire records

    /** The statement to send, to which operator, to which of the tenant's companies. */
    public record SendRequest(
            @NotBlank @Pattern(regexp = "DIDOX|FAKTURA_UZ") String provider,
            @Nullable UUID legalEntityId,
            @NotBlank @Size(max = 1000) String reason) {}

    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}

    /**
     * An account's editable fields. {@code secretReference} is the ADR 0028 reference, not a
     * value; {@code config} holds the seller's public identity (taxpayer number, name, address,
     * VAT registration code, bank) and the login language.
     */
    public record InstallationUpdate(
            @NotBlank @Size(max = 200) String displayName,
            @Nullable @Size(max = 512) String secretReference,
            @Nullable Map<String, String> config,
            @NotBlank @Size(max = 1000) String reason) {}

    public record ClassificationUpdate(
            @NotBlank @Size(max = 200) String itemLabel,
            @NotBlank @Size(max = 32) String catalogCode,
            @NotBlank @Size(max = 200) String catalogName,
            @NotBlank @Size(max = 32) String packageCode,
            @NotBlank @Size(max = 100) String packageName,
            @NotNull Integer vatRateBp,
            @Nullable Boolean confirmed,
            @NotBlank @Size(max = 1000) String reason) {}

    /**
     * One operator account as the screen shows it. {@code connected} is the one fact that
     * matters; {@code missing} says what is still needed while it is false. The secret
     * reference itself is never returned, only whether one is bound.
     */
    public record InstallationView(
            UUID installationId,
            String provider,
            String displayName,
            String status,
            boolean connected,
            boolean secretBound,
            List<String> missing,
            boolean adapterWired,
            String adapterVersion,
            String environmentCode,
            @Nullable String baseUrl,
            boolean production,
            Map<String, String> config,
            long version,
            String updatedBy,
            String updatedAt) {

        static InstallationView of(InstallationDetail detail) {
            EInvoicingInstallation installation = detail.installation();
            return new InstallationView(
                    installation.id(),
                    installation.providerType(),
                    installation.displayName(),
                    installation.status(),
                    installation.connected(),
                    installation.secretReference() != null,
                    installation.missing(),
                    detail.adapterWired(),
                    installation.adapterVersion(),
                    installation.environmentCode(),
                    detail.environment() == null ? null : detail.environment().baseUrl(),
                    detail.environment() != null && detail.environment().production(),
                    installation.config(),
                    installation.version(),
                    installation.updatedBy(),
                    installation.updatedAt().toString());
        }
    }

    public record ClassificationView(
            String lineKind,
            String itemLabel,
            String catalogCode,
            String catalogName,
            String packageCode,
            String packageName,
            int vatRateBp,
            boolean provisional,
            @Nullable String confirmedBy,
            @Nullable String confirmedAt,
            long version,
            String updatedBy,
            String updatedAt) {

        static ClassificationView of(EInvoicingLineClassification classification) {
            return new ClassificationView(
                    classification.lineKind(),
                    classification.itemLabel(),
                    classification.catalogCode(),
                    classification.catalogName(),
                    classification.packageCode(),
                    classification.packageName(),
                    classification.vatRateBp(),
                    classification.provisional(),
                    classification.confirmedBy(),
                    text(classification.confirmedAt()),
                    classification.version(),
                    classification.updatedBy(),
                    classification.updatedAt().toString());
        }
    }

    /** One attempt to send a statement, with what the operator reported. {@code lines} only on the detail read. */
    public record EInvoiceView(
            UUID einvoiceId,
            UUID statementId,
            UUID tenantId,
            String provider,
            String documentNumber,
            String documentDate,
            UUID buyerLegalEntityId,
            String buyerName,
            String buyerTaxpayerNumber,
            String sellerTaxpayerNumber,
            ApiMoney net,
            ApiMoney vat,
            ApiMoney total,
            boolean classificationProvisional,
            String delivery,
            @Nullable String failureCode,
            @Nullable String failureDetail,
            @Nullable String operatorDocumentId,
            @Nullable String operatorState,
            @Nullable String operatorStatus,
            @Nullable String stateCheckedAt,
            @Nullable String stateChangedAt,
            boolean live,
            String sendReason,
            String sentBy,
            String createdAt,
            long version,
            @Nullable List<LineView> lines) {

        static EInvoiceView summary(StatementEInvoice row) {
            return of(row, null);
        }

        static EInvoiceView detail(StatementEInvoice row) {
            String currency = row.currency();
            return of(
                    row,
                    row.sentDocument().lines().stream()
                            .map(line -> LineView.of(line, currency))
                            .toList());
        }

        private static EInvoiceView of(StatementEInvoice row, @Nullable List<LineView> lines) {
            String currency = row.currency();
            return new EInvoiceView(
                    row.id(),
                    row.statementId(),
                    row.tenantId(),
                    row.providerType(),
                    row.documentNumber(),
                    row.documentDate().toString(),
                    row.legalEntityId(),
                    row.buyerName(),
                    row.buyerTaxpayerNumber(),
                    row.sellerTaxpayerNumber(),
                    ApiMoney.of(row.netMinor(), currency),
                    ApiMoney.of(row.vatMinor(), currency),
                    ApiMoney.of(row.totalMinor(), currency),
                    row.classificationProvisional(),
                    row.delivery().name(),
                    row.failureCode(),
                    row.failureDetail(),
                    row.operatorDocumentId(),
                    row.operatorState() == null ? null : row.operatorState().name(),
                    row.operatorStatus(),
                    text(row.stateCheckedAt()),
                    text(row.stateChangedAt()),
                    row.live(),
                    row.sendReason(),
                    row.sentBy(),
                    row.createdAt().toString(),
                    row.version(),
                    lines);
        }
    }

    public record LineView(
            int lineNumber,
            String name,
            String classificationCode,
            long quantity,
            ApiMoney unitPrice,
            ApiMoney net,
            int vatRateBp,
            ApiMoney vat,
            ApiMoney gross) {

        static LineView of(EInvoiceDocument.Line line, String currency) {
            return new LineView(
                    line.number(),
                    line.name(),
                    line.classificationCode(),
                    line.quantity(),
                    ApiMoney.of(line.unitPriceMinor(), currency),
                    ApiMoney.of(line.netMinor(), currency),
                    line.vatRateBp(),
                    ApiMoney.of(line.vatMinor(), currency),
                    ApiMoney.of(line.grossMinor(), currency));
        }
    }

    /** The document as it stands after asking, and why nothing could be learned if the operator did not answer. */
    public record RefreshView(
            EInvoiceView einvoice, @Nullable String unavailableCode) {}
}
