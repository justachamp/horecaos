package uz.horecaos.platform.telemetry.infrastructure.realtime;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.iam.api.GrantChanged;

/**
 * The clock and the revocation hook behind {@link SseStreamRegistry}
 * (ADR 0045).
 *
 * <p>Separated from the registry so the registry has no schedule and no Spring
 * event of its own, and can therefore be driven instant by instant from a test.
 * Everything interesting about a stream is a question of what is written and
 * when, and a test that has to sleep to ask it is slow and flaky in the same
 * change.
 *
 * <p>The tick is 50 ms because the tightest cadence cap in the catalogue is
 * 250 ms and ADR 0045 budgets 100 ms from replica to browser frame. A one-second
 * tick would spend the whole budget on granularity; a one-millisecond tick would
 * spend a core on an empty map.
 *
 * <p>The grants listener fires {@code AFTER_COMMIT} rather than before, which is
 * the opposite of {@code GrantAuditListener} and is right for the opposite
 * reason: an audit fact belongs inside the transaction so a rolled-back grant
 * leaves no evidence, while closing a socket is an external effect that must not
 * happen for a grant change that then rolls back.
 */
@Component
@ConditionalOnProperty(name = "horecaos.realtime.streams.enabled", havingValue = "true", matchIfMissing = true)
public class RealtimeStreamMaintenance {

    private static final Logger log = LoggerFactory.getLogger(RealtimeStreamMaintenance.class);

    private final SseStreamRegistry registry;
    private final Clock clock;

    public RealtimeStreamMaintenance(SseStreamRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    @Scheduled(fixedRateString = "${horecaos.realtime.streams.tick:PT0.05S}")
    public void tick() {
        registry.tick(clock.instant());
    }

    /**
     * ADR 0033's grants invalidation, applied to held connections.
     *
     * <p>The named failure this exists for: a supervisor whose location scope is
     * revoked keeps watching that kitchen's queue until end of shift, because the
     * capability was checked once at connect and the connection outlived the
     * decision by eight hours.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGrantChanged(GrantChanged event) {
        int closed = registry.closeForPrincipal(event.principalSubject(), "GRANTS_CHANGED");
        if (closed > 0) {
            log.info(
                    "Closed {} streams after a grant change; the client reconnects and is "
                            + "re-authorized against its new grants",
                    closed);
        }
    }
}
