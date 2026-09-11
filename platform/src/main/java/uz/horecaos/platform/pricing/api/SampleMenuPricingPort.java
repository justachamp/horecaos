package uz.horecaos.platform.pricing.api;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Prices the onboarding sample menu (ADR 0099).
 *
 * <p>Narrow by design, the same shape {@link QuoteAcceptancePort} and {@code
 * inventory}'s {@code StockAvailabilityPort} already take: one write, nothing
 * about price books, assignments or the resolution order behind them. A caller
 * outside {@code pricing} cannot reach {@code PriceAuthoringService} at all
 * under Spring Modulith, and a sample menu that is not priced cannot be
 * published — {@code VARIANT_HAS_NO_ACTIVE_PRICE} is a publication blocker.
 *
 * <p>Idempotent, because the onboarding step that calls it may die between any
 * two of its three calls and will then run again from the top.
 */
public interface SampleMenuPricingPort {

    /** The name the sample's own price book always carries, and what a retry finds it by. */
    String SAMPLE_PRICE_BOOK_NAME = "Sample menu prices";

    /**
     * Ensures every variant has an active price, in the tenant's own currency.
     *
     * <p>Creates the sample price book, assigns it to the brand at the lowest
     * priority so any book the tenant later authors wins, sets a VAT profile
     * when the brand has none, and activates. Does nothing at all when the
     * tenant's own prices already cover every variant.
     *
     * @throws SamplePricingRefusedException when pricing refuses permanently; the
     *     caller must not retry it
     */
    SamplePricing priceSample(UUID tenantId, UUID brandId, String currency, List<SampleVariantPrice> prices);

    /**
     * Pricing refused the sample book, permanently.
     *
     * <p>Declared on the port rather than letting {@code
     * PriceAuthoringService.PriceBookLifecycleException} escape, so the
     * onboarding step can tell a permanent refusal from a transient one without
     * reaching past this interface into {@code pricing.application} — the
     * boundary ADR 0099 chose these three ports to protect.
     *
     * <p>The refusal that actually happens: the tenant has already activated its
     * own {@code BRAND}-scope book at priority 0 with an overlapping window, and
     * the sample's own priority-0 book ties with it. A caller that mapped this to
     * a retry would burn every attempt on a condition no amount of waiting fixes
     * — the same reasoning behind the step's {@code NO_CHANNEL} pre-check. An
     * {@code OptimisticLockingFailureException} from two writers racing is
     * deliberately *not* wrapped: that one is genuinely transient and has to keep
     * its retry.
     */
    class SamplePricingRefusedException extends RuntimeException {
        public SamplePricingRefusedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** One sample item and what it costs, in integer minor units of the tenant's currency. */
    record SampleVariantPrice(UUID variantId, long amountMinor) {}

    /**
     * What {@link #priceSample} did.
     *
     * @param priceBookId the sample's own book, or null when the tenant's
     *                    existing prices already covered every variant and no
     *                    sample book was needed
     * @param priced      how many variants the call priced, zero on a retry that
     *                    found them all priced already
     * @param created     whether this call created the sample price book
     */
    record SamplePricing(@Nullable UUID priceBookId, int priced, boolean created) {}
}
