package uz.qoida.platform.partner.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import uz.qoida.platform.iam.api.Capability;

/**
 * Declares an endpoint authorised by an active partner credential and one of
 * that installation's live bindings (ADR 0049).
 *
 * <p>The capability is the code-owned name of the operation; it is not an
 * {@code iam.grants} row. A confidential client is not tenant staff and cannot
 * inherit a staff role. The handler must authenticate the client, match the
 * tenant in the path, and constrain the affected resource to
 * {@link PartnerPrincipal#bindingIds()} before causing an effect.
 *
 * <p>This annotation is a declaration, not an interceptor. Binding reach depends
 * on the venue or order resolved from the request body, which is information a
 * path-variable interceptor does not have. The partner application service owns
 * that check; {@code EndpointCapabilityDeclarationTests} makes forgetting the
 * declaration, or combining it with a staff capability, a build failure.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PartnerBound {

    Capability value();
}
