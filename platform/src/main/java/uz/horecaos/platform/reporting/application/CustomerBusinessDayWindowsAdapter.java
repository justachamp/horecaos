package uz.horecaos.platform.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.customers.api.BusinessDayWindows;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;

/**
 * Reporting's answer to the Customers module's {@link BusinessDayWindows}
 * port (ADR 0043, row {@code 5.1a}) — the customers-side twin of {@link
 * BusinessDayWindowsAdapter}, which answers the identically-shaped port
 * {@code ordering.api} declares.
 *
 * <p>A second, near-identical adapter rather than one class implementing both
 * ports: {@code ordering.api.BusinessDayWindows} and {@code
 * customers.api.BusinessDayWindows} are two distinct types with the same
 * method name and parameter list but incompatible return types (each port's
 * own nested {@code Window} record), which Java cannot resolve as overrides
 * of a single method on one class. Each consumer module owns the interface
 * it depends on for the same reason {@code BusinessDayWindowsAdapter}'s own
 * doc gives for ordering's: reporting is not in the business of deciding
 * what a generic cross-module surface looks like, only of answering the one
 * question ADR 0043 makes it the owner of.
 */
@Component
public class CustomerBusinessDayWindowsAdapter implements BusinessDayWindows {

    private final BusinessDayService businessDays;

    public CustomerBusinessDayWindowsAdapter(BusinessDayService businessDays) {
        this.businessDays = businessDays;
    }

    @Override
    public Window businessDayContaining(UUID tenantId, Instant at) {
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        LocalDate date = boundary.dateOf(at);
        return new Window(boundary.startOf(date), boundary.endOf(date));
    }
}
