package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.commercial.api.ArrearsDirectory;
import uz.horecaos.platform.commercial.api.ArrearsDirectory.Arrear;
import uz.horecaos.platform.notifications.api.ControlPlaneAlert;
import uz.horecaos.platform.notifications.api.ControlPlaneAlertPort;

/**
 * Asks a person to review a tenant that has been past due too long (ADR 0089).
 *
 * <p>Raises a control-plane incident; it never moves the subscription. ADR
 * 0021 treats lateness as a conversation rather than a switch, so the
 * platform's part is to say when the conversation is due. A tenant still past
 * due after another interval gets a fresh incident, so an operator who closed
 * the first after talking to the owner is asked again only when the next
 * review falls due, not every hour.
 */
@Component
@ConditionalOnProperty(
        name = "horecaos.notifications.control-plane.arrears-review.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CommercialArrearsReviewSweeper {

    /** The event class a control-plane operator's tooling filters on. */
    public static final String COMMERCIAL_ARREARS_REVIEW = "COMMERCIAL_ARREARS_REVIEW";

    static final String SUBJECT_TYPE = "Subscription";

    private static final Logger log = LoggerFactory.getLogger(CommercialArrearsReviewSweeper.class);

    private final ArrearsDirectory arrears;
    private final ControlPlaneAlertPort controlPlaneAlerts;
    private final Clock clock;
    private final Duration reviewAfter;
    private final int batchSize;

    public CommercialArrearsReviewSweeper(
            ArrearsDirectory arrears,
            ControlPlaneAlertPort controlPlaneAlerts,
            Clock clock,
            @Value("${horecaos.notifications.control-plane.arrears-review.after:P14D}") Duration reviewAfter,
            @Value("${horecaos.notifications.control-plane.arrears-review.batch-size:200}") int batchSize) {
        if (reviewAfter.isNegative() || reviewAfter.isZero()) {
            throw new IllegalArgumentException("The arrears review interval must be positive");
        }
        this.arrears = arrears;
        this.controlPlaneAlerts = controlPlaneAlerts;
        this.clock = clock;
        this.reviewAfter = reviewAfter;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${horecaos.notifications.control-plane.arrears-review.initial-delay:PT2M}",
            fixedDelayString = "${horecaos.notifications.control-plane.arrears-review.interval:PT1H}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The arrears review sweep could not run", failure);
        }
    }

    /** @return how many subscriptions were raised for review on this pass */
    public int runOnce() {
        Instant now = clock.instant();
        List<Arrear> due = arrears.pastDueSince(now.minus(reviewAfter), batchSize);
        for (Arrear arrear : due) {
            long review = Duration.between(arrear.since(), now).dividedBy(reviewAfter);
            try {
                controlPlaneAlerts.raise(new ControlPlaneAlert(
                        COMMERCIAL_ARREARS_REVIEW,
                        SUBJECT_TYPE,
                        arrear.subscriptionId() + "/review-" + review,
                        variablesFor(arrear, now),
                        now));
            } catch (RuntimeException failure) {
                log.error("Could not raise the arrears review for subscription {}", arrear.subscriptionId(), failure);
            }
        }
        return due.size();
    }

    /** Identifiers and a day count: nothing about who at the tenant is involved. */
    static Map<String, String> variablesFor(Arrear arrear, Instant now) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("tenantId", arrear.tenantId().toString());
        variables.put("subscriptionId", arrear.subscriptionId().toString());
        variables.put(
                "pastDueDays",
                String.valueOf(Duration.between(arrear.since(), now).toDays()));
        return variables;
    }
}
