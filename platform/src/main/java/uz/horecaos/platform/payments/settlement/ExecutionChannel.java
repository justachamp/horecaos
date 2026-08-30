package uz.horecaos.platform.payments.settlement;

/**
 * Where the operator says they gave the money back.
 *
 * <p>Recorded because "refunded" is not one act in this market. A card payment is
 * reversed in Click's or Payme's cabinet; a cash order is refunded out of the
 * drawer at the branch, with no provider anywhere in the story; and a large or
 * stale one is sent by bank transfer. They have different evidence, different
 * people, and different reconciliation sources, and a single free-text note would
 * lose all three distinctions.
 *
 * <p>{@code PROVIDER_CONSOLE} is the only one that must carry a provider-side
 * reference, because it is the only one where a provider issued an identifier.
 */
public enum ExecutionChannel {

    /** Click's or Payme's merchant cabinet. Requires the reference it shows. */
    PROVIDER_CONSOLE,

    /** Cash handed back at the branch. There is no provider identifier to give. */
    CASH_DRAWER,

    /** A transfer from the tenant's own account, referenced by its payment order. */
    BANK_TRANSFER
}
