package uz.horecaos.platform.notifications.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily clock behind {@link FiscalReferenceAlerts#check()} (ADR 0173 Decision 9): the national list is
 * checked once a day, after a start-up delay, and a check that cannot run does not take the scheduler thread
 * with it.
 */
@Component
@ConditionalOnProperty(name = "horecaos.catalog.mxik.freshness.enabled", havingValue = "true", matchIfMissing = true)
public class FiscalReferenceFreshnessSweeper {

    private static final Logger log = LoggerFactory.getLogger(FiscalReferenceFreshnessSweeper.class);

    private final FiscalReferenceAlerts alerts;

    public FiscalReferenceFreshnessSweeper(FiscalReferenceAlerts alerts) {
        this.alerts = alerts;
    }

    @Scheduled(
            initialDelayString = "${horecaos.catalog.mxik.freshness.initial-delay:PT10M}",
            fixedDelayString = "${horecaos.catalog.mxik.freshness.interval:PT24H}")
    public void check() {
        try {
            alerts.check().ifPresent(eventClass -> log.warn("ИКПУ/MXIK reference freshness: {}", eventClass));
        } catch (RuntimeException failure) {
            // The next day tries again.
            log.error("The ИКПУ/MXIK freshness check failed", failure);
        }
    }
}
