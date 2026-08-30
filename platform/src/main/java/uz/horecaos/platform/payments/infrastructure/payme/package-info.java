/**
 * The Payme (Paycom) adapter (ADR 0013).
 *
 * <p>Payme is an inbound integration wearing an outbound costume. The only thing
 * HorecaOS ever sends Payme is a checkout link the customer's browser follows, and
 * that link is unsigned and proves nothing; everything that decides whether an
 * order is paid arrives here, as JSON-RPC, from Payme. So the seven methods in
 * {@link uz.horecaos.platform.payments.infrastructure.payme.PaymeMerchantApi} are the
 * integration and {@link uz.horecaos.platform.payments.infrastructure.payme.PaymeProviderAdapter}
 * is a link builder.
 *
 * <p>Nothing here is shared with the Click adapter and nothing ever should be.
 * Click's {@code Price} is a line total and Payme's {@code price} is a unit price;
 * Click answers small negative integers with note strings and Payme answers a
 * JSON-RPC error whose {@code message} is a localised {@code {ru, uz, en}} object;
 * Click's reversal is outbound and Payme's arrives inbound as
 * {@code CancelTransaction}. A helper shared between the two is how one provider's
 * semantics silently become the other's.
 *
 * <p>There is no Camel route in this package and its absence is deliberate rather
 * than an omission of ADR 0007. ADR 0007 governs outbound provider calls, and the
 * Payme Merchant API has none: there is no merchant-initiated create, no
 * merchant-initiated query, and no merchant-initiated refund. The one outbound
 * artefact is a base64 string built in this process, which has no transport to
 * classify, no timeout to survive, and nothing for a circuit breaker to open on.
 * If the Subscribe API is ever adopted — {@code receipts.*} over HTTP with an
 * {@code X-Auth} header — that call goes through a route like every other.
 */
package uz.horecaos.platform.payments.infrastructure.payme;
