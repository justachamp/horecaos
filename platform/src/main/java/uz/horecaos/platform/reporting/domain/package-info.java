/**
 * The vocabulary of ADR 0043: what a number means, which day it falls on, and
 * which bucket an elapsed time lands in.
 *
 * <p>Nothing here reaches a database, a provider, or a clock. That is deliberate.
 * The metric registry, the business-day boundary, and the SLA bucket set are the
 * three definitions that have to be identical in the console tile, the export,
 * the close job, and the API response, so they live in one place with no runtime
 * of their own and can be tested without infrastructure.
 *
 * <p>The registry in particular is code and not rows. A definition that can be
 * changed with an UPDATE is a definition two surfaces can disagree about, and
 * ADR 0043 exists because a dashboard tile and a finance CSV that disagree about
 * average check destroy trust in both.
 */
package uz.horecaos.platform.reporting.domain;
