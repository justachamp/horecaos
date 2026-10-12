package uz.horecaos.platform.fiscal.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.fiscal.api.FiscalEvidencePolicies;
import uz.horecaos.platform.fiscal.api.SentReceiptEvidence;
import uz.horecaos.platform.fiscal.domain.FiscalReasonCode;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalEvidenceStore;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalEvidenceStore.CorrectionRow;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalEvidenceStore.EntityRow;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalEvidenceStore.EvidenceRow;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalEvidenceStore.MethodRow;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalEvidenceStore.PartnerPathRow;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalEvidenceStore.SentRequestRow;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalEvidenceStore.TerminalPathRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * What an operator is shown about the receipts filed for an order, and what they can be shown
 * with a reason (ADR 0166).
 *
 * <p>A read model over the rows that already exist, never a second copy and never a receipt
 * HorecaOS composes. The console draws identifiers, a link to the OFD and the lines as filed; it
 * does not draw a receipt body, because a HorecaOS rendering is a second document that can disagree
 * with the tax authority's, and a partial one is worse than none.
 *
 * <h2>Three rules the shape enforces</h2>
 *
 * <ul>
 *   <li><strong>The INN is the one on the document, not the one on the entity today.</strong>
 *       The seller is read from the snapshot taken when the document was opened; a row with no
 *       snapshot says {@code UNRESOLVED} rather than joining the entity and answering for a
 *       taxpayer who may not have issued it, and a row backfilled from the entity says {@code
 *       BACKFILL}, which is a weaker claim and is stated.
 *   <li><strong>An identifier needs a purpose.</strong> The fiscal sign, the receipt number, the
 *       receipt link and the terminal id are returned masked to their last four characters unless
 *       the caller holds {@code fiscal.evidence.reveal} and declares one of a closed set of
 *       purposes, and then every document revealed writes a {@code SECURITY} audit fact naming the
 *       document, the fields and the purpose. The identifiers appear in no log, metric, event or
 *       error message.
 *   <li><strong>A marking code is not a field this class can return.</strong> The sent lines carry
 *       a <em>count</em> of codes transmitted; the mark store is ADR 0161's and is read through
 *       {@link OrderMarkCounts}, counts only. No purpose, and no capability, changes that.
 * </ul>
 *
 * <p>The lines come from the protected sent request, decrypted per read, so a read costs a
 * decrypt per document. A request that cannot be read (a key that is gone, a shape this version
 * does not know) answers {@code NOT_READABLE} for that document; it never fails the whole read,
 * and it never shows a reconstruction in its place.
 */
@Service
public class FiscalEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(FiscalEvidenceService.class);

    /** The purpose recorded when the platform itself opens the sent request to draw its lines. */
    private static final String LINES_READ_PURPOSE = "fiscal-evidence-read";

    private final JdbcFiscalEvidenceStore store;
    private final FieldProtection protection;
    private final ObjectMapper json;
    private final AuditRecorder audit;
    private final PolicyResolver policies;
    private final OrderMarkCounts marks;
    private final Clock clock;
    private final FiscalReceiptLinks receiptLinks;

    public FiscalEvidenceService(
            JdbcFiscalEvidenceStore store,
            FieldProtection protection,
            ObjectMapper json,
            AuditRecorder audit,
            PolicyResolver policies,
            OrderMarkCounts marks,
            Clock clock,
            FiscalReceiptLinks receiptLinks) {
        this.store = store;
        this.protection = protection;
        this.json = json;
        this.audit = audit;
        this.policies = policies;
        this.marks = marks;
        this.clock = clock;
        this.receiptLinks = receiptLinks;
    }

    /** Why an identifier is being looked at in full. A closed set, never free text. */
    public enum Purpose {
        TAX_INQUIRY,
        CUSTOMER_COMPLAINT,
        AUDIT,
        CORRECTION;

        public static Optional<Purpose> parse(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(valueOf(value.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                return Optional.empty();
            }
        }
    }

    /** Whole som in the platform's minor unit, with its currency. */
    public record Money(long amountMinor, String currency) {}

    public record Retention(int years, boolean provisional) {}

    /**
     * Which INN issued the receipt.
     *
     * @param source {@code OPENING} (taken when the document was opened), {@code BACKFILL} (resolved
     *     from the entity as it stood when the snapshot columns were added, not from the filing) or
     *     {@code UNRESOLVED} (the document names no entity, so nobody can be said to have issued it)
     */
    public record Seller(
            @Nullable UUID legalEntityId,
            @Nullable String tin,
            @Nullable String name,
            String source) {}

    /**
     * The identifiers of a filed receipt. Masked unless {@code revealed}.
     *
     * @param receiptNumber the number a customer and an inspector both quote
     * @param terminalId    the fiscal terminal or virtual module that registered the receipt
     */
    public record Identifiers(
            boolean revealed,
            @Nullable String fiscalSign,
            @Nullable String receiptNumber,
            @Nullable String receiptUrl,
            @Nullable String terminalId) {}

    /** The merchant account the submission went through. The reference names it; it does not authenticate. */
    public record MerchantAccount(UUID bindingId, @Nullable String accountReferenceMasked) {}

    /**
     * One line as filed. {@code markingCodeCount} is a number of codes, never a code.
     *
     * @param commissionTin the seller's TIN the line was sent with (Click's {@code CommissionInfo}), as
     *     sent: a business identifier. Shown beside {@link Seller#tin()} so the INN a document was opened
     *     under and the INN its receipt was filed under can be read side by side, and a difference between
     *     the two is visible and not silently resolved in favour of the snapshot. Null on a provider that
     *     has no such field, and on a request that predates it
     */
    public record Line(
            String kind,
            String name,
            String ikpu,
            String packageCode,
            @Nullable Long unitCode,
            int quantity,
            Money unitPrice,
            Money tax,
            int vatPercent,
            @Nullable Money discount,
            @Nullable String barcode,
            int markingCodeCount,
            @Nullable String commissionTin) {}

    /**
     * The lines of a filed receipt.
     *
     * @param state {@code AVAILABLE}; {@code NOT_SENT} (no request was recorded: nothing has been
     *     sent, or what was sent was not kept); {@code NOT_READABLE} (a request is recorded and
     *     cannot be opened or understood)
     */
    public record Lines(
            String state,
            boolean synthetic,
            List<Line> items,
            @Nullable Integer markingCodesTransmitted) {}

    /**
     * A request that left for the provider and was later replaced by another, kept as it was sent.
     *
     * <p>Oldest first. A reply that never came proves nothing about what the provider received, so the request
     * a resubmission replaced may be the one that is on file with the tax authority, and the evidence of an
     * incorrect receipt is what was sent. The latest request is {@link Document#lines()}.
     *
     * @param attempt 1 for the first request that left
     */
    public record EarlierRequest(int attempt, Instant sentAt, Lines lines) {}

    /** How the provider was told the payment was split. */
    public record TenderSplit(Money card, Money cash, Money ecash) {}

    public record CorrectionView(
            UUID requestId,
            String kind,
            String status,
            Instant requestedAt,
            @Nullable UUID attachedDocumentId) {}

    /** One fiscal document, as the evidence surface shows it. */
    public record Document(
            UUID documentId,
            String documentType,
            @Nullable UUID correctsDocumentId,
            String status,
            String reasonCode,
            String reasonNote,
            String responsibility,
            @Nullable String providerType,
            Seller seller,
            @Nullable MerchantAccount merchantAccount,
            @Nullable UUID tenderId,
            @Nullable UUID paymentIntentId,
            boolean hasEvidence,
            Identifiers identifiers,
            @Nullable Instant registeredAt,
            @Nullable String providerStatusCode,
            Lines lines,
            List<EarlierRequest> earlierRequests,
            @Nullable TenderSplit tenderSplit,
            @Nullable CorrectionView correction,
            @Nullable Instant submittedAt,
            @Nullable Instant issuedAt,
            @Nullable Instant blockedAt,
            int attemptCount,
            int version,
            Instant createdAt) {}

    /**
     * Marking, as counts.
     *
     * @param state {@code NOT_BUILT} when this deployment has no mark store (and no count is shown),
     *     otherwise {@code COUNTS}
     */
    public record Marks(
            String state,
            @Nullable Integer required,
            @Nullable Integer captured) {}

    /** The answer for one order. */
    public record OrderEvidence(
            UUID orderId,
            Retention retention,
            Marks marks,
            boolean revealed,
            @Nullable Purpose purpose,
            List<Document> documents) {}

    /**
     * The order's fiscal documents with their evidence.
     *
     * @param reveal null for the masked read; a purpose for a reveal, in which case the caller has
     *     already been checked for {@link Capability#FISCAL_EVIDENCE_REVEAL}
     */
    @Transactional
    public OrderEvidence forOrder(
            UUID tenantId, UUID orderId, @Nullable Purpose reveal, ActorRef actor, @Nullable String correlationId) {

        List<EvidenceRow> rows = store.documentsOfOrder(tenantId, orderId);
        Map<UUID, List<SentRequestRow>> sentRequests = new LinkedHashMap<>();
        for (SentRequestRow sent : store.sentRequestsOfOrder(tenantId, orderId)) {
            sentRequests
                    .computeIfAbsent(sent.documentId(), id -> new ArrayList<>())
                    .add(sent);
        }
        Map<UUID, CorrectionRow> latestCorrection = new LinkedHashMap<>();
        for (CorrectionRow request : store.correctionsOfOrder(tenantId, orderId)) {
            // Newest first: the first one seen per document is the one that is shown.
            latestCorrection.putIfAbsent(request.documentId(), request);
        }

        List<Document> documents = new ArrayList<>(rows.size());
        for (EvidenceRow row : rows) {
            Document document = describe(
                    tenantId,
                    row,
                    reveal != null,
                    latestCorrection.get(row.id()),
                    sentRequests.getOrDefault(row.id(), List.of()));
            documents.add(document);
            if (reveal != null) {
                recordReveal(tenantId, orderId, row, reveal, actor, correlationId);
            }
        }

        return new OrderEvidence(
                orderId, retention(tenantId), marksOf(tenantId, orderId), reveal != null, reveal, documents);
    }

    private Document describe(
            UUID tenantId,
            EvidenceRow row,
            boolean reveal,
            @Nullable CorrectionRow correction,
            List<SentRequestRow> sentRequests) {
        Lines lines = linesOf(tenantId, row);
        return new Document(
                row.id(),
                row.documentType(),
                row.correctsDocumentId(),
                row.state().name(),
                row.reasonCode(),
                row.reasonNote(),
                row.responsibility(),
                row.providerType(),
                sellerOf(row),
                row.merchantBindingId() == null
                        ? null
                        : new MerchantAccount(row.merchantBindingId(), mask(row.merchantAccountReference())),
                row.tenderId(),
                row.paymentIntentId(),
                row.hasEvidence(),
                identifiersOf(row, reveal),
                row.registeredAt(),
                row.providerStatusCode(),
                lines,
                earlierRequestsOf(tenantId, row, sentRequests),
                splitOf(tenantId, row),
                correction == null
                        ? null
                        : new CorrectionView(
                                correction.id(),
                                correction.kind(),
                                correction.status(),
                                correction.requestedAt(),
                                correction.attachedDocumentId()),
                row.submittedAt(),
                row.issuedAt(),
                row.blockedAt(),
                row.attemptCount(),
                row.version(),
                row.createdAt());
    }

    private static Seller sellerOf(EvidenceRow row) {
        if (row.sellerTin() == null) {
            return new Seller(row.legalEntityId(), null, null, "UNRESOLVED");
        }
        String source = row.sellerSnapshotSource() == null ? "UNRESOLVED" : row.sellerSnapshotSource();
        return new Seller(row.legalEntityId(), row.sellerTin(), row.sellerName(), source);
    }

    private static Identifiers identifiersOf(EvidenceRow row, boolean reveal) {
        String receiptNumber = row.receiptReference() != null ? row.receiptReference() : row.externalReceiptId();
        if (reveal) {
            return new Identifiers(true, row.fiscalSign(), receiptNumber, row.receiptUrl(), row.terminalId());
        }
        return new Identifiers(
                false, mask(row.fiscalSign()), mask(receiptNumber), mask(row.receiptUrl()), mask(row.terminalId()));
    }

    /**
     * The last four characters behind a fixed prefix, so a masked value says it is masked and says how
     * many characters it kept, and never how many it hid.
     */
    static @Nullable String mask(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String tail = value.length() <= 4 ? "" : value.substring(value.length() - 4);
        return "****" + tail;
    }

    // ------------------------------------------------------------------- lines

    private Lines linesOf(UUID tenantId, EvidenceRow row) {
        return linesOf(tenantId, row.id(), row.protectedRequestReference(), row.linesSynthetic());
    }

    /**
     * The requests the latest one replaced: every row of the document's history but the newest, which is the
     * document's own columns and so {@link #linesOf(UUID, EvidenceRow)}. A document written before the history
     * existed has one backfilled row and so none here.
     */
    private List<EarlierRequest> earlierRequestsOf(UUID tenantId, EvidenceRow row, List<SentRequestRow> sent) {
        if (sent.size() < 2) {
            return List.of();
        }
        List<EarlierRequest> earlier = new ArrayList<>(sent.size() - 1);
        for (SentRequestRow request : sent.subList(0, sent.size() - 1)) {
            earlier.add(new EarlierRequest(
                    request.attempt(),
                    request.sentAt(),
                    linesOf(tenantId, row.id(), request.protectedRequestReference(), request.linesSynthetic())));
        }
        return List.copyOf(earlier);
    }

    private Lines linesOf(UUID tenantId, UUID documentId, @Nullable String reference, boolean synthetic) {
        if (reference == null) {
            return new Lines("NOT_SENT", synthetic, List.of(), null);
        }
        try {
            String plain = protection.reveal(
                    tenantId,
                    ProtectedValue.deserialize(reference),
                    SentReceiptEvidence.requestRecord(documentId),
                    LINES_READ_PURPOSE);
            SentReceiptEvidence.Lines sent = json.readValue(plain, SentReceiptEvidence.Lines.class);
            if (sent.version() != SentReceiptEvidence.VERSION) {
                return unreadable(documentId, synthetic, "an unknown version");
            }
            List<Line> items = new ArrayList<>(sent.lines().size());
            int codes = 0;
            for (SentReceiptEvidence.Line line : sent.lines()) {
                codes += line.markingCodeCount();
                items.add(new Line(
                        line.kind(),
                        line.name(),
                        line.ikpu(),
                        line.packageCode(),
                        line.unitCode(),
                        line.quantity(),
                        new Money(line.unitPriceMinor(), sent.currency()),
                        new Money(line.taxAmountMinor(), sent.currency()),
                        line.vatPercent(),
                        line.discountMinor() == null ? null : new Money(line.discountMinor(), sent.currency()),
                        line.barcode(),
                        line.markingCodeCount(),
                        line.commissionTin()));
            }
            return new Lines("AVAILABLE", sent.synthetic(), items, codes);
        } catch (RuntimeException unreadable) {
            // No payload and no exception message: either could carry the thing that failed to parse.
            return unreadable(documentId, synthetic, unreadable.getClass().getSimpleName());
        }
    }

    private static Lines unreadable(UUID documentId, boolean synthetic, String why) {
        log.warn("The sent lines of fiscal document {} could not be read: {}", documentId, why);
        return new Lines("NOT_READABLE", synthetic, List.of(), null);
    }

    private @Nullable TenderSplit splitOf(UUID tenantId, EvidenceRow row) {
        if (row.sentTenderSplitReference() == null) {
            return null;
        }
        try {
            String plain = protection.reveal(
                    tenantId,
                    ProtectedValue.deserialize(row.sentTenderSplitReference()),
                    SentReceiptEvidence.splitRecord(row.id()),
                    LINES_READ_PURPOSE);
            SentReceiptEvidence.TenderSplit split = json.readValue(plain, SentReceiptEvidence.TenderSplit.class);
            if (split.version() != SentReceiptEvidence.VERSION) {
                return null;
            }
            return new TenderSplit(
                    new Money(split.receivedCardMinor(), split.currency()),
                    new Money(split.receivedCashMinor(), split.currency()),
                    new Money(split.receivedEcashMinor(), split.currency()));
        } catch (RuntimeException unreadable) {
            log.warn(
                    "The sent tender split of fiscal document {} could not be read: {}",
                    row.id(),
                    unreadable.getClass().getSimpleName());
            return null;
        }
    }

    // ------------------------------------------------------------------- policy

    private Retention retention(UUID tenantId) {
        FiscalEvidencePolicies.FiscalEvidenceRetention policy;
        try {
            policy = policies.resolve(FiscalEvidencePolicies.EVIDENCE_RETENTION, ResourceScope.tenant(tenantId))
                    .map(ResolvedPolicy::document)
                    .orElseGet(FiscalEvidencePolicies.FiscalEvidenceRetention::platformDefault);
        } catch (RuntimeException unreadable) {
            log.error(
                    "Tenant {} has an unreadable {} policy; the platform default applies.",
                    tenantId,
                    FiscalEvidencePolicies.EVIDENCE_RETENTION.code(),
                    unreadable);
            policy = FiscalEvidencePolicies.FiscalEvidenceRetention.platformDefault();
        }
        return new Retention(policy.years(), policy.provisional());
    }

    private Marks marksOf(UUID tenantId, UUID orderId) {
        return marks.forOrder(tenantId, orderId)
                .map(totals -> new Marks("COUNTS", totals.required(), totals.captured()))
                .orElseGet(() -> new Marks("NOT_BUILT", null, null));
    }

    // ------------------------------------------------------------------- audit

    private void recordReveal(
            UUID tenantId,
            UUID orderId,
            EvidenceRow row,
            Purpose purpose,
            ActorRef actor,
            @Nullable String correlationId) {
        List<String> fields = new ArrayList<>(4);
        if (row.fiscalSign() != null) {
            fields.add("fiscalSign");
        }
        if (row.receiptReference() != null || row.externalReceiptId() != null) {
            fields.add("receiptNumber");
        }
        if (row.receiptUrl() != null) {
            fields.add("receiptUrl");
        }
        if (row.terminalId() != null) {
            fields.add("terminalId");
        }
        if (fields.isEmpty()) {
            // Nothing identifying was on the row, so nothing was revealed and nothing is claimed.
            return;
        }
        // One fact per document, so the document's own access log answers who looked and why. The
        // fact names the fields and never a value: ADR 0029 keeps the identifier out of the trail.
        audit.record(AuditFact.of("fiscal.evidence.revealed", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("fiscal_document", row.id())
                .because("Fiscal evidence opened in full for " + purpose.name())
                .usingCapability(Capability.FISCAL_EVIDENCE_REVEAL.code())
                .changed(ChangeDocuments.created(Map.of(
                        "orderId", orderId.toString(),
                        "purpose", purpose.name(),
                        "fields", List.copyOf(fields))))
                .correlatedBy(correlationId == null ? row.id().toString() : correlationId)
                .occurredAt(clock.instant())
                .build());
    }

    // --------------------------------------------------------- the customer's side

    /**
     * A receipt link on the customer's own order page.
     *
     * @param documentType {@code SALE}, {@code REFUND} or {@code CORRECTION}
     */
    public record CustomerReceipt(
            String documentType,
            String receiptUrl,
            @Nullable Instant registeredAt) {}

    /**
     * The links to the receipts filed for an order, for the customer whose order it is.
     *
     * <p>The caller has already established that the order is the customer's; this class cannot, and
     * does not look at who is asking. What it returns is the link and nothing else: the customer was
     * sent the same link when the receipt was issued (ADR 0058: a legal artifact, not a courtesy), so
     * showing it on the order page opens nothing the notification did not, and the fiscal sign, the
     * terminal, the INN and the lines stay behind the staff reveal. No audit fact is written, because
     * the staff reveal exists to record someone looking at a customer's data and this is the customer
     * looking at their own receipt.
     */
    @Transactional(readOnly = true)
    public List<CustomerReceipt> customerReceipts(UUID tenantId, UUID orderId) {
        return store.receiptLinksOfOrder(tenantId, orderId).stream()
                .filter(row ->
                        !typedByAnOperator(row.reasonCode()) || receiptLinks.isTaxAuthorityLink(row.receiptUrl()))
                .map(row -> new CustomerReceipt(row.documentType(), row.receiptUrl(), row.registeredAt()))
                .toList();
    }

    /**
     * Whether the document's link is one a person typed when attaching the agent's reference, and not one a
     * provider reported. A provider's link is the provider's; a typed one is shown to a customer only if it
     * is the tax authority's, whether or not it was checked at the door (the check is newer than some rows).
     */
    private static boolean typedByAnOperator(String reasonCode) {
        return FiscalCorrectionService.CORRECTION_ATTACHED.equals(reasonCode)
                || FiscalCorrectionService.VOID_ATTACHED.equals(reasonCode);
    }

    // ------------------------------------------------------------ payment types

    /**
     * One path that can discharge a receipt for a payment method, under one legal entity.
     *
     * @param bindingId                 the merchant account, for a partner path
     * @param accountReferenceMasked    its reference, masked
     * @param capableTerminals          for a terminal path, how many of the entity's terminals can issue
     * @param supportsPartnerFiscalization whether the merchant account can issue a receipt at all
     */
    public record PathEntry(
            UUID legalEntityId,
            @Nullable String tin,
            @Nullable String legalName,
            @Nullable UUID bindingId,
            @Nullable String accountReferenceMasked,
            long capableTerminals,
            boolean supportsPartnerFiscalization) {}

    /**
     * One payment method and who issues its receipt.
     *
     * @param path    {@code PARTNER_ACCOUNT}, {@code TERMINAL}, {@code AGGREGATOR}, {@code NOT_A_FISCAL_PATH}
     *     (loyalty points are a discount on lines, not a tender anyone receipts) or {@code NONE}
     * @param live    whether a receipt for this method can actually be issued today
     * @param reasonCode {@code NO_FISCAL_PATH} when it cannot, said as a reason and never as «HorecaOS
     *     fiscalizes this»
     * @param note    a stable code for why, when the reason code alone does not say it
     */
    public record PaymentType(
            String code,
            String displayName,
            String methodStatus,
            String responsibility,
            String path,
            boolean live,
            @Nullable String reasonCode,
            @Nullable String note,
            List<PathEntry> entries) {}

    /**
     * The per-tenant answer to «which payment types are fiscalized», derived and not settable.
     *
     * <p>A setting that disagreed with the bindings would be a lie on a tax document, so there is
     * none: the registry says who is responsible, the merchant bindings and the terminal register say
     * whether that responsibility is discharged, and where neither does the answer is {@code
     * NO_FISCAL_PATH}, an unevidenced obligation of the tenant, never «HorecaOS fiscalizes this».
     */
    @Transactional(readOnly = true)
    public List<PaymentType> paymentTypes(UUID tenantId) {
        Map<UUID, EntityRow> entities = new LinkedHashMap<>();
        store.legalEntities(tenantId).forEach(entity -> entities.put(entity.id(), entity));
        List<PartnerPathRow> partners = store.activePartnerPaths(tenantId);
        List<TerminalPathRow> terminals = store.capableTerminals(tenantId);

        List<PaymentType> types = new ArrayList<>();
        for (MethodRow method : store.paymentMethods(tenantId)) {
            types.add(paymentType(method, entities, partners, terminals));
        }
        return types;
    }

    private static PaymentType paymentType(
            MethodRow method,
            Map<UUID, EntityRow> entities,
            List<PartnerPathRow> partners,
            List<TerminalPathRow> terminals) {
        switch (method.responsibility()) {
            case "PARTNER" -> {
                List<PathEntry> entries = partners.stream()
                        .filter(path -> path.providerType().equals(method.code()))
                        .map(path -> {
                            EntityRow entity = entities.get(path.legalEntityId());
                            return new PathEntry(
                                    path.legalEntityId(),
                                    entity == null ? null : entity.tin(),
                                    entity == null ? null : entity.legalName(),
                                    path.bindingId(),
                                    mask(path.merchantAccountReference()),
                                    0,
                                    path.supportsPartnerFiscalization());
                        })
                        .toList();
                boolean live = entries.stream().anyMatch(PathEntry::supportsPartnerFiscalization);
                return of(
                        method, "PARTNER_ACCOUNT", live, live ? null : FiscalReasonCode.NO_FISCAL_PATH, null, entries);
            }
            case "TERMINAL" -> {
                List<PathEntry> entries = terminals.stream()
                        .map(path -> {
                            EntityRow entity = entities.get(path.legalEntityId());
                            return new PathEntry(
                                    path.legalEntityId(),
                                    entity == null ? null : entity.tin(),
                                    entity == null ? null : entity.legalName(),
                                    null,
                                    null,
                                    path.capableTerminals(),
                                    false);
                        })
                        .toList();
                boolean live = !entries.isEmpty();
                return of(method, "TERMINAL", live, live ? null : FiscalReasonCode.NO_FISCAL_PATH, null, entries);
            }
            case "MARKETPLACE" ->
            // The aggregator collects the money and may issue the receipt as the tenant's agent, but
            // only where the tenant's written contract says so, and no contract reference is held
            // here. So it is reported as the tenant's obligation until one is attached.
            {
                return of(
                        method,
                        "AGGREGATOR",
                        false,
                        FiscalReasonCode.NO_FISCAL_PATH,
                        "AGGREGATOR_CONTRACT_NOT_RECORDED",
                        List.of());
            }
            default -> {
                if (method.settlesFromBalance()) {
                    // Loyalty points settle nothing externally: a per-line discount on the receipt of
                    // the money tender, never a tender that anyone receipts (ADR 0046).
                    return of(method, "NOT_A_FISCAL_PATH", false, null, "BALANCE_IS_A_LINE_DISCOUNT", List.of());
                }
                return of(
                        method,
                        "NONE",
                        false,
                        FiscalReasonCode.NO_FISCAL_PATH,
                        "OPERATOR_RESPONSIBILITY_UNIMPLEMENTED",
                        List.of());
            }
        }
    }

    private static PaymentType of(
            MethodRow method,
            String path,
            boolean live,
            @Nullable String reasonCode,
            @Nullable String note,
            List<PathEntry> entries) {
        return new PaymentType(
                method.code(),
                method.displayName(),
                method.status(),
                method.responsibility(),
                path,
                live,
                reasonCode,
                note,
                entries);
    }

    // ------------------------------------------------------------- worklist

    /** The open correction and void requests, longest-waiting first. */
    @Transactional(readOnly = true)
    public List<CorrectionRow> openCorrections(UUID tenantId, int limit) {
        return store.openCorrections(tenantId, limit);
    }
}
