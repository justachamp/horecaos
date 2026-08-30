package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.iam.api.CurrentActor;

/**
 * Binds {@link CurrentCustomer} to whatever credential is on the current request
 * (ADR 0015, ADR 0051).
 *
 * <p>Two principal models reach here, and they are answered in order.
 *
 * <p><strong>A platform-issued customer session, first.</strong> It resolves
 * without a lookup, because the account is a column on the session row rather
 * than a claim in the token — the token itself is 256 opaque bits and says
 * nothing. What is still checked is that the session speaks for the tenant and
 * the partition being addressed; see {@link CustomerSession#covers}, which is
 * where the identity-mode split actually happens.
 *
 * <p><strong>A realm token, second, and unchanged.</strong> The issuer is
 * configuration, never a claim read back out of the token being checked.
 * {@code (issuer, subject)} is the identity, and a subject is only unique within
 * the realm that minted it — so taking the issuer from the token would let a
 * second realm that the resource server also trusted mint a subject string
 * matching somebody else's and resolve to their account. The resource server
 * validates one issuer, and this is that one.
 *
 * <p><strong>Why a customer session does not break that argument.</strong> It
 * would, if it were a second JWT issuer — which is the shape ADR 0051 considered
 * and rejected. It is not: an opaque token carries no subject, enters no subject
 * namespace, and cannot collide with one. There is still exactly one issuer whose
 * <em>claims</em> this platform believes.
 *
 * <p>Resolution never creates. {@link CustomerIdentityService#resolve} is the
 * sign-in path and is governed by the tenant's identity policy; a storefront read
 * that could bring an account into existence would mean a request for a stranger's
 * cart silently registering the requester as a customer of that brand.
 */
@Service
public class PrincipalCustomer implements CurrentCustomer {

    private final CustomerIdentityService identity;
    private final CurrentCustomerSession currentSession;
    private final CustomerPolicyLookup policies;
    private final CurrentActor currentActor;
    private final Clock clock;
    private final String trustedIssuer;

    public PrincipalCustomer(
            CustomerIdentityService identity,
            CurrentCustomerSession currentSession,
            CustomerPolicyLookup policies,
            CurrentActor currentActor,
            Clock clock,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String trustedIssuer) {

        this.identity = identity;
        this.currentSession = currentSession;
        this.policies = policies;
        this.currentActor = currentActor;
        this.clock = clock;
        this.trustedIssuer = trustedIssuer;
    }

    @Override
    public Optional<CustomerAccountRef> account(UUID tenantId, UUID brandId) {
        Optional<CustomerSession> session = currentSession.get();
        if (session.isPresent()) {
            // Empty rather than an exception when the session does not cover this
            // tenant or brand. The caller is signed in, at somewhere else; the
            // honest answer to "which account is this, here" is that there is not
            // one, and every caller already handles that — it is the same answer a
            // customer who has never visited this brand gets.
            return session.filter(live -> live.covers(
                            tenantId,
                            brandId,
                            policies.policyFor(tenantId, clock.instant()).mode()))
                    .map(live -> new CustomerAccountRef(live.accountId(), tenantId));
        }
        return identity.find(
                tenantId, brandId, trustedIssuer, currentActor.get().subject());
    }

    @Override
    public boolean owns(UUID tenantId, UUID accountId) {
        Optional<CustomerSession> session = currentSession.get();
        if (session.isPresent()) {
            CustomerSession live = session.get();
            // Narrower than the token path below, deliberately. That one is
            // partition-blind because a Keycloak subject is linked to every
            // account the person has in the tenant, and the callers that ask this
            // have no brand in their path. A session is not: it was minted in one
            // partition and names one account, so under BRAND_ISOLATED the same
            // person's account at a sibling brand is not the caller's here. It
            // becomes theirs when they sign in there, which is what
            // BRAND_ISOLATED means.
            return live.tenantId().equals(tenantId) && identity.sameAccount(tenantId, live.accountId(), accountId);
        }
        return identity.owns(tenantId, trustedIssuer, currentActor.get().subject(), accountId);
    }
}
