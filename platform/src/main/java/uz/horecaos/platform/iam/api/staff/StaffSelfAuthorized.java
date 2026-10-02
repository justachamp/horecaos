package uz.horecaos.platform.iam.api.staff;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import uz.horecaos.platform.iam.api.Capability;

/**
 * Declares an endpoint authorised by the caller holding a capability at
 * <em>any</em> scope in the tenant, and touching only the caller's own row
 * (ADR 0139), the staff analogue of {@code CourierSelfAuthorized}.
 *
 * <p>Scope coverage cannot express this. A person's own profile belongs to no
 * location, and ADR 0025's rule is that a grant covers downward only: a grant at
 * {@code LOCATION} scope never covers a {@code TENANT} route, and nearly every
 * member of staff holds only a location grant. Declaring the route at {@code
 * TENANT} scope would therefore lock out the line cook it is written for.
 *
 * <p>So the enforcement interceptor checks something different here, and says
 * so: that the caller's grants in the path's tenant carry the capability at
 * some scope. That is a weaker statement than a coverage check, and it is
 * sound only because of the second half of the contract, which the handler
 * owns: it resolves the member from the token subject and the path tenant,
 * never from a supplied id, so the capability can be turned into the power to
 * act on the caller's own row and no other.
 *
 * <p>{@code EndpointCapabilityDeclarationTests} counts this as an authorization
 * strategy, so combining it with {@code @RequiresCapability} is refused: the
 * capability interceptor runs first and would answer 403 to the very caller
 * this exists for.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface StaffSelfAuthorized {

    Capability value();
}
