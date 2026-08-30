/**
 * What a provider installation is, and what one call to a provider came to
 * (ADR 0026, ADR 0007).
 *
 * <p>Exposed as a named interface for the same reason
 * {@code integration.api.payment} is: a payment adapter lives in
 * {@code payments.infrastructure} rather than here, because Click's and Payme's
 * wire knowledge belongs with the module that owns the payment, and an adapter
 * that may not name the outcome type its own transport returns cannot classify
 * anything.
 *
 * <p>The type that matters here is {@code ProviderOutcome} and specifically its
 * {@code UNCERTAIN} status. It is the vocabulary the delivery adapters and the
 * payment adapters share, deliberately: "we do not know whether that worked" is
 * one concept, and two modules holding two versions of it is how a lost response
 * on one path is retried while the other reconciles.
 */
@org.springframework.modulith.NamedInterface("provider")
package uz.qoida.platform.integration.api.provider;
