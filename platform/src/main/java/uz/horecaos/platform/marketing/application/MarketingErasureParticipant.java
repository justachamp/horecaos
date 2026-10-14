package uz.horecaos.platform.marketing.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.customers.spi.ErasureCounts;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore;

/**
 * Takes a customer's profile out of {@code marketing} when she is erased (ADR 0029, ADR 0044).
 *
 * <p>ADR 0044 recorded two operations that "exist and are tested" and that nothing ever called, because nothing
 * produced the fact "this customer asked to be erased": {@link CustomerMetricProjectionService#erase} and {@code
 * JdbcAudienceStore.eraseMembership}. {@code CustomerErasureService} is that producer, and this is the adapter that
 * lets it reach them without customers importing marketing (the seam {@code dinein} and {@code reviews} already use).
 *
 * <p>Three things go. The projection row holds her order counts, spend, locale and the month and day of her birthday:
 * a profile built to select people. Her membership of every audience snapshot says which segments she was in on
 * which day. The drift observations quote her metrics. Campaign recipient counts and campaign spend stay (an
 * aggregate that no longer identifies anyone is not erased, and reversing a finance number to honour a privacy request
 * is a different kind of wrong), and so does her suppression: that she must not be contacted is not a thing to forget
 * because she asked to be forgotten.
 *
 * <p>Idempotent under retry: a second call finds nothing left to delete.
 */
@Component
@ErasesCustomerData(
        name = "marketing",
        tables = {
            "marketing.customer_metrics",
            "marketing.audience_snapshot_members",
            "marketing.metric_drift_observations"
        })
public class MarketingErasureParticipant implements CustomerErasureParticipant {

    private final CustomerMetricProjectionService metrics;
    private final JdbcAudienceStore audiences;

    public MarketingErasureParticipant(CustomerMetricProjectionService metrics, JdbcAudienceStore audiences) {
        this.metrics = metrics;
        this.audiences = audiences;
    }

    @Override
    @Transactional
    public ErasureCounts erase(UUID tenantId, UUID customerAccountId, ErasedContacts held) {
        return ErasureCounts.builder()
                .add("marketing.customer_metrics", metrics.erase(tenantId, customerAccountId))
                .add("marketing.audience_snapshot_members", audiences.eraseMembership(tenantId, customerAccountId))
                .add("marketing.metric_drift_observations", metrics.eraseDriftObservations(tenantId, customerAccountId))
                .build();
    }
}
