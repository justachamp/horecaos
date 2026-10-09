package uz.horecaos.platform.kitchen.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.kitchen.domain.KitchenPaidHoldPolicy;

/**
 * How the owner publishes {@code kitchen.paid_hold} at platform scope (ADR 0168 Decision 8): by starting the
 * application once with {@code horecaos.kitchen.paid-hold.platform-mode} set to {@code HOLD_UNTIL_PAID}, {@code
 * SHADOW} or {@code OFF}, and {@code horecaos.kitchen.paid-hold.platform-reason} saying why.
 *
 * <p>The record adds no platform-scoped endpoint, and the platform document is the one thing a tenant's own
 * editor cannot write, so this is the operator-run publication it asks for: it goes through {@link
 * KitchenPaidHoldPolicies#author} like every other write, so it is validated, versioned and audited (one {@code
 * kitchen.paid_hold.published} fact naming the diff and the reason) exactly as a tenant's own publication is.
 * The owner reads the fortnight of {@code kitchen.payment_hold{outcome=shadow_would_hold}} first, then sets
 * the property for one start-up and removes it afterwards: publishing is a start, not a setting.
 *
 * <p>Absent the mode property this bean does not exist, so nothing is published and a restart changes
 * nothing. Present, a start-up with a document already at that mode and minutes publishes nothing again, so
 * a restart that forgot to remove the property does not stack versions.
 */
@Component
@ConditionalOnProperty(name = "horecaos.kitchen.paid-hold.platform-mode")
public class KitchenPaidHoldPlatformPublisher {

    private static final Logger log = LoggerFactory.getLogger(KitchenPaidHoldPlatformPublisher.class);

    /** The actor of the publication: an operator-run job, never a person's session. */
    public static final String ACTOR = "kitchen-paid-hold-platform-publication";

    private final KitchenPaidHoldPolicies policies;
    private final String mode;
    private final int overdueAfterMinutes;
    private final String reason;

    public KitchenPaidHoldPlatformPublisher(
            KitchenPaidHoldPolicies policies,
            @Value("${horecaos.kitchen.paid-hold.platform-mode}") String mode,
            @Value("${horecaos.kitchen.paid-hold.platform-overdue-after-minutes:"
                            + KitchenPaidHoldPolicy.DEFAULT_OVERDUE_AFTER_MINUTES
                            + "}")
                    int overdueAfterMinutes,
            @Value("${horecaos.kitchen.paid-hold.platform-reason:}") String reason) {
        this.policies = policies;
        this.mode = mode;
        this.overdueAfterMinutes = overdueAfterMinutes;
        this.reason = reason;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            Outcome outcome = publish();
            log.info(
                    "The platform paid-hold policy is {} ({}); remove horecaos.kitchen.paid-hold.platform-mode",
                    mode,
                    outcome);
        } catch (RuntimeException failure) {
            // A refused publication (an unknown mode, no reason) must not stop the application starting: the
            // platform keeps the document it had, and the operator reads this line and tries again.
            log.error("The platform paid-hold policy could not be published as {}: {}", mode, failure.getMessage());
        }
    }

    /**
     * Publishes the configured document at platform scope unless that is already what is in force there.
     *
     * @throws IllegalArgumentException when the mode is not one of the three, or no reason was given
     */
    public Outcome publish() {
        KitchenPaidHoldPolicy.Mode requested;
        try {
            requested = KitchenPaidHoldPolicy.Mode.valueOf(mode.trim());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "horecaos.kitchen.paid-hold.platform-mode must be SHADOW, HOLD_UNTIL_PAID or OFF, was \"%s\""
                            .formatted(mode));
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException(
                    "horecaos.kitchen.paid-hold.platform-reason must say why the platform policy is being published");
        }
        KitchenPaidHoldPolicy document =
                new KitchenPaidHoldPolicy(KitchenPaidHoldPolicy.SCHEMA, requested, overdueAfterMinutes);

        ResourceScope platform = ResourceScope.platform();
        PolicyEditorView<KitchenPaidHoldPolicy> current = policies.view(platform);
        if (current.versionAtScope() > 0 && current.document().equals(document)) {
            return Outcome.UNCHANGED;
        }
        policies.author(platform, document, current.versionAtScope(), ActorRef.systemJob(ACTOR), reason.trim());
        return Outcome.PUBLISHED;
    }

    /** What a start-up did. */
    public enum Outcome {
        PUBLISHED,
        UNCHANGED
    }
}
