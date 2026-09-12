package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.api.OrderingConfigurationKeys;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;

/**
 * Deletes abandoned carts (ADR 0092, decided 2026-09-11): a cart that never
 * became an order is gone 90 days after it was last touched.
 *
 * <p>A cart carries who was shopping -- a customer account or a guest's
 * hashed reference -- what they wanted, and the note they wrote for the
 * kitchen. Nothing needs any of it once the cart is neither going to be
 * checked out nor part of an order. Counts are logged; no cart, tenant or
 * customer is named.
 *
 * <p><strong>Tenant self-service since ADR 0109 (Settings 10.11).</strong> The
 * sweep runs once, globally, across every tenant's carts in one pass, so it
 * cannot honour ninety different retention windows the way a per-tenant job
 * could. It instead sweeps on the <em>longer</em> of the platform default and
 * the largest value any tenant configured through {@link
 * OrderingConfigurationKeys#CART_RETENTION_DAYS}, the identical rule {@code
 * TrackRetentionSweeper.effectiveRetentionDays} already uses for courier
 * location tracks: a stored value can only lengthen the window, never
 * shorten another tenant's, because deleting a different tenant's cart early
 * is not this job's decision to make on its behalf.
 */
@Component
public class CartRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(CartRetentionSweeper.class);

    private final JdbcCartStore carts;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final int configuredRetentionDays;
    private final int batchSize;

    public CartRetentionSweeper(
            JdbcCartStore carts,
            JdbcClient jdbc,
            Clock clock,
            @Value("${horecaos.ordering.cart-retention.days:90}") int retentionDays,
            @Value("${horecaos.ordering.cart-retention.batch-size:500}") int batchSize) {
        this.carts = carts;
        this.jdbc = jdbc;
        this.clock = clock;
        this.configuredRetentionDays = retentionDays;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${horecaos.ordering.cart-retention.initial-delay:PT2M}",
            fixedDelayString = "${horecaos.ordering.cart-retention.interval:PT1H}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The abandoned cart sweep could not run", failure);
        }
    }

    /** @return how many carts this pass deleted, for a deterministic test */
    public int runOnce() {
        int retentionDays = effectiveRetentionDays();
        Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
        int deleted = carts.deleteAbandoned(cutoff, batchSize);
        if (deleted > 0) {
            log.info("Abandoned cart sweep: {} carts untouched for {} days deleted", deleted, retentionDays);
        }
        return deleted;
    }

    /**
     * The platform default, or the longest value any tenant configured,
     * whichever is greater. Read with plain SQL against the shared ADR 0030
     * value store rather than through the resolver, for the identical reason
     * {@code TrackRetentionSweeper.effectiveRetentionDays} gives: this sweep
     * has no single tenant scope to resolve against, since one pass covers
     * every tenant's carts.
     */
    int effectiveRetentionDays() {
        Long longest = jdbc.sql("""
                        SELECT max(integer_value) FROM tenant.configuration_values
                         WHERE key_code = :keyCode AND is_explicit_null = false
                        """)
                .param("keyCode", OrderingConfigurationKeys.CART_RETENTION_DAYS_CODE)
                .query(Long.class)
                .optional()
                .orElse(null);

        return longest == null ? configuredRetentionDays : Math.max(configuredRetentionDays, Math.toIntExact(longest));
    }
}
