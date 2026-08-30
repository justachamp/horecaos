package uz.horecaos.platform.payments.settlement;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.payments.api.EntitlementBenefit;
import uz.horecaos.platform.payments.api.EntitlementScope;
import uz.horecaos.platform.payments.api.RemedyEntitlementPort;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The half of the future-discount remedy that payments owns after the grant
 * (ADR 0013 as amended 2026-08-25).
 *
 * <p>What is here: which entitlements exist, whose they are, how many uses are
 * left, what one use is worth at most, and the single write that spends one.
 * What is deliberately not here: any arithmetic over a cart. Pricing decides what
 * a discount is worth against its own subtotal and fee and then says how much it
 * took, and payments checks that figure against the per-use maximum. A percentage
 * applied to a basket in this class would be a second pricing engine, and it
 * would disagree with ADR 0018's the first time a promotion stacked.
 *
 * <p>Refusals are returned rather than thrown. An exhausted entitlement is an
 * ordinary answer to an ordinary question — the customer had two uses and this is
 * the third order — and pricing needs to carry on quoting without it, not handle
 * an exception. The one case that does throw is a lost race, which is a retry
 * rather than a refusal.
 */
@Service
public class RemedyEntitlementService implements RemedyEntitlementPort {

    private final JdbcRemedyStore store;
    private final Clock clock;

    public RemedyEntitlementService(JdbcRemedyStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<GrantedEntitlement> available(UUID tenantId, UUID brandId, UUID customerAccountId, Instant at) {
        return store.spendableEntitlements(tenantId, brandId, customerAccountId, at).stream()
                .map(RemedyEntitlementService::toGranted)
                .toList();
    }

    @Override
    @Transactional
    public RedemptionOutcome redeem(RedeemCommand command) {
        Instant now = clock.instant();
        JdbcRemedyStore.EntitlementRow entitlement = store.findEntitlement(command.tenantId(), command.entitlementId())
                .orElse(null);
        if (entitlement == null) {
            return RedemptionOutcome.refused("NOT_FOUND");
        }
        // Whose it is, checked before anything else about it is revealed. An
        // entitlement is one brand's promise to one person, and answering "how
        // many uses are left" to anyone else is a small enumeration oracle.
        if (!entitlement.customerAccountId().equals(command.customerAccountId())) {
            return RedemptionOutcome.refused("NOT_FOUND");
        }
        if (entitlement.status() != EntitlementStatus.ACTIVE) {
            return RedemptionOutcome.refused(entitlement.status().name());
        }
        if (!now.isBefore(entitlement.expiresAt()) || now.isBefore(entitlement.startsAt())) {
            return RedemptionOutcome.refused("OUTSIDE_WINDOW");
        }
        if (entitlement.usesRemaining() <= 0) {
            return RedemptionOutcome.refused("EXHAUSTED");
        }
        if (!entitlement.currency().equals(command.currency())) {
            return RedemptionOutcome.refused(entitlement.usesRemaining(), "CURRENCY_MISMATCH");
        }

        long subtotal = command.subtotalDiscountMinor();
        long delivery = command.deliveryDiscountMinor();
        if (subtotal < 0 || delivery < 0) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A redemption takes a non-negative amount off each component");
        }
        // The refusals from here down are about the amount rather than about the
        // grant, so they report the uses that are still there: pricing that read
        // zero would stop offering an entitlement nothing had spent.
        if (subtotal == 0 && delivery == 0) {
            return RedemptionOutcome.refused(entitlement.usesRemaining(), "NOTHING_TO_TAKE");
        }
        if (subtotal > 0 && !entitlement.appliesTo().covers(EntitlementScope.SUBTOTAL)) {
            return RedemptionOutcome.refused(entitlement.usesRemaining(), "SCOPE_NOT_COVERED");
        }
        if (delivery > 0 && !entitlement.appliesTo().covers(EntitlementScope.DELIVERY_FEE)) {
            return RedemptionOutcome.refused(entitlement.usesRemaining(), "SCOPE_NOT_COVERED");
        }
        if (Math.addExact(subtotal, delivery) > perUseMaximum(entitlement)) {
            // The cap is checked here rather than trusted from pricing because a
            // per-use maximum that only the caller enforces is not a maximum.
            return RedemptionOutcome.refused(entitlement.usesRemaining(), "EXCEEDS_MAXIMUM");
        }

        // The redemption row goes in first, and its unique index is what makes a
        // retried order placement a retry instead of a second use. Consuming
        // first would spend a use that the conflicting insert then abandons.
        boolean inserted = store.insertRedemption(new JdbcRemedyStore.RedemptionRow(
                UUID.randomUUID(),
                command.tenantId(),
                command.entitlementId(),
                command.orderId(),
                subtotal,
                delivery,
                command.currency(),
                now));
        if (!inserted) {
            return RedemptionOutcome.took(entitlement.usesRemaining());
        }

        if (!store.consumeUse(command.tenantId(), command.entitlementId(), now)) {
            // Read as available a moment ago and gone now: another order took the
            // last use between the two statements. Thrown rather than refused so
            // this transaction rolls back and takes its redemption row with it.
            throw new ApiException(
                    ErrorCode.STALE_VERSION, "This entitlement was redeemed concurrently. Re-read it and re-quote.");
        }
        return RedemptionOutcome.took(entitlement.usesRemaining() - 1);
    }

    /** Closes out grants whose window has passed. Scheduled, never on a request path. */
    @Transactional
    public int expireLapsed() {
        return store.expireLapsedEntitlements(clock.instant());
    }

    /**
     * The most one use can be worth.
     *
     * <p>A fixed grant is its own ceiling; a percentage has one because
     * {@link EntitlementBenefit#PERCENT} refuses to be created without it.
     */
    private static long perUseMaximum(JdbcRemedyStore.EntitlementRow entitlement) {
        return entitlement.benefit() == EntitlementBenefit.FIXED_AMOUNT
                ? entitlement.amountMinor()
                : entitlement.maximumMinor();
    }

    private static GrantedEntitlement toGranted(JdbcRemedyStore.EntitlementRow row) {
        return new GrantedEntitlement(
                row.id(),
                row.brandId(),
                row.appliesTo(),
                row.benefit(),
                row.percentBasisPoints(),
                row.amountMinor(),
                row.maximumMinor(),
                row.currency(),
                row.usesRemaining(),
                row.expiresAt());
    }
}
