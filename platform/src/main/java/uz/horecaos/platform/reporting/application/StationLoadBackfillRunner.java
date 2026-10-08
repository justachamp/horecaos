package uz.horecaos.platform.reporting.application;

import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * How an operator starts {@link StationLoadBackfill} (ADR 0163): by setting {@code
 * horecaos.reporting.station-load-backfill.cutover} to the first business date the live close wrote the fact,
 * for the one start-up that should run it, optionally with {@code .tenant-id} to run a single tenant.
 *
 * <p>Absent the property this bean does not exist, so nothing is scheduled and nothing runs on a restart.
 * The run happens on its own thread after the application is ready, because eight weeks of dates for every
 * tenant is minutes of work that must not hold up the health check, and the property is removed afterwards:
 * the backfill is a start, not a setting.
 */
@Component
@ConditionalOnProperty(name = "horecaos.reporting.station-load-backfill.cutover")
public class StationLoadBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(StationLoadBackfillRunner.class);

    private final StationLoadBackfill backfill;
    private final LocalDate cutover;
    private final String tenantId;

    public StationLoadBackfillRunner(
            StationLoadBackfill backfill,
            @Value("${horecaos.reporting.station-load-backfill.cutover}") LocalDate cutover,
            @Value("${horecaos.reporting.station-load-backfill.tenant-id:}") String tenantId) {
        this.backfill = backfill;
        this.cutover = cutover;
        this.tenantId = tenantId;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread worker = new Thread(this::run, "station-load-backfill");
        worker.setDaemon(true);
        worker.start();
    }

    void run() {
        try {
            if (tenantId.isBlank()) {
                backfill.runAll(cutover);
            } else {
                backfill.run(UUID.fromString(tenantId.trim()), cutover);
            }
            log.info("The station-load backfill before {} has finished; remove the property", cutover);
        } catch (RuntimeException failure) {
            log.error("The station-load backfill before {} failed", cutover, failure);
        }
    }
}
