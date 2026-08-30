/**
 * Click's SHOP API, from the side that receives it (ADR 0013, ADR 0031).
 *
 * <p>This is the only Click surface that credits an order. The outbound MERCHANT
 * API initiates, queries, reverses and fiscalizes; the redirect is a browser event
 * that proves nothing; and a customer arriving back at {@code return_url} has told
 * HorecaOS nothing at all. Money is learned about here.
 *
 * <p>It is also a public endpoint that takes money instructions from outside with
 * <strong>no authentication header of any kind</strong>. An MD5 over a
 * secret-prefixed concatenation is the whole of the authentication, which is why
 * the signature is verified before any database is touched, why every arrival
 * including a failed one is recorded, and why the failure count per binding is an
 * alert rather than a log line.
 *
 * <p>ADR 0031's conventions do not apply to these two endpoints and cannot: the
 * request is form-encoded, the response is a flat JSON object Click's own parser
 * expects, the status is always 200 with the error inside the body, and there is
 * no idempotency key, no expected version and no problem detail. A Problem Details
 * response here would simply not be understood, and a non-200 is an undocumented
 * case that Click reads as a transport failure and retries.
 */
package uz.horecaos.platform.payments.web.click;
