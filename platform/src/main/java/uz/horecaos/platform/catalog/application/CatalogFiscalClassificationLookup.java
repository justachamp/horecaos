package uz.horecaos.platform.catalog.application;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.api.FiscalClassificationLookup;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;

/** Answers {@link FiscalClassificationLookup} from {@code catalog.fiscal_classifications}: one indexed read (ADR 0161). */
@Component
public class CatalogFiscalClassificationLookup implements FiscalClassificationLookup {

    private final JdbcCatalogStore store;

    public CatalogFiscalClassificationLookup(JdbcCatalogStore store) {
        this.store = store;
    }

    /** The code of the fee node the delivery charge is filed under: V0028 seeds one per brand. */
    static final String DELIVERY_FEE_CODE = "DELIVERY";

    @Override
    @Transactional(readOnly = true)
    public Optional<FiscalClassificationFacts> deliveryFee(UUID tenantId, UUID brandId) {
        return store.feesForBrand(tenantId, brandId).stream()
                .filter(fee -> DELIVERY_FEE_CODE.equals(fee.code()))
                .findFirst()
                .map(fee -> classifications(tenantId, brandId, Set.of(fee.id())).get(fee.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, FiscalClassificationFacts> classifications(UUID tenantId, UUID brandId, Set<UUID> priceableIds) {
        return store.classificationsFor(tenantId, brandId, priceableIds).entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> {
                    var classification = entry.getValue();
                    return new FiscalClassificationFacts(
                            classification.mxikCode(),
                            classification.packageCode(),
                            classification.fiscalUnitCode(),
                            classification.fiscalName(),
                            classification.barcode(),
                            classification.markingRequired());
                }));
    }
}
