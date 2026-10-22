package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcExportRequestStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcExportRequestStore.StoredBundle;
import uz.horecaos.platform.media.api.ObjectStorage;

/**
 * Takes a data-subject export out of object storage when its time is up (ADR 0029).
 *
 * <p>The bundle is the one place a customer's data is gathered into a single file, so it is not kept: each request carries
 * the day it expires ({@code customers.dsar_bundle_retention_days} after it was produced), and this removes the object and
 * records that it is gone. The request row stays, as the record that an export was asked for, produced and removed. A
 * request whose object cannot be deleted this pass keeps its row as it was and is tried again on the next, so a store that
 * is briefly unreachable delays the removal and never loses the fact that it is owed.
 *
 * <p>A legal hold does not keep a bundle. The hold keeps the customer's own records, which the bundle only copies; a
 * copy that outlived its expiry would be the one place the hold's subject is gathered into a file, and the file is
 * regenerated on a new request.
 *
 * <p>Destructive, so it can be started in report-only mode ({@code horecaos.customers.dsar-bundle.report-only}): it logs how
 * many bundles it would remove and removes none (ADR 0029). Counts are logged; no request, tenant or customer is named.
 */
@Component
public class ExportBundleSweeper {

    private static final Logger log = LoggerFactory.getLogger(ExportBundleSweeper.class);

    private final JdbcExportRequestStore requests;
    private final ObjectStorage storage;
    private final Clock clock;
    private final int batchSize;
    private final boolean reportOnly;

    public ExportBundleSweeper(
            JdbcExportRequestStore requests,
            ObjectStorage storage,
            Clock clock,
            @Value("${horecaos.customers.dsar-bundle.batch-size:100}") int batchSize,
            @Value("${horecaos.customers.dsar-bundle.report-only:false}") boolean reportOnly) {
        this.requests = requests;
        this.storage = storage;
        this.clock = clock;
        this.batchSize = batchSize;
        this.reportOnly = reportOnly;
    }

    @Scheduled(
            initialDelayString = "${horecaos.customers.dsar-bundle.initial-delay:PT3M}",
            fixedDelayString = "${horecaos.customers.dsar-bundle.interval:PT1H}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The data-subject export sweep could not run", failure);
        }
    }

    /** @return how many bundles this pass removed, for a deterministic test */
    public int runOnce() {
        Instant now = clock.instant();
        List<StoredBundle> due = requests.expiredBundles(now, batchSize);
        if (reportOnly) {
            if (!due.isEmpty()) {
                log.info(
                        "Export bundle sweep is report-only: {} bundles are past their expiry and would be removed",
                        due.size());
            }
            return 0;
        }
        int removed = 0;
        for (StoredBundle bundle : due) {
            try {
                storage.delete(bundle.bucket(), bundle.key());
            } catch (RuntimeException unreachable) {
                // Tried again next pass; the row still says the object is there, which is true.
                log.warn("An expired export bundle could not be removed from object storage; it will be retried");
                continue;
            }
            if (requests.markBundleRemoved(bundle.tenantId(), bundle.requestId(), now) == 1) {
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Export bundle sweep: {} expired bundles removed", removed);
        }
        return removed;
    }
}
