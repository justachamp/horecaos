package uz.qoida.platform.tenancy.api;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The one answer to "which company sold this" (ADR 0038).
 *
 * <p>The same shape {@link ServiceabilityResolver} takes and for the same reason:
 * there must be no second implementation. A fiscal document, a merchant binding
 * and an ADR 0018 tax profile all need the selling entity, and three modules each
 * resolving it their own way is how a receipt, a settlement and a VAT line come
 * to name three different companies for one order.
 *
 * <p><strong>By location and business date, never by entity id alone.</strong> An
 * identifier arriving from a request body or another module's row is not evidence
 * of anything; every query behind this interface constrains on the tenant and the
 * location together, so an entity belonging to another tenant cannot be returned
 * whatever id is passed.
 *
 * <p>Resolved once and snapshotted. Nothing downstream re-resolves: a
 * re-registration must not rewrite which company a delivered order's receipt said
 * it was sold by, which is precisely what a second resolution on today's date
 * would do.
 */
public interface LegalEntityDirectory {

    /**
     * @param businessDate the branch's own calendar day, not a UTC one. Uzbekistan
     *                     is UTC+5 and branches trade past midnight, so a UTC date
     *                     rolls over at 05:00 Tashkent in the middle of a night
     *                     service and would answer with yesterday's taxpayer
     * @return empty when the location has no assignment covering that date, which
     *         is a receipt that cannot be issued rather than a receipt issued
     *         under a default. A caller must handle it, and the handling is
     *         visible work rather than a guess
     */
    Optional<FiscalSeller> sellerFor(UUID tenantId, UUID locationId, LocalDate businessDate);

    /**
     * Whether the legal-entity schema this reads is present in the deployment.
     *
     * <p>Read the same way {@code PartnerFiscalizationPort.isWired} is read: while
     * ADR 0038's rollout stage 1 is unbuilt the gap belongs on every response that
     * depends on it, not in a startup log line nobody sees twice. False means
     * {@link #sellerFor} answers empty for every location — never that it answers
     * with something plausible.
     */
    default boolean isWired() {
        return true;
    }
}
