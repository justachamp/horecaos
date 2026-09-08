package uz.horecaos.platform.fulfillment.domain.sourcing;

import java.util.Objects;

/**
 * The ADR 0030 policy document behind ADR 0014's {@code DELIVERY_COST_SUBSIDY}
 * bearer.
 *
 * <p>Who bears a delivery-cost overrun is an explicit open input in both ADR
 * 0013 and ADR 0048 — "who bears a delivery-fee reimbursement... carried forward
 * unanswered from ADR 0013" — and neither product nor finance has closed it.
 * This document exists so the gap is still recorded honestly while that
 * question is open, the same trade {@link DeliverySourcingPolicy#DEFAULTS}
 * already makes for timing: a provisional, clearly-labelled default in force
 * until operations answers ADR 0014's checklist item, not a silent guess dressed
 * up as a decision.
 */
public record DeliverySubsidyPolicy(DeliverySubsidyBearer bearer) {

    /**
     * {@link DeliverySubsidyBearer#PLATFORM} — the one answer that commits
     * nobody else's money. Absorbing at the tenant, brand or location would
     * assert a contractual allocation nobody has agreed to; routing every
     * overrun to {@code MANUAL_APPROVAL_REQUIRED} by default would mean every
     * cheaper-than-Noor Yandex booking waits on a human before the row can even
     * be written. Platform absorption is reversible by policy the moment product
     * and finance decide otherwise, and costs nobody a contract in the meantime.
     */
    public static final DeliverySubsidyPolicy DEFAULTS = new DeliverySubsidyPolicy(DeliverySubsidyBearer.PLATFORM);

    public DeliverySubsidyPolicy {
        Objects.requireNonNull(bearer, "A subsidy bearer is required");
    }
}
