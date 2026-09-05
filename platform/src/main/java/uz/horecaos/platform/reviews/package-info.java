/**
 * Order reviews: a customer's own rating of their own completed order
 * (ADR 0071).
 *
 * <p>A leaf module by design, the same shape {@code referral} and {@code
 * conversations} already ship. It depends one-way on {@code ordering.api}
 * ({@link uz.horecaos.platform.ordering.api.OrderDirectory}, to resolve whose
 * order this is and whether it is eligible) and {@code customers.api}
 * ({@code CurrentCustomer}, {@code CustomerOwned}, {@code CustomerAccountRef},
 * to authorise a submission by account ownership rather than a capability),
 * and exposes no {@code api} package of its own — nothing outside this module
 * reads a review yet, exactly the state {@code referral} shipped in before it
 * needed one.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Reviews")
package uz.horecaos.platform.reviews;
