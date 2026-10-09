package uz.horecaos.platform.observability;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Masks the values of the query parameters that carry a secret or a person, in any text.
 *
 * <p>The backstop behind ADR 0029 and ADR 0028 for the one place no amount of care in this
 * code base reaches: a library that logs the request it is making. A map provider reads the
 * typed address from {@code ?geocode=} or {@code ?text=}, a coordinate from {@code ?ll=}, and
 * the key from {@code ?apikey=}, because that is the provider's API and it offers no other way
 * to ask. {@code ProviderHttpClient} keeps all of it out of every line this code writes, but a
 * server, a proxy or an HTTP client that is later put on the path, or the JDK's own client with
 * {@code -Djdk.httpclient.HttpClient.log=requests}, writes the request line itself, and the
 * first time anyone raises that logger to {@code DEBUG} the key and the address are in the log
 * aggregator. This is what turns that line into {@code ...?apikey=[redacted]&geocode=[redacted]&...}.
 *
 * <p><strong>What is masked.</strong> The value, never the name or the rest of the line, so the
 * line stays useful for finding out <em>that</em> a call was made.
 *
 * <ul>
 *   <li>{@code apikey}, {@code api_key}, {@code api-key}, {@code access_token} and {@code
 *       geocode}: wherever they appear followed by {@code =}, query string or not. None of them
 *       is a name that means anything harmless.
 *   <li>{@code key}, {@code text}, {@code ll} and {@code ull}: only as a query parameter, right
 *       after {@code ?} or {@code &}. Those are ordinary words in an ordinary log line
 *       ({@code monkey=banana}, {@code text=Hello}), so masking them everywhere would hide more
 *       than it protects.
 *   <li>The same names in a URL that was itself percent-encoded into another one
 *       ({@code ...%3Fapikey%3DKEY%26geocode%3D...}), which is how a redirect target or a callback
 *       URL turns up in a log.
 * </ul>
 *
 * <p><strong>Where a value ends.</strong> At the next {@code &}, {@code #}, whitespace or double
 * quote. A request line is percent-encoded, so an address is one token ({@code Zaglushka%20ko%27chasi})
 * and so is a key; an {@code %26} inside a value is a literal ampersand in the address and does
 * not end it. A value with a raw space in it was not taken from an HTTP request line and is
 * masked only up to the space, which is the one gap in this class and is why it is a backstop and
 * not a substitute for keeping the query out of the log in the first place.
 *
 * <p>Case-insensitive, linear in the length of the text (no nested quantifier), and a text with
 * no {@code =} and no {@code %3} in it is returned as the same object without a regex running,
 * which is nearly every log line.
 */
public final class SensitiveQueryRedactor {

    /** What a masked value is replaced with. */
    public static final String MASK = "[redacted]";

    private static final String ALWAYS = "apikey|api_key|api-key|access_token|geocode";
    private static final String IN_A_QUERY = "key|text|ll|ull";

    /** {@code name=value} in the plain form every request line takes. */
    private static final Pattern PLAIN = Pattern.compile(
            "(?:(?:" + ALWAYS + ")|(?<=[?&])(?:" + IN_A_QUERY + "))=[^&#\\s\"]*", Pattern.CASE_INSENSITIVE);

    /** {@code name%3Dvalue} inside a URL that was percent-encoded into another one, where {@code %26} separates. */
    private static final Pattern ENCODED = Pattern.compile(
            "(?:(?:" + ALWAYS + ")|(?<=%3F|%26)(?:" + IN_A_QUERY + "))%3D(?:(?!%26)[^&#\\s\"])*",
            Pattern.CASE_INSENSITIVE);

    private SensitiveQueryRedactor() {}

    /**
     * @return {@code text} with the sensitive values masked; the same object when there was
     *     nothing to mask, and {@code null} for {@code null}
     */
    public static @Nullable String redact(@Nullable String text) {
        if (text == null) {
            return null;
        }
        boolean plain = text.indexOf('=') >= 0;
        boolean encoded = text.indexOf("%3") >= 0;
        if (!plain && !encoded) {
            return text;
        }
        String masked = text;
        if (plain) {
            masked = mask(PLAIN.matcher(masked), '=');
        }
        if (encoded) {
            masked = mask(ENCODED.matcher(masked), '%');
        }
        return masked.equals(text) ? text : masked;
    }

    /** Keeps everything up to and including the separator that ends the name, and replaces the rest. */
    private static String mask(Matcher matcher, char separatorStart) {
        return matcher.replaceAll((MatchResult match) -> {
            String found = match.group();
            int nameEnd = found.indexOf(separatorStart);
            int valueStart = separatorStart == '=' ? nameEnd + 1 : nameEnd + 3;
            return Matcher.quoteReplacement(found.substring(0, valueStart) + MASK);
        });
    }
}
