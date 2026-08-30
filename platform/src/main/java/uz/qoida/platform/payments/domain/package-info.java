/**
 * The payment domain: intents, attempts, provider transactions, and the money
 * types that keep som and tiyin apart (ADR 0013).
 *
 * <p>Nothing here speaks Click or Payme. Both providers' vocabularies are carried
 * as {@link uz.qoida.platform.payments.domain.ProviderEvidence} beside a Qoida
 * state and never as one, because Payme's signed numeric states have no Click
 * equivalent and Click has no reservation or expiry state at all: adopting either
 * would leave half of the other provider unrepresentable.
 */
package uz.qoida.platform.payments.domain;
