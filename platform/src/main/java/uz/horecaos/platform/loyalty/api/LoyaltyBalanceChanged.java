package uz.horecaos.platform.loyalty.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer's points balance moved (ADR 0046, gap-map row 6.5's
 * {@code CASHBACK_CHANGE} trigger).
 *
 * <p>Published by {@code LoyaltyAccrualService#accrue} (a positive {@link
 * #deltaMinor()}) and {@code PointsRedemptionService#reserve} — the "redemption
 * / debit path" (a negative one) — as an ordinary Spring {@code
 * ApplicationEventPublisher.publishEvent}, the same shape {@code ordering}
 * already publishes {@code OrderingEvent} with. {@code
 * uz.horecaos.platform.marketing.application.LoyaltyBalanceChangeAutomationTrigger}
 * is this event's one consumer today, listening with {@code
 * @TransactionalEventListener(phase = AFTER_COMMIT)} — deliberately after, not
 * before: see that class's own doc for why a marketing firing that may fail
 * must never be able to roll back the balance movement that produced it, which
 * an in-process {@code BEFORE_COMMIT} listener cannot guarantee once the
 * consumer's own method is itself {@code @Transactional}.
 *
 * <p><strong>Not an ADR 0032 governed event.</strong> It never reaches Kafka,
 * carries no {@code eventVersion}, and is not in the event catalogue. ADR
 * 0032's outbox exists for durability across a process boundary between
 * publish and consumption; this event's one consumer runs in-process, moments
 * after the publishing transaction commits, in the same deployable — a much
 * narrower gap than an outbox closes, and one this trigger's own doc accepts
 * for the same reason a missed scheduled sweep is already accepted for
 * {@code BIRTHDAY}/{@code INACTIVITY}/{@code CART_ABANDONMENT}: this is a
 * best-effort marketing notification, not a record the ledger itself depends
 * on. Should a consumer ever need this fact to survive a restart between
 * publish and receipt — an external service, a queue outside this deployable
 * — that is a new decision, not a retrofit of this record.
 *
 * <p><strong>Ids and amounts only.</strong> No contact value, no display name,
 * nothing ADR 0029 would call personal data — a consumer needing those calls
 * an authorized API with {@link #customerAccountId()}, the same rule every
 * other event payload in this codebase follows.
 *
 * @param tenantId           the tenant whose points moved
 * @param brandId            the loyalty account's brand — a cashback-change
 *                           rule is authored per brand, the same scope every
 *                           other automation trigger fires within
 * @param customerAccountId  whose balance moved
 * @param accountId          the {@code loyalty.accounts} row itself
 * @param changeId            the ledger entry id for an accrual, or the
 *                           reservation id for a redemption — there is no
 *                           single entry id for a redemption that can span
 *                           several lots, so the reservation stands in as the
 *                           one identifier a guard key can be built from.
 *                           Never reused across two different balance changes
 * @param changeType         {@code "ACCRUAL"} or {@code "REDEMPTION"} — {@link
 *                           uz.horecaos.platform.loyalty.domain.EntryType}'s
 *                           name, restated as a plain string here so this
 *                           package does not have to expose the domain enum
 * @param deltaMinor         signed: positive for an accrual, negative for a
 *                           redemption/debit
 * @param balanceAfterMinor  the account's balance immediately after this
 *                           movement
 * @param occurredAt         when the movement was recorded
 */
public record LoyaltyBalanceChanged(
        UUID tenantId,
        UUID brandId,
        UUID customerAccountId,
        UUID accountId,
        UUID changeId,
        String changeType,
        long deltaMinor,
        long balanceAfterMinor,
        Instant occurredAt) {

    public LoyaltyBalanceChanged {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(customerAccountId, "Customer account ID is required");
        Objects.requireNonNull(accountId, "Loyalty account ID is required");
        Objects.requireNonNull(changeId, "Change ID is required");
        Objects.requireNonNull(changeType, "Change type is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
        if (deltaMinor == 0) {
            throw new IllegalArgumentException("A balance change with a zero delta is not a change");
        }
    }
}
