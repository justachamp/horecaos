package uz.qoida.platform.catalog.api;

/**
 * The one place an ADR 0018 tax rate becomes the integer percent a fiscal
 * receipt line carries (ADR 0038).
 *
 * <p>ADR 0018 stores a rate as basis points — 1200 for 12 percent — and is right
 * to: integers throughout, so no rate is a float that rounds differently on two
 * machines. Both providers type the per-line VAT rate as an integer *percent*:
 * Click {@code VATPercent}, Payme {@code vat_percent}. There is no
 * representation of 12.5 percent on either wire.
 *
 * <p>So a rate that is not a whole number of percent has no conformant receipt,
 * and there are exactly three things a platform can do about it. It can round,
 * which puts a misstated tax figure on a legal document and is a fiscal offence
 * rather than a rounding error. It can discover the problem in an adapter on the
 * checkout path, which fails an order that was already priced and accepted. Or
 * it can refuse the rate where it is configured. V0028 does the third with a
 * database constraint on {@code pricing.tax_profiles}; this class is the same
 * refusal in code, for the rate that reaches an adapter from anywhere the
 * constraint does not cover — a legacy import, a fixture, a profile read before
 * V0028 ran.
 *
 * <p>It is deliberately a conversion with a name rather than a {@code / 100}
 * written at each call site, for the same reason ADR 0038 forbids a bare
 * {@code * 100} in a payment adapter: a factor-of-a-hundred error in this
 * neighbourhood is money, and the only defence that scales is that the
 * conversion happens once, somewhere findable.
 *
 * <p>It lives in catalog's public interface because the classification it travels
 * with is catalog's, and because payments and the eventual {@code fiscal} module
 * must both reach it without either of them writing its own division.
 */
public final class FiscalVatRate {

    private FiscalVatRate() {
    }

    /**
     * The whole percent both providers require, or a refusal.
     *
     * @param rateBasisPoints an ADR 0018 {@code rate_basis_points}
     * @return the percent to put in Click's {@code VATPercent} or Payme's
     *         {@code vat_percent}
     * @throws UnrepresentableVatRate when the rate is not a whole number of
     *         percent, which is a configuration error and never something to
     *         work around
     */
    public static int wholePercentOf(int rateBasisPoints) {
        if (rateBasisPoints < 0) {
            throw new UnrepresentableVatRate(rateBasisPoints,
                    "a negative VAT rate is not a rate");
        }
        if (rateBasisPoints % 100 != 0) {
            throw new UnrepresentableVatRate(rateBasisPoints,
                    "Click's VATPercent and Payme's vat_percent are integer percents, so "
                            + "%d basis points (%.2f percent) cannot be stated on a receipt. "
                                    .formatted(rateBasisPoints, rateBasisPoints / 100.0)
                            + "Correct the ADR 0018 tax profile; rounding it would misstate "
                            + "tax on a legal document");
        }
        return rateBasisPoints / 100;
    }

    /**
     * Whether a rate can be stated on a receipt at all.
     *
     * <p>For a caller that wants to report the problem against a whole catalog
     * rather than fail on the first bad row.
     */
    public static boolean isExpressible(int rateBasisPoints) {
        return rateBasisPoints >= 0 && rateBasisPoints % 100 == 0;
    }

    /**
     * A tax rate that no conformant fiscal receipt can carry.
     *
     * <p>An {@link IllegalStateException} rather than a checked exception because
     * there is no recovery: the caller cannot choose a different rate, and the
     * order must not complete under a rate that will be printed wrong.
     */
    public static final class UnrepresentableVatRate extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private final int rateBasisPoints;

        UnrepresentableVatRate(int rateBasisPoints, String detail) {
            super("VAT rate %d basis points is not expressible on a fiscal receipt: %s"
                    .formatted(rateBasisPoints, detail));
            this.rateBasisPoints = rateBasisPoints;
        }

        public int rateBasisPoints() {
            return rateBasisPoints;
        }
    }
}
