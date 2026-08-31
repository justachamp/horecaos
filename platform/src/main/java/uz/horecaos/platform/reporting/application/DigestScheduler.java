package uz.horecaos.platform.reporting.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.api.EntitlementSnapshot;
import uz.horecaos.platform.integration.api.provider.ProviderHealth;
import uz.horecaos.platform.integration.api.provider.ProviderHealthQuery;
import uz.horecaos.platform.notifications.api.DigestFanout;
import uz.horecaos.platform.notifications.api.OperationsSubscriptionDirectory;
import uz.horecaos.platform.notifications.api.OperationsSubscriptionDirectory.ScopedBinding;
import uz.horecaos.platform.ordering.api.OrderCounts;
import uz.horecaos.platform.ordering.api.OrderCountsQuery;
import uz.horecaos.platform.tenancy.api.OnboardingHealth;
import uz.horecaos.platform.tenancy.api.OnboardingHealthQuery;

/**
 * Supervisor and control-plane digests over Telegram (ADR 0058), the digests
 * ADR 0043's day-close was the named blocker for.
 *
 * <p><b>Why this lives in {@code reporting}, not {@code notifications}.</b> A
 * digest scheduler needs closed-day facts ({@code reporting}'s own tables),
 * live order counts ({@code ordering.api}), onboarding health ({@code
 * tenancy.api}), provider health ({@code integration.api.provider}), an
 * entitlement gate ({@code commercial.api}), and a way to actually send ({@code
 * notifications.api}). {@code reporting} already depends on {@code
 * integration.api} for its one inbox consumer (a one-way edge), and {@code
 * integration} already depends on {@code notifications.api} for its Telegram
 * adapter (also one-way) — so a class that depended on both {@code reporting}
 * and {@code notifications} from inside {@code notifications} would close a
 * module cycle: {@code integration -> notifications -> reporting ->
 * integration}. Placing the scheduler in {@code reporting} instead keeps every
 * edge one-directional: {@code reporting -> notifications.api} joins the
 * existing {@code reporting -> integration.api} and {@code integration ->
 * notifications.api} edges without closing a loop, because nothing {@code
 * notifications} depends on reaches back to {@code reporting}. {@link
 * DigestFanout} and {@link OperationsSubscriptionDirectory#tenantDigestBindings}/
 * {@code platformDigestBindings} are the two-method seam that makes this
 * possible: {@code notifications} still owns templates, delivery, and consent;
 * this class only asks "who wants this" and "send this."
 *
 * <p>Three cadences, two audiences:
 *
 * <ul>
 *   <li>{@link #emitFifteenMinuteDigests()} — {@code OPERATIONS} only. Live
 *       counts from {@link OrderCountsQuery}, scoped to each binding's own
 *       brand/location, because two chats bound to two different branches want
 *       two different pulses.
 *   <li>{@link #emitHalfDayDigests()} and {@link #emitDayCloseDigests()} —
 *       both audiences. Both read {@link ReportQueryService#mostRecentlyClosedDay},
 *       ADR 0043's day-grain facts, which {@code DayCloseScheduler} is what
 *       makes non-empty. A half-day digest is not a partial-day snapshot — day
 *       grain facts do not exist until a day closes — it is the most recently
 *       closed day's numbers, sent again as a reminder roughly twice a day; a
 *       day-close digest is that same content's first delivery, sent once per
 *       closed day.
 * </ul>
 *
 * <p><b>Idempotent by construction, not by a claim this class takes.</b> Every
 * send gets an {@code idempotencyKeyBase} deterministic in the digest kind and
 * the period (a 15-minute bucket, a half-day AM/PM bucket, or the business date
 * itself), and {@code notifications.notifications}'s own unique constraint on
 * {@code (tenant_id, idempotency_key)} is what stops a scheduler tick that runs
 * twice — one replica or several — from creating the same message twice. This
 * is the same idempotency-key discipline {@code OrderNotificationTrigger} and
 * {@code ApprovalDeadlineWarningSweeper} already use; a digest needs no lease
 * table of its own the way {@code DayCloseScheduler} does, because unlike a day
 * close, sending the same digest message twice by accident would be caught by
 * the database rather than by corrupting a fact table.
 *
 * <p><b>Entitlement-gated from day one</b> (ADR 0058's own resolved open
 * input): a tenant's {@code OPERATIONS} digests are skipped, silently and with
 * a metric, unless {@link EntitlementKeys#TELEGRAM_DIGESTS_ENABLED} resolves
 * true for that tenant — the first real caller of ADR 0021's {@code
 * EntitlementService}. The check reads the resolved grant directly ({@link
 * EntitlementSnapshot#require}), not {@link EntitlementService#featureEnabled},
 * because the catalogue's default enforcement mode is {@code METER_ONLY} and
 * {@code featureEnabled} answers "should the platform currently act on this",
 * which is {@code true} for an ungranted meter-only key — the wrong question
 * for a boolean gate that has to actually gate. Platform-audience digests are
 * not gated: they are a platform operating capability, not a tenant's
 * commercial entitlement.
 */
@Component
@ConditionalOnProperty(name = "horecaos.notifications.digests.enabled", havingValue = "true", matchIfMissing = true)
public class DigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(DigestScheduler.class);

    static final String DIGEST_15M = "DIGEST_15M";
    static final String DIGEST_HALF_DAY = "DIGEST_HALF_DAY";
    static final String DIGEST_DAY_CLOSE = "DIGEST_DAY_CLOSE";
    static final String PLATFORM_DIGEST_HALF_DAY = "PLATFORM_DIGEST_HALF_DAY";
    static final String PLATFORM_DIGEST_DAY_CLOSE = "PLATFORM_DIGEST_DAY_CLOSE";

    private final OperationsSubscriptionDirectory subscriptions;
    private final DigestFanout fanout;
    private final OrderCountsQuery liveCounts;
    private final ReportQueryService reportQueries;
    private final OnboardingHealthQuery onboardingHealth;
    private final ProviderHealthQuery providerHealth;
    private final EntitlementService entitlements;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Duration fifteenMinuteExpiry;
    private final Duration halfDayExpiry;
    private final Duration dayCloseExpiry;

    public DigestScheduler(
            OperationsSubscriptionDirectory subscriptions,
            DigestFanout fanout,
            OrderCountsQuery liveCounts,
            ReportQueryService reportQueries,
            OnboardingHealthQuery onboardingHealth,
            ProviderHealthQuery providerHealth,
            EntitlementService entitlements,
            Clock clock,
            MeterRegistry meters,
            @Value("${horecaos.notifications.digests.fifteen-minute.expiry:PT20M}") Duration fifteenMinuteExpiry,
            @Value("${horecaos.notifications.digests.half-day.expiry:PT13H}") Duration halfDayExpiry,
            @Value("${horecaos.notifications.digests.day-close.expiry:P2D}") Duration dayCloseExpiry) {
        this.subscriptions = subscriptions;
        this.fanout = fanout;
        this.liveCounts = liveCounts;
        this.reportQueries = reportQueries;
        this.onboardingHealth = onboardingHealth;
        this.providerHealth = providerHealth;
        this.entitlements = entitlements;
        this.clock = clock;
        this.meters = meters;
        this.fifteenMinuteExpiry = fifteenMinuteExpiry;
        this.halfDayExpiry = halfDayExpiry;
        this.dayCloseExpiry = dayCloseExpiry;
    }

    @Scheduled(
            initialDelayString = "${horecaos.notifications.digests.fifteen-minute.initial-delay:PT1M}",
            fixedDelayString = "${horecaos.notifications.digests.fifteen-minute.interval:PT15M}")
    public void emitFifteenMinuteDigests() {
        String period = fifteenMinuteBucket(clock.instant());
        for (UUID tenantId : reportQueries.activeTenantIds()) {
            try {
                emitFifteenMinuteDigestFor(tenantId, period);
            } catch (RuntimeException failure) {
                log.warn("15-minute digest failed for tenant {}", tenantId, failure);
            }
        }
    }

    @Scheduled(
            initialDelayString = "${horecaos.notifications.digests.half-day.initial-delay:PT2M}",
            fixedDelayString = "${horecaos.notifications.digests.half-day.interval:PT12H}")
    public void emitHalfDayDigests() {
        String period = halfDayBucket(clock.instant());
        for (UUID tenantId : reportQueries.activeTenantIds()) {
            try {
                emitTenantClosedDayDigest(tenantId, DIGEST_HALF_DAY, period, halfDayExpiry);
            } catch (RuntimeException failure) {
                log.warn("Half-day digest failed for tenant {}", tenantId, failure);
            }
        }
        try {
            emitPlatformClosedDayDigest(PLATFORM_DIGEST_HALF_DAY, period, halfDayExpiry);
        } catch (RuntimeException failure) {
            log.warn("Platform half-day digest failed", failure);
        }
    }

    @Scheduled(
            initialDelayString = "${horecaos.notifications.digests.day-close.initial-delay:PT3M}",
            fixedDelayString = "${horecaos.notifications.digests.day-close.interval:PT15M}")
    public void emitDayCloseDigests() {
        for (UUID tenantId : reportQueries.activeTenantIds()) {
            try {
                emitTenantClosedDayDigest(tenantId, DIGEST_DAY_CLOSE, null, dayCloseExpiry);
            } catch (RuntimeException failure) {
                log.warn("Day-close digest failed for tenant {}", tenantId, failure);
            }
        }
        try {
            emitPlatformDayCloseDigest();
        } catch (RuntimeException failure) {
            log.warn("Platform day-close digest failed", failure);
        }
    }

    // -------------------------------------------------------- 15-minute

    private void emitFifteenMinuteDigestFor(UUID tenantId, String period) {
        List<ScopedBinding> bindings = subscriptions.tenantDigestBindings(tenantId, DIGEST_15M);
        if (bindings.isEmpty() || !entitledForDigests(tenantId, "15m")) {
            return;
        }
        for (ScopedBinding binding : bindings) {
            OrderCounts counts = liveCounts.liveCounts(tenantId, binding.brandId(), binding.locationId());
            UUID subjectId = digestSubjectId(DIGEST_15M, binding.bindingId() + ":" + period);
            fanout.send(
                    List.of(binding),
                    DIGEST_15M,
                    subjectId,
                    DIGEST_15M + ":" + binding.bindingId() + ":" + period,
                    fifteenMinuteVariables(counts),
                    fifteenMinuteExpiry);
        }
    }

    // ---------------------------------------------------- half-day / day-close

    /**
     * Emits a tenant's own closed-day digest.
     *
     * @param period the send-cadence bucket for a half-day digest (the same
     *               content resent, deduplicated by this key); null for a
     *               day-close digest, which uses the closed business date
     *               itself instead — its own natural once-per-day key
     */
    private void emitTenantClosedDayDigest(UUID tenantId, String eventClass, @Nullable String period, Duration expiry) {
        List<ScopedBinding> bindings = subscriptions.tenantDigestBindings(tenantId, eventClass);
        if (bindings.isEmpty() || !entitledForDigests(tenantId, eventClass)) {
            return;
        }
        reportQueries.mostRecentlyClosedDay(tenantId).ifPresent(facts -> {
            String key = period == null ? facts.businessDate().toString() : period;
            UUID subjectId = digestSubjectId(eventClass, tenantId + ":" + key);
            fanout.send(
                    bindings,
                    eventClass,
                    subjectId,
                    eventClass + ":" + tenantId + ":" + key,
                    closedDayVariables(facts),
                    expiry);
        });
    }

    private void emitPlatformClosedDayDigest(String eventClass, String period, Duration expiry) {
        List<ScopedBinding> bindings = subscriptions.platformDigestBindings(eventClass);
        if (bindings.isEmpty()) {
            return;
        }
        UUID subjectId = digestSubjectId(eventClass, period);
        fanout.send(bindings, eventClass, subjectId, eventClass + ":" + period, currentPlatformVariables(), expiry);
    }

    private void emitPlatformDayCloseDigest() {
        List<ScopedBinding> bindings = subscriptions.platformDigestBindings(PLATFORM_DIGEST_DAY_CLOSE);
        if (bindings.isEmpty()) {
            return;
        }
        // Once per UTC calendar day: platform totals have no single tenant's
        // business-day boundary to key off, so the send cadence is a plain
        // calendar day and the idempotency key is what stops it repeating
        // inside that day.
        String period = clock.instant().atOffset(ZoneOffset.UTC).toLocalDate().toString();
        UUID subjectId = digestSubjectId(PLATFORM_DIGEST_DAY_CLOSE, period);
        fanout.send(
                bindings,
                PLATFORM_DIGEST_DAY_CLOSE,
                subjectId,
                PLATFORM_DIGEST_DAY_CLOSE + ":" + period,
                currentPlatformVariables(),
                dayCloseExpiry);
    }

    private Map<String, String> currentPlatformVariables() {
        List<UUID> tenantIds = reportQueries.activeTenantIds();
        long totalOrders = 0;
        long totalGross = 0;
        for (UUID tenantId : tenantIds) {
            Optional<DigestFacts> facts = reportQueries.mostRecentlyClosedDay(tenantId);
            if (facts.isPresent()) {
                totalOrders += facts.get().ordersCompleted();
                totalGross += facts.get().grossRevenueSom();
            }
        }
        return platformVariables(
                tenantIds.size(),
                totalOrders,
                totalGross,
                onboardingHealth.onboardingHealth(),
                providerHealth.providerHealth());
    }

    // ------------------------------------------------------- entitlement gate

    /**
     * ADR 0021's first real caller. Reads the raw resolved grant, not {@link
     * EntitlementService#featureEnabled}: see the class doc for why.
     */
    private boolean entitledForDigests(UUID tenantId, String digestKind) {
        EntitlementSnapshot snapshot = entitlements.snapshot(tenantId);
        boolean granted = Boolean.TRUE.equals(
                snapshot.require(EntitlementKeys.TELEGRAM_DIGESTS_ENABLED).featureEnabled());
        if (!granted) {
            Counter.builder("horecaos.notifications.digest.entitlement_denied")
                    .description("A digest skipped because the tenant is not entitled to ADR 0058's Telegram digests")
                    .tag("kind", digestKind)
                    .register(meters)
                    .increment();
            log.debug(
                    "Tenant {} is not entitled to Telegram digests ({}); skipping, no error raised",
                    tenantId,
                    digestKind);
        }
        return granted;
    }

    // ------------------------------------------------------------- vocabulary
    //
    // Package-visible and static so the ADR 0032/0029 classification test can
    // call them directly, the same way OrderNotificationTrigger.reasonVariables
    // and ApprovalDeadlineWarningSweeper.variablesFor are — numbers and a date
    // only, never a customer name, phone, or address.

    public static Map<String, String> fifteenMinuteVariables(OrderCounts counts) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("newOrderCount", String.valueOf(counts.newOrders()));
        variables.put("pendingApprovalCount", String.valueOf(counts.awaitingApproval()));
        variables.put("kitchenCount", String.valueOf(counts.inKitchen()));
        variables.put("readyCount", String.valueOf(counts.ready()));
        variables.put("fulfillingCount", String.valueOf(counts.fulfilling()));
        variables.put("completedCount", String.valueOf(counts.completed()));
        variables.put("cancelledCount", String.valueOf(counts.cancelled()));
        variables.put("totalActiveCount", String.valueOf(counts.totalNonTerminal()));
        return variables;
    }

    public static Map<String, String> closedDayVariables(DigestFacts facts) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("businessDate", facts.businessDate().toString());
        variables.put("ordersCompleted", String.valueOf(facts.ordersCompleted()));
        variables.put("ordersCancelled", String.valueOf(facts.ordersCancelled()));
        variables.put("grossRevenueSom", String.valueOf(facts.grossRevenueSom()));
        variables.put("netRevenueSom", String.valueOf(facts.netRevenueSom()));
        variables.put("refundedSom", String.valueOf(facts.refundedSom()));
        variables.put("hasDivergence", String.valueOf(facts.hasOpenDivergences()));
        return variables;
    }

    public static Map<String, String> platformVariables(
            long activeTenantCount,
            long totalOrdersCompleted,
            long totalGrossRevenueSom,
            OnboardingHealth onboarding,
            ProviderHealth provider) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("activeTenantCount", String.valueOf(activeTenantCount));
        variables.put("totalOrdersCompleted", String.valueOf(totalOrdersCompleted));
        variables.put("totalGrossRevenueSom", String.valueOf(totalGrossRevenueSom));
        variables.put("onboardingRunsPending", String.valueOf(onboarding.runsWaiting()));
        variables.put("onboardingRunsFailed", String.valueOf(onboarding.runsFailed()));
        variables.put("activeInstallations", String.valueOf(provider.activeInstallations()));
        variables.put("failingConnections", String.valueOf(provider.failingConnections()));
        return variables;
    }

    // ------------------------------------------------------------------ time

    /** Floors an instant to a 15-minute UTC bucket, so ticks within one window collapse onto one send. */
    static String fifteenMinuteBucket(Instant now) {
        long epochMinute = now.getEpochSecond() / 60;
        long flooredMinute = epochMinute - (epochMinute % 15);
        return String.valueOf(flooredMinute);
    }

    /** The UTC calendar day plus AM/PM, a plain send-cadence marker independent of any tenant's own business day. */
    static String halfDayBucket(Instant now) {
        var utc = now.atOffset(ZoneOffset.UTC);
        return utc.toLocalDate() + (utc.getHour() < 12 ? "-AM" : "-PM");
    }

    private static UUID digestSubjectId(String eventClass, String key) {
        return UUID.nameUUIDFromBytes((eventClass + ":" + key).getBytes(StandardCharsets.UTF_8));
    }
}
