package uz.horecaos.platform.web.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that an endpoint causes an effect, and therefore requires an
 * {@code Idempotency-Key} under ADR 0031.
 *
 * <p>Exists because idempotency used to be carried by
 * {@code @RequiresCapability(mutating = true)} and nothing else. That coupled two
 * unrelated decisions: who may call an endpoint, and what a second identical call
 * must do. The storefront is where they came apart — a customer opening a payment
 * session holds no ADR 0025 capability, so dropping the capability declaration
 * from that handler would have silently dropped its replay protection with it,
 * and a double-tapped pay button would open a second attempt against the same
 * order.
 *
 * <p>{@code @RequiresCapability(mutating = true)} still implies this, so no
 * existing endpoint changes. Declare this one where there is no capability to
 * hang it on.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Idempotent {
}
