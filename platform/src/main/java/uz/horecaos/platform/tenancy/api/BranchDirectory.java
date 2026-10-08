package uz.horecaos.platform.tenancy.api;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A brand's branches as a customer may be told about them: where they are, how
 * to reach them, and when they are open (ADR 0069, ADR 0036).
 *
 * <p>Everything here is already published. A restaurant's address and opening
 * hours are printed on the receipt, shown in the storefront and handed to every
 * courier -- V0023's own distinction from a customer's address, which stays
 * encrypted -- so answering "where are you" from this port discloses nothing the
 * tenant has not chosen to advertise. What it deliberately does not answer is
 * "is it open right now": that is {@link ServiceabilityResolver}, the one
 * resolver every other caller already shares, and a second answer here would be
 * the second implementation ADR 0036 forbids.
 *
 * <p>Tenant- and brand-scoped in every implementation: a location id from
 * another tenant is simply absent.
 */
public interface BranchDirectory {

    /** Every {@code ACTIVE} branch of an {@code ACTIVE} brand of an {@code ACTIVE} tenant, by name. */
    List<Branch> activeBranches(UUID tenantId, UUID brandId);

    /**
     * @param contactPhone the branch's own business number, which the tenant
     *                     publishes -- never a person's
     * @param point        null when the branch has no coordinates
     * @param weeklyHours  the weekly windows bound to each fulfilment mode that has
     *                     a timetable at all; a mode absent from the map has none,
     *                     which is "unknown" and never "always open"
     */
    record Branch(
            UUID locationId,
            String name,
            @Nullable String addressLine,
            @Nullable String district,
            @Nullable String city,
            @Nullable String landmark,
            @Nullable String contactPhone,
            @Nullable GeoPoint point,
            String timezone,
            Map<FulfillmentMode, List<WeeklyWindow>> weeklyHours) {

        public Branch {
            weeklyHours = Map.copyOf(weeklyHours);
        }
    }

    /** One weekly window in the branch's own local time. {@code dayOfWeek} is ISO-8601: 1 is Monday. */
    record WeeklyWindow(int dayOfWeek, LocalTime opensAt, LocalTime closesAt) {}
}
