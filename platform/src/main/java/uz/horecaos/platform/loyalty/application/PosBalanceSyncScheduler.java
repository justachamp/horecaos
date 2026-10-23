package uz.horecaos.platform.loyalty.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.loyalty.api.LoyaltyConfigurationKeys;
import uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort;
import uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort.TenantBinding;
import uz.horecaos.platform.loyalty.application.PosBalanceSyncService.Mode;
import uz.horecaos.platform.loyalty.application.PosBalanceSyncService.RunKind;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcPosBalanceSyncStore;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * Drives the till-balance sync on a clock (ADR 0171 Decision 3, 4 and 6): a pull every {@code
 * loyalty.pos_sync.pull_interval_seconds} for a binding whose mode says so, a push sweep of its own every {@code
 * loyalty.pos_sync.push_interval_seconds} (so a platform movement reaches the till within a minute and not within five),
 * and a reconciliation once a day, after the business day has closed.
 *
 * <p>A one-minute tick that asks each syncing binding whether it is due, rather than a timer sized to the policy, so the
 * interval is read from ADR 0030 on every tick and a change to it applies without a restart. Nothing runs for a vendor
 * that cannot sync: {@link PosLoyaltyBalancePort#syncingBindings()} lists only bindings that are active, capable and
 * enabled. A binding in {@code REPORT_ONLY} is reconciled and nothing else -- it applies nothing and pushes nothing.
 *
 * <p>One failing binding never stops the others, and nothing here names a customer in a log.
 */
@Component
@ConditionalOnProperty(name = "horecaos.loyalty.pos-sync.enabled", havingValue = "true", matchIfMissing = true)
public class PosBalanceSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(PosBalanceSyncScheduler.class);

    static final String JOB = "pos-sync-scheduler";

    /** The reconciliation is after the day-close hour, so the figures it compares are a closed day's. */
    static final int RECONCILE_AFTER_LOCAL_HOUR = 3;

    private final PosLoyaltyBalancePort port;
    private final PosBalanceSyncService sync;
    private final JdbcPosBalanceSyncStore store;
    private final ConfigurationResolver configuration;
    private final Clock clock;

    public PosBalanceSyncScheduler(
            PosLoyaltyBalancePort port,
            PosBalanceSyncService sync,
            JdbcPosBalanceSyncStore store,
            ConfigurationResolver configuration,
            Clock clock) {
        this.port = port;
        this.sync = sync;
        this.store = store;
        this.configuration = configuration;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${horecaos.loyalty.pos-sync.initial-delay:PT90S}",
            fixedDelayString = "${horecaos.loyalty.pos-sync.tick:PT1M}")
    public void tick() {
        for (TenantBinding tenantBinding : port.syncingBindings()) {
            try {
                tickOne(tenantBinding);
            } catch (RuntimeException failure) {
                log.warn(
                        "The till-balance sync tick failed for binding {}",
                        tenantBinding.binding().bindingId(),
                        failure);
            }
        }
    }

    /**
     * The push sweep, on its own clock (ADR 0171 Decision 3 and the accepted convergence window): a platform movement
     * reaches the till within 60 seconds, so it cannot wait for the five-minute pull. Asked every ten seconds, it sends what
     * has moved once {@code loyalty.pos_sync.push_interval_seconds} (30) have passed since its last pass, and only when
     * something has moved, so an idle binding writes no run and a busy one writes one per interval.
     */
    @Scheduled(
            initialDelayString = "${horecaos.loyalty.pos-sync.initial-delay:PT90S}",
            fixedDelayString = "${horecaos.loyalty.pos-sync.push-tick:PT10S}")
    public void pushTick() {
        for (TenantBinding tenantBinding : port.syncingBindings()) {
            try {
                pushOne(tenantBinding);
            } catch (RuntimeException failure) {
                log.warn(
                        "The till-balance push failed for binding {}",
                        tenantBinding.binding().bindingId(),
                        failure);
            }
        }
    }

    /** @return the passes it started, for a deterministic test */
    public int tickOne(TenantBinding tenantBinding) {
        Instant now = clock.instant();
        var binding = tenantBinding.binding();
        Mode mode = sync.modeOf(binding);
        int started = 0;

        if (mode.pulls() && due(tenantBinding, RunKind.PULL, interval(tenantBinding), now)) {
            sync.run(tenantBinding.tenantId(), tenantBinding.brandId(), binding.bindingId(), RunKind.PULL, JOB);
            started++;
        }
        // After the pull, so the first number sent to a till already includes what the till just reported; from then on
        // the push keeps its own cadence (pushTick), which is why this is a call and not a step of the pull.
        started += pushOne(tenantBinding);
        if (reconcileDue(tenantBinding, now)) {
            sync.run(tenantBinding.tenantId(), tenantBinding.brandId(), binding.bindingId(), RunKind.RECONCILE, JOB);
            started++;
        }
        return started;
    }

    /**
     * One push pass if the binding pushes, the push interval has passed and a matched account has moved.
     *
     * <p>A till that could not be written to last time (every account left unverifiable, none sent) is asked no more often
     * than the pull interval, so a till that is down is not hammered every thirty seconds with a run row each.
     *
     * @return 1 when a pass was started, else 0
     */
    public int pushOne(TenantBinding tenantBinding) {
        var binding = tenantBinding.binding();
        if (!sync.modeOf(binding).pushes()) {
            return 0;
        }
        Instant now = clock.instant();
        if (!due(tenantBinding, RunKind.PUSH, pushInterval(tenantBinding), now)) {
            return 0;
        }
        if (!store.hasPendingPushes(tenantBinding.tenantId(), binding.bindingId())) {
            return 0;
        }
        sync.run(tenantBinding.tenantId(), tenantBinding.brandId(), binding.bindingId(), RunKind.PUSH, JOB);
        return 1;
    }

    private boolean due(TenantBinding tenantBinding, RunKind kind, Duration interval, Instant now) {
        return store.lastStartedAt(
                        tenantBinding.tenantId(), tenantBinding.binding().bindingId(), kind.name())
                .map(last -> !last.plus(interval).isAfter(now))
                .orElse(true);
    }

    /** Once per local day, and only after {@value #RECONCILE_AFTER_LOCAL_HOUR}:00 there. */
    private boolean reconcileDue(TenantBinding tenantBinding, Instant now) {
        ZoneId zone = store.tenantZone(tenantBinding.tenantId());
        ZonedDateTime local = now.atZone(zone);
        if (local.getHour() < RECONCILE_AFTER_LOCAL_HOUR) {
            return false;
        }
        LocalDate today = local.toLocalDate();
        return store.lastStartedAt(
                        tenantBinding.tenantId(), tenantBinding.binding().bindingId(), RunKind.RECONCILE.name())
                .map(last -> last.atZone(zone).toLocalDate().isBefore(today))
                .orElse(true);
    }

    private Duration pushInterval(TenantBinding tenantBinding) {
        Integer seconds =
                configuration.value(LoyaltyConfigurationKeys.POS_SYNC_PUSH_INTERVAL_SECONDS, ResourceScope.platform());
        Duration base = Duration.ofSeconds(
                seconds == null ? LoyaltyConfigurationKeys.POS_SYNC_PUSH_INTERVAL_DEFAULT_SECONDS : seconds);
        boolean lastPassSentNothing = store.lastRunOfKind(
                        tenantBinding.tenantId(), tenantBinding.binding().bindingId(), RunKind.PUSH.name())
                .map(last -> last.pushed() == 0 && last.unverifiable() > 0)
                .orElse(false);
        return lastPassSentNothing ? interval(tenantBinding) : base;
    }

    private Duration interval(TenantBinding tenantBinding) {
        Integer seconds =
                configuration.value(LoyaltyConfigurationKeys.POS_SYNC_PULL_INTERVAL_SECONDS, ResourceScope.platform());
        return Duration.ofSeconds(
                seconds == null ? LoyaltyConfigurationKeys.POS_SYNC_PULL_INTERVAL_DEFAULT_SECONDS : seconds);
    }
}
