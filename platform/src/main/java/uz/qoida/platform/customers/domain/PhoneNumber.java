package uz.qoida.platform.customers.domain;

import java.util.regex.Pattern;

/**
 * Canonicalises a phone number so the same person hashes to the same value
 * (ADR 0015, ADR 0029).
 *
 * <p>The lookup hash is a keyed MAC over the canonical string, so canonicalisation
 * <em>is</em> the identity function for a contact point. Without it
 * {@code +998 90 111-22-33} and {@code +998901112233} produce different hashes and
 * a support agent searching for a customer finds nobody — and, worse here, two
 * spellings of one number get two separate rate-limit budgets.
 *
 * <p>{@link #normalize} is the permissive form the profile has always used and
 * keeps, because a contact point may hold a landline, a foreign number, or
 * something imported from a POS that nobody has cleaned up.
 * {@link #requireDeliverableMobile} is strict, and only the verification path uses
 * it.
 */
public final class PhoneNumber {

    /**
     * Uzbekistan: {@code +998} then a two-digit operator code and seven digits.
     *
     * <p>The verification path refuses anything else, and that is an anti-fraud
     * control rather than a parochialism. An open OTP endpoint that will dial any
     * country is the standard shape of SMS pumping — an attacker drives traffic to
     * a premium range they collect revenue share on, and the tenant pays for
     * messages nobody reads. Widening this is a deliberate change with a
     * conversation about destination pricing attached, which is exactly the point
     * of it being a constant here rather than an accident of validation.
     */
    private static final Pattern UZBEK_MOBILE = Pattern.compile("\\+998\\d{9}");

    private static final String UZBEK_DIALING_PREFIX = "+998";

    private PhoneNumber() {
    }

    /**
     * Digits only, keeping a leading {@code +}.
     *
     * <p>Uzbek numbers are written with spaces, dashes, and brackets in about
     * equal measure, and every one of those spellings is the same person.
     */
    public static String normalize(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("A contact value is required");
        }
        String digits = rawValue.replaceAll("[^0-9+]", "");
        return digits.startsWith("+") ? digits : "+" + digits;
    }

    /**
     * The strict form the verification path accepts, in E.164.
     *
     * <p>Accepts a national nine-digit number and a {@code 998}-prefixed one as
     * well as full E.164, because all three are how people in this market write
     * their own number, and rejecting two of the three would look like the
     * platform refusing a number the customer knows is correct.
     *
     * @throws IllegalArgumentException on anything that is not an Uzbek mobile
     *                                  number. The message never contains the
     *                                  value: it is personal data, and an error
     *                                  message is a log line waiting to happen
     */
    public static String requireDeliverableMobile(String rawValue) {
        String normalized = normalize(rawValue);

        String candidate = switch (normalized.length()) {
            // "+901112233" — a national number that picked up a plus from
            // normalize. Nine digits is unambiguous here: it is the only length a
            // national Uzbek mobile number has.
            case 10 -> UZBEK_DIALING_PREFIX + normalized.substring(1);
            // "+998901112233" already, or "998901112233" before the plus.
            case 13 -> normalized;
            default -> normalized;
        };

        if (!UZBEK_MOBILE.matcher(candidate).matches()) {
            throw new IllegalArgumentException(
                    "That is not an Uzbek mobile number. Expected the +998 form.");
        }
        return candidate;
    }
}
