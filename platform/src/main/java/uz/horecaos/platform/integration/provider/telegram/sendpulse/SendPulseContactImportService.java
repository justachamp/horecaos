package uz.horecaos.platform.integration.provider.telegram.sendpulse;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.integration.provider.telegram.TelegramInstallationBrandLookup;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup.WebhookInstallation;
import uz.horecaos.platform.integration.provider.telegram.sendpulse.JdbcSendPulseImportStore.RunCounts;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Turns one SendPulse contact export into a full report, and — unless {@code
 * dryRun} — into real customers, real ADR 0058 CUSTOMER-audience Telegram
 * bindings, and real ADR 0020 consent decisions (ADR 0059 stage 3).
 *
 * <p>The whole run is never one transaction. Each row is its own, via {@link
 * SendPulseContactImportRowService#process}, so a failure on row 500 of 1000
 * leaves the first 499 exactly as processed rather than rolling the batch
 * back — "per-row failures collect into the report, never abort the batch"
 * is this record's own words for it. What is NOT per-row is the file-level
 * refusal below: an unknown, wrong-tenant, wrong-type, inactive, or
 * brand-less installation is refused before any row is touched, because
 * there is no bot to bind chats to yet and every row would fail the same way
 * for the same reason.
 */
@Service
public class SendPulseContactImportService {

    private static final Logger log = LoggerFactory.getLogger(SendPulseContactImportService.class);

    private static final String TELEGRAM_PROVIDER_TYPE = "TELEGRAM_BOT_API";

    private final SendPulseContactFileParser parser;
    private final SendPulseContactImportRowService rowService;
    private final JdbcSendPulseImportStore store;
    private final TelegramWebhookInstallationLookup installations;
    private final TelegramInstallationBrandLookup brands;
    private final Clock clock;

    public SendPulseContactImportService(
            SendPulseContactFileParser parser,
            SendPulseContactImportRowService rowService,
            JdbcSendPulseImportStore store,
            TelegramWebhookInstallationLookup installations,
            TelegramInstallationBrandLookup brands,
            Clock clock) {
        this.parser = parser;
        this.rowService = rowService;
        this.store = store;
        this.installations = installations;
        this.brands = brands;
        this.clock = clock;
    }

    public SendPulseImportReport run(
            UUID tenantId,
            UUID installationId,
            SendPulseImportFormat format,
            String content,
            String sourceFileName,
            boolean dryRun,
            String importedBySubject) {

        UUID brandId = requireBoundTelegramInstallation(tenantId, installationId);

        UUID runId = UUID.randomUUID();
        Instant startedAt = clock.instant();
        store.openRun(
                runId, tenantId, installationId, brandId, dryRun, format, sourceFileName, importedBySubject, startedAt);

        List<SendPulseContactRow> parsedRows;
        try {
            parsedRows = parser.parse(format, content);
        } catch (SendPulseContactFileParser.SendPulseImportFormatException malformed) {
            // The run row this study already opened stays FAILED — see
            // JdbcSendPulseImportStore.openRun's own comment on why that is
            // the started status rather than a later transition.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, malformed.getMessage());
        }

        List<SendPulseImportReport.RowEntry> reportRows = new ArrayList<>(parsedRows.size());
        int created = 0;
        int matched = 0;
        int skipped = 0;
        int rejected = 0;
        int subscribed = 0;
        int unsubscribed = 0;

        for (SendPulseContactRow row : parsedRows) {
            SendPulseImportRowOutcome outcome =
                    rowService.process(tenantId, installationId, brandId, runId, row, dryRun, importedBySubject);

            switch (outcome.type()) {
                case CREATED_CUSTOMER -> created++;
                case MATCHED_CUSTOMER -> matched++;
                case SKIPPED_ALREADY_LINKED -> skipped++;
                case REJECTED -> rejected++;
            }
            if (outcome.subscribed() != null) {
                if (outcome.subscribed()) {
                    subscribed++;
                } else {
                    unsubscribed++;
                }
            }

            Instant now = clock.instant();
            store.insertRow(
                    tenantId,
                    runId,
                    row.rowNumber(),
                    row.chatId(),
                    outcome.subscribed(),
                    outcome.type().name(),
                    outcome.customerAccountId(),
                    outcome.rejectReason() == null
                            ? null
                            : outcome.rejectReason().name(),
                    now);
            reportRows.add(new SendPulseImportReport.RowEntry(
                    row.rowNumber(),
                    row.chatId(),
                    outcome.subscribed(),
                    outcome.type().name(),
                    outcome.customerAccountId(),
                    outcome.rejectReason() == null
                            ? null
                            : outcome.rejectReason().name()));
        }

        RunCounts counts =
                new RunCounts(parsedRows.size(), created, matched, skipped, rejected, subscribed, unsubscribed);
        store.completeRun(tenantId, runId, counts, dryRun, clock.instant());

        log.info(
                "SendPulse import run {} for tenant {} ({}): {} rows, {} created, {} matched, {} skipped, {} rejected",
                runId,
                tenantId,
                dryRun ? "dry run" : "real run",
                counts.total(),
                created,
                matched,
                skipped,
                rejected);

        return new SendPulseImportReport(runId, dryRun, counts, List.copyOf(reportRows));
    }

    public SendPulseImportReport report(UUID tenantId, UUID runId, int limit, int offset) {
        JdbcSendPulseImportStore.RunRow run = store.run(tenantId, runId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such import run"));
        List<SendPulseImportReport.RowEntry> rows = store.rows(tenantId, runId, limit, offset).stream()
                .map(row -> new SendPulseImportReport.RowEntry(
                        row.rowNumber(),
                        row.chatId(),
                        row.subscribed(),
                        row.outcome(),
                        row.customerAccountId(),
                        row.rejectReason()))
                .toList();
        return new SendPulseImportReport(run.id(), run.dryRun(), run.counts(), rows);
    }

    /** @return the bot's own brand id, the target every row's binding is created under */
    private UUID requireBoundTelegramInstallation(UUID tenantId, UUID installationId) {
        WebhookInstallation installation = installations
                .find(installationId)
                .filter(candidate -> candidate.tenantId().equals(tenantId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such Telegram installation"));

        if (!TELEGRAM_PROVIDER_TYPE.equals(installation.providerType())) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "Installation " + installationId + " is not a Telegram bot");
        }
        if (!"ACTIVE".equals(installation.status())) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "Installation " + installationId + " is not ACTIVE");
        }
        return brands.brandFor(installationId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "Installation " + installationId + " has no bound brand to import contacts into"));
    }
}
