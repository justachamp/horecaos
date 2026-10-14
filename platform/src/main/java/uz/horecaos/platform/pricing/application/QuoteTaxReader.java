package uz.horecaos.platform.pricing.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.pricing.api.QuoteTaxLookup;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;

/** {@link QuoteTaxLookup} over the calculation document every quote stores. */
@Service
public class QuoteTaxReader implements QuoteTaxLookup {

    private final JdbcPricingStore store;

    public QuoteTaxReader(JdbcPricingStore store) {
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QuoteTax> of(UUID tenantId, UUID quoteId) {
        return store.findQuoteTax(tenantId, quoteId);
    }
}
