package uz.horecaos.platform.customers.domain;

/**
 * Why a lead was declined or lost (ADR 0111 §6).
 *
 * <p>A closed vocabulary, never free text: a reason typed into a box is where a guest's name ends
 * up in a report, and the whole point of recording one is to be able to count them. The two the
 * decision names are branch reasons -- out of catering capacity, wrong cuisine -- and the rest are
 * the ones a call centre meets every day.
 */
public enum LeadClosedReason {
    OUT_OF_CAPACITY,
    OUT_OF_COVERAGE,
    WRONG_CUISINE_OR_MENU,
    NOT_REACHABLE,
    NOT_INTERESTED,
    DUPLICATE,
    SPAM_OR_WRONG_NUMBER,
    OTHER
}
