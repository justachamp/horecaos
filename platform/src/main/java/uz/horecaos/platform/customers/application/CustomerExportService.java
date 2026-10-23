package uz.horecaos.platform.customers.application;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalParameters;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.customers.api.CustomerConfigurationKeys;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.ContactPointRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcExportRequestStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcExportRequestStore.ExportRequestRow;
import uz.horecaos.platform.customers.spi.CustomerExportParticipant;
import uz.horecaos.platform.customers.spi.CustomerExportParticipant.ExportSubject;
import uz.horecaos.platform.customers.spi.ExportSection;
import uz.horecaos.platform.customers.spi.ExportTable;
import uz.horecaos.platform.customers.spi.ExportsCustomerData;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.media.api.ObjectStorage;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.Resolved;

/**
 * A data-subject access request, from the asking to the file (ADR 0029: "export: assemble a customer's data across modules,
 * decrypted under audit").
 *
 * <p><strong>Asking and producing are two acts, like an erasure's request and its execution.</strong> A customer asks on the
 * storefront, or staff ask on her behalf; that records intent and a date the tenant owes an answer by. {@link #produce} is
 * the act that gathers her data, and it is held by the capability that opens personal data to a person
 * ({@code customer.pii.export}), under an ADR 0027 approval when the tenant published a policy for it, with a purpose the
 * person states. The audit fact naming that person and purpose is written before any participant decrypts a value, and it is
 * registered in {@code PiiEgressActions} so the merchant's egress log shows it.
 *
 * <p><strong>The bundle is one JSON document in object storage</strong>, a section per {@link CustomerExportParticipant},
 * under a key made of the tenant and the request id and nothing about the person. The request row holds the key, a SHA-256
 * and a manifest of counts; never a value. It is kept for {@link CustomerConfigurationKeys#DSAR_BUNDLE_RETENTION_DAYS} days
 * and then removed by {@code ExportBundleSweeper}, or at once if the customer is erased first
 * ({@code CustomerRecordsErasureParticipant}). A link to it lasts minutes, is minted on each authorised read, and every
 * mint is a second audit fact.
 *
 * <p>What it cannot do alone: the cover note's wording and the response deadline are legal's to confirm. They come from
 * ADR 0030 keys with provisional defaults, and the bundle says which it is carrying.
 */
@Service
public class CustomerExportService {

    /** How long a link to the bundle lasts. The report export centre uses the same five minutes. */
    static final Duration LINK_VALIDITY = Duration.ofMinutes(5);

    /** The format identifier a reader of the file can check. */
    static final String BUNDLE_FORMAT = "horecaos.dsar.v1";

    static final String ACCOUNT_TABLE = "customer.customer_accounts";
    static final String REQUEST_TABLE = "customer.export_requests";

    /** Where the wording stays until legal sets it; the bundle marks it {@code PROVISIONAL}. */
    static final Map<String, String> PROVISIONAL_COVER_NOTE = Map.of(
            "ru",
            "Этот файл содержит персональные данные, которые мы храним о вас, собранные по вашему запросу. "
                    + "Формулировка этого уведомления предварительная: её юридическая проверка не завершена. "
                    + "Если что-то указано неверно или отсутствует, попросите исправить.",
            "uz-latn",
            "Ushbu faylda sizning so'rovingiz bo'yicha biz saqlayotgan shaxsiy ma'lumotlaringiz jamlangan. "
                    + "Bu xabarnoma matni dastlabki: uning huquqiy tekshiruvi yakunlanmagan. "
                    + "Agar biror narsa noto'g'ri ko'rsatilgan yoki yetishmasa, tuzatishni so'rang.",
            "en",
            "This file contains the personal data we hold about you, gathered at your request. The wording of this "
                    + "notice is provisional: its legal review is not complete. If something here is wrong or missing, "
                    + "ask for a correction.");

    private static final List<String> COVER_NOTE_LANGUAGES = List.of("ru", "uz-latn", "en");

    /** Who raised a request. */
    public enum RequestedVia {
        STOREFRONT,
        OPERATIONS
    }

    /** The bundle produced, or the approval it is waiting for. */
    public sealed interface Result {

        /** The bundle was produced (or had been). */
        record Done(ExportRequestRow request) implements Result {}

        /** A second person has to decide first, or said no. Nothing else happened. */
        record Awaiting(ApprovalOutcome outcome) implements Result {}
    }

    /** A link to read the bundle by, and when it stops working. */
    public record BundleLink(URI url, Instant expiresAt) {}

    /** What the second person signs when a bundle is produced. Public so the hash can read it. */
    public record ProduceProposal(UUID tenantId, UUID customerAccountId, UUID requestId) {}

    private final JdbcExportRequestStore requests;
    private final JdbcCustomerStore accounts;
    private final ApprovalService approvals;
    private final AuditRecorder audit;
    private final ObjectStorage storage;
    private final ConfigurationResolver configuration;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String bucket;
    private final List<DeclaredParticipant> participants;

    public CustomerExportService(
            JdbcExportRequestStore requests,
            JdbcCustomerStore accounts,
            ApprovalService approvals,
            AuditRecorder audit,
            ObjectStorage storage,
            ConfigurationResolver configuration,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${horecaos.customers.exports.bucket:${horecaos.media.bucket:horecaos-media}}") String bucket,
            List<CustomerExportParticipant> participants) {
        this.requests = requests;
        this.accounts = accounts;
        this.approvals = approvals;
        this.audit = audit;
        this.storage = storage;
        this.configuration = configuration;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.bucket = bucket;
        this.participants = participants.stream()
                .map(CustomerExportService::declared)
                .sorted(Comparator.comparing(DeclaredParticipant::name))
                .toList();
        Set<String> names = new java.util.HashSet<>();
        for (DeclaredParticipant declared : this.participants) {
            if (!names.add(declared.name())) {
                throw new IllegalStateException("Two export participants are named " + declared.name()
                        + "; a section of the bundle needs one owner (ADR 0029)");
            }
        }
    }

    private static DeclaredParticipant declared(CustomerExportParticipant participant) {
        Class<?> type = AopUtils.getTargetClass(participant);
        ExportsCustomerData declaration = AnnotatedElementUtils.findMergedAnnotation(type, ExportsCustomerData.class);
        if (declaration == null) {
            throw new IllegalStateException(type.getName() + " is a CustomerExportParticipant with no "
                    + "@ExportsCustomerData, so a manifest could not say what an export covered (ADR 0029)");
        }
        return new DeclaredParticipant(participant, declaration.name(), List.of(declaration.tables()));
    }

    private record DeclaredParticipant(CustomerExportParticipant participant, String name, List<String> tables) {}

    /**
     * Raises a request, or returns the one already outstanding.
     *
     * <p>Idempotent two ways, as an erasure request is: a retried HTTP call is replayed by the {@code Idempotency-Key}
     * interceptor before it gets here, and a second distinct ask while one is {@code PENDING} lands on the same row (the
     * partial unique index decides a race). An account that is anonymised or merged has no data to give and is refused.
     *
     * @throws CustomerErasureService.AccountNotFoundException when the account is not this tenant's
     * @throws AccountNotExportableException                    when the account is anonymised or merged away
     */
    @Transactional
    public ExportRequestRow request(UUID tenantId, UUID accountId, RequestedVia via, ActorRef actor) {
        requireExportable(tenantId, accountId);

        Optional<ExportRequestRow> existing = requests.pending(tenantId, accountId);
        if (existing.isPresent()) {
            return existing.get();
        }

        Instant now = clock.instant();
        UUID requestId = Ids.newId();
        Instant due = now.plus(Duration.ofDays(deadlineDays(tenantId)));
        boolean created = requests.insertIfNonePending(
                requestId, tenantId, accountId, via.name(), actor.type().name(), actor.subject(), now, due);
        if (!created) {
            // Lost a race to a concurrent ask: its row is what the caller should see.
            return requests.pending(tenantId, accountId).orElseThrow();
        }

        audit.record(AuditFact.of("customer.dsar.requested", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target(ACCOUNT_TABLE, accountId)
                .because(
                        via == RequestedVia.STOREFRONT
                                ? "The customer asked for a copy of her data"
                                : "Staff asked for a copy of the customer's data on her behalf")
                // Staff 9.3a: a freshly raised request has no prior state.
                .changed(ChangeDocuments.created(
                        Map.of("requestId", requestId.toString(), "requestedVia", via.name(), "status", "PENDING")))
                .correlatedBy(accountId.toString())
                .occurredAt(now)
                .build());
        return requests.find(tenantId, requestId).orElseThrow();
    }

    /**
     * One account's requests, newest first.
     *
     * @throws CustomerErasureService.AccountNotFoundException when the account is not this tenant's
     */
    @Transactional(readOnly = true)
    public List<ExportRequestRow> history(UUID tenantId, UUID accountId) {
        if (!accounts.accountExists(tenantId, accountId)) {
            throw new CustomerErasureService.AccountNotFoundException();
        }
        return requests.history(tenantId, accountId);
    }

    /** The outstanding request if there is one, else the most recent of any status: what the storefront shows. */
    @Transactional(readOnly = true)
    public Optional<ExportRequestRow> current(UUID tenantId, UUID accountId) {
        Optional<ExportRequestRow> pending = requests.pending(tenantId, accountId);
        if (pending.isPresent()) {
            return pending;
        }
        return requests.history(tenantId, accountId).stream().findFirst();
    }

    /**
     * Gathers the customer's data into a bundle, stores it, and completes the request.
     *
     * <p>Idempotent: a request already {@code COMPLETED} returns itself and gathers nothing a second time. The row is
     * locked first, so two people pressing the button write one object and one fact.
     *
     * @param purpose why the data is being revealed, in the producing person's words; recorded on the audit fact, passed
     *                to every participant's reveal, and shown to the second approver
     * @throws NoSuchExportRequestException        when the request is not this account's
     * @throws ExportRequestNotPendingException    when the request was withdrawn
     * @throws AccountNotExportableException       when the account was anonymised or merged since it was asked
     */
    @Transactional
    public Result produce(UUID tenantId, UUID accountId, UUID requestId, String purpose, ActorRef actor) {
        ExportRequestRow request = requests.findForUpdate(tenantId, requestId)
                .filter(row -> row.customerAccountId().equals(accountId))
                .orElseThrow(NoSuchExportRequestException::new);
        if ("COMPLETED".equals(request.status())) {
            return new Result.Done(request);
        }
        if (!"PENDING".equals(request.status())) {
            throw new ExportRequestNotPendingException(request.status());
        }
        requireExportable(tenantId, accountId);

        ApprovalOutcome outcome = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.CUSTOMER_PII_EXPORT.code(),
                ApprovalParameters.of(new ProduceProposal(tenantId, accountId, requestId))
                        .excluding()
                        .hash(),
                ResourceScope.tenant(tenantId),
                actor,
                purpose,
                ApprovalRequestCommand.DEFAULT_VALIDITY));
        if (!outcome.mayProceed()) {
            return new Result.Awaiting(outcome);
        }
        outcome.consume();
        UUID approvalId = outcome instanceof ApprovalOutcome.Approved approved ? approved.requestId() : null;

        Instant now = clock.instant();
        Set<String> phoneHashes = accounts.contactPoints(tenantId, accountId).stream()
                .filter(contact -> "PHONE".equals(contact.type()))
                .map(ContactPointRow::normalizedHash)
                .collect(Collectors.toSet());

        // The fact first, then the first decrypt: if a participant throws, the transaction rolls both back together,
        // and
        // if it does not, no value was revealed that the trail does not name a person and a purpose for.
        audit.record(AuditFact.of("customer.dsar.exported", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target(ACCOUNT_TABLE, accountId)
                .because(purpose)
                .underApproval(approvalId)
                // Staff 9.3a: producing the bundle sets its outcome for the first time; there is no earlier outcome.
                .changed(ChangeDocuments.created(Map.of("requestId", requestId.toString(), "status", "COMPLETED")))
                .correlatedBy(accountId.toString())
                .occurredAt(now)
                .build());

        ExportSubject subject = new ExportSubject(tenantId, accountId, phoneHashes, purpose);
        List<SectionResult> sections = new ArrayList<>();
        for (DeclaredParticipant declared : participants) {
            sections.add(new SectionResult(declared, declared.participant().export(subject)));
        }

        byte[] bundle = bundle(tenantId, request, sections, now);
        String key = "tenants/%s/dsar-exports/%s.json".formatted(tenantId, requestId);
        storage.put(bucket, key, "application/json", bundle);

        Instant expiresAt = now.plus(Duration.ofDays(retentionDays(tenantId)));
        String manifest = objectMapper.writeValueAsString(manifest(sections));
        int written = requests.complete(
                tenantId,
                requestId,
                actor.type().name(),
                actor.subject(),
                approvalId,
                bucket,
                key,
                sha256(bundle),
                bundle.length,
                manifest,
                expiresAt,
                now);
        if (written == 0) {
            // The row was locked above, so this is a request that left PENDING in the same transaction's view; refuse
            // rather than leave an object nobody's row points at.
            storage.delete(bucket, key);
            throw new ExportRequestNotPendingException(
                    requests.find(tenantId, requestId).orElseThrow().status());
        }
        return new Result.Done(requests.find(tenantId, requestId).orElseThrow());
    }

    /**
     * A link to read the produced bundle by. Every call is an audit fact naming who asked and why, registered as personal
     * data leaving ({@code customer.dsar.bundle_revealed}); the link itself is never stored or logged.
     *
     * @throws NoSuchExportRequestException when the request is not this account's
     * @throws BundleNotAvailableException  when nothing was produced, or it has expired or been removed
     */
    @Transactional
    public BundleLink bundleLink(UUID tenantId, UUID accountId, UUID requestId, String purpose, ActorRef actor) {
        ExportRequestRow request = requests.find(tenantId, requestId)
                .filter(row -> row.customerAccountId().equals(accountId))
                .orElseThrow(NoSuchExportRequestException::new);
        Instant now = clock.instant();
        if (!request.bundleAvailableAt(now)) {
            throw new BundleNotAvailableException(request.status());
        }
        audit.record(AuditFact.of("customer.dsar.bundle_revealed", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target(ACCOUNT_TABLE, accountId)
                .because(purpose)
                // Staff 9.3a: reading is not a change; the fact records who was given a way into the bundle.
                .changed(ChangeDocuments.created(Map.of("requestId", requestId.toString())))
                .correlatedBy(accountId.toString())
                .occurredAt(now)
                .build());
        URI url = storage.presignDownload(
                Objects.requireNonNull(request.bundleBucket()),
                Objects.requireNonNull(request.bundleKey()),
                LINK_VALIDITY);
        return new BundleLink(url, now.plus(LINK_VALIDITY));
    }

    /**
     * Withdraws a {@code PENDING} request. Idempotent for one already cancelled; refused for one already produced.
     *
     * @throws NoSuchExportRequestException     when the request is not this account's
     * @throws ExportRequestNotPendingException when it was produced already
     */
    @Transactional
    public ExportRequestRow cancel(UUID tenantId, UUID accountId, UUID requestId, ActorRef actor) {
        ExportRequestRow request = requests.findForUpdate(tenantId, requestId)
                .filter(row -> row.customerAccountId().equals(accountId))
                .orElseThrow(NoSuchExportRequestException::new);
        if ("CANCELLED".equals(request.status())) {
            return request;
        }
        if ("COMPLETED".equals(request.status())) {
            throw new ExportRequestNotPendingException(request.status());
        }
        Instant now = clock.instant();
        if (requests.cancel(tenantId, requestId, actor.type().name(), actor.subject(), now) == 0) {
            return requests.find(tenantId, requestId).orElseThrow();
        }
        audit.record(AuditFact.of("customer.dsar.cancelled", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target(ACCOUNT_TABLE, accountId)
                .because("The request for a copy of the customer's data was withdrawn")
                .changed(ChangeDocuments.diff(
                        Map.of("requestId", requestId.toString(), "status", "PENDING"),
                        Map.of("requestId", requestId.toString(), "status", "CANCELLED")))
                .correlatedBy(accountId.toString())
                .occurredAt(now)
                .build());
        return requests.find(tenantId, requestId).orElseThrow();
    }

    // ---------------------------------------------------------------- the document

    private byte[] bundle(UUID tenantId, ExportRequestRow request, List<SectionResult> sections, Instant now) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", BUNDLE_FORMAT);
        document.put("generatedAt", now.toString());
        document.put("requestId", request.id().toString());
        document.put("customerAccountId", request.customerAccountId().toString());
        document.put("cover", cover(tenantId, request));
        Map<String, Object> body = new LinkedHashMap<>();
        for (SectionResult section : sections) {
            Map<String, Object> tables = new LinkedHashMap<>();
            for (ExportTable table : section.section().tables()) {
                Map<String, Object> rendered = new LinkedHashMap<>();
                rendered.put("rows", table.rows());
                rendered.put("truncated", table.truncated());
                tables.put(table.table(), rendered);
            }
            body.put(section.declared().name(), tables);
        }
        document.put("sections", body);
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document);
    }

    /** The note that opens the file: legal's wording where it was set, the provisional wording marked as such where not. */
    private Map<String, Object> cover(UUID tenantId, ExportRequestRow request) {
        Map<String, Object> note = new LinkedHashMap<>();
        Map<String, Object> status = new LinkedHashMap<>();
        for (String language : COVER_NOTE_LANGUAGES) {
            String authored = CustomerConfigurationKeys.coverNoteKeyFor(language)
                    .map(key -> resolveString(key, tenantId))
                    .orElse("");
            boolean isAuthored = !authored.isBlank();
            note.put(language, isAuthored ? authored : PROVISIONAL_COVER_NOTE.get(language));
            status.put(language, isAuthored ? "AUTHORED" : "PROVISIONAL");
        }
        Map<String, Object> cover = new LinkedHashMap<>();
        cover.put("responseDueAt", request.responseDueAt().toString());
        cover.put("responseDeadlineDays", deadlineDays(tenantId));
        cover.put("note", note);
        cover.put("noteStatus", status);
        return cover;
    }

    private String resolveString(ConfigurationKey<String> key, UUID tenantId) {
        Resolved<String> resolved = configuration.resolve(key, ResourceScope.tenant(tenantId));
        String value = resolved.value();
        return value == null ? "" : value;
    }

    /** Counts and names, never a value: what the request row keeps of the bundle. */
    private Map<String, Object> manifest(List<SectionResult> sections) {
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (SectionResult section : sections) {
            List<Map<String, Object>> tables = new ArrayList<>();
            for (ExportTable table : section.section().tables()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("table", table.table());
                entry.put("rows", table.rows().size());
                entry.put("truncated", table.truncated());
                tables.add(entry);
            }
            // A table the participant declared and did not report is "looked, found 0", not "not looked".
            for (String declaredTable : section.declared().tables()) {
                if (tables.stream().noneMatch(entry -> declaredTable.equals(entry.get("table")))) {
                    tables.add(Map.of("table", declaredTable, "rows", 0, "truncated", false));
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", section.declared().name());
            entry.put("tables", tables);
            rendered.add(entry);
        }
        List<String> withheld = sections.stream()
                .flatMap(section -> section.section().withheld().stream()
                        .map(code -> section.declared().name() + ":" + code))
                .toList();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("sections", rendered);
        manifest.put("withheld", withheld);
        return manifest;
    }

    private record SectionResult(DeclaredParticipant declared, ExportSection section) {}

    // ---------------------------------------------------------------- settings

    private int deadlineDays(UUID tenantId) {
        return resolveInt(CustomerConfigurationKeys.DSAR_RESPONSE_DEADLINE_DAYS, tenantId, 30);
    }

    private int retentionDays(UUID tenantId) {
        return resolveInt(CustomerConfigurationKeys.DSAR_BUNDLE_RETENTION_DAYS, tenantId, 7);
    }

    private int resolveInt(ConfigurationKey<Integer> key, UUID tenantId, int fallback) {
        Integer value =
                configuration.resolve(key, ResourceScope.tenant(tenantId)).value();
        return value == null ? fallback : value;
    }

    private void requireExportable(UUID tenantId, UUID accountId) {
        JdbcCustomerStore.AccountRow account =
                accounts.account(tenantId, accountId).orElseThrow(CustomerErasureService.AccountNotFoundException::new);
        if ("ANONYMIZED".equals(account.status()) || "MERGED".equals(account.status())) {
            throw new AccountNotExportableException(account.status());
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** No such export request for this account in this tenant. */
    public static class NoSuchExportRequestException extends RuntimeException {
        public NoSuchExportRequestException() {
            super("No such export request");
        }
    }

    /** The request is not waiting to be produced: it was produced already, or it was withdrawn. */
    public static class ExportRequestNotPendingException extends RuntimeException {
        private final String status;

        public ExportRequestNotPendingException(String status) {
            super("The export request is " + status + ", not pending");
            this.status = status;
        }

        public String status() {
            return status;
        }
    }

    /** There is no bundle to read: nothing was produced, or it expired, or the customer was erased. */
    public static class BundleNotAvailableException extends RuntimeException {
        public BundleNotAvailableException(String status) {
            super("No bundle is available for this request (status " + status + ")");
        }
    }

    /** The account holds nothing to export: it was anonymised, or merged into another account. */
    public static class AccountNotExportableException extends RuntimeException {
        public AccountNotExportableException(String accountStatus) {
            super("The account is " + accountStatus + " and holds no data to export");
        }
    }

    /** Exposed for the sweeper and the tests: the bucket the bundles are put in. */
    public String bucket() {
        return bucket;
    }
}
