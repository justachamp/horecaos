package uz.horecaos.platform.notifications.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.notifications.api.DispatchOutcome;
import uz.horecaos.platform.notifications.api.ReceiptEnablement;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore.ReceiptCandidate;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore.UnknownStateAttempt;

/**
 * The two sweeps ADR 0146 Decision 5 allows over delivery receipts, and the one
 * thing neither does: they never resend.
 *
 * <p><strong>"No receipt" is a state, and only where a receipt can arrive.</strong>
 * {@link #markNoReceipt} walks the provider types whose receipt endpoint is enabled
 * ({@link ReceiptEnablement}) and marks an accepted attempt that nothing has
 * reported on, once the configured window has passed. A provider type with no
 * receipt source is never visited, so a VAS attempt stays "handed to the operator"
 * however old it is: with nothing listening, the absence of a receipt says nothing,
 * and CDMA subscribers produce none at all. Reporting silence as failure would
 * misreport successful deliveries and invite the retry that costs a second message.
 *
 * <p><strong>The pull is narrow on purpose.</strong> {@link #pullUnknown} asks the
 * gateway only about attempts the provider itself reported as unresolved, because
 * every lookup decrypts a number and chasing "delivered" for every message would
 * turn a best-effort signal into a standing personal-data workload.
 *
 * <p>Both are best effort and both are safe to run twice: the writes are guarded on
 * the attempt's state, so a receipt that raced a sweep wins.
 */
@Component
public class SmsReceiptSweeper {

    private static final Logger log = LoggerFactory.getLogger(SmsReceiptSweeper.class);
    private static final int BATCH = 200;

    private final JdbcDeliveryReceiptStore store;
    private final ReceiptEnablement enablement;
    private final NotificationDispatchService dispatch;
    private final DeliveryReceiptService receipts;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Duration noReceiptWindow;
    private final Duration unknownSettleDelay;

    public SmsReceiptSweeper(
            JdbcDeliveryReceiptStore store,
            ReceiptEnablement enablement,
            NotificationDispatchService dispatch,
            DeliveryReceiptService receipts,
            MeterRegistry meters,
            Clock clock,
            // ADR 0146 open input 8, closed on its proposed default.
            @Value("${horecaos.sms.receipts.no-receipt-window:PT24H}") Duration noReceiptWindow,
            @Value("${horecaos.sms.receipts.unknown-settle-delay:PT1H}") Duration unknownSettleDelay) {
        this.store = store;
        this.enablement = enablement;
        this.dispatch = dispatch;
        this.receipts = receipts;
        this.meters = meters;
        this.clock = clock;
        this.noReceiptWindow = noReceiptWindow;
        this.unknownSettleDelay = unknownSettleDelay;
    }

    @Scheduled(
            initialDelayString = "${horecaos.sms.receipts.sweep-initial-delay:PT2M}",
            fixedDelayString = "${horecaos.sms.receipts.sweep-interval:PT15M}")
    public void sweepOnce() {
        try {
            markNoReceipt();
        } catch (RuntimeException failure) {
            log.error("The no-receipt sweep could not run", failure);
        }
        try {
            pullUnknown();
        } catch (RuntimeException failure) {
            log.error("The unknown-state sweep could not run", failure);
        }
    }

    /** @return how many attempts were marked "no receipt" in this pass */
    public int markNoReceipt() {
        Instant now = clock.instant();
        int marked = 0;
        for (String providerType : enablement.enabledProviderTypes()) {
            for (ReceiptCandidate candidate : store.awaitingReceipt(providerType, now.minus(noReceiptWindow), BATCH)) {
                if (store.markNoReceipt(candidate.tenantId(), candidate.attemptId(), now)) {
                    meters.counter("horecaos.sms.no_receipt", "provider", providerType)
                            .increment();
                    marked++;
                }
            }
        }
        return marked;
    }

    /** @return how many attempts the pull advanced in this pass */
    public int pullUnknown() {
        Instant now = clock.instant();
        int advanced = 0;
        for (String providerType : enablement.enabledProviderTypes()) {
            for (UnknownStateAttempt attempt :
                    store.reportedUnknown(providerType, now.minus(unknownSettleDelay), BATCH)) {
                if (attempt.externalMessageId() == null || attempt.providerBindingId() == null) {
                    continue;
                }
                Optional<DispatchOutcome> answer;
                try {
                    answer = dispatch.pullState(attempt);
                } catch (RuntimeException failure) {
                    log.warn("Could not pull the state of attempt {}: {}", attempt.attemptId(), failure.getClass());
                    continue;
                }
                if (answer.isEmpty() || answer.get().status() != DispatchOutcome.Status.ACCEPTED) {
                    continue;
                }
                DispatchOutcome outcome = answer.get();
                String normalized = outcome.normalizedStatus();
                if (normalized == null) {
                    continue;
                }
                var disposition = receipts.applyPulled(
                        attempt.tenantId(),
                        attempt.providerBindingId(),
                        attempt.externalMessageId(),
                        normalized,
                        outcome.providerStatus(),
                        outcome.hardBounce());
                if (disposition
                        == uz.horecaos.platform.notifications.api.DeliveryReceiptPort.ReceiptDisposition.APPLIED) {
                    advanced++;
                }
            }
        }
        return advanced;
    }
}
