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
 * Drives the till-balance sync on a clock (ADR 0171 Decision 3, 4 and 6): a pull and a push every {@code
 * loyalty.pos_sync.pull_interval_seconds} for a binding whose mode says so, and a reconciliation once a day, after the
 * business day has closed.
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

    /** @return the passes it started, for a deterministic test */
    public int tickOne(TenantBinding tenantBinding) {
        Instant now = clock.instant();
        var binding = tenantBinding.binding();
        Mode mode = sync.modeOf(binding);
        int started = 0;

        if (mode.pulls() && due(tenantBinding, RunKind.PULL, interval(tenantBinding), now)) {
            sync.run(tenantBinding.tenantId(), tenantBinding.brandId(), binding.bindingId(), RunKind.PULL, JOB);
            started++;
            if (mode.pushes()) {
                // After the pull, so the number sent to the till already includes what the till just reported.
                sync.run(tenantBinding.tenantId(), tenantBinding.brandId(), binding.bindingId(), RunKind.PUSH, JOB);
                started++;
            }
        }
        if (reconcileDue(tenantBinding, now)) {
            sync.run(tenantBinding.tenantId(), tenantBinding.brandId(), binding.bindingId(), RunKind.RECONCILE, JOB);
            started++;
        }
        return started;
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

    private Duration interval(TenantBinding tenantBinding) {
        Integer seconds =
                configuration.value(LoyaltyConfigurationKeys.POS_SYNC_PULL_INTERVAL_SECONDS, ResourceScope.platform());
        return Duration.ofSeconds(
                seconds == null ? LoyaltyConfigurationKeys.POS_SYNC_PULL_INTERVAL_DEFAULT_SECONDS : seconds);
    }
}
