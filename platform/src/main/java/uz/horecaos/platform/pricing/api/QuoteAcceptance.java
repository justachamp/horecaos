package uz.horecaos.platform.pricing.api;

import org.jspecify.annotations.Nullable;

/**
 * The outcome of accepting a quote (ADR 0018).
 *
 * <p>Three outcomes and no exception, because all three are ordinary business
 * answers a storefront has to render differently: pay this, re-quote and show
 * the customer why, or start again.
 *
 * @param totalMinor the accepted total in integer minor units; zero unless the
 *                   outcome is {@link Outcome#ACCEPTED}
 * @param currency null unless the outcome is {@link Outcome#ACCEPTED}
 */
public record QuoteAcceptance(
        Outcome outcome, long totalMinor, @Nullable String currency) {

    public enum Outcome {
        ACCEPTED,

        /**
         * The quote's inputs changed under the customer's feet. Never a silent
         * charge of the difference, which is what customers experience as a scam.
         */
        PRICE_CHANGED,

        /** Past its TTL, or already accepted by a checkout that won the race. */
        EXPIRED
    }

    public boolean isAccepted() {
        return outcome == Outcome.ACCEPTED;
    }
}
