package uz.qoida.platform.reporting.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import uz.qoida.platform.reporting.domain.MetricDefinition;
import uz.qoida.platform.reporting.domain.MetricRegistry;
import uz.qoida.platform.reporting.infrastructure.persistence.JdbcReportingStore;

/**
 * Mirrors the code-owned registry into {@code reporting.metric_definitions} and
 * refuses to start when the two disagree (ADR 0043).
 *
 * <p>The table is not a second definition. It exists so a finance signature has
 * somewhere to live and so a report can name the exact definition it used, and
 * everything except the signature is written once and never updated.
 *
 * <p>Drift is a startup failure, not a repair. ADR 0043 says a definition change
 * is a new version, so a stored row whose digest no longer matches the code means
 * somebody edited a definition in place — and quietly rewriting the row would
 * leave a signature standing over words finance never read, which is worse than
 * an outage because nothing about it is visible afterwards.
 */
@Component
public class MetricDefinitionSynchronizer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MetricDefinitionSynchronizer.class);

    private final JdbcReportingStore store;

    public MetricDefinitionSynchronizer(JdbcReportingStore store) {
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        synchronizeAll();
    }

    /** Package-visible so a test can run it without a Spring context. */
    public void synchronizeAll() {
        int inserted = 0;
        for (MetricDefinition definition : MetricRegistry.all()) {
            if (synchronize(definition)) {
                inserted++;
            }
        }
        if (inserted > 0) {
            log.info("Recorded {} metric definitions from the code-owned registry", inserted);
        }
    }

    private boolean synchronize(MetricDefinition definition) {
        var stored = store.findStoredMetric(definition.id().name(), definition.id().version());
        if (stored.isEmpty()) {
            store.insertDefinitionIfAbsent(definition);
            return true;
        }
        if (!stored.get().digest().equals(definition.digest())) {
            throw new MetricDefinitionDriftException(definition.id().code(),
                    stored.get().signedBy() != null);
        }
        return false;
    }

    /** A stored definition no longer says what the code says. */
    public static final class MetricDefinitionDriftException extends IllegalStateException {

        public MetricDefinitionDriftException(String metricCode, boolean signed) {
            super(("Metric %s was edited in place. ADR 0043: a definition change is a new "
                    + "version, so cut a v%s rather than changing this one.%s")
                    .formatted(metricCode, nextVersion(metricCode),
                            signed ? " The stored row carries a finance signature over the "
                                    + "previous wording." : ""));
        }

        private static String nextVersion(String metricCode) {
            int marker = metricCode.lastIndexOf(".v");
            return marker < 0
                    ? "2"
                    : String.valueOf(Integer.parseInt(metricCode.substring(marker + 2)) + 1);
        }
    }
}
