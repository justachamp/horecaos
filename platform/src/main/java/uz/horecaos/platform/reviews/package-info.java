/**
 * Order reviews and service recovery: a customer's own rating of their own completed order (ADR 0071),
 * the tags that say which part went wrong, the public face a tenant may switch on under a moderation that
 * hides words and never numbers, and the worklist of cases about low reviews (ADR 0159).
 *
 * <p>A leaf-shaped module by design, the same shape {@code referral} and {@code conversations} ship. It
 * keeps ADR 0071's entity and ADR 0048's remedies, and takes from ADR 0013 only the idea of a case with a
 * lifecycle and a clock -- placed here and not in a {@code recovery} module, because a case's only source
 * is a review created in the same transaction.
 *
 * <p><strong>Edges.</strong> Out of {@code reviews}: {@code ordering.api} (whose order is this, and what
 * state is it in), {@code customers.api} (account ownership, and the consent recorder), {@code pricing.api}
 * ({@code BenefitGrantDirectory}), {@code media.api} (a tag icon), {@code iam.api} (staff members, field
 * protection), {@code tenancy.api} (configuration, policies, branches), {@code audit.api}. Into {@code
 * reviews}: {@code integration -> reviews.api} (the Telegram star buttons and the outbox listener), {@code
 * notifications -> reviews.api} (the prompt policy and the case events), {@code reporting ->
 * reviews.api} (two fact sources) and {@code payments -> reviews.spi} ({@code payments} implements {@code
 * CompensationDirectory}). {@code reviews} imports nothing from {@code notifications}, {@code payments},
 * {@code integration} or {@code reporting}: it publishes events and is called, never the caller, which is
 * what keeps every one of those edges from closing a cycle. {@code ModularArchitectureTests} verifies it.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Reviews")
package uz.horecaos.platform.reviews;
