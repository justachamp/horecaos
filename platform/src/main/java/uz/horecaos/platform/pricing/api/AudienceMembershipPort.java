package uz.horecaos.platform.pricing.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * The audiences a customer belongs to, for the {@code CUSTOMER_SEGMENT} condition
 * (ADR 0140, ADR 0044).
 *
 * <p>A segment is an audience, named by its id. Membership is read from the most
 * recent ready audience snapshot -- the evaluation ADR 0044 already makes
 * explainable -- and counts every candidate the predicates matched: price
 * eligibility is not a message, so the consent and suppression subtractions that
 * decide whether a customer may be written to do not decide whether a customer
 * matches a segment. No id list lives inside a rule (a per-customer entitlement
 * list in a definition table would have no consent, suppression or erasure hook);
 * named customers wait on a static audience.
 *
 * <p>Implemented by {@code marketing}, which already depends on pricing's api.
 */
public interface AudienceMembershipPort {

    /** The ids (as text) of the brand's audiences this account is a candidate of in their latest ready snapshot. */
    Set<String> segmentsOf(UUID tenantId, UUID brandId, UUID customerAccountId);

    /** Which of these audience ids exist in the brand and are not archived; the validator refuses the rest. */
    Set<String> knownAudiences(UUID tenantId, UUID brandId, Collection<String> audienceIds);
}
