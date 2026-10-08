package uz.horecaos.platform.catalog.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMxikImportStore;

/**
 * The daily check that keeps the national list from quietly being absent or old (ADR 0173 Decision 9).
 *
 * <p>An absent list silences the validator's unknown-code finding by design, so without a check the platform
 * would go on judging every classification correct against a list nobody loaded. This raises an incident on the
 * ADR 0085 control-plane surface when the list is empty while any tenant has classified a node, and when it is
 * older than {@code catalog.mxik_reference_max_age_days}. One incident per class and subject; a successful
 * import resolves them.
 */
@Component
@ConditionalOnProperty(name = "horecaos.catalog.mxik.freshness.enabled", havingValue = "true", matchIfMissing = true)
public class MxikReferenceFreshnessSweeper {

    private static final Logger log = LoggerFactory.getLogger(MxikReferenceFreshnessSweeper.class);

    private final MxikReferenceImportService imports;
    private final JdbcMxikImportStore store;

    public MxikReferenceFreshnessSweeper(MxikReferenceImportService imports, JdbcMxikImportStore store) {
        this.imports = imports;
        this.store = store;
    }

    @Scheduled(
            initialDelayString = "${horecaos.catalog.mxik.freshness.initial-delay:PT10M}",
            fixedDelayString = "${horecaos.catalog.mxik.freshness.interval:PT24H}")
    public void check() {
        try {
            imports.checkFreshness(store.anyTenantHasClassifiedNode())
                    .ifPresent(eventClass -> log.warn("ИКПУ/MXIK reference freshness: {}", eventClass));
        } catch (RuntimeException failure) {
            // A check that cannot run must not take the scheduler thread down with it; the next day tries again.
            log.error("The ИКПУ/MXIK freshness check failed", failure);
        }
    }
}
