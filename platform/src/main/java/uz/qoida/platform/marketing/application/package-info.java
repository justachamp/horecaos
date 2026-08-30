/**
 * The marketing module's services (ADR 0044): the metric projection, the audience
 * evaluator, suppression, the campaign state machine, and the batched expansion
 * that turns a snapshot into ADR 0020 intents.
 *
 * <p>Two rules divide this package from the rest of the platform.
 *
 * <p>Nothing here decides consent. ADR 0015 owns the append-only record with its
 * policy version and its evidence; {@link uz.qoida.platform.customers.api.ConsentDirectory}
 * is read and its answer is acted on, never reinterpreted. A second module
 * reasoning about consent would be a second answer to a legal question that has
 * one.
 *
 * <p>Nothing here sends a message. Every outbound message is an ADR 0020 intent of
 * class {@code MARKETING} created through
 * {@link uz.qoida.platform.marketing.api.CampaignMessagePort}. Marketing never
 * calls a gateway, never holds a phone number, an email address, a push token, or
 * a chat id, and never renders a body onto a row.
 */
package uz.qoida.platform.marketing.application;
