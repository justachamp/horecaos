package uz.horecaos.platform.support;

import java.util.Map;

/**
 * A name per locale as the translation tables hold it (V0431, V0679): the shape a region's or a zone's
 * name has now that the three fixed columns are gone. For the many fixtures that need only the platform
 * triple's wording.
 */
public final class LocaleNames {

    private LocaleNames() {}

    /** Russian, Uzbek (Latin) and English, keyed by the registry's tags. */
    public static Map<String, String> triple(String ru, String uz, String en) {
        return Map.of("ru", ru, "uz-Latn", uz, "en", en);
    }
}
