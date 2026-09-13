package uz.horecaos.platform.customers.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The tenant's business-day boundary (ADR 0043), in the shape the Customers
 * grid header needs to scope "registered today" and "ordered today" to a
 * real business day rather than the UTC calendar.
 *
 * <p>A port, declared here and implemented by reporting — the identical shape
 * {@code ordering.api.BusinessDayWindows} already uses, for the same reason
 * that port's own doc gives: the boundary is {@code reporting}'s (ADR 0043,
 * {@code BusinessDayService}), and a second interface exists per consumer
 * rather than one shared {@code reporting.api} package so that each module
 * depends on exactly the one read it needs and reporting never has to decide
 * what a generic cross-module surface should look like.
 *
 * <p>Before this port existed, {@code CustomerListQueryService#counts} computed
 * "today" against UTC midnight — a second, unregistered notion of a day (row
 * {@code 5.1a}) that disagreed with every other screen in the console for the
 * five hours between UTC midnight and midnight in a Tashkent tenant's own
 * zone: a customer who registers at 02:00 local stops counting as
 * "registered today" the moment the wall clock passes 05:00, because the
 * UTC-dated window has already rolled over to a range that never contained
 * that row's timestamp.
 */
public interface BusinessDayWindows {

    /**
     * The half-open window {@code [from, to)} of the business day that
     * contains {@code at}, for this tenant's own boundary and timezone.
     *
     * @throws IllegalStateException if the tenant has no timezone at all, which
     *                               is a provisioning fault rather than a
     *                               condition a caller can recover from
     */
    Window businessDayContaining(UUID tenantId, Instant at);

    /** A half-open instant range: {@code from} inclusive, {@code to} exclusive. */
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
