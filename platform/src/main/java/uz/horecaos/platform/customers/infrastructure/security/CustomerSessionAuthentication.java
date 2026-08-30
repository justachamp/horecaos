package uz.horecaos.platform.customers.infrastructure.security;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import uz.horecaos.platform.customers.application.CustomerSession;
import uz.horecaos.platform.iam.api.NonStaffPrincipal;

/**
 * The security-context representation of a signed-in customer (ADR 0051,
 * ADR 0049).
 *
 * <p><strong>No authorities, ever, and the list is not merely empty by
 * default.</strong> A customer holds no ADR 0025 capability and no ADR 0003
 * organization role; what authorizes them is ownership of the row they are
 * touching, which is a comparison the handler makes and an authority cannot
 * express. Granting a placeholder authority here — {@code ROLE_CUSTOMER}, say —
 * would be a claim the rest of the platform could come to read as authorization,
 * and the first method-security expression that tested for it would be a
 * privilege the customer never actually had checked against anything.
 *
 * <p>Carries no credential. The token was hashed, looked up, and dropped by the
 * filter; keeping it on the principal would put a live bearer in every heap dump
 * and in anything that ever serialises an {@link Authentication}.
 *
 * <p>The principal is a {@link NonStaffPrincipal} so the cross-cutting machinery
 * that only wants a stable handle for the caller — ADR 0031's idempotency
 * scoping — can have one without knowing what a customer is.
 */
public final class CustomerSessionAuthentication implements Authentication, NonStaffPrincipal {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient CustomerSession session;

    public CustomerSessionAuthentication(CustomerSession session) {
        this.session = Objects.requireNonNull(session, "A customer session is required");
    }

    public CustomerSession session() {
        return session;
    }

    @Override
    public String subject() {
        return session.actorSubject();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    /** Never the token. See the class comment. */
    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getDetails() {
        return session;
    }

    @Override
    public Object getPrincipal() {
        return this;
    }

    @Override
    public boolean isAuthenticated() {
        return true;
    }

    /**
     * Refuses to be de-authenticated in place.
     *
     * <p>Spring's contract allows {@code setAuthenticated(false)}, and honouring
     * it would let any code holding this object silently turn a resolved session
     * into an unauthenticated one that still reports an account. Replacing the
     * context's authentication is the supported way to end a request's identity.
     */
    @Override
    public void setAuthenticated(boolean authenticated) {
        throw new IllegalArgumentException(
                "A customer session authentication is created resolved and cannot be altered");
    }

    /** The account, and deliberately not the phone number that proved it. */
    @Override
    public String getName() {
        return session.actorSubject();
    }
}
