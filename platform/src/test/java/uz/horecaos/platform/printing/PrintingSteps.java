package uz.horecaos.platform.printing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.printing.application.PrintDocumentCodec;
import uz.horecaos.platform.printing.application.PrintJobFactory;
import uz.horecaos.platform.printing.domain.PrintDocument;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.JobRow;
import uz.horecaos.platform.tenancy.api.TenantId;

/** Small things every printing integration test does: read a stored document, make the fact an order confirmation is. */
final class PrintingSteps {

    private PrintingSteps() {}

    /** The stored document of a job, decrypted: what an artifact is rendered from. */
    static PrintDocument documentOf(JobRow job, FieldProtection protection, PrintDocumentCodec codec) {
        String stored = job.protectedDocument();
        if (stored == null) {
            throw new IllegalStateException("The document of job " + job.id() + " has been erased");
        }
        return codec.fromJson(protection.reveal(
                job.tenantId(),
                ProtectedValue.deserialize(stored),
                new FieldProtection.RecordRef(PrintJobFactory.TABLE, PrintJobFactory.COLUMN, job.id()),
                "TEST"));
    }

    /** The decrypted JSON text of a job's document, for a test that asks what words it holds. */
    static String jsonOf(JobRow job, FieldProtection protection, PrintDocumentCodec codec) {
        return codec.toJson(documentOf(job, protection, codec));
    }

    static OrderConfirmed confirmed(
            UUID tenantId, UUID brandId, UUID locationId, UUID orderId, int version, Instant at) {
        return new OrderConfirmed(
                UUID.randomUUID(),
                new TenantId(tenantId),
                orderId,
                at,
                brandId,
                locationId,
                "AUTO_CONFIRM",
                null,
                at,
                "UZS",
                0,
                "CONFIRMED",
                version);
    }

    static List<String> statuses(List<JobRow> jobs) {
        return jobs.stream().map(job -> job.status().name()).toList();
    }

    static @Nullable JobRow only(List<JobRow> jobs) {
        return jobs.size() == 1 ? jobs.getFirst() : null;
    }
}
