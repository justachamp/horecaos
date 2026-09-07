package uz.horecaos.platform.marketing.application;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCustomerMetricStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCustomerMetricStore.BrandRef;

/**
 * Wires {@link CustomerMetricProjectionService#sweep} to a clock instead of to
 * luck (ADR 0044).
 *
 * <p>Before this class, {@code CustomerMetricProjectionService.sweep} — the
 * method that observes drift and then recomputes from source — had exactly one
 * caller: a test. The projection is "eventually consistent with a five-minute
 * staleness budget at p99" by the record's own words, and the recompute sweep
 * is, today, the <em>whole</em> maintenance path — there is no incremental fold
 * from the order terminal event inbox yet. A five-minute budget enforced by a
 * job that never runs is not a budget; it is a number in a document. This
 * class is that job.
 *
 * <p>The default interval below is four minutes, deliberately inside the
 * five-minute figure rather than equal to it: the budget is what a caller sees
 * between two sweeps finishing, so an interval equal to the budget would only
 * meet it if every sweep took zero time.
 *
 * <p>Same shape as {@link CampaignExpansionScheduler}: a worklist read
 * ({@link JdbcCustomerMetricStore#brandsWithProfiles}) followed by one call per
 * item, each failure logged and swallowed so one tenant's bad row cannot stop
 * every other tenant's brand from being swept on the same pass.
 * {@code brandsWithProfiles} is driven from {@code customer.brand_profiles}
 * rather than from {@code marketing.customer_metrics} itself, so a brand's
 * first night runs this sweep exactly as every later one does — see that
 * method's own doc.
 *
 * <p><b>No {@code TenantRlsSession} binding here, and that is a finding, not an
 * oversight.</b> {@code brandsWithProfiles} and {@link
 * CustomerMetricProjectionService#sweep} are genuinely cross-tenant reads and
 * writes, the same shape {@code InventoryService#expireStaleReservations} is —
 * but unlike {@code inventory.reservations} (V0162), no migration has ever
 * turned an ADR 0056 row-level-security policy on for {@code
 * marketing.customer_metrics} or {@code customer.brand_profiles}, and no
 * service anywhere in this module calls {@code bindTenant} or {@code
 * bindPlatform} either — every one of {@code AudienceService}, {@code
 * CampaignService}, and this class's own {@link CustomerMetricProjectionService}
 * reaches these tables today exactly as it always has. Adding {@code
 * bindPlatform} to only this one class would protect nothing: the moment a
 * policy actually goes on one of these tables, every other marketing service
 * that still binds nothing breaks the same way this class would have avoided,
 * which is a worse outcome than an honest gap, because it would look like the
 * module was already covered. Turning row-level security on for this schema is
 * its own migration, in the shape V0162 already set for inventory, retrofitting
 * the binding across the whole module at once — not a side effect of scheduling
 * a job that has run bypass-free since the day it was written.
 */
@Component
public class CustomerMetricProjectionSweeper {

    private static final Logger log = LoggerFactory.getLogger(CustomerMetricProjectionSweeper.class);

    private final JdbcCustomerMetricStore metrics;
    private final CustomerMetricProjectionService projection;

    public CustomerMetricProjectionSweeper(JdbcCustomerMetricStore metrics, CustomerMetricProjectionService projection) {
        this.metrics = metrics;
        this.projection = projection;
    }

    @Scheduled(
            initialDelayString = "${horecaos.marketing.projection-sweeper.initial-delay:PT20S}",
            fixedDelayString = "${horecaos.marketing.projection-sweeper.interval:PT4M}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            // Logged and swallowed, not rethrown: the next tick retries, and the
            // alternative is a dead scheduler that also stops every other
            // module's timer sharing this pool (SchedulingConfiguration).
            log.error("The marketing projection sweep could not run", failure);
        }
    }

    /** @return how many brands this pass swept and what it found, for a deterministic test */
    public Result runOnce() {
        List<BrandRef> brands = metrics.brandsWithProfiles();
        int rowsRecomputed = 0;
        int driftObservations = 0;
        for (BrandRef brand : brands) {
            try {
                CustomerMetricProjectionService.SweepResult result = projection.sweep(brand.tenantId(), brand.brandId());
                rowsRecomputed += result.rowsRecomputed();
                driftObservations += result.driftObservations();
            } catch (RuntimeException failure) {
                // One brand's failure must not stop every other tenant's brand
                // from being swept on this pass.
                log.error("Marketing projection sweep failed for brand {}", brand.brandId(), failure);
            }
        }
        return new Result(brands.size(), rowsRecomputed, driftObservations);
    }

    /** What one pass swept, recomputed, and found drifting, across every brand. */
    public record Result(int brandsSwept, int rowsRecomputed, int driftObservations) {}
}
