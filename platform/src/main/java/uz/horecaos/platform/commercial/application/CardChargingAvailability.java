package uz.horecaos.platform.commercial.application;

/**
 * Whether a card can be charged through this build right now (ADR 0095): a merchant account is connected,
 * its adapter is wired, and it may be used here.
 *
 * <p>A port of its own, and not a method on {@link CardCharger}, because asking it is not charging: a
 * sweep that would only be told {@code NotConfigured} must not write an attempt row per tenant per pass to
 * learn that, and the console must be able to say "cards are not available yet" without trying one.
 */
public interface CardChargingAvailability {

    boolean available();
}
