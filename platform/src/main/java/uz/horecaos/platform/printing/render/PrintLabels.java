package uz.horecaos.platform.printing.render;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The words on paper, in one language (ADR 0154, Open inputs: «one language per document, the
 * location's own»).
 *
 * <p>Wording lives in a catalogue per locale under {@code printing/labels/}, so a legal correction to a
 * fiscal receipt is a change to a string and a release of catalogue text, not code. A missing key is a
 * failure and never a bare key printed on a customer's receipt.
 */
public final class PrintLabels {

    /** The languages a document can print in. A fourth is a catalogue and a glyph audit first. */
    public static final List<String> LOCALES = List.of("ru", "uz-Latn", "en");

    private static final Map<String, PrintLabels> CATALOGUES = loadAll();

    private final String locale;
    private final Properties values;

    private PrintLabels(String locale, Properties values) {
        this.locale = locale;
        this.values = values;
    }

    public static PrintLabels forLocale(String locale) {
        PrintLabels labels = CATALOGUES.get(locale);
        if (labels == null) {
            throw new IllegalArgumentException("No print catalogue for the language \"" + locale + "\"");
        }
        return labels;
    }

    public String locale() {
        return locale;
    }

    public String get(String key) {
        String value = values.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("The print catalogue \"" + locale + "\" has no label \"" + key + "\"");
        }
        return value;
    }

    public boolean has(String key) {
        return values.getProperty(key) != null;
    }

    /** The label with {@code {0}}, {@code {1}} replaced. Plain replacement: the Uzbek apostrophes are text, not quoting. */
    public String format(String key, String... arguments) {
        String text = get(key);
        for (int index = 0; index < arguments.length; index++) {
            text = text.replace("{" + index + "}", arguments[index]);
        }
        return text;
    }

    /** Every string this catalogue can put on a document, for the glyph test. */
    public Collection<String> allValues() {
        return values.stringPropertyNames().stream().map(values::getProperty).toList();
    }

    public Collection<String> keys() {
        return values.stringPropertyNames();
    }

    private static Map<String, PrintLabels> loadAll() {
        Map<String, PrintLabels> catalogues = new LinkedHashMap<>();
        for (String locale : LOCALES) {
            catalogues.put(locale, new PrintLabels(locale, read(locale)));
        }
        return Map.copyOf(catalogues);
    }

    private static Properties read(String locale) {
        String resource = "/printing/labels/labels_" + locale + ".properties";
        try (InputStream stream = PrintLabels.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing print catalogue " + resource);
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return properties;
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read the print catalogue " + resource, failure);
        }
    }
}
