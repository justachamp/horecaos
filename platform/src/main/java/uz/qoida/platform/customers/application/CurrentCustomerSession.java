package uz.qoida.platform.customers.application;

import java.util.Optional;

/**
 * The customer session on the current request, if the caller presented one
 * (ADR 0051).
 *
 * <p>A port so that {@link PrincipalCustomer} — which decides which account a
 * caller is — stays free of Spring Security types. The implementation reads the
 * security context, and lives in {@code customers.infrastructure.security} with
 * the filter that put it there.
 *
 * <p>Empty means "this request carries no customer session", which is the honest
 * answer for a staff token, for the pre-account surface, and for an anonymous
 * read of a published menu. It never means "the session was rejected": a
 * presented-and-refused token never reaches a handler at all, because the filter
 * answers it, and answering it there is what keeps an expired session
 * distinguishable from no session.
 */
@FunctionalInterface
public interface CurrentCustomerSession {

    Optional<CustomerSession> get();
}
