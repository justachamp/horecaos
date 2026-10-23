package uz.horecaos.platform.pricing.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The tax rule a quote was priced under, as the quote recorded it (ADR 0018, ADR 0166 Decision 9).
 *
 * <p>A fiscal receipt states a whole-percent VAT rate per line, and the only honest source for it is the
 * rate that produced the tax share the order kept: not the tax profile as it reads today, which a
 * re-registration or a new rate has since superseded. The quote's calculation document records the rate
 * and the mode it extracted tax under, and this is the read of exactly that.
 *
 * <p>Read-only and personal-data-free. Tenant-scoped: a quote id from another tenant answers empty.
 */
public interface QuoteTaxLookup {

    /**
     * @return empty for a quote that recorded no tax rule, which is a quote priced before the document
     *     carried one; the caller treats it as an obstacle and does not guess a rate
     */
    Optional<QuoteTax> of(UUID tenantId, UUID quoteId);

    /**
     * @param rateBasisPoints ADR 0018's rate, 1200 for 12 percent
     * @param inclusive whether prices were VAT-inclusive, the only mode a price book is authored under
     */
    record QuoteTax(UUID taxProfileId, int rateBasisPoints, boolean inclusive) {}
}
