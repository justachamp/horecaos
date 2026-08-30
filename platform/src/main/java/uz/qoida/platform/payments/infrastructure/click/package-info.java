/**
 * The Click adapter: SHOP API inbound, MERCHANT API outbound (ADR 0013, ADR 0007).
 *
 * <p>Everything Click-shaped lives here and nowhere else. The domain speaks whole
 * som and Qoida states; this package is where those become Click's som decimal
 * strings on the payment side, Click's tiyin integers on the fiscal side, and
 * Click's {@code -1 … -9} note strings on the callback side.
 *
 * <p>Three properties of this provider decide the shape of the code below, and
 * each one costs money if it is treated as an implementation detail.
 *
 * <ul>
 *   <li><b>The units flip mid-integration.</b> Click's SHOP API {@code amount} and
 *       its payment calls are in <em>som</em>; its fiscalization {@code Price} and
 *       {@code VAT} are in <em>tiyin</em> — for the same payment. Every method here
 *       takes a {@code SomAmount} or a {@code TiyinAmount}, so which side of the
 *       boundary a figure is on is readable from its type and the conversion
 *       happens exactly once, in {@code TiyinAmount.of}.</li>
 *   <li><b>The inbound signature is the only authentication there is.</b> SHOP API
 *       carries no auth header. An MD5 over a secret-prefixed concatenation of the
 *       <em>raw received strings</em> is all that stands between an anonymous form
 *       post and a credited order, so the raw strings are never reformatted before
 *       hashing — see {@link uz.qoida.platform.payments.infrastructure.click.ClickSignature}.</li>
 *   <li><b>The outbound error enumeration is unpublished.</b> Click documents HTTP
 *       statuses and shows {@code error_code: 0} in every example, and nothing
 *       else. What is not established is treated as uncertain rather than mapped
 *       from an invented table — see
 *       {@link uz.qoida.platform.payments.infrastructure.click.ClickErrorCodes}.</li>
 * </ul>
 *
 * <p>No Camel here, and none in the payments module at all: outbound calls go out
 * through {@code MerchantApiTransport}, which is the ADR 0007 route seen from this
 * side.
 */
package uz.qoida.platform.payments.infrastructure.click;
