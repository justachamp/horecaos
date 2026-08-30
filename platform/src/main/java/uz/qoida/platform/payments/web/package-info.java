/**
 * The payment endpoints Qoida's own clients call (ADR 0013, ADR 0031).
 *
 * <p>Distinct from {@code payments.web.click} and {@code payments.web.payme},
 * which speak the providers' wire formats and are deliberately outside ADR 0031's
 * conventions. Everything here is ordinary platform HTTP: a declared capability, an
 * idempotency key, a JSON body, and Problem Details on refusal.
 *
 * <p>Nothing here credits an order. Opening an attempt and handing over a checkout
 * surface are the outbound half; both providers' surfaces are unauthenticated, so
 * the amount the platform enforces is the one it checks when the provider calls
 * back — which happens in the two packages beside this one.
 */
package uz.qoida.platform.payments.web;
