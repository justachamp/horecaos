package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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
 */
@Component
public class CartRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(CartRetentionSweeper.class);

    private final JdbcCartStore carts;
    private final Clock clock;
    private final int retentionDays;
    private final int batchSize;

    public CartRetentionSweeper(
            JdbcCartStore carts,
            Clock clock,
            @Value("${horecaos.ordering.cart-retention.days:90}") int retentionDays,
            @Value("${horecaos.ordering.cart-retention.batch-size:500}") int batchSize) {
        this.carts = carts;
        this.clock = clock;
        this.retentionDays = retentionDays;
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
        Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
        int deleted = carts.deleteAbandoned(cutoff, batchSize);
        if (deleted > 0) {
            log.info("Abandoned cart sweep: {} carts untouched for {} days deleted", deleted, retentionDays);
        }
        return deleted;
    }
}
