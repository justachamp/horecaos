package uz.horecaos.platform.loyalty.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * What a points redemption becomes on a fiscal receipt (ADR 0046, ADR 0038).
 *
 * <p><strong>A redemption reaches a fiscal receipt as a per-line discount, not
 * as a tender.</strong> It stays a tender in {@code payments} — it discharges
 * part of the order total, drives the courier's cash figure, and is counted as a
 * tender by reporting — and it is a discount in {@code fiscal}. The two
 * registers answer different questions and ADR 0046 keeps them apart on purpose.
 *
 * <p>The provider contracts force the representation. Click's
 * {@code payment/ofd_data/submit_items} fiscalizes one Click payment: its
 * {@code received_cash} / {@code received_card} / {@code received_ecash} fields
 * describe how <em>that payment</em> was tendered and must sum to it. There is no
 * fourth bucket for platform-held value, and inflating {@code received_ecash}
 * would assert that Click moved money it did not move. Payme fiscalizes the
 * Payme receipt amount from a {@code detail} object fixed before payment and has
 * no tender split at all. Both providers do carry a per-line discount — Click's
 * {@code Discount}, Payme's {@code discount} — and that is the only field in
 * either contract into which a redemption fits.
 *
 * <p>The substance points the same way. Nobody funded the redeemed 12 000: the
 * seller receives 82 000, and the consideration for the supply is 82 000. Under
 * ADR 0018 prices are VAT-inclusive and a discount reduces the gross, so tax
 * follows the consideration down. That is the treatment of a merchant-funded
 * rebate, and it is a tax determination, which is why finance and legal own the
 * confirmation and why it is ADR 0046's first open input.
 *
 * <p>Two rules are in the code below. The redemption is <em>allocated across
 * lines and never carried as a line of its own</em>, because a receipt has no
 * line type meaning "bonus" exactly as ADR 0038 records that it has none meaning
 * "rounding". And <em>lines the redemption policy excludes carry no share</em>,
 * so with the default policy the delivery fee's discount is zero and its own
 * classification and VAT are untouched.
 *
 * <p>The remainder goes to the highest-value line, which is the rule ADR 0038
 * already uses for ADR 0018's rounding remainder, so the two allocations compose
 * instead of fighting over the last som.
 *
 * <p>Pure. The caller passes the accepted quote snapshot's line values and the
 * settled tender amount; nothing is recomputed at fiscalization time, because a
 * recomputation after a partial refund would produce a discount that no longer
 * matches the receipt already issued.
 */
public final class RedemptionAllocation {

    private RedemptionAllocation() {
    }

    /**
     * One line as the quote snapshot recorded it.
     *
     * @param eligible false for a line the redemption policy excludes — the
     *                 delivery fee under the default policy. An ineligible line
     *                 carries no share and keeps its own VAT
     */
    public record Line(UUID lineId, long grossMinor, boolean eligible) {

        public Line {
            if (grossMinor < 0) {
                throw new IllegalArgumentException("A line's gross value is not negative");
            }
        }
    }

    /** The discount to write onto one {@code fiscal.fiscal_document_lines} row. */
    public record LineDiscount(UUID lineId, long discountMinor) {
    }

    /**
     * Allocates a settled points tender across the order's lines.
     *
     * @param lines             the quote snapshot's lines, in any order
     * @param redeemedMinor     the settled amount of the points tender
     * @return one entry per line, including zeros, so a caller writing
     *         {@code discount_minor} onto every row cannot silently skip one
     * @throws IllegalArgumentException when the redemption exceeds the eligible
     *         value. Refused rather than clamped: a discount larger than the
     *         lines it applies to produces a negative receipt line, and clamping
     *         it would hide a redemption cap that was not enforced upstream
     */
    public static List<LineDiscount> allocate(List<Line> lines, long redeemedMinor) {
        if (redeemedMinor < 0) {
            throw new IllegalArgumentException("A redemption is not negative");
        }
        List<Line> eligible = lines.stream().filter(Line::eligible).filter(l -> l.grossMinor() > 0)
                .toList();
        long eligibleTotal = eligible.stream().mapToLong(Line::grossMinor).sum();

        if (redeemedMinor == 0 || eligible.isEmpty()) {
            if (redeemedMinor > 0) {
                throw new IllegalArgumentException(
                        "A redemption of " + redeemedMinor + " has no eligible line to reduce");
            }
            return lines.stream().map(line -> new LineDiscount(line.lineId(), 0L)).toList();
        }
        if (redeemedMinor > eligibleTotal) {
            throw new IllegalArgumentException("A redemption of " + redeemedMinor
                    + " exceeds the eligible line value of " + eligibleTotal);
        }

        // Pro rata to line value, truncated, so every line is at or below its
        // exact share and the remainder is a positive number to place.
        List<LineDiscount> shares = new ArrayList<>();
        long allocated = 0L;
        for (Line line : eligible) {
            long share = Math.multiplyExact(redeemedMinor, line.grossMinor()) / eligibleTotal;
            shares.add(new LineDiscount(line.lineId(), share));
            allocated += share;
        }

        long remainder = redeemedMinor - allocated;
        if (remainder > 0) {
            UUID highest = eligible.stream()
                    .max(Comparator.comparingLong(Line::grossMinor)
                            // Deterministic on a tie, so re-deriving the
                            // allocation for a correction document reproduces the
                            // receipt that was issued rather than a near miss.
                            .thenComparing(Line::lineId))
                    .orElseThrow()
                    .lineId();
            shares.replaceAll(share -> share.lineId().equals(highest)
                    ? new LineDiscount(highest, share.discountMinor() + remainder)
                    : share);
        }

        List<LineDiscount> result = new ArrayList<>();
        for (Line line : lines) {
            result.add(shares.stream()
                    .filter(share -> share.lineId().equals(line.lineId()))
                    .findFirst()
                    .orElseGet(() -> new LineDiscount(line.lineId(), 0L)));
        }
        return List.copyOf(result);
    }
}
