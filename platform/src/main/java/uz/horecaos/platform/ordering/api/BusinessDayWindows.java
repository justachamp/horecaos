package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The tenant's business-day boundary (ADR 0043), in the shape ordering needs to
 * scope a live counts read to "today".
 *
 * <p>A port, declared here and implemented by reporting, for the reason this
 * package's own doc gives: reporting already depends on {@code ordering.api}
 * (its digest scheduler reads {@link OrderCountsQuery}), so ordering cannot
 * depend on reporting without making the two cyclic and failing
 * {@code ModularArchitectureTests}. The boundary itself stays where ADR 0043
 * put it — {@code reporting.business_day_policies}, resolved by
 * {@code BusinessDayService} — and ordering never learns how it is stored.
 *
 * <p>The whole reason this exists rather than a {@code date_trunc('day', …)} in
 * the SQL: a restaurant that closes at 02:00 files those orders under the
 * previous trading day, and a supervisor reading «Отменено» off a board cut at
 * UTC midnight sees a counter that resets at 05:00 local, mid-service.
 */
public interface BusinessDayWindows {

    /**
     * The half-open window {@code [from, to)} of the business day that contains
     * {@code at}, for this tenant's own boundary and timezone.
     *
     * @throws IllegalStateException if the tenant has no timezone at all, which
     *                               is a provisioning fault rather than a
     *                               condition a caller can recover from
     */
    Window businessDayContaining(UUID tenantId, Instant at);

    /**
     * A half-open instant range: {@code from} inclusive, {@code to} exclusive.
     *
     * <p>Half-open because the alternative — an inclusive end — either
     * double-counts the order placed exactly on the boundary or drops it,
     * depending on which of two adjacent days is asked first.
     */
    record Window(Instant from, Instant to) {

        public Window {
            Objects.requireNonNull(from, "A window needs a start");
            Objects.requireNonNull(to, "A window needs an end");
            if (!to.isAfter(from)) {
                throw new IllegalArgumentException(
                        "A business day window ends after it starts, was " + from + ".." + to);
            }
        }
    }
}
