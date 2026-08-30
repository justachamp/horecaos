package uz.qoida.platform.courier.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import uz.qoida.platform.iam.api.Capability;

/**
 * Declares an endpoint authorised by the calling courier's own relationship to
 * the affected shift or handover (ADR 0049).
 *
 * <p>The capability names the operation but is not delegated through a staff
 * role. The handler resolves a courier from the signed token subject and tenant,
 * then the application service proves that the affected row belongs to that
 * courier. A caller cannot supply a courier id as authority.
 *
 * <p>This is a declaration rather than an interceptor because ownership is a
 * comparison against the shift or handover row, not a property of the URL.
 * {@code EndpointCapabilityDeclarationTests} rejects an endpoint that combines
 * this strategy with a staff capability or omits an authorization strategy.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CourierSelfAuthorized {

    Capability value();
}
