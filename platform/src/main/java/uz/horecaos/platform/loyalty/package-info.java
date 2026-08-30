/**
 * Loyalty points: an append-only movement ledger, expiring lots, and the
 * redemption that becomes one tender of a split settlement (ADR 0046).
 *
 * <p>Points and nothing else. There is no customer-funded balance in this module
 * and none is deferred: no deposit account, no top-up, no withdrawal, and no
 * kill switch behind which any of them waits. Nobody holds customer funds, so no
 * Central Bank of Uzbekistan e-money question arises, and the reversal
 * conditions produce a new ADR rather than an edit to this package.
 *
 * <p>Points are not money, and this module is where that is made structurally
 * true rather than asserted. Not withdrawable: no command here produces a money
 * movement, and V0042 refuses a payment intent on a balance-settled tender by
 * check constraint. Not transferable: an entry has one account and no
 * counterparty, and a redemption is refused unless the order's customer and
 * brand equal the account's. No cash value outside the platform: redemption's
 * only sink is a tender on such an order, and closure forfeits rather than pays
 * out.
 *
 * <p>The ledger is the authority and the balance is a projection of it, for the
 * three reasons ADR 0021 gives for metering — a balance updated in place cannot
 * be audited, cannot be recomputed after a bug, and cannot be defended to a
 * customer who disputes it.
 *
 * <p>What a redemption becomes downstream is deliberately two different things.
 * In payments it is a tender: it discharges part of the order total, drives the
 * courier's cash figure, and is counted as a tender by reporting. In fiscal it
 * is a per-line discount, because the seller received 82 000 som and not 94 000,
 * and because neither Click nor Payme has a field in which platform-held value
 * could be tendered. {@link uz.horecaos.platform.loyalty.api.RedemptionAllocation}
 * is the allocation that reaches the receipt.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Loyalty")
package uz.horecaos.platform.loyalty;
