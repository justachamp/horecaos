package uz.horecaos.platform.ordering.application;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.FiscalNodeFacts;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.api.OrderMarksPort;
import uz.horecaos.platform.ordering.api.PaymentIntentPort;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.FeatureFlagKeys;

/**
 * {@link MarkedOrderPayment} over the order's marked lines ({@link OrderMarksPort}) and the catalog's
 * classification of the variants an amendment adds ({@link FiscalNodeFacts}).
 */
@Component
class CatalogMarkedOrderPayment implements MarkedOrderPayment {

    private final OrderMarksPort marks;
    private final FiscalNodeFacts facts;
    private final PaymentIntentPort payments;
    private final ConfigurationResolver configuration;

    CatalogMarkedOrderPayment(
            OrderMarksPort marks,
            FiscalNodeFacts facts,
            PaymentIntentPort payments,
            ConfigurationResolver configuration) {
        this.marks = marks;
        this.facts = facts;
        this.payments = payments;
        this.configuration = configuration;
    }

    @Override
    public Optional<String> refusalOf(
            UUID tenantId, UUID brandId, UUID orderId, String paymentMethodCode, Set<UUID> addedVariantIds) {
        // The cheap question first: a method whose receipt carries the code (every method but Payme) is never
        // refused, and the answer comes from the method's own receipt port, not from a second statement of it.
        if (payments.carriesMarkingCodes(tenantId, paymentMethodCode)) {
            return Optional.empty();
        }
        // The order's own marks answer for what it already holds (empty while marked goods are off for the
        // tenant); the catalog answers for what this same batch adds.
        boolean marked = !marks.markCounts(tenantId, orderId).isEmpty() || adds(tenantId, brandId, addedVariantIds);
        return marked
                ? Optional.of("This order has a marked item, whose receipt must carry its code, so its payment "
                        + "cannot be changed to "
                        + paymentMethodCode)
                : Optional.empty();
    }

    private boolean adds(UUID tenantId, UUID brandId, Set<UUID> addedVariantIds) {
        if (addedVariantIds.isEmpty()
                || !Boolean.TRUE.equals(
                        configuration.value(FeatureFlagKeys.MARKED_GOODS, ResourceScope.tenant(tenantId)))) {
            return false;
        }
        return facts.requiresMarkingCapablePayment(tenantId, brandId, addedVariantIds);
    }
}
