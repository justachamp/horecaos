package uz.horecaos.platform.iam.application.terminals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.terminals.TerminalAlert;
import uz.horecaos.platform.iam.api.terminals.TerminalServiceHours;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcTerminalStore;

/**
 * The two housekeeping jobs of the terminals (ADR 0155).
 *
 * <p><b>Unseen.</b> A kitchen tablet that a person must unlock and that has gone quiet during service is the only urgent
 * row the Terminals screen has, so the platform says so to the chats that subscribed to {@code TERMINAL_DEVICE_UNSEEN}. It
 * is an operations alert and never a page: the alert names the device by the label the tenant chose and the instant it was
 * last heard from, and nobody else. "During service" is the branch's own schedule, asked of {@link TerminalServiceHours};
 * with no answer available the monitor assumes the branch is open, because a missed alert at the pass is worse than an
 * alert at midnight.
 *
 * <p>The monitor re-reads the whole set each minute and remembers, in memory, which quiet episode it has already raised so
 * that a minute later it stays silent. The memory is an optimisation and not the guard: the alert's idempotency key names
 * the device and the last-seen instant, so a restart that forgets raises the same alert once more and the fan-out delivers
 * it once. A device that returns and goes quiet again has a new last-seen instant, hence a new episode, hence a new alert.
 *
 * <p><b>Retention.</b> Unlock sessions and spent activation codes are kept for the audit window and then removed in
 * batches, each under its own statement. The durable record of who did what is the ADR 0027 audit fact, which this does not
 * touch; these rows are the working state that explains the fact.
 *
 * <p>Neither job runs on the request path, and neither is a correctness control: a session is judged live by its own
 * deadlines on every request, whether or not the purge ran.
 */
@Component
@ConditionalOnProperty(name = "horecaos.terminals.maintenance.enabled", havingValue = "true", matchIfMissing = true)
public class TerminalMaintenance {

    private static final Logger log = LoggerFactory.getLogger(TerminalMaintenance.class);

    /** The audit window: older sessions and spent codes are removed (ADR 0155, Decision 7). */
    static final Duration RETENTION = Duration.ofDays(400);

    private final JdbcTerminalStore store;
    private final ObjectProvider<TerminalServiceHours> serviceHours;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration unseenAfter;

    /** Device to the last-seen instant already alerted on: the in-memory half of "once per episode". */
    private final Map<UUID, Instant> alerted = new ConcurrentHashMap<>();

    public TerminalMaintenance(
            JdbcTerminalStore store,
            ObjectProvider<TerminalServiceHours> serviceHours,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${horecaos.terminals.unseen-after:PT5M}") Duration unseenAfter) {
        this.store = store;
        this.serviceHours = serviceHours;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.unseenAfter = unseenAfter;
    }

    @Scheduled(
            initialDelayString = "${horecaos.terminals.unseen-check.initial-delay:PT2M}",
            fixedDelayString = "${horecaos.terminals.unseen-check.interval:PT1M}")
    public void watchUnseen() {
        Instant now = clock.instant();
        var quiet = store.unseenRequiredDevices(now.minus(unseenAfter));
        // A device that came back is forgotten, so its next quiet spell is a new episode.
        var stillQuiet =
                quiet.stream().map(JdbcTerminalStore.UnseenDevice::deviceId).toList();
        alerted.keySet().removeIf(id -> !stillQuiet.contains(id));

        TerminalServiceHours hours = serviceHours.getIfAvailable();
        for (JdbcTerminalStore.UnseenDevice device : quiet) {
            if (device.lastSeenAt().equals(alerted.get(device.deviceId()))) {
                continue;
            }
            if (hours != null && !hours.inService(device.tenantId(), device.brandId(), device.locationId(), now)) {
                continue;
            }
            alerted.put(device.deviceId(), device.lastSeenAt());
            // The trigger runs before this transaction commits, which is how every operations alert in the platform is
            // raised: an event published with no transaction open reaches no listener, and the alert is lost without
            // a trace. One transaction per device, so one failing fan-out cannot swallow the others.
            transactions.executeWithoutResult(status -> events.publishEvent(new TerminalAlert(
                    TerminalAlert.DEVICE_UNSEEN,
                    device.tenantId(),
                    device.brandId(),
                    device.locationId(),
                    device.deviceId(),
                    "Device",
                    device.deviceId(),
                    device.displayName(),
                    "",
                    "",
                    device.lastSeenAt(),
                    Ids.newId())));
            // A count and a device id: the label is the tenant's, the instant is the device's.
            log.info("Terminal {} has not been seen since {}", device.deviceId(), device.lastSeenAt());
        }
    }

    @Scheduled(
            initialDelayString = "${horecaos.terminals.purge.initial-delay:PT10M}",
            fixedDelayString = "${horecaos.terminals.purge.interval:PT6H}")
    public void purge() {
        Instant horizon = clock.instant().minus(RETENTION);
        int sessions = store.purgeSessionsOpenedBefore(horizon);
        int activations = store.purgeActivationsCreatedBefore(horizon);
        if (sessions > 0 || activations > 0) {
            log.info("Terminal retention: removed {} sessions and {} spent codes", sessions, activations);
        }
    }
}
