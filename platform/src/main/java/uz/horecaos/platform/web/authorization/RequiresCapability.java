package uz.horecaos.platform.web.authorization;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * Declares the ADR 0025 capability an endpoint requires.
 *
 * <p>Every mutating endpoint must carry this annotation; a startup test fails
 * the build otherwise, so an endpoint cannot quietly ship without an
 * authorization decision attached to it.
 *
 * <p>The declaration is enforced: a principal who does not hold the capability
 * at the scope is refused with ADR 0031's {@code INSUFFICIENT_CAPABILITY}.
 * Setting {@code horecaos.authorization.enforce} to false puts the declaration back
 * into shadow mode, where the decision is computed, compared against the ADR
 * 0003 rule and logged without denying anything. That is an opt-out for
 * re-measuring a live estate, not the resting state.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresCapability {

    Capability value();

    /**
     * The scope level the capability is required at. The identifiers are taken
     * from the {@code tenantId}, {@code brandId}, and {@code locationId} path
     * variables of the request.
     */
    ScopeType scope() default ScopeType.TENANT;

    /**
     * Marks an endpoint that causes an external effect or creates a resource,
     * and therefore requires an {@code Idempotency-Key} under ADR 0031.
     */
    boolean mutating() default false;
}
