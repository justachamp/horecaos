package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerImportStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerImportStore.ClaimedRun;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerImportStore.RunRow;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The async job surface row {@code X.13}/{@code 5.1b}'s brief names as
 * missing: submits a customer CSV import as a durable, pollable job rather
 * than the synchronous POST both existing imports (SendPulse's contact
 * import, POS catalog sync) use.
 *
 * <p>{@link #submit} does the one thing that must happen on the request
 * thread — parsing the file, which costs no external call, to know {@code
 * rowsTotal} from the very first {@code GET} — then hands the file itself,
 * envelope-encrypted (ADR 0029: a customer list is as sensitive as the rows
 * it becomes), to {@code customer.customer_import_runs} for {@link
 * CustomerImportRunWorker} to claim and work.
 * {@link #processNextQueuedRun} is that worker's own call — public so the
 * worker can invoke it without this service reaching into a scheduling
 * concern that is not its own.
 */
@Service
public class CustomerImportService {

    private static final Logger log = LoggerFactory.getLogger(CustomerImportService.class);

    private static final String CONTENT_TABLE = "customer.customer_import_runs";
    private static final String CONTENT_COLUMN = "encrypted_content";
    private static final String REVEAL_PURPOSE = "Customer CSV import processing";

    /** A short, loggable code — never the parser's own message, which could echo a row's content back (ADR 0029). */
    private static final String FAILURE_REASON = "PROCESSING_FAILED";

    private final CustomerCsvImportParser parser;
    private final CustomerCsvImportRowService rowService;
    private final JdbcCustomerImportStore store;
    private final FieldProtection protection;
    private final Clock clock;

    public CustomerImportService(
            CustomerCsvImportParser parser,
            CustomerCsvImportRowService rowService,
            JdbcCustomerImportStore store,
            FieldProtection protection,
            Clock clock) {
        this.parser = parser;
        this.rowService = rowService;
        this.store = store;
        this.protection = protection;
        this.clock = clock;
    }

    /** Parses and queues a run; throws if the document itself is not readable as CSV. */
    public UUID submit(
            UUID tenantId,
            UUID brandId,
            boolean dryRun,
            String sourceFileName,
            String content,
            String importedBySubject) {
        List<CustomerCsvImportRow> parsedRows;
        try {
            parsedRows = parser.parse(content);
        } catch (CustomerCsvImportParser.CustomerCsvImportFormatException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, malformed.getMessage());
        }

        UUID runId = Ids.newId();
        Instant now = clock.instant();
        ProtectedValue encrypted = protection.protect(
                tenantId, DataClass.PERSONAL, new RecordRef(CONTENT_TABLE, CONTENT_COLUMN, runId), content);
        store.insertQueuedRun(
                runId,
                tenantId,
                brandId,
                dryRun,
                sourceFileName,
                encrypted.serialize(),
                importedBySubject,
                parsedRows.size(),
                now);
        return runId;
    }

    /**
     * Claims and fully works one queued run, if one exists.
     *
     * <p>Never throws for a single run's own failure — a malformed re-parse or
     * a row that throws unexpectedly settles that run as {@code FAILED} and
     * this method returns normally, the same "one bad item costs its own
     * item" rule {@code MediaVerificationWorker}'s own doc states. What can
     * still throw is the claim itself (a database fault), which the caller
     * (the scheduled tick) is left to log — there is no run to blame it on.
     *
     * @return true if a run was claimed (whether it then completed or failed)
     */
    public boolean processNextQueuedRun() {
        Optional<ClaimedRun> claimed = store.claimNextQueuedRun(clock.instant());
        if (claimed.isEmpty()) {
            return false;
        }
        ClaimedRun run = claimed.get();
        try {
            String content = protection.reveal(
                    run.tenantId(),
                    ProtectedValue.deserialize(run.encryptedContent()),
                    new RecordRef(CONTENT_TABLE, CONTENT_COLUMN, run.id()),
                    REVEAL_PURPOSE);
            List<CustomerCsvImportRow> rows = parser.parse(content);
            for (CustomerCsvImportRow row : rows) {
                CustomerCsvImportRowOutcome outcome =
                        rowService.process(run.tenantId(), run.brandId(), row, run.dryRun());
                store.insertRow(
                        run.tenantId(),
                        run.id(),
                        row.rowNumber(),
                        outcome.type().name(),
                        outcome.customerAccountId(),
                        outcome.rejectReason() == null
                                ? null
                                : outcome.rejectReason().name(),
                        clock.instant());
                store.advanceProgress(run.tenantId(), run.id(), outcome.type().name());
            }
            store.completeRun(run.tenantId(), run.id(), run.dryRun(), clock.instant());
        } catch (RuntimeException failure) {
            log.error("Customer CSV import run {} failed while processing", run.id(), failure);
            store.failRun(run.tenantId(), run.id(), FAILURE_REASON, clock.instant());
        }
        return true;
    }

    public RunRow status(UUID tenantId, UUID runId) {
        return store.run(tenantId, runId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such import run"));
    }

    public List<JdbcCustomerImportStore.ImportRowView> rows(UUID tenantId, UUID runId, int limit, int offset) {
        return store.rows(tenantId, runId, limit, offset);
    }
}
