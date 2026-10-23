package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.configuration.RetentionWindow;
import uz.horecaos.platform.configuration.RetentionWindow.Scope;
import uz.horecaos.platform.configuration.RetentionWindow.Unit;
import uz.horecaos.platform.configuration.RetentionWindowProvider;
import uz.horecaos.platform.configuration.TenantRetention;
import uz.horecaos.platform.ordering.api.OrderingConfigurationKeys;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;

/**
 * Deletes abandoned carts (ADR 0092, decided 2026-09-11): a cart that never became an order is gone 90 days after it was
 * last touched, unless its tenant chose another window.
 *
 * <p>A cart carries who was shopping -- a customer account or a guest's hashed reference -- what they wanted, and the note
 * they wrote for the kitchen. Nothing needs any of it once the cart is neither going to be checked out nor part of an order.
 * Counts are logged; no cart, tenant or customer is named.
 *
 * <p><strong>Each tenant is swept on its own window (ADR 0109, batch 21).</strong> Settings 10.11 let a tenant set its cart
 * retention, but the sweep ran once with a single cutoff, so it took the <em>longer</em> of the platform default and the
 * largest value any tenant had stored: a tenant that asked for thirty days got ninety and was told nothing, which ADR 0109
 * recorded as a negative consequence. The statement now resolves the window per tenant (the tenant's value, else the
 * platform's, else {@code horecaos.ordering.cart-retention.days}) and raises it to {@link
 * OrderingConfigurationKeys#CART_RETENTION_FLOOR_DAYS}, so a shorter window is honoured and a longer one is too, and one
 * tenant's choice can no longer delete, or keep, another's. A cart whose customer is under a legal hold is never swept
 * (ADR 0029).
 *
 * <p>Destructive, so it can be started in report-only mode ({@code horecaos.ordering.cart-retention.report-only}), which
 * logs how many carts it would delete and deletes none (ADR 0029).
 */
@Component
public class CartRetentionSweeper implements RetentionWindowProvider {

    private static final Logger log = LoggerFactory.getLogger(CartRetentionSweeper.class);

    private final JdbcCartStore carts;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final int defaultRetentionDays;
    private final int batchSize;
    private final boolean reportOnly;

    public CartRetentionSweeper(
            JdbcCartStore carts,
            JdbcClient jdbc,
            Clock clock,
            @Value("${horecaos.ordering.cart-retention.days:90}") int retentionDays,
            @Value("${horecaos.ordering.cart-retention.batch-size:500}") int batchSize,
            @Value("${horecaos.ordering.cart-retention.report-only:false}") boolean reportOnly) {
        this.carts = carts;
        this.jdbc = jdbc;
        this.clock = clock;
        this.defaultRetentionDays = retentionDays;
        this.batchSize = batchSize;
        this.reportOnly = reportOnly;
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
        Instant now = clock.instant();
        if (reportOnly) {
            int wouldDelete = carts.countAbandoned(now, defaultRetentionDays, floorDays());
            if (wouldDelete > 0) {
                log.info(
                        "Abandoned cart sweep is report-only: {} carts are past their tenant's window and would be deleted",
                        wouldDelete);
            }
            return 0;
        }
        int deleted = carts.deleteAbandoned(now, batchSize, defaultRetentionDays, floorDays());
        if (deleted > 0) {
            log.info("Abandoned cart sweep: {} carts past their tenant's retention window deleted", deleted);
        }
        return deleted;
    }

    /**
     * The window this job applies to one tenant's carts: its own value, else the platform's, else the default, raised to
     * the floor. Read the way the sweep reads it, so the retention card cannot disagree with what is deleted.
     */
    @Override
    public RetentionWindow windowFor(UUID tenantId) {
        TenantRetention.Reading reading = TenantRetention.read(
                jdbc, tenantId, OrderingConfigurationKeys.CART_RETENTION_DAYS_CODE, defaultRetentionDays, floorDays());
        return new RetentionWindow(
                OrderingConfigurationKeys.CART_RETENTION_DAYS_CODE,
                Unit.DAYS,
                defaultRetentionDays,
                floorDays(),
                reading.configured(),
                reading.effective(),
                Scope.PER_TENANT,
                CartRetentionSweeper.class.getSimpleName());
    }

    private static int floorDays() {
        return OrderingConfigurationKeys.CART_RETENTION_FLOOR_DAYS;
    }
}
