/**
 * The reconciliation rule library (ADR 0024, step 6).
 *
 * <p>ADR 0024 rejected row counts as reconciliation evidence — "counts match
 * while money, ancestry, and status are wrong" — and named what it wants instead:
 * exact counts and checksums for authoritative ids, exact money totals by
 * currency and status, and zero cross-tenant or invalid ancestry. Those are the
 * four rules here.
 *
 * <p>A rule is a pair of queries over two databases and a comparison, which is
 * why evaluation is code and only the declaration is a table. {@code
 * migration.reconciliation_rules} carries the severity and tolerance <em>per
 * version</em>, so a rule loosened later cannot make a past approval look
 * stricter than it was; {@code migration.reconciliation_results} carries both
 * sides of every comparison, so a finding can be re-derived months later without
 * re-running anything.
 *
 * <p>Three properties hold across every rule and are worth stating once.
 *
 * <p><strong>A rule is evaluated per dimension, never summed.</strong> A money
 * total that nets a shortfall in completed orders against an excess in cancelled
 * ones reconciles to zero while both figures are wrong. Currency, status and
 * provider are separate slices for that reason, and the empty dimension key is
 * reserved for a rule that genuinely has one number.
 *
 * <p><strong>Money is exact integers in minor units.</strong> {@link
 * java.math.BigInteger} throughout, matching {@code numeric(38,0)} on the results
 * table, and never a double. For UZS a minor unit is a whole som, so a difference
 * of 1 here is one som — a formatter that asked ISO 4217 for a decimal count and
 * divided by 100 would report a hundredth of the discrepancy.
 *
 * <p><strong>The count and the checksum are one rule in two parts.</strong> A run
 * that dropped one row and duplicated another has the right count and the wrong
 * set. Neither half is evidence alone, which is exactly ADR 0024's objection to
 * counts, and both are CRITICAL for the same reason.
 */
package uz.qoida.platform.migration.application.reconciliation;
