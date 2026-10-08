package uz.horecaos.platform.marketing.api;

/**
 * Whether the platform can address a courier by telephone at all (ADR 0146
 * Decision 8, gap-map row 6.4b).
 *
 * <p>It cannot, today. {@code fulfillment.couriers} holds an IAM subject, a
 * display reference and an encrypted name, and no number to send to: a courier
 * signs in through Keycloak, and the only telephone number on their file is a third
 * party's, an emergency contact who never agreed to be in this system. A courier
 * broadcast therefore has an SMS path (an account cleared to carry courier
 * traffic) and no recipients, and which of the two this answers is the owner's
 * open question, not an implementation detail: ADR 0112 names "couriers as an
 * audience" as undecided, and widening its customer-keyed audience shape to a
 * courier identity is a structural change it declines to make in passing.
 *
 * <p>A port that answers {@code false} until somebody builds the source, rather
 * than a broadcast that records "sent to 12 couriers" having messaged nobody.
 */
public interface CourierContactSource {

    /** True only when a courier's own number can be resolved for one call, with its purpose recorded. */
    boolean isWired();
}
