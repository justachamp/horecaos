package uz.horecaos.platform.customers.api;

/**
 * Where a lead came from (ADR 0111 §4).
 *
 * <p>The storefront and the Telegram bot already create the customer account on first sign-in, so
 * a lead exists only for a contact that is not yet an order, a reservation or a signed-in
 * account: a form, a message from an unlinked chat, a reservation that named no customer, a
 * callback request, an aggregator's first order that arrived with a masked identity, a catering
 * enquiry -- and a campaign scenario's call step (ADR 0112), which lands in the same queue with
 * the same capabilities and the same audit so that {@code marketing} never keeps a parallel call
 * queue of its own.
 *
 * <p>There is deliberately no "phoned in" value: an operator who types in what a caller said
 * records the source of the request, which is one of these, not the medium it arrived by.
 */
public enum LeadSource {
    STOREFRONT,
    TELEGRAM_BOT,
    SITE,
    RESERVATION,
    CALLBACK_REQUEST,
    AGGREGATOR_FIRST_ORDER,
    B2B_CATERING_ENQUIRY,
    CAMPAIGN_SCENARIO
}
