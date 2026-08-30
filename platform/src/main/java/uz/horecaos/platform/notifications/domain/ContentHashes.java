package uz.horecaos.platform.notifications.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * The hashes that stand in for content this module must not keep (ADR 0020,
 * ADR 0029).
 *
 * <p>A notification row records what was sent as three frozen facts: the template
 * version, a hash of the variables, and a hash of the rendered message. Together
 * they let an auditor re-render the exact message from the template and the inputs
 * and confirm it matches — without this table ever holding the sentence that has
 * the customer's order in it, or, on other templates, their name.
 *
 * <p>Hex rather than Base64 because these values are read by people in support
 * tickets and pasted between systems, and Base64's {@code +/=} do not survive that
 * reliably.
 */
public final class ContentHashes {

    private ContentHashes() {
    }

    public static String of(String value) {
        return hex(digest().digest(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * A hash of a variable set that does not depend on map iteration order.
     *
     * <p>Sorted by key and length-prefixed. Concatenating {@code key + value} alone
     * would give {@code {"ab":"c"}} and {@code {"a":"bc"}} the same hash, and two
     * different messages proving identical is exactly what this must not do.
     */
    public static String ofVariables(Map<String, String> variables) {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(variables).entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            canonical.append(entry.getKey().length()).append(':').append(entry.getKey())
                    .append(value.length()).append(':').append(value);
        }
        return of(canonical.toString());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unreachable) {
            throw new IllegalStateException("SHA-256 is required by every JVM", unreachable);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
