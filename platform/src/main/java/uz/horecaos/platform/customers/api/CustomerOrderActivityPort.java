package uz.horecaos.platform.customers.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The one order-derived fact the Customers section's grid needs: how many
 * distinct customers ordered inside a window (frontend information
 * architecture §5.1's "ordered today" header counter).
 *
 * <p>Defined here, in {@code customers.api}, rather than consumed from {@code
 * ordering.api.OrderDirectory} the way {@code notifications} reads order data
 * — deliberately the inverted direction. {@code ordering.web.StorefrontOrderingController}
 * already depends on {@code customers.api} (for {@code CustomerOwned}/{@code
 * CurrentCustomer}), so a {@code customers -> ordering} dependency would close
 * a cycle {@code ModularArchitectureTests} refuses to allow. This interface
 * keeps the dependency arrow pointing the one way it already points: {@code
 * customers.application.CustomerListQueryService} depends on its own
 * module's port, and {@code ordering} is the one that implements it —
 * {@code ordering -> customers.api}, the same direction the rest of the
 * module already has.
 */
public interface CustomerOrderActivityPort {

    /**
     * Distinct customer accounts with at least one order inside {@code [from,
     * to)}, tenant-wide. Guest orders are not counted: there is no account
     * for them to be "a customer who ordered" about.
     *
     * <p>Defaulted to zero so a hand-written test double needs no mechanical
     * implementation of a read it never exercises — the same convention
     * {@code OrderDirectory}'s own optional reads use.
     */
    default long customersOrderedBetween(UUID tenantId, Instant from, Instant to) {
        return 0;
    }
}
