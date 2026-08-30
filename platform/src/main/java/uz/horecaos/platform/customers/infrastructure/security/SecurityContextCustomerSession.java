package uz.horecaos.platform.customers.infrastructure.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import uz.horecaos.platform.customers.application.CurrentCustomerSession;
import uz.horecaos.platform.customers.application.CustomerSession;

/**
 * Reads the customer session off the security context (ADR 0051).
 *
 * <p>The whole of the adapter, and the reason {@code PrincipalCustomer} needs no
 * Spring Security import to decide which account a caller is.
 */
@Component
public class SecurityContextCustomerSession implements CurrentCustomerSession {

    @Override
    public Optional<CustomerSession> get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof CustomerSessionAuthentication customer
                ? Optional.of(customer.session())
                : Optional.empty();
    }
}
