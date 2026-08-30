/**
 * Payment services and the ports payments needs others to implement (ADR 0013).
 *
 * <p>{@code PaymentProviderPort} and {@code FiscalReceiptPort} are declared here
 * and implemented per provider under {@code payments.infrastructure}, the
 * arrangement {@code OrderingTenantContext} sets. The two adapters share nothing
 * but these interfaces, deliberately: Click's {@code Price} means the opposite of
 * Payme's {@code price}, their error vocabularies have no values in common, and
 * their reversal and reconciliation directions are inverted, so code shared
 * between them is how one provider's semantics silently become the other's.
 */
package uz.horecaos.platform.payments.application;
