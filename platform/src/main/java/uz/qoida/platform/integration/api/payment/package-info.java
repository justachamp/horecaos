/**
 * The transport contract a payment adapter calls its provider through (ADR 0007,
 * ADR 0013).
 *
 * <p>Exposed as a named interface because the dependency runs the opposite way to
 * the notification one. A notification adapter lives here and implements a port
 * the notifications module declares; a payment adapter lives in
 * {@code payments.infrastructure} — the arrangement {@code PaymentProviderPort}
 * states — and so needs something of integration's to call. This package is that
 * something, and it is deliberately the smallest surface that will carry a
 * provider call: a method, a path, a body, and a function from the resolved
 * credential to the headers it authorises.
 *
 * <p>Nothing here knows Click or Payme. The whole reason for the credential
 * function rather than a named authentication scheme is that Click's
 * {@code Auth: user:sha1(timestamp + secret):timestamp} is Click's business, and
 * putting it here would split one provider's wire knowledge across two modules —
 * which is how a signature quietly stops matching the documentation it was
 * written from.
 */
@org.springframework.modulith.NamedInterface("payment")
package uz.qoida.platform.integration.api.payment;
