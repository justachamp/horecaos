package uz.horecaos.platform.commercial.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An invoice in HorecaOS's own terms (ADR 0096): the seller, the buyer, the lines
 * and their VAT, in integer minor units of one currency.
 *
 * <p>This is what the platform builds from an issued statement and records as what
 * it sent; each adapter maps it to its operator's document. No operator's field
 * names, codes or date formats appear here. Frozen once recorded.
 *
 * @param clientReference our identifier for the document, thirty-two lower-case hex
 *                        characters: the key an operator that supports one deduplicates
 *                        and looks up on
 * @param documentNumber  the statement's number, which is unique across the platform
 * @param contractNumber  the agreement the invoice is made under; a provisional default
 *                        until finance names one (ADR 0096's open input)
 * @param vatApplies      whether the seller charges VAT at all: false when the seller has
 *                        no VAT registration, in which case every line carries rate zero
 */
public record EInvoiceDocument(
        String clientReference,
        String documentNumber,
        LocalDate documentDate,
        String currency,
        Party seller,
        Party buyer,
        String contractNumber,
        LocalDate contractDate,
        boolean vatApplies,
        List<Line> lines) {

    public EInvoiceDocument {
        Objects.requireNonNull(clientReference, "A client reference is required");
        Objects.requireNonNull(documentNumber, "A document number is required");
        Objects.requireNonNull(documentDate, "A document date is required");
        Objects.requireNonNull(currency, "A currency is required");
        Objects.requireNonNull(seller, "A seller is required");
        Objects.requireNonNull(buyer, "A buyer is required");
        Objects.requireNonNull(contractNumber, "A contract number is required");
        Objects.requireNonNull(contractDate, "A contract date is required");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("An invoice has at least one line");
        }
    }

    /** What the lines come to before VAT. */
    public long netMinor() {
        return lines.stream().mapToLong(Line::netMinor).sum();
    }

    public long vatMinor() {
        return lines.stream().mapToLong(Line::vatMinor).sum();
    }

    public long grossMinor() {
        return Math.addExact(netMinor(), vatMinor());
    }

    /**
     * A minor-unit amount as a decimal in the currency's own scale: 150000 tiyin of
     * UZS is 1500.00. The one place minor units become a decimal, and only at an
     * operator's boundary, because an operator's document carries decimals.
     */
    public static BigDecimal decimal(long minor, String currency) {
        return BigDecimal.valueOf(minor, Currency.getInstance(currency).getDefaultFractionDigits());
    }

    /** A company on an invoice: public business identifiers only. */
    public record Party(
            String taxpayerNumber,
            String name,
            @Nullable String address,
            @Nullable String vatRegistrationCode,
            @Nullable String bankAccount,
            @Nullable String bankCode,
            @Nullable String bankName) {

        public Party {
            Objects.requireNonNull(taxpayerNumber, "A taxpayer number is required");
            Objects.requireNonNull(name, "A name is required");
        }
    }

    /**
     * One invoiced line. {@code netMinor} is quantity times unit price, before VAT;
     * {@code vatMinor} is that times the rate, rounded half up to a whole minor unit;
     * {@code grossMinor} is the two together.
     */
    public record Line(
            int number,
            String name,
            String classificationCode,
            String classificationName,
            String packageCode,
            String packageName,
            long quantity,
            long unitPriceMinor,
            long netMinor,
            int vatRateBp,
            long vatMinor,
            long grossMinor) {

        public Line {
            Objects.requireNonNull(name, "A line name is required");
            Objects.requireNonNull(classificationCode, "A classification code is required");
            Objects.requireNonNull(classificationName, "A classification name is required");
            Objects.requireNonNull(packageCode, "A package code is required");
            Objects.requireNonNull(packageName, "A package name is required");
            if (grossMinor != Math.addExact(netMinor, vatMinor)) {
                throw new IllegalArgumentException("A line's gross is its net plus its VAT");
            }
        }

        /** The VAT rate as a plain percentage with no trailing zeros: 1200 basis points is "12", 250 is "2.5". */
        public String vatPercentText() {
            return BigDecimal.valueOf(vatRateBp, 2).stripTrailingZeros().toPlainString();
        }
    }
}
