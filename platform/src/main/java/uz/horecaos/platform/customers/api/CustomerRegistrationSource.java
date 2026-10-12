package uz.horecaos.platform.customers.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * New brand profiles in a window, counted by brand and by how the account came to exist, for the
 * customer funnel and the acquisition chart (ADR 0167, row 7.6c).
 *
 * <p>Implemented by {@code customers}, read by {@code reporting}'s day close (ADR 0023). The
 * answer is a count and an origin, never an account: a registration is "a person became a
 * customer of this brand today", and the chart needs how many, not who.
 *
 * <p>Counted per brand profile and not per account because an account is tenant-wide (ADR 0015)
 * and the funnel is per brand: one person joining a second brand of the same tenant is a
 * registration for that brand.
 */
public interface CustomerRegistrationSource {

    List<RegistrationCount> registeredBetween(UUID tenantId, Instant fromInclusive, Instant toExclusive);

    /**
     * @param origin the account's {@code origin}: {@code SELF_SERVICE}, {@code OPERATOR}, {@code IMPORT},
     *               {@code AGGREGATOR} or {@code MIGRATION}
     */
    record RegistrationCount(UUID brandId, String origin, int registered) {}
}
