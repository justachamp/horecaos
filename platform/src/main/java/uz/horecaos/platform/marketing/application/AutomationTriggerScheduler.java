package uz.horecaos.platform.marketing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps every active automation rule swept (gap-map row 6.5, ADR 0044
 * Triggers). One {@code @Scheduled} method for all three sweeps, the same
 * shape {@code CampaignExpansionScheduler#sweepOnce} already gives one
 * concern's worth of work — each sweep is caught independently so one
 * trigger kind's failure does not stop the other two.
 *
 * <p>Guard-key idempotency ({@code marketing.automation_runs}) is what makes
 * running this more often than the ADR's own "daily" cadence for BIRTHDAY and
 * INACTIVITY safe rather than wasteful-and-wrong: a BIRTHDAY rule's guard is a
 * calendar year and an INACTIVITY rule's is a {@code cooldown_days} bucket, so
 * an hourly tick re-evaluates the same candidates many times and fires each
 * one exactly once. The default interval is deliberately wider than {@link
 * uz.horecaos.platform.marketing.application.CampaignExpansionScheduler}'s own
 * five seconds — a trigger sweep is not pacing an approved send, and re-scanning
 * every active rule's full candidate set every five seconds would be a
 * meaningful, pointless load on {@code marketing.customer_metrics}.
 */
@Component
public class AutomationTriggerScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutomationTriggerScheduler.class);

    private final AutomationSweepService sweeps;

    public AutomationTriggerScheduler(AutomationSweepService sweeps) {
        this.sweeps = sweeps;
    }

    @Scheduled(
            initialDelayString = "${horecaos.marketing.automations.initial-delay:PT30S}",
            fixedDelayString = "${horecaos.marketing.automations.sweep-interval:PT1H}")
    public void sweepOnce() {
        try {
            sweeps.sweepBirthday();
        } catch (RuntimeException failure) {
            log.error("The BIRTHDAY automation sweep could not run", failure);
        }
        try {
            sweeps.sweepInactivity();
        } catch (RuntimeException failure) {
            log.error("The INACTIVITY automation sweep could not run", failure);
        }
        try {
            sweeps.sweepCartAbandonment();
        } catch (RuntimeException failure) {
            log.error("The CART_ABANDONMENT automation sweep could not run", failure);
        }
    }
}
