package uz.horecaos.platform.marketing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps every sending scenario's due guests decided (ADR 0112).
 *
 * <p>The same shape as {@code CampaignExpansionScheduler}: one {@code @Scheduled} method,
 * each scenario caught independently so one scenario's fault does not stop another's, and
 * a short interval because a wait is a duration measured in minutes or longer and a due
 * guest decided a few seconds late is not late in any way a guest could tell. Running it
 * more often than a wait elapses is harmless: a guest whose wait has not elapsed is not
 * selected, and one already decided has moved on.
 */
@Component
public class ScenarioScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScenarioScheduler.class);

    private final ScenarioRunner runner;

    public ScenarioScheduler(ScenarioRunner runner) {
        this.runner = runner;
    }

    @Scheduled(
            initialDelayString = "${horecaos.marketing.scenarios.initial-delay:PT20S}",
            fixedDelayString = "${horecaos.marketing.scenarios.sweep-interval:PT15S}")
    public void sweepOnce() {
        try {
            runner.sweepOnce();
        } catch (RuntimeException failure) {
            log.error("The scenario sweep could not run", failure);
        }
    }
}
