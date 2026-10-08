package uz.horecaos.platform.printing.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.printing.application.PrintDocumentFactory.Built;
import uz.horecaos.platform.printing.domain.JobStatus;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.JobRow;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.PrinterRow;
import uz.horecaos.platform.printing.render.EscPosEncoder;

/**
 * Makes a job row from a built document (ADR 0154, Specification): the document stored envelope-encrypted
 * (ADR 0029) because a line's free-text note can hold anything a customer typed, its digest kept in the
 * clear, and the resolved language, roll, profile version and render protocol persisted so a reprint
 * explains itself.
 */
@Component
public class PrintJobFactory {

    /** Every document that is not a kitchen ticket waits this long for a printer before it is dropped. */
    static final Duration DEFAULT_EXPIRY = Duration.ofHours(24);

    /** An upper bound on how long a document is kept until the job ends and sets the real schedule. */
    static final Duration PROVISIONAL_RETENTION = Duration.ofDays(30);

    /** Erased this long after a job reaches a terminal state, and after a dead one. */
    public static final Duration RETENTION_AFTER_END = Duration.ofDays(7);

    public static final Duration RETENTION_AFTER_DEAD = Duration.ofDays(30);

    static final String TABLE = "printing.print_jobs";
    static final String COLUMN = "protected_document";

    private final FieldProtection protection;
    private final PrintDocumentCodec codec;

    public PrintJobFactory(FieldProtection protection, PrintDocumentCodec codec) {
        this.protection = protection;
        this.codec = codec;
    }

    /** The shape of one job to make, before its identity and its stored document. */
    public record Spec(
            PrinterRow printer,
            @Nullable UUID routeId,
            PrintDocumentType type,
            String sourceType,
            @Nullable UUID sourceId,
            int sourceRevision,
            @Nullable UUID stationId,
            int copyNo,
            String origin,
            @Nullable String requestedBy,
            @Nullable String reasonCode,
            @Nullable UUID reprintOf,
            JobStatus status,
            Duration expiry) {}

    public JobRow create(Spec spec, Built built, Instant now) {
        UUID id = Ids.newId();
        String json = codec.toJson(built.document());
        String stored = protection
                .protect(
                        spec.printer().tenantId(),
                        DataClass.PERSONAL,
                        new FieldProtection.RecordRef(TABLE, COLUMN, id),
                        json)
                .serialize();
        boolean handedOff = spec.status() == JobStatus.HANDED_OFF;
        return new JobRow(
                id,
                spec.printer().tenantId(),
                spec.printer().brandId(),
                spec.printer().locationId(),
                spec.printer().id(),
                spec.routeId(),
                spec.type(),
                spec.sourceType(),
                spec.sourceId(),
                spec.sourceRevision(),
                spec.stationId(),
                spec.copyNo(),
                spec.origin(),
                spec.requestedBy(),
                spec.reasonCode(),
                spec.reprintOf(),
                spec.status(),
                0,
                now,
                now.plus(spec.expiry()),
                null,
                null,
                null,
                null,
                built.document().locale(),
                spec.printer().tapeWidthMm(),
                built.profileVersion(),
                EscPosEncoder.PROTOCOL,
                stored,
                codec.digest(built.document()),
                handedOff ? now.plus(RETENTION_AFTER_END) : now.plus(PROVISIONAL_RETENTION),
                now,
                null,
                1,
                now,
                now);
    }
}
