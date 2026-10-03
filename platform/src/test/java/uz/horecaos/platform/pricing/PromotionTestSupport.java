package uz.horecaos.platform.pricing;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.pricing.api.AudienceMembershipPort;
import uz.horecaos.platform.pricing.api.CustomerOrderHistoryPort;
import uz.horecaos.platform.pricing.application.PromoCodeEligibilityService;
import uz.horecaos.platform.pricing.application.PromotionInputResolver;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcMenuMembershipLookup;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromotionStore;
import uz.horecaos.platform.pricing.infrastructure.tenancy.JdbcLocationTimeZoneLookup;

/**
 * Builds the {@link PromotionInputResolver} the way the application context does,
 * over a test's own {@link JdbcClient}, for the suites that construct a {@code
 * QuoteService} by hand.
 */
public final class PromotionTestSupport {

    private PromotionTestSupport() {}

    /** No order history and no audiences: a brand whose promotions read neither. */
    public static PromotionInputResolver resolver(JdbcClient jdbc, JdbcPromoCodeStore promoCodeStore) {
        return resolver(jdbc, promoCodeStore, null, null);
    }

    public static PromotionInputResolver resolver(
            JdbcClient jdbc,
            JdbcPromoCodeStore promoCodeStore,
            @Nullable CustomerOrderHistoryPort history,
            @Nullable AudienceMembershipPort audiences) {
        var mapper = JsonMapper.builder().build();
        return new PromotionInputResolver(
                promoCodeStore,
                new JdbcPromotionStore(jdbc, mapper),
                new JdbcPricingStore(jdbc, mapper),
                new PromoCodeEligibilityService(promoCodeStore),
                new JdbcMenuMembershipLookup(jdbc),
                new JdbcLocationTimeZoneLookup(jdbc),
                providerOf(CustomerOrderHistoryPort.class, history),
                providerOf(AudienceMembershipPort.class, audiences));
    }

    private static <T> ObjectProvider<T> providerOf(Class<T> type, @Nullable T instance) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (instance != null) {
            factory.addBean(type.getSimpleName(), instance);
        }
        return factory.getBeanProvider(type);
    }
}
