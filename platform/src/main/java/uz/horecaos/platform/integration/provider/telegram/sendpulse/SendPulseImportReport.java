package uz.horecaos.platform.integration.provider.telegram.sendpulse;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.integration.provider.telegram.sendpulse.JdbcSendPulseImportStore.RunCounts;

/**
 * The full report one import run produces (ADR 0059 stage 3) — exactly what
 * a dry run returns before anything is written, and what a real run's own
 * outcome was, in the same shape either way.
 */
public record SendPulseImportReport(UUID runId, boolean dryRun, RunCounts counts, List<RowEntry> rows) {

    public record RowEntry(
            int rowNumber,
            @Nullable Long chatId,
            @Nullable Boolean subscribed,
            String outcome,
            @Nullable UUID customerAccountId,
            @Nullable String rejectReason) {}
}
