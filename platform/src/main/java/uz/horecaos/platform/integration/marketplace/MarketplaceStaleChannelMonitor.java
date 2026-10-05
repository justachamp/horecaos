package uz.horecaos.platform.integration.marketplace;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceChannelWentStalePayload;
import uz.horecaos.platform.integration.marketplace.JdbcMarketplaceAvailabilityStore.BindingRow;
import uz.horecaos.platform.integration.marketplace.JdbcMarketplaceAvailabilityStore.OverdueSummary;
import uz.horecaos.platform.integration.outbox.MarketplaceOutbox;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;

/**
 * Notices that a marketplace channel has gone stale and says so, once (ADR 0040 "Liveness
 * watermarks", ADR 0141 "When the partner is down").
 *
 * <p>A channel is stale when at least one dish has been unconfirmed — the platform believes one
 * thing about the stop list and has not been able to make the partner agree — for longer than the
 * binding's bound ({@code marketplace.availability.stale_after_seconds}, ADR 0030). That is a
 * statement about <em>our</em> pushes failing to land, which is not the same as the liveness
 * watermark's inbound silence: a venue can take orders all evening while every stop we send is
 * refused, and the manager who stopped the plov at 14:32 needs to be told the aggregator still
 * sells it.
 *
 * <h2>Once per episode</h2>
 *
 * <p>The report is three things in one transaction: the {@code stale_alerted_at} mark, the
 * {@code MarketplaceChannelWentStale} fact on the outbox, and the ADR 0058 operations alert. The
 * mark is a conditional write, so two replicas ticking one binding cannot both report it, and a
 * transaction that rolls back reports nothing and is tried again on the next tick. When the binding
 * has nothing unconfirmed past its bound the mark is cleared, so the next staleness is a new
 * report with its own alert.
 *
 * <h2>What the alert says</h2>
 *
 * <p>The provider code, a count and the instant the longest-waiting dish has been unconfirmed
 * since — "Yandex Eda: 7 dishes not confirmed since 14:32 — update the partner portal". No dish
 * name and no partner error text (ADR 0029). The wording is the tenant's own, authored against
 * the semantic template key.
 */
@Component
public class MarketplaceStaleChannelMonitor {

    /** The semantic template key a tenant authors this alert's wording against, and its Telegram event class. */
    public static final String MARKETPLACE_CHANNEL_STALE = "MARKETPLACE_CHANNEL_STALE";

    static final String SUBJECT_TYPE = "MarketplaceBinding";

    private final JdbcMarketplaceAvailabilityStore store;
    private final MarketplaceOutbox outbox;
    private final OperationsAlertPort alerts;
    private final MeterRegistry meters;
    private final TransactionTemplate transactions;
    private final Duration alertExpiry;

    @Autowired
    public MarketplaceStaleChannelMonitor(
            JdbcMarketplaceAvailabilityStore store,
            MarketplaceOutbox outbox,
            OperationsAlertPort alerts,
            MeterRegistry meters,
            PlatformTransactionManager transactionManager,
            // A stale-channel notice nobody acted on within the day is superseded by the next
            // episode's; long enough to survive a night shift to the next manager's login.
            @Value("${horecaos.notifications.marketplace-stale-alert-expiry:P1D}") Duration alertExpiry) {
        this.store = store;
        this.outbox = outbox;
        this.alerts = alerts;
        this.meters = meters;
        this.transactions = new TransactionTemplate(transactionManager);
        this.alertExpiry = alertExpiry;
    }

    /** What one evaluation did. */
    public enum Verdict {
        /** Nothing is unconfirmed past the bound, and nothing was reported. */
        HEALTHY,
        /** The binding is stale and this call is the one that reported it. */
        REPORTED,
        /** The binding is stale and was already reported in this episode. */
        ALREADY_REPORTED
    }

    /**
     * @param staleAfterSeconds the binding's bound
     * @param now the reconciler's own instant, so the bound is judged at the instant the pass ran
     */
    public Verdict evaluate(BindingRow binding, int staleAfterSeconds, Instant now) {
        Optional<OverdueSummary> overdue =
                store.overdueUnconfirmed(binding.tenantId(), binding.bindingId(), now.minusSeconds(staleAfterSeconds));
        if (overdue.isEmpty()) {
            store.clearStaleReported(binding.tenantId(), binding.bindingId(), now);
            return Verdict.HEALTHY;
        }
        OverdueSummary summary = overdue.get();
        Instant since = summary.oldestSince() == null ? now : summary.oldestSince();

        Boolean reported = transactions.execute(status -> {
            if (!store.markStaleReported(binding.tenantId(), binding.bindingId(), now)) {
                return false;
            }
            outbox.channelWentStale(
                    binding.tenantId(),
                    new MarketplaceChannelWentStalePayload(
                            binding.bindingId(),
                            binding.locationId(),
                            binding.providerType(),
                            staleAfterSeconds,
                            summary.itemCount(),
                            since));
            alerts.fanOut(
                    binding.tenantId(),
                    binding.brandId(),
                    binding.locationId(),
                    MARKETPLACE_CHANNEL_STALE,
                    MARKETPLACE_CHANNEL_STALE,
                    SUBJECT_TYPE,
                    binding.bindingId(),
                    null,
                    // One alert per binding per episode: the episode is named by the instant its
                    // longest-waiting dish has been unconfirmed since, which does not move
                    // while the episode lasts.
                    episodeKey(binding.bindingId(), since),
                    alertVariables(binding.providerType(), summary.itemCount(), since, staleAfterSeconds),
                    alertExpiry);
            return true;
        });
        if (Boolean.TRUE.equals(reported)) {
            meters.counter("horecaos.marketplace.channel.went_stale").increment();
            return Verdict.REPORTED;
        }
        return Verdict.ALREADY_REPORTED;
    }

    /**
     * The entire variable set the alert ever renders with: a provider code, a count, an instant
     * and the bound -- nothing about a dish, a guest or the partner's own words. Package-visible so
     * the classification test asserts against the call fixed here.
     */
    static Map<String, String> alertVariables(
            String providerType, int itemCount, Instant since, int staleAfterSeconds) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("provider", providerType);
        variables.put("itemCount", Integer.toString(itemCount));
        variables.put("unconfirmedSince", since.toString());
        variables.put("staleAfterMinutes", Long.toString(Math.max(1, staleAfterSeconds / 60)));
        return variables;
    }

    /** For callers that only need to know the episode's identity. */
    static String episodeKey(UUID bindingId, Instant since) {
        return "%s:%s:%s:%d".formatted(MARKETPLACE_CHANNEL_STALE, SUBJECT_TYPE, bindingId, since.getEpochSecond());
    }
}
