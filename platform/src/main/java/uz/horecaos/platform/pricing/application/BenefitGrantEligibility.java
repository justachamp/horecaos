package uz.horecaos.platform.pricing.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.pricing.application.BenefitGrantService.RedemptionOutcome.Reason;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcBenefitGrantStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcBenefitGrantStore.GrantRow;

/**
 * The read-only half of spending a coded benefit grant (ADR 0140, ADR 0044, ADR 0072): whether a presented code
 * stands for a grant that this customer may spend at this brand right now.
 *
 * <p>The same question {@code PromoCodeEligibilityService} answers for a shared code, asked of the other kind of
 * code: a code the customer types into the cart resolves to a coupon first and, when there is none, to the
 * customer's grant. It reserves nothing and writes nothing; {@link BenefitGrantService#claimForQuote} is the one
 * place a grant is spent, and it claims with a conditional update that does not trust this answer.
 *
 * <p>A grant that belongs to somebody else answers {@link Reason#CODE_NOT_FOUND} to the caller: the code is a
 * bearer secret, and saying "it exists, but it is not yours" would confirm a guess.
 */
@Component
public class BenefitGrantEligibility {

    private final JdbcBenefitGrantStore store;

    public BenefitGrantEligibility(JdbcBenefitGrantStore store) {
        this.store = store;
    }

    /** The verdict: the grant when the code may be spent, otherwise why not. */
    public record Verdict(
            @Nullable GrantRow grant, @Nullable Reason reason) {

        public boolean isEligible() {
            return grant != null;
        }

        static Verdict eligible(GrantRow grant) {
            return new Verdict(grant, null);
        }

        static Verdict refused(Reason reason) {
            return new Verdict(null, reason);
        }
    }

    /**
     * @param presentedCode the raw code; normalised the way a shared code is
     * @param customerAccountId null for a guest, who owns no grant
     */
    public Verdict check(
            UUID tenantId, UUID brandId, String presentedCode, @Nullable UUID customerAccountId, Instant now) {
        String normalized =
                uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore.normalize(presentedCode);
        Optional<GrantRow> found = store.findByCodeHash(
                tenantId, uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore.hash(normalized));
        // A grant of another brand is not this brand's code, and is answered exactly as a code that is not there.
        if (found.isEmpty() || !found.get().brandId().equals(brandId)) {
            return Verdict.refused(Reason.CODE_NOT_FOUND);
        }
        if (customerAccountId == null) {
            return Verdict.refused(Reason.REQUIRES_ACCOUNT);
        }
        return refusalOf(found.get(), customerAccountId, now)
                .map(Verdict::refused)
                .orElseGet(() -> Verdict.eligible(found.get()));
    }

    /**
     * Why this customer may not spend this grant at this instant, or empty when they may. Shared with {@link
     * BenefitGrantService#redeem} so the two paths cannot disagree about what "spendable" means.
     */
    static Optional<Reason> refusalOf(GrantRow row, UUID customerAccountId, Instant now) {
        if (!row.customerAccountId().equals(customerAccountId)) {
            return Optional.of(Reason.WRONG_CUSTOMER);
        }
        if (!"ACTIVE".equals(row.status())) {
            return Optional.of(Reason.ALREADY_USED);
        }
        if (now.isBefore(row.validFrom())) {
            return Optional.of(Reason.NOT_YET_ACTIVE);
        }
        if (row.expiresAt() != null && !now.isBefore(row.expiresAt())) {
            return Optional.of(Reason.EXPIRED);
        }
        return Optional.empty();
    }
}
