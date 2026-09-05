package uz.horecaos.platform.integration.provider.voice.asterisk;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The AMI (Asterisk Manager Interface) wire format: a block of {@code Key:
 * Value} lines terminated by a blank line (ADR 0064's Asterisk-class adapter).
 *
 * <p>Pure I/O framing, no domain meaning — {@link AsteriskAmiEventMapper}
 * decides what a block means. Kept separate so a malformed or unrecognized
 * block never becomes a parse failure, only an unmapped one.
 */
public final class AsteriskAmiEventParser {

    private AsteriskAmiEventParser() {}

    /**
     * Reads one block: every {@code Key: Value} line up to (and consuming) the
     * next blank line.
     *
     * @return empty at end-of-stream with nothing read; a block with zero
     *         fields is still returned non-empty if at least one blank line
     *         was seen after content, matching AMI's own framing
     */
    public static Optional<Map<String, String>> readBlock(BufferedReader reader) throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        boolean sawContent = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (sawContent) {
                    return Optional.of(fields);
                }
                // Blank lines between blocks (or Asterisk's greeting banner) are
                // skipped rather than treated as an empty block.
                continue;
            }
            sawContent = true;
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).strip();
                String value =
                        colon + 1 < line.length() ? line.substring(colon + 1).strip() : "";
                fields.put(key, value);
            }
        }
        return sawContent ? Optional.of(fields) : Optional.empty();
    }
}
