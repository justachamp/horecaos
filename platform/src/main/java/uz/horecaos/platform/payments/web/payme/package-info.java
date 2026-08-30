/**
 * Payme's Merchant API, from the side that receives it (ADR 0013, ADR 0031).
 *
 * <p>This is the Payme integration. Payme is the JSON-RPC client and HorecaOS is the
 * server; the only thing HorecaOS ever sends Payme is an unsigned checkout link, and
 * everything that decides whether an order is paid arrives on the single endpoint
 * in this package.
 *
 * <p><strong>Every response is HTTP 200, errors included.</strong> Payme reads any
 * other status as {@code -32400}, so a 401, a 404, a 406, a 415 and a 500 are all
 * the same wrong answer. That constraint shapes every decision here and is worth
 * enumerating, because each one is a way an ordinary Spring MVC endpoint would
 * fail it:
 *
 * <ul>
 * <li>The endpoint is mapped for <em>every</em> HTTP method rather than for POST
 * alone, because a method-restricted mapping answers 405. A non-POST arrival is
 * answered {@code -32300}, which is the code Payme documents for it and which
 * neither of Payme's own templates implements.</li>
 * <li>The body is taken as raw bytes and parsed here, because the documented
 * request carries {@code Content-Type: text/json} while Payme's own PHP template's
 * test call sends {@code application/json}. A converter bound to one of them
 * answers 415 to the other.</li>
 * <li>The content type of the response is set explicitly, which takes the response
 * out of content negotiation. A negotiated response answers 406 to a request whose
 * {@code Accept} header does not name JSON, and Payme's is undocumented.</li>
 * <li>Nothing is allowed to leave this class as an exception. An unexpected fault
 * is caught and answered {@code -32400} in a 200 body, because Spring's error
 * handling would answer 500 and Payme would read that as {@code -32400} anyway —
 * without the request id, and after a delay.</li>
 * </ul>
 *
 * <p><strong>The endpoint authenticates itself</strong>, and the reason is the
 * same constraint. See {@link uz.horecaos.platform.payments.web.payme.PaymeEndpointSecurity}.
 *
 * <p><strong>It is not an ADR 0031 endpoint and cannot be.</strong> No idempotency
 * key, no expected version, no cursor page, and no Problem Details: the request
 * shape and the response shape are both Payme's, the status is always 200, and a
 * Problem Details body would simply not be understood. Idempotency is still a hard
 * requirement here — Payme sends every mutating method at least twice — but it is
 * keyed on {@code params.id}, which Payme minted, rather than on a header HorecaOS
 * would have had to ask for.
 *
 * <p>It lives under {@code /providers} rather than under {@code /api/v1}, which is
 * the root the endpoint-authorization test already recognises as machine-called and
 * exempts from ADR 0025's capability declaration — there is no actor here to hold a
 * capability, because Payme's Basic credential belongs to a cashbox rather than to a
 * person. Keeping it outside {@code /api/v1} also matters on its own account: the
 * path is exempt from the platform's bearer-token filter chain, and an exemption
 * inside the authenticated namespace is one careless wildcard away from exempting
 * part of the real API.
 */
package uz.horecaos.platform.payments.web.payme;
