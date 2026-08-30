package uz.qoida.platform.customers.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that an endpoint is authorised by ownership rather than by an ADR 0025
 * capability.
 *
 * <p>Capabilities are delegated staff authority: a manager may refund because a
 * grant says so. A customer paying for their own basket is not exercising
 * delegated authority over anything, and there is no grant row per customer for
 * them to hold — so a storefront handler declaring {@code ORDER_PLACE} answers
 * 403 to precisely the caller it was written for.
 *
 * <p>Nothing enforces this annotation, and that is deliberate. The check it
 * stands for is a row comparison the handler has to make against the resource it
 * actually touches — this cart, this order, this account — and an interceptor
 * working from path variables cannot make it. What the annotation buys is that
 * {@code EndpointCapabilityDeclarationTests} can tell an endpoint that decided to
 * be ownership-checked from one that forgot to declare anything, so the absence
 * of {@code @RequiresCapability} stays a decision somebody wrote down.
 *
 * <p>It says nothing about idempotency. A mutating endpoint still needs
 * {@code @Idempotent} under ADR 0031; the two are separate because the caller who
 * may act and the effect of acting twice are separate questions.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CustomerOwned {
}
