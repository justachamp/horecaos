package uz.horecaos.platform.iam.api;

/**
 * A caller who is authenticated by a relationship rather than by a realm token
 * (ADR 0049, ADR 0051).
 *
 * <p>ADR 0049 established that a customer, a partner client and a courier are
 * authorized by what they are related to rather than by an {@code iam.grants}
 * row. ADR 0051 adds the other half for the customer: they are <em>authenticated</em>
 * by a platform-issued session too, so there is no JWT under their request and
 * {@link CurrentActor} has no {@code JwtAuthenticationToken} to read.
 *
 * <p>This is what such a principal exposes to the cross-cutting machinery that
 * only ever wanted a stable handle for the caller — ADR 0031's idempotency
 * scoping, chiefly, which keys a replay window by subject so that two customers
 * sending the same key cannot read each other's stored response. Without this,
 * every {@code @Idempotent} storefront mutation would refuse a signed-in customer
 * with an access-denied error raised inside an interceptor, which is a 403 whose
 * message describes the wrong problem entirely.
 *
 * <p><strong>It confers nothing.</strong> An actor built from one carries no
 * global roles and no organization roles, so it satisfies no ADR 0003 tenant rule
 * and holds no ADR 0025 capability; a staff endpoint reached with one is refused
 * exactly as an unknown subject is. The subject it returns is namespaced by its
 * issuer so it cannot collide with a Keycloak subject, for the same reason
 * {@code PrincipalCustomer} insists that {@code (issuer, subject)} and not
 * {@code subject} alone is the identity.
 */
public interface NonStaffPrincipal {

    /**
     * A stable, opaque handle for this caller, unique across every principal
     * model the platform has.
     *
     * <p>Never personal data. A phone number here would reach an idempotency row,
     * a log line and an audit record, all of which ADR 0029 keeps it out of.
     */
    String subject();
}
