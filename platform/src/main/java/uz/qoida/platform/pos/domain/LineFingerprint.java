package uz.qoida.platform.pos.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * A stable hash over what an order asked for (ADR 0011).
 *
 * <p>The recovery read needs to compare an order at the provider against the one
 * we may have sent, and the only comparable content is the line composition. It
 * cannot be compared on names: a restaurant edits product names in their own back
 * office and the provider carries no version on them, so a name that differed by
 * a space would report every order as unmatched. It is compared on the provider's
 * own product identifiers, the quantities, and the unit amounts.
 *
 * <p>Canonicalised by sorting, because line order is a presentation detail on
 * both sides and two orders for the same food must produce the same fingerprint
 * whichever way round the kitchen printed them.
 *
 * <p>This is a comparison aid and nothing else. It is not a signature, it carries
 * no secret, and a collision costs an operator one extra candidate to look at.
 */
public final class LineFingerprint {

    private LineFingerprint() {
    }

    /**
     * @param lines the exported lines. May be empty only in a test; an order with
     *              no lines is refused earlier, because a kitchen ticket for
     *              nothing is a ticket somebody has to walk over and ask about
     */
    public static String of(List<Line> lines) {
        StringBuilder canonical = new StringBuilder();
        lines.stream()
                .sorted(Comparator.comparing(Line::externalProductId)
                        .thenComparingInt(Line::quantity)
                        .thenComparingLong(Line::unitAmountMinor))
                .forEach(line -> canonical
                        .append(line.externalProductId()).append(':')
                        .append(line.quantity()).append(':')
                        .append(line.unitAmountMinor()).append('|'));
        return sha256(canonical.toString());
    }

    /** A hash of the customer's telephone number, never the number (ADR 0029). */
    public static String phoneHash(String phone) {
        // Normalised before hashing so "+998 90 123 45 67" and "+998901234567"
        // are the same customer. Without this the recovery read would fail to
        // match on formatting the restaurant's back office applied.
        String normalised = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        return sha256(normalised);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM this runs on has SHA-256. A checked exception here would
            // make every caller handle a case that cannot occur.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * @param unitAmountMinor whole minor units. For UZS a minor unit is a whole
     *                        som, which is why this is a long and never a double:
     *                        a fingerprint computed through floating point would
     *                        differ between two runs over the same order
     */
    public record Line(String externalProductId, int quantity, long unitAmountMinor) { }
}
