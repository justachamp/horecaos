package uz.qoida.platform.marketing.domain;

/**
 * The five comparisons the catalogue offers (ADR 0044).
 *
 * <p>Five and not more. Every additional operator is another SQL fragment to get
 * right and another shape a stored predicate can take, and the catalogue is small
 * on purpose: ADR 0044 says extend it when a predicate is requested three times,
 * and extend the catalogue rather than the language.
 */
public enum PredicateOperator {

    AT_LEAST,
    AT_MOST,
    BETWEEN,
    IN,
    NOT_IN
}
