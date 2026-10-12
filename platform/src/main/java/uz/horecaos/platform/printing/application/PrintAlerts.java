package uz.horecaos.platform.printing.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.JobRow;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.WaitingPrinter;

/**
 * The two things an operator watches in printing, raised as ADR 0058 operations alerts (ADR 0154, "Events,
 * alerts and metrics"): a branch printer that is not answering while a kitchen ticket waits on it, and a
 * job that used all its attempts.
 *
 * <p>The event class names are the closed {@code TelegramEventClass} values (V0522), each its own semantic
 * template key whose wording the tenant authors. The variables are ids, a document type and a stable code --
 * never a dish, a note or a figure (ADR 0029). The idempotency key base names the printer or the job and the
 * episode (the status change, the job id), so a replayed trigger reaches a chat once and a later outage
 * alerts again.
 */
@Component
public class PrintAlerts {

    public static final String PRINTER_OFFLINE = "PRINTER_OFFLINE";
    public static final String PRINT_JOB_DEAD = "PRINT_JOB_DEAD";

    private static final String PRINTER_SUBJECT = "Printer";
    private static final String JOB_SUBJECT = "PrintJob";

    /** A branch that has not looked at a dead job by tomorrow has been told twice. */
    private static final Duration EXPIRY = Duration.ofDays(1);

    private final OperationsAlertPort alerts;

    public PrintAlerts(OperationsAlertPort alerts) {
        this.alerts = alerts;
    }

    public void jobDead(JobRow job) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("documentType", job.documentType().name());
        variables.put(
                "failureCode",
                job.failureCode() == null ? "UNKNOWN" : job.failureCode().name());
        variables.put("attempts", Integer.toString(job.attempt()));
        alerts.fanOut(
                job.tenantId(),
                job.brandId(),
                job.locationId(),
                PRINT_JOB_DEAD,
                PRINT_JOB_DEAD,
                JOB_SUBJECT,
                job.id(),
                null,
                "%s:%s:%s".formatted(PRINT_JOB_DEAD, JOB_SUBJECT, job.id()),
                variables,
                EXPIRY);
    }

    public void printerOffline(WaitingPrinter printer) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("printerStatus", printer.status());
        UUID printerId = printer.printerId();
        alerts.fanOut(
                printer.tenantId(),
                printer.brandId(),
                printer.locationId(),
                PRINTER_OFFLINE,
                PRINTER_OFFLINE,
                PRINTER_SUBJECT,
                printerId,
                null,
                "%s:%s:%s:%d"
                        .formatted(
                                PRINTER_OFFLINE,
                                PRINTER_SUBJECT,
                                printerId,
                                printer.statusAt().toEpochMilli()),
                variables,
                EXPIRY);
    }
}
