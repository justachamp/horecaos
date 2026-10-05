package uz.horecaos.platform.pricing.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromotionStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * «Who redeemed it»: the audited step from a redemption the 7.9 report shows to the customer
 * account behind it (ADR 0140, ADR 0029).
 *
 * <p>The report holds a customer only as the ADR 0029 keyed pseudonym, by design: a reporting fact
 * with an account id in it is exactly what that record forbids, and a hash cannot be walked back.
 * The plaintext relationship lives where the redemption was recorded, in {@code pricing}, so this
 * is the one place it can be answered, by the redemption's own id, which the report row carries.
 *
 * <p><b>Why it is a recorded act and not a read.</b> Knowing which customer used which offer is
 * the linkage the pseudonym exists to withhold. The step therefore needs the capability that
 * says a customer exists ({@code customer.read}, the one the customer card itself opens with), a
 * stated purpose, and it writes an ADR 0027 security fact in the same transaction, targeted at the
 * customer account so the account's own access log shows who looked and why. What it returns is
 * the account id and nothing about the person: opening the card, and the card's own reveal of a
 * contact value behind {@code customer.pii.reveal}, are the customers module's decisions and are
 * not shortened by this one.
 *
 * <p>The fact carries the redemption, the promotion and the kind of source, and never an amount,
 * a name or a contact.
 */
@Service
public class PromotionRedemptionRevealService {

    private final JdbcPromotionStore store;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final Clock clock;

    public PromotionRedemptionRevealService(
            JdbcPromotionStore store, AuditRecorder audit, CurrentActor currentActor, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    /**
     * What the lookup resolved to.
     *
     * @param customerAccountId null for a guest order, which has no account to open; that is an
     *     answer ("a guest"), not a refusal, and it is audited like any other lookup
     */
    public record Revealed(
            UUID redemptionId,
            UUID promotionId,
            String sourceKind,
            UUID orderId,
            @Nullable UUID customerAccountId) {}

    @Transactional
    public Revealed reveal(UUID tenantId, UUID brandId, UUID promotionId, UUID redemptionId, String purpose) {
        var owner = store.findRedemptionOwner(tenantId, brandId, promotionId, redemptionId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such redemption"));

        AuditFact.Builder fact = AuditFact.of("pricing.promotion.redemption.customer_revealed", AuditClass.SECURITY)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.brand(tenantId, brandId))
                .because(purpose)
                .usingCapability(Capability.CUSTOMER_READ.code())
                // Each lookup is its own append-only access-log fact, with no earlier one to diff against.
                .changed(ChangeDocuments.created(Map.of(
                        "redemptionId", redemptionId,
                        "promotionId", promotionId,
                        "sourceKind", owner.sourceKind())))
                .correlatedBy(redemptionId.toString())
                .occurredAt(clock.instant());
        // The account's own access log is where "who looked at this customer" is read; a guest
        // order has no account, so the fact is then about the redemption.
        fact = owner.customerAccountId() == null
                ? fact.target("promotion_redemption", redemptionId)
                : fact.target("customer_account", owner.customerAccountId());
        audit.record(fact.build());

        return new Revealed(redemptionId, promotionId, owner.sourceKind(), owner.orderId(), owner.customerAccountId());
    }
}
