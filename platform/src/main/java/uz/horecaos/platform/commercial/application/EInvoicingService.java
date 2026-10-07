package uz.horecaos.platform.commercial.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.commercial.api.EInvoiceDocument;
import uz.horecaos.platform.commercial.api.EInvoiceDocumentReference;
import uz.horecaos.platform.commercial.api.EInvoiceOperatorAccount;
import uz.horecaos.platform.commercial.api.EInvoiceOperatorState;
import uz.horecaos.platform.commercial.api.EInvoiceSendOutcome;
import uz.horecaos.platform.commercial.api.EInvoiceStateOutcome;
import uz.horecaos.platform.commercial.api.EInvoicingOperator;
import uz.horecaos.platform.commercial.domain.EInvoiceDelivery;
import uz.horecaos.platform.commercial.domain.EInvoicingInstallation;
import uz.horecaos.platform.commercial.domain.EInvoicingLineClassification;
import uz.horecaos.platform.commercial.domain.Statement;
import uz.horecaos.platform.commercial.domain.StatementEInvoice;
import uz.horecaos.platform.commercial.domain.StatementLine;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcEInvoiceStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcEInvoiceStore.Environment;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcStatementStore;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.tenancy.api.LegalEntityDirectory;
import uz.horecaos.platform.tenancy.api.LegalParty;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Sending an issued statement to an e-invoicing operator as an electronic invoice
 * (ADR 0096): HorecaOS is the seller, the tenant's legal entity the buyer; Didox and
 * Faktura.uz are adapters behind one port ({@link EInvoicingOperator}).
 *
 * <p><strong>Sending is a deliberate act per statement</strong>, by HorecaOS staff,
 * never automatic at issue (ADR 0096's accepted trade-off). It runs in three steps,
 * and the operator is called in none of the transactions:
 *
 * <ol>
 *   <li>one transaction validates, builds the document from the frozen statement and
 *       the current classification, writes the attempt as {@code PENDING} and audits
 *       the request -- so the attempt is durable before anything can have been sent;
 *   <li>the operator is asked, with no connection checked out (a slow operator must not
 *       hold one of ten pooled connections);
 *   <li>one transaction records what became of it: {@code SUBMITTED} with the operator's
 *       identifier and state, {@code FAILED} with why, or {@code UNCERTAIN}.
 * </ol>
 *
 * <p>An {@code UNCERTAIN} send is resolved by asking, never by sending again: a second
 * call may create a second invoice at the operator, and a database index keeps one live
 * document per statement across both operators.
 *
 * <p>What is recorded is the operator's document identifier and the state it reports.
 * No signature material is ever received or stored: documents go out as drafts and are
 * signed by people inside the operator's own product.
 */
@Service
public class EInvoicingService {

    /** The currency both operators invoice in. A statement in any other is refused with a reason. */
    static final String OPERATOR_CURRENCY = "UZS";

    /** HorecaOS is a Tashkent company; its invoices are dated by that calendar. */
    static final ZoneId INVOICE_ZONE = ZoneId.of("Asia/Tashkent");

    /**
     * How long a send whose answer was lost keeps its statement before "the operator holds
     * nothing under this number" is believed: a document list can lag the create that made it.
     */
    static final Duration LOST_SEND_SETTLES_AFTER = Duration.ofMinutes(5);

    /** Past this, an attempt still {@code PENDING} was interrupted rather than in flight. */
    static final Duration PENDING_IS_INTERRUPTED_AFTER = Duration.ofMinutes(10);

    private static final int MAX_LINE_NAME = 250;

    private static final Pattern TAXPAYER_NUMBER = Pattern.compile("\\d{9}(\\d{5})?");

    private static final List<String> LOCALES = List.of("ru", "uz");

    private final JdbcEInvoiceStore store;
    private final JdbcStatementStore statements;
    private final LegalEntityDirectory legalEntities;
    private final Map<String, EInvoicingOperator> operators;
    private final AuditRecorder audit;
    private final TransactionTemplate unitOfWork;
    private final Clock clock;

    public EInvoicingService(
            JdbcEInvoiceStore store,
            JdbcStatementStore statements,
            LegalEntityDirectory legalEntities,
            List<EInvoicingOperator> operators,
            AuditRecorder audit,
            TransactionTemplate unitOfWork,
            Clock clock) {
        this.store = store;
        this.statements = statements;
        this.legalEntities = legalEntities;
        this.operators = operators.stream()
                .collect(Collectors.toUnmodifiableMap(EInvoicingOperator::providerType, operator -> operator));
        this.audit = audit;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    /** One operator account with the approved endpoint it names and whether an adapter is wired for it. */
    public record InstallationDetail(
            EInvoicingInstallation installation, @Nullable Environment environment, boolean adapterWired) {}

    public List<InstallationDetail> installations() {
        return store.installations().stream()
                .map(installation -> new InstallationDetail(
                        installation,
                        store.environment(installation.environmentCode()).orElse(null),
                        operators.containsKey(installation.providerType())))
                .toList();
    }

    /** One account with the approved endpoint it names, as {@link #installations()} lists it. */
    public InstallationDetail installationDetail(UUID installationId) {
        EInvoicingInstallation installation = requireInstallation(installationId);
        return new InstallationDetail(
                installation,
                store.environment(installation.environmentCode()).orElse(null),
                operators.containsKey(installation.providerType()));
    }

    public List<EInvoicingLineClassification> classifications() {
        return store.classifications();
    }

    public List<StatementEInvoice> forStatement(UUID tenantId, UUID statementId) {
        return store.forStatement(tenantId, statementId);
    }

    public List<StatementEInvoice> forTenant(UUID tenantId) {
        return store.forTenant(tenantId);
    }

    public StatementEInvoice find(UUID tenantId, UUID einvoiceId) {
        return store.find(tenantId, einvoiceId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such e-invoice"));
    }

    // ----------------------------------------------------- installations (staff)

    /**
     * Replaces the editable fields of one operator account: its display name, the
     * reference to the operator login in the secrets manager, and the seller's public
     * identity. The value behind the reference is put there by an operator with
     * {@code bao kv put} (ADR 0028); this stores the reference only.
     *
     * @param secretReference null keeps the reference on file (the screen never learns it, so it
     *                        cannot send it back); blank clears it; anything else replaces it
     * @param config          every seller field to keep; a missing or blank key is cleared
     */
    public EInvoicingInstallation updateInstallation(
            UUID installationId,
            long expectedVersion,
            String displayName,
            @Nullable String secretReference,
            Map<String, String> config,
            ActorRef actor,
            String reason,
            String correlationId) {
        Map<String, String> cleaned = cleanConfig(config);
        boolean keepReference = secretReference == null;
        String requestedReference = keepReference ? null : cleanReference(secretReference);
        if (displayName.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An account has a name");
        }
        return unitOfWork.execute(status -> {
            EInvoicingInstallation current = requireInstallation(installationId);
            checkVersion(current.version(), expectedVersion);
            String reference = keepReference ? current.secretReference() : requestedReference;

            Map<String, Object> before = new LinkedHashMap<>();
            Map<String, Object> after = new LinkedHashMap<>();
            if (!current.displayName().equals(displayName.strip())) {
                before.put("displayName", current.displayName());
                after.put("displayName", displayName.strip());
            }
            if (!Objects.equals(current.secretReference(), reference)) {
                before.put("secretReference", current.secretReference());
                after.put("secretReference", reference);
            }
            for (String key : EInvoicingInstallation.CONFIG_KEYS) {
                String was = current.config().get(key);
                String is = cleaned.get(key);
                if (!Objects.equals(was, is)) {
                    before.put(key, was);
                    after.put(key, is);
                }
            }
            if (after.isEmpty()) {
                return current;
            }
            EInvoicingInstallation proposed = new EInvoicingInstallation(
                    current.id(),
                    current.providerType(),
                    current.environmentCode(),
                    displayName.strip(),
                    current.status(),
                    reference,
                    cleaned,
                    current.adapterVersion(),
                    current.version(),
                    current.updatedBy(),
                    current.updatedAt());
            if (EInvoicingInstallation.ACTIVE.equals(current.status())
                    && !proposed.missing().isEmpty()) {
                throw new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "An active account keeps its secret reference and the seller's identity; suspend it first",
                        Map.of("reason", "ACTIVE_NEEDS_CONFIGURATION", "missing", proposed.missing()));
            }
            Instant now = clock.instant();
            if (!store.updateInstallation(
                    installationId, expectedVersion, displayName.strip(), reference, cleaned, subject(actor), now)) {
                throw staleInstallation(installationId, expectedVersion);
            }
            recordInstallationChange(
                    "commercial.einvoicing.installation.updated",
                    actor,
                    installationId,
                    reason,
                    before,
                    after,
                    correlationId,
                    now);
            return requireInstallation(installationId);
        });
    }

    /**
     * Makes an account live. Refused, saying what is missing, while it is unbound -- the
     * secret reference or the seller's identity -- and while no adapter is wired for the operator.
     */
    public EInvoicingInstallation activateInstallation(
            UUID installationId, long expectedVersion, ActorRef actor, String reason, String correlationId) {
        return unitOfWork.execute(status -> {
            EInvoicingInstallation current = requireInstallation(installationId);
            checkVersion(current.version(), expectedVersion);
            if (EInvoicingInstallation.ACTIVE.equals(current.status())) {
                throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This account is already active");
            }
            if (!current.missing().isEmpty()) {
                throw new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "The account is not bound yet: it still needs " + String.join(", ", current.missing()),
                        Map.of("reason", "OPERATOR_NOT_CONNECTED", "missing", current.missing()));
            }
            if (!operators.containsKey(current.providerType())) {
                throw new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "No adapter for %s is wired in this build".formatted(current.providerType()),
                        Map.of("reason", "NO_ADAPTER", "operator", current.providerType()));
            }
            return moveInstallation(
                    current, EInvoicingInstallation.ACTIVE, expectedVersion, actor, reason, correlationId);
        });
    }

    /** Stops sending through an account: the one switch ADR 0096's rollout names. */
    public EInvoicingInstallation suspendInstallation(
            UUID installationId, long expectedVersion, ActorRef actor, String reason, String correlationId) {
        return unitOfWork.execute(status -> {
            EInvoicingInstallation current = requireInstallation(installationId);
            checkVersion(current.version(), expectedVersion);
            if (!EInvoicingInstallation.ACTIVE.equals(current.status())) {
                throw new ApiException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "Only an active account is suspended; this one is " + current.status());
            }
            return moveInstallation(
                    current, EInvoicingInstallation.SUSPENDED, expectedVersion, actor, reason, correlationId);
        });
    }

    private EInvoicingInstallation moveInstallation(
            EInvoicingInstallation current,
            String target,
            long expectedVersion,
            ActorRef actor,
            String reason,
            String correlationId) {
        Instant now = clock.instant();
        boolean moved;
        try {
            moved = store.setInstallationStatus(current.id(), expectedVersion, target, subject(actor), now);
        } catch (DuplicateKeyException another) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Another %s account is already active; suspend it first".formatted(current.providerType()),
                    Map.of("operator", current.providerType()));
        }
        if (!moved) {
            throw staleInstallation(current.id(), expectedVersion);
        }
        recordInstallationChange(
                "commercial.einvoicing.installation." + target.toLowerCase(java.util.Locale.ROOT),
                actor,
                current.id(),
                reason,
                Map.of("status", current.status()),
                Map.of("status", target),
                correlationId,
                now);
        return requireInstallation(current.id());
    }

    private void recordInstallationChange(
            String action,
            ActorRef actor,
            UUID installationId,
            String reason,
            Map<String, Object> before,
            Map<String, Object> after,
            String correlationId,
            Instant now) {
        audit.record(AuditFact.of(action, AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.platform())
                .target("commercial.einvoicing_installation", installationId)
                .because(reason)
                .changed(ChangeDocuments.diff(before, after))
                .usingCapability(Capability.COMMERCIAL_EINVOICING_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
    }

    // ----------------------------------------------------- classification (staff)

    /**
     * Replaces what one kind of statement line is invoiced as. {@code confirmed} says finance
     * has stated this treatment: it clears the provisional flag and records who and when.
     */
    public EInvoicingLineClassification updateClassification(
            String lineKind,
            long expectedVersion,
            String itemLabel,
            String catalogCode,
            String catalogName,
            String packageCode,
            String packageName,
            int vatRateBp,
            boolean confirmed,
            ActorRef actor,
            String reason,
            String correlationId) {
        if (!EInvoicingLineClassification.KINDS.contains(lineKind)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Unknown line kind " + lineKind, Map.of("lineKind", lineKind));
        }
        if (vatRateBp < 0 || vatRateBp > 10_000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A VAT rate is between 0 and 100 percent");
        }
        for (String text : List.of(itemLabel, catalogCode, catalogName, packageCode, packageName)) {
            if (text.isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Every classification field is required");
            }
        }
        return unitOfWork.execute(status -> {
            EInvoicingLineClassification current = store.classification(lineKind)
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such line kind"));
            checkVersion(current.version(), expectedVersion);
            Instant now = clock.instant();
            if (!store.updateClassification(
                    lineKind,
                    expectedVersion,
                    itemLabel.strip(),
                    catalogCode.strip(),
                    catalogName.strip(),
                    packageCode.strip(),
                    packageName.strip(),
                    vatRateBp,
                    confirmed,
                    subject(actor),
                    now)) {
                throw ApiException.staleVersion(expectedVersion, expectedVersion + 1);
            }
            Map<String, Object> before = new LinkedHashMap<>();
            Map<String, Object> after = new LinkedHashMap<>();
            diff(before, after, "itemLabel", current.itemLabel(), itemLabel.strip());
            diff(before, after, "catalogCode", current.catalogCode(), catalogCode.strip());
            diff(before, after, "catalogName", current.catalogName(), catalogName.strip());
            diff(before, after, "packageCode", current.packageCode(), packageCode.strip());
            diff(before, after, "packageName", current.packageName(), packageName.strip());
            diff(before, after, "vatRateBp", current.vatRateBp(), vatRateBp);
            diff(before, after, "provisional", current.provisional(), !confirmed);
            if (before.isEmpty() && after.isEmpty()) {
                // Confirming what is already there still leaves a fact: who confirmed it.
                before.put("provisional", current.provisional());
                after.put("provisional", !confirmed);
            }
            audit.record(AuditFact.of("commercial.einvoicing.classification.updated", AuditClass.BUSINESS)
                    .by(actor)
                    .at(ResourceScope.platform())
                    .target("commercial.einvoicing_line_classification", classificationId(lineKind))
                    .because(reason)
                    .changed(ChangeDocuments.diff(before, after))
                    .usingCapability(Capability.COMMERCIAL_EINVOICING_MANAGE.code())
                    .correlatedBy(correlationId)
                    .occurredAt(now)
                    .build());
            return store.classification(lineKind).orElseThrow();
        });
    }

    /** The audit target needs a UUID and a line kind has none: a stable name-based one. */
    static UUID classificationId(String lineKind) {
        return UUID.nameUUIDFromBytes(
                ("einvoicing-line-classification:" + lineKind).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void diff(
            Map<String, Object> before, Map<String, Object> after, String field, Object was, Object is) {
        if (!Objects.equals(was, is)) {
            before.put(field, was);
            after.put(field, is);
        }
    }

    // -------------------------------------------------------------- sending

    /** What staff ask for when they send a statement. */
    public record SendRequest(
            UUID tenantId,
            UUID statementId,
            String providerType,
            @Nullable UUID legalEntityId,
            String reason) {}

    /** What step one decided, carried to the call and to the conclusion. */
    private record Prepared(
            StatementEInvoice row,
            EInvoicingOperator operator,
            EInvoiceOperatorAccount account,
            EInvoiceDocument document) {}

    /**
     * Sends an issued statement to an operator and records what became of it.
     *
     * @return the attempt as it stands once the operator has answered (or has not): {@code
     *         SUBMITTED}, {@code FAILED} or {@code UNCERTAIN}
     */
    public StatementEInvoice send(SendRequest request, ActorRef actor, String correlationId) {
        Prepared prepared = Objects.requireNonNull(
                unitOfWork.execute(status -> prepare(request, actor, correlationId)), "prepare answered nothing");

        EInvoiceSendOutcome outcome = ask(prepared);

        return Objects.requireNonNull(
                unitOfWork.execute(status -> conclude(prepared, outcome, actor, correlationId)),
                "conclude answered nothing");
    }

    private Prepared prepare(SendRequest request, ActorRef actor, String correlationId) {
        Statement statement = statements
                .find(request.tenantId(), request.statementId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such statement"));
        if (!Statement.ISSUED.equals(statement.status())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Only an issued statement is sent to an operator; %s is %s"
                            .formatted(statement.number(), statement.status()),
                    Map.of("reason", "STATEMENT_NOT_ISSUED"));
        }
        if (!OPERATOR_CURRENCY.equals(statement.currency())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "E-invoicing operators invoice in %s; this statement is in %s"
                            .formatted(OPERATOR_CURRENCY, statement.currency()),
                    Map.of("reason", "CURRENCY_NOT_INVOICEABLE", "currency", String.valueOf(statement.currency())));
        }
        EInvoicingOperator operator = operators.get(request.providerType());
        if (operator == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Unknown e-invoicing operator " + request.providerType(),
                    Map.of("operator", request.providerType()));
        }
        EInvoicingInstallation installation = store.activeInstallation(request.providerType())
                .filter(EInvoicingInstallation::connected)
                .orElseThrow(() -> notConnected(request.providerType()));
        Environment environment = store.environment(installation.environmentCode())
                .orElseThrow(() -> notConnected(request.providerType()));

        LegalParty buyer = chooseBuyer(request);

        Map<String, EInvoicingLineClassification> byKind = new LinkedHashMap<>();
        store.classifications().forEach(classification -> byKind.put(classification.lineKind(), classification));

        UUID id = Ids.newId();
        Instant now = clock.instant();
        EInvoiceDocument document =
                documentOf(statement, installation, buyer, byKind, StatementEInvoice.clientReferenceOf(id), now);
        boolean provisional = statement.lines().stream()
                .filter(line -> line.amountMinor() != 0)
                .map(line -> byKind.get(line.kind()))
                .anyMatch(EInvoicingLineClassification::provisional);

        StatementEInvoice row = new StatementEInvoice(
                id,
                request.tenantId(),
                request.statementId(),
                installation.id(),
                request.providerType(),
                buyer.id(),
                buyer.taxpayerNumber(),
                buyer.legalName(),
                document.seller().taxpayerNumber(),
                document.documentNumber(),
                document.documentDate(),
                document.currency(),
                document.netMinor(),
                document.vatMinor(),
                document.grossMinor(),
                provisional,
                document,
                EInvoiceDelivery.PENDING,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                request.reason(),
                subject(actor),
                now,
                now,
                0);
        try {
            store.insertPending(row);
        } catch (DuplicateKeyException live) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "%s already has an invoice at an operator; wait for its answer or cancel it there first"
                            .formatted(statement.number()),
                    Map.of("reason", "EINVOICE_LIVE", "statementNumber", String.valueOf(statement.number())));
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("operator", request.providerType());
        after.put("statementId", request.statementId().toString());
        after.put("documentNumber", document.documentNumber());
        after.put("buyerLegalEntityId", buyer.id().toString());
        after.put("currency", document.currency());
        after.put("netMinor", document.netMinor());
        after.put("vatMinor", document.vatMinor());
        after.put("totalMinor", document.grossMinor());
        after.put("lineCount", document.lines().size());
        after.put("classificationProvisional", provisional);
        after.put("delivery", EInvoiceDelivery.PENDING.name());
        recordSend(
                "commercial.einvoice.send_requested",
                actor,
                row,
                request.reason(),
                Map.of(),
                after,
                correlationId,
                now);

        EInvoiceOperatorAccount account = accountOf(installation, environment);
        return new Prepared(row, operator, account, document);
    }

    private EInvoiceSendOutcome ask(Prepared prepared) {
        try {
            return prepared.operator().send(prepared.account(), prepared.document());
        } catch (RuntimeException failure) {
            // An adapter that throws has not said whether the operator acted, which is
            // uncertain and never "not sent": the message is dropped on purpose (it may
            // carry what was being sent), and the class name is the whole diagnostic.
            return new EInvoiceSendOutcome.Uncertain(
                    "ADAPTER_FAILURE", failure.getClass().getSimpleName());
        }
    }

    private StatementEInvoice conclude(
            Prepared prepared, EInvoiceSendOutcome outcome, ActorRef actor, String correlationId) {
        StatementEInvoice row = find(prepared.row().tenantId(), prepared.row().id());
        Instant now = clock.instant();
        boolean written;
        String action;
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        before.put("delivery", row.delivery().name());

        switch (outcome) {
            case EInvoiceSendOutcome.Accepted accepted -> {
                written = store.markSubmitted(
                        row.id(),
                        row.version(),
                        accepted.operatorDocumentId(),
                        accepted.state(),
                        accepted.rawStatus(),
                        now);
                action = "commercial.einvoice.sent";
                after.put("delivery", EInvoiceDelivery.SUBMITTED.name());
                after.put("operatorDocumentId", accepted.operatorDocumentId());
                after.put("operatorState", accepted.state().name());
            }
            case EInvoiceSendOutcome.Refused refused -> {
                written = store.markFailed(row.id(), row.version(), refused.code(), refused.detail(), now);
                action = "commercial.einvoice.send_failed";
                after.put("delivery", EInvoiceDelivery.FAILED.name());
                after.put("failureCode", refused.code());
            }
            case EInvoiceSendOutcome.NotSent notSent -> {
                written = store.markFailed(row.id(), row.version(), notSent.code(), notSent.detail(), now);
                action = "commercial.einvoice.send_failed";
                after.put("delivery", EInvoiceDelivery.FAILED.name());
                after.put("failureCode", notSent.code());
            }
            case EInvoiceSendOutcome.Uncertain uncertain -> {
                written = store.markUncertain(row.id(), row.version(), uncertain.code(), uncertain.detail(), now);
                action = "commercial.einvoice.send_uncertain";
                after.put("delivery", EInvoiceDelivery.UNCERTAIN.name());
                after.put("failureCode", uncertain.code());
            }
        }
        if (!written) {
            // Nothing else moves a PENDING row, so this is a defect and not a race; the
            // attempt is on record as PENDING and the interrupted-send sweep will mark it.
            throw new IllegalStateException("The send of " + row.id() + " could not be recorded");
        }
        recordSend(action, actor, row, "Operator answered the send", before, after, correlationId, now);
        return find(row.tenantId(), row.id());
    }

    private void recordSend(
            String action,
            ActorRef actor,
            StatementEInvoice row,
            String reason,
            Map<String, Object> before,
            Map<String, Object> after,
            String correlationId,
            Instant now) {
        audit.record(AuditFact.of(action, AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(row.tenantId()))
                .target("commercial.einvoice", row.id())
                .because(reason)
                .changed(ChangeDocuments.diff(before, after))
                .usingCapability(Capability.COMMERCIAL_EINVOICE_SEND.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
    }

    /**
     * The invoice for a statement in HorecaOS's own terms: each statement line becomes an
     * invoice line under its kind's classification, with VAT added on top of the statement's
     * before-tax amount at that kind's rate -- and no VAT at all when the seller is not
     * registered for it. Lines that bill nothing (a trial month's plan at zero) are not
     * invoiced: an operator refuses a zero line.
     */
    EInvoiceDocument documentOf(
            Statement statement,
            EInvoicingInstallation installation,
            LegalParty buyer,
            Map<String, EInvoicingLineClassification> byKind,
            String clientReference,
            Instant now) {
        boolean vatApplies = installation.sellerChargesVat();
        List<EInvoiceDocument.Line> lines = new ArrayList<>();
        for (StatementLine line : statement.lines()) {
            if (line.amountMinor() == 0) {
                continue;
            }
            EInvoicingLineClassification classification = byKind.get(line.kind());
            if (classification == null) {
                throw new IllegalStateException("No classification is configured for line kind " + line.kind());
            }
            int rate = vatApplies ? classification.vatRateBp() : 0;
            long vat = vatOn(line.amountMinor(), rate);
            String name = classification.itemLabel() + ": " + line.description();
            lines.add(new EInvoiceDocument.Line(
                    lines.size() + 1,
                    name.length() <= MAX_LINE_NAME ? name : name.substring(0, MAX_LINE_NAME),
                    classification.catalogCode(),
                    classification.catalogName(),
                    classification.packageCode(),
                    classification.packageName(),
                    line.quantity(),
                    line.unitPriceMinor(),
                    line.amountMinor(),
                    rate,
                    vat,
                    Math.addExact(line.amountMinor(), vat)));
        }
        if (lines.isEmpty()) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Nothing to invoice: every line of %s is zero".formatted(statement.number()),
                    Map.of("reason", "NOTHING_TO_INVOICE"));
        }
        Map<String, String> seller = installation.config();
        LocalDate issued = LocalDate.ofInstant(Objects.requireNonNull(statement.issuedAt()), INVOICE_ZONE);
        EInvoiceDocument document = new EInvoiceDocument(
                clientReference,
                Objects.requireNonNull(statement.number()),
                LocalDate.ofInstant(now, INVOICE_ZONE),
                Objects.requireNonNull(statement.currency()),
                new EInvoiceDocument.Party(
                        Objects.requireNonNull(seller.get(EInvoicingInstallation.SELLER_TAXPAYER_NUMBER)),
                        Objects.requireNonNull(seller.get(EInvoicingInstallation.SELLER_NAME)),
                        seller.get(EInvoicingInstallation.SELLER_ADDRESS),
                        seller.get(EInvoicingInstallation.SELLER_VAT_REGISTRATION_CODE),
                        seller.get(EInvoicingInstallation.SELLER_BANK_ACCOUNT),
                        seller.get(EInvoicingInstallation.SELLER_BANK_CODE),
                        seller.get(EInvoicingInstallation.SELLER_BANK_NAME)),
                new EInvoiceDocument.Party(
                        buyer.taxpayerNumber(), buyer.legalName(), buyer.registeredAddress(), null, null, null, null),
                // Provisional until finance names the agreement an invoice is made under
                // (ADR 0096's open input): the statement is the one document both sides can point at.
                statement.number(),
                issued,
                vatApplies,
                lines);
        if (document.netMinor() != statement.totalMinor()) {
            throw new IllegalStateException(
                    "The invoice for " + statement.number() + " does not add up to the statement it is made from");
        }
        return document;
    }

    /** VAT on a before-tax amount at a rate in basis points, rounded half up to a whole minor unit. */
    static long vatOn(long netMinor, int rateBp) {
        return BigDecimal.valueOf(netMinor)
                .multiply(BigDecimal.valueOf(rateBp))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private LegalParty chooseBuyer(SendRequest request) {
        List<LegalParty> parties = legalEntities.activeParties(request.tenantId());
        if (parties.isEmpty()) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "The tenant has no active legal entity to invoice; register one first",
                    Map.of("reason", "TENANT_HAS_NO_LEGAL_ENTITY"));
        }
        UUID chosen = request.legalEntityId();
        if (chosen != null) {
            return parties.stream()
                    .filter(party -> party.id().equals(chosen))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.VALIDATION_FAILED,
                            "That company is not an active legal entity of this tenant",
                            Map.of("legalEntityId", chosen.toString())));
        }
        if (parties.size() == 1) {
            return parties.getFirst();
        }
        throw new ApiException(
                ErrorCode.VALIDATION_FAILED,
                "The tenant has several companies; choose which one is invoiced",
                Map.of(
                        "reason",
                        "BUYER_CHOICE_REQUIRED",
                        "candidates",
                        parties.stream().map(LegalParty::code).toList()));
    }

    // ------------------------------------------------------------ the state

    /** What asking the operator did: the document as it stands, and why nothing could be learned if so. */
    public record Refreshed(
            StatementEInvoice einvoice, @Nullable String unavailableCode) {}

    /**
     * Asks the operator what became of one document and records the answer. A failed or
     * pending attempt has nothing at an operator to ask about.
     */
    public Refreshed refresh(UUID tenantId, UUID einvoiceId, ActorRef actor, String correlationId) {
        StatementEInvoice row = find(tenantId, einvoiceId);
        if (row.delivery() != EInvoiceDelivery.SUBMITTED && row.delivery() != EInvoiceDelivery.UNCERTAIN) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Nothing stands at an operator for an attempt that is " + row.delivery(),
                    Map.of("reason", "NOTHING_AT_OPERATOR"));
        }
        EInvoicingOperator operator = operators.get(row.providerType());
        EInvoicingInstallation installation = store.installation(row.installationId())
                .filter(EInvoicingInstallation::connected)
                .orElseThrow(() -> notConnected(row.providerType()));
        if (operator == null) {
            throw notConnected(row.providerType());
        }
        Environment environment =
                store.environment(installation.environmentCode()).orElseThrow(() -> notConnected(row.providerType()));

        EInvoiceStateOutcome outcome;
        try {
            outcome = operator.state(
                    accountOf(installation, environment),
                    new EInvoiceDocumentReference(
                            row.operatorDocumentId(),
                            StatementEInvoice.clientReferenceOf(row.id()),
                            row.documentNumber(),
                            row.documentDate()));
        } catch (RuntimeException failure) {
            outcome = new EInvoiceStateOutcome.Unavailable(
                    "ADAPTER_FAILURE", failure.getClass().getSimpleName());
        }
        EInvoiceStateOutcome answer = outcome;
        return Objects.requireNonNull(
                unitOfWork.execute(status -> applyState(row.tenantId(), row.id(), answer, actor, correlationId)));
    }

    /**
     * Asks about every document an operator may still tell us something new about, and marks
     * attempts that were interrupted before they heard back. For the scheduled sweep.
     *
     * @return how many documents' state moved
     */
    public int refreshOpen(int batchSize) {
        Instant now = clock.instant();
        ActorRef sweep = ActorRef.systemJob("einvoice-state-sweep");
        String correlationId = UUID.randomUUID().toString();
        int moved = 0;
        for (StatementEInvoice stale : store.stalePending(now.minus(PENDING_IS_INTERRUPTED_AFTER), batchSize)) {
            unitOfWork.executeWithoutResult(status -> interrupt(stale, sweep, correlationId));
            moved++;
        }
        for (StatementEInvoice open : store.openDocuments(now.minus(Duration.ofMinutes(10)), batchSize)) {
            try {
                Refreshed refreshed = refresh(open.tenantId(), open.id(), sweep, correlationId);
                if (refreshed.einvoice().version() != open.version() && changed(open, refreshed.einvoice())) {
                    moved++;
                }
            } catch (ApiException notAskable) {
                // An account suspended since: the document keeps its last known state.
            }
        }
        return moved;
    }

    private static boolean changed(StatementEInvoice before, StatementEInvoice after) {
        return before.operatorState() != after.operatorState() || before.delivery() != after.delivery();
    }

    private void interrupt(StatementEInvoice stale, ActorRef actor, String correlationId) {
        Instant now = clock.instant();
        if (store.markUncertain(
                stale.id(),
                stale.version(),
                "SEND_INTERRUPTED",
                "The send was recorded and the answer never was; ask the operator for its state",
                now)) {
            recordSend(
                    "commercial.einvoice.send_uncertain",
                    actor,
                    stale,
                    "The send was interrupted before the operator's answer was recorded",
                    Map.of("delivery", EInvoiceDelivery.PENDING.name()),
                    Map.of("delivery", EInvoiceDelivery.UNCERTAIN.name(), "failureCode", "SEND_INTERRUPTED"),
                    correlationId,
                    now);
        }
    }

    private Refreshed applyState(
            UUID tenantId, UUID einvoiceId, EInvoiceStateOutcome outcome, ActorRef actor, String correlationId) {
        StatementEInvoice row = find(tenantId, einvoiceId);
        Instant now = clock.instant();
        switch (outcome) {
            case EInvoiceStateOutcome.Known known -> {
                boolean differs = row.delivery() != EInvoiceDelivery.SUBMITTED
                        || row.operatorState() != known.state()
                        || !Objects.equals(row.operatorStatus(), known.rawStatus())
                        || !Objects.equals(row.operatorDocumentId(), known.operatorDocumentId());
                if (!store.recordState(
                        row.id(), row.version(), known.operatorDocumentId(), known.state(), known.rawStatus(), now)) {
                    throw ApiException.staleVersion(row.version(), row.version() + 1);
                }
                if (differs) {
                    Map<String, Object> before = new LinkedHashMap<>();
                    Map<String, Object> after = new LinkedHashMap<>();
                    diff(before, after, "delivery", row.delivery().name(), EInvoiceDelivery.SUBMITTED.name());
                    diff(
                            before,
                            after,
                            "operatorState",
                            row.operatorState() == null
                                    ? "none"
                                    : row.operatorState().name(),
                            known.state().name());
                    diff(
                            before,
                            after,
                            "operatorStatus",
                            Objects.toString(row.operatorStatus(), "none"),
                            known.rawStatus());
                    diff(
                            before,
                            after,
                            "operatorDocumentId",
                            Objects.toString(row.operatorDocumentId(), "none"),
                            known.operatorDocumentId());
                    recordSend(
                            "commercial.einvoice.state_changed",
                            actor,
                            row,
                            "Operator reported its state",
                            before,
                            after,
                            correlationId,
                            now);
                }
                return new Refreshed(find(tenantId, einvoiceId), null);
            }
            case EInvoiceStateOutcome.NotFound ignored -> {
                if (row.delivery() == EInvoiceDelivery.UNCERTAIN
                        && row.createdAt().plus(LOST_SEND_SETTLES_AFTER).isBefore(now)) {
                    // The send's answer was lost and the operator holds nothing under our number
                    // after a settling time: it never arrived. The statement is free to be sent again.
                    if (!store.markFailed(
                            row.id(),
                            row.version(),
                            "NOT_FOUND_AT_OPERATOR",
                            "The operator holds no document under this number; the statement can be sent again",
                            now)) {
                        throw ApiException.staleVersion(row.version(), row.version() + 1);
                    }
                    recordSend(
                            "commercial.einvoice.send_failed",
                            actor,
                            row,
                            "The operator holds nothing for the send whose answer was lost",
                            Map.of("delivery", row.delivery().name()),
                            Map.of("delivery", EInvoiceDelivery.FAILED.name(), "failureCode", "NOT_FOUND_AT_OPERATOR"),
                            correlationId,
                            now);
                } else if (row.delivery() == EInvoiceDelivery.SUBMITTED) {
                    // We hold the operator's own identifier and it now says it knows nothing of it.
                    if (!store.recordState(
                            row.id(),
                            row.version(),
                            Objects.requireNonNull(row.operatorDocumentId()),
                            EInvoiceOperatorState.UNKNOWN,
                            "NOT_FOUND_AT_OPERATOR",
                            now)) {
                        throw ApiException.staleVersion(row.version(), row.version() + 1);
                    }
                    recordSend(
                            "commercial.einvoice.state_changed",
                            actor,
                            row,
                            "The operator no longer finds the document",
                            Map.of("operatorState", Objects.toString(row.operatorState(), "none")),
                            Map.of("operatorState", EInvoiceOperatorState.UNKNOWN.name()),
                            correlationId,
                            now);
                } else if (!store.touchChecked(row.id(), row.version(), now)) {
                    throw ApiException.staleVersion(row.version(), row.version() + 1);
                }
                return new Refreshed(find(tenantId, einvoiceId), null);
            }
            case EInvoiceStateOutcome.Unavailable unavailable -> {
                return new Refreshed(row, unavailable.code());
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private EInvoiceOperatorAccount accountOf(EInvoicingInstallation installation, Environment environment) {
        return new EInvoiceOperatorAccount(
                installation.id(),
                installation.providerType(),
                environment.code(),
                environment.baseUrl(),
                Objects.requireNonNull(installation.secretReference()),
                installation.config());
    }

    private EInvoicingInstallation requireInstallation(UUID id) {
        return store.installation(id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such operator account"));
    }

    private static ApiException notConnected(String providerType) {
        return new ApiException(
                ErrorCode.UNPROCESSABLE_STATE,
                "HorecaOS has no connected %s account yet: the account is not bound (a secret reference and the seller's identity) and active"
                        .formatted(providerType),
                Map.of("reason", "OPERATOR_NOT_CONNECTED", "operator", providerType));
    }

    private static ApiException staleInstallation(UUID id, long expectedVersion) {
        return ApiException.staleVersion(expectedVersion, expectedVersion + 1);
    }

    private static void checkVersion(long actual, long expected) {
        if (actual != expected) {
            throw ApiException.staleVersion(expected, actual);
        }
    }

    private static Map<String, String> cleanConfig(Map<String, String> config) {
        Map<String, String> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : config.entrySet()) {
            if (!EInvoicingInstallation.CONFIG_KEYS.contains(entry.getKey())) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Unknown account setting " + entry.getKey(),
                        Map.of("setting", entry.getKey()));
            }
            String value = entry.getValue() == null ? "" : entry.getValue().strip();
            if (value.isEmpty()) {
                continue;
            }
            cleaned.put(entry.getKey(), value);
        }
        String tin = cleaned.get(EInvoicingInstallation.SELLER_TAXPAYER_NUMBER);
        if (tin != null && !TAXPAYER_NUMBER.matcher(tin).matches()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A taxpayer number is 9 digits (14 for a person)",
                    Map.of("setting", EInvoicingInstallation.SELLER_TAXPAYER_NUMBER));
        }
        String locale = cleaned.get(EInvoicingInstallation.LOCALE);
        if (locale != null && !LOCALES.contains(locale)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The login language is ru or uz",
                    Map.of("setting", EInvoicingInstallation.LOCALE));
        }
        return cleaned;
    }

    /** A reference is parsed, not trusted: it must be one of ours, in the platform-owned category. */
    private static @Nullable String cleanReference(@Nullable String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        SecretReference parsed;
        try {
            parsed = SecretReference.parse(reference.strip());
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Not a secret reference: expected horecaos:{environment}:provider_einvoicing:{owner}:{id}",
                    Map.of("setting", "secretReference"));
        }
        if (parsed.category() != SecretCategory.PROVIDER_EINVOICING) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The reference must be in the provider_einvoicing category",
                    Map.of("setting", "secretReference"));
        }
        return parsed.toString();
    }

    private static String subject(ActorRef actor) {
        return actor.subject() == null ? "" : actor.subject();
    }

    /** Whether an operator adapter is wired, for the screen to say so beside the account. */
    public boolean adapterWired(String providerType) {
        return operators.containsKey(providerType);
    }

    /** The adapter versions in this build, so a connected account records what it speaks. */
    public Optional<String> adapterVersion(String providerType) {
        return Optional.ofNullable(operators.get(providerType)).map(EInvoicingOperator::adapterVersion);
    }
}
