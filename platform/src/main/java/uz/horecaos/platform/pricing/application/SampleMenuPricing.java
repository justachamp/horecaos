package uz.horecaos.platform.pricing.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.pricing.api.SampleMenuPricingPort;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;

/**
 * The {@code pricing.api} face of the onboarding sample menu (ADR 0099).
 *
 * <p>A translation layer over {@link PriceAuthoringService}, the same shape
 * {@code StockAvailabilityPortAdapter} takes in {@code inventory}: it calls the
 * service an operator's own request calls, so the sample's price book carries
 * the same guards — the "prices nothing" refusal, the ties-with-a-live-book
 * refusal, the activation compare-and-set — as one a person authored.
 *
 * <p>Priority 0 is deliberate and is the whole of this class's opinion about
 * pricing: the sample book must lose to anything the tenant later authors. It
 * cannot lose to a book at priority 0, which is why a tenant that activates its
 * own book at that priority is refused by {@code tiesWithALivePriceBook} and
 * told to raise one — a real consequence, recorded in ADR 0099.
 */
@Component
public class SampleMenuPricing implements SampleMenuPricingPort {

    private static final Logger log = LoggerFactory.getLogger(SampleMenuPricing.class);

    /** {@code QuoteService.DEFAULT_JURISDICTION}: the only jurisdiction a quote resolves against. */
    private static final String JURISDICTION = "UZ";

    /** Uzbekistan's standard VAT, in basis points, matching {@code tools/seed-data}. */
    private static final int VAT_BASIS_POINTS = 1200;

    /** Below anything an operator would choose, so a real price book always wins. */
    private static final int SAMPLE_PRIORITY = 0;

    private final PriceAuthoringService authoring;
    private final JdbcPricingStore store;
    private final Clock clock;

    public SampleMenuPricing(PriceAuthoringService authoring, JdbcPricingStore store, Clock clock) {
        this.authoring = authoring;
        this.store = store;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SamplePricing priceSample(UUID tenantId, UUID brandId, String currency, List<SampleVariantPrice> prices) {

        if (prices.isEmpty()) {
            return new SamplePricing(null, 0, false);
        }
        Set<UUID> variantIds =
                prices.stream().map(SampleVariantPrice::variantId).collect(Collectors.toUnmodifiableSet());

        Optional<JdbcPricingStore.PriceBookSummaryRow> existing = sampleBook(tenantId, brandId);
        if (existing.isEmpty()
                && store.pricedVariants(tenantId, brandId, variantIds, clock.instant())
                        .containsAll(variantIds)) {
            // The tenant's own prices already cover every sample item. Adding a
            // second book for the same variants would put two live prices in
            // resolution order for no reason.
            return new SamplePricing(null, 0, false);
        }

        boolean created = existing.isEmpty();
        UUID priceBookId = existing.map(JdbcPricingStore.PriceBookSummaryRow::id)
                .orElseGet(() -> createSampleBook(tenantId, brandId, currency));

        // A previous attempt that got as far as pricing everything is not priced
        // again: setPrice closes the open row and opens a new one, so re-running
        // it would leave a price history a tenant never made. An attempt that
        // stopped halfway does re-set all of them, which costs a few superseded
        // rows in a book nobody has seen yet.
        int priced = 0;
        if (store.openPriceCount(tenantId, brandId, priceBookId) < prices.size()) {
            for (SampleVariantPrice price : prices) {
                authoring.setPrice(
                        tenantId, brandId, priceBookId, PriceableType.VARIANT, price.variantId(), price.amountMinor());
                priced++;
            }
        }

        // Without a tax profile every cart in the brand refuses with
        // NO_TAX_PROFILE, which is what ACTIVATION_SMOKE_TEST's quote would hit.
        // Only when the brand has none: a rate the tenant already chose is not
        // this step's to overwrite.
        if (authoring.taxProfile(tenantId, brandId, JURISDICTION).isEmpty()) {
            authoring.setTaxProfile(tenantId, brandId, JURISDICTION, PricingEngine.TaxMode.INCLUSIVE, VAT_BASIS_POINTS);
        }

        PriceAuthoringService.PriceBook book = authoring.require(tenantId, brandId, priceBookId);
        if (book.status() == PriceAuthoringService.Status.DRAFT) {
            authoring.activate(
                    tenantId,
                    brandId,
                    priceBookId,
                    book.version(),
                    ActorRef.systemJob("onboarding-sample-menu-publish"));
        }

        log.info("Sample price book {} for brand {}: priced {}, created={}", priceBookId, brandId, priced, created);
        return new SamplePricing(priceBookId, priced, created);
    }

    /** The sample's own book, ignoring an archived one — that is a book somebody retired. */
    private Optional<JdbcPricingStore.PriceBookSummaryRow> sampleBook(UUID tenantId, UUID brandId) {
        return store.priceBooksForBrand(tenantId, brandId).stream()
                .filter(row -> SAMPLE_PRICE_BOOK_NAME.equals(row.name()))
                .filter(row -> !PriceAuthoringService.Status.ARCHIVED.name().equals(row.status()))
                .findFirst();
    }

    private UUID createSampleBook(UUID tenantId, UUID brandId, String currency) {
        PriceAuthoringService.PriceBook book = authoring.create(
                tenantId,
                brandId,
                new PriceAuthoringService.NewPriceBook(SAMPLE_PRICE_BOOK_NAME, currency, null, null, SAMPLE_PRIORITY));
        authoring.assign(
                tenantId,
                brandId,
                book.id(),
                PriceAuthoringService.AssignmentScope.BRAND,
                null,
                new PriceAuthoringService.Assignment(SAMPLE_PRIORITY, null, null));
        return book.id();
    }
}
