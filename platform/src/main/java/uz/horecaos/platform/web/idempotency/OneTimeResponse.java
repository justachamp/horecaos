package uz.horecaos.platform.web.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that an idempotent endpoint's successful response carries a value that is shown
 * exactly once and must never be kept: a freshly minted secret (ADR 0028, ADR 0031).
 *
 * <p>An idempotency record keeps the verbatim response for at least a day so a retry can be
 * answered with the first call's own result. For a response that carries a credential that is
 * the wrong thing to keep, and the usual protection does not apply: {@link ResponseBodyProtection}
 * encrypts a classified body under the <em>tenant's</em> key, and an endpoint that sits above the
 * tenants — the platform's own registry — has no tenant to encrypt it under. The answer is not a
 * key borrowed from somewhere else but no copy at all.
 *
 * <p>What the record still does is bar a second execution: the same key sent again is answered
 * {@code 409} with the reason, never with the secret and never by minting a second one. A rejection
 * (a status of 300 or more) carries no secret and is kept and replayed as for any other endpoint.
 *
 * <p>Reviewed one by one: {@code IdempotentResponseClassificationTests} fails on any handler
 * carrying this that is not named there, because the annotation exempts a classified response
 * from the rule that it be encrypted under a tenant, and a convenient way round that rule is
 * exactly what the rule exists to prevent.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OneTimeResponse {}
