package uz.horecaos.platform.tenancy.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The rules for a row that keeps its wording in the platform's three fixed
 * columns <em>and</em> a per-locale translations table (row 10.12's one-release
 * transition — V0430, V0431): what a write may supply, and how a read merges the
 * two homes into one answer.
 *
 * <p>The three columns ({@code ru}, {@code uz-Latn}, {@code en}) stay the source
 * for the platform triple. The translations table receives every label written
 * and is the only home of a locale outside the triple. Nothing in a write ever
 * <em>removes</em> a locale: a label that is not in the request is left as it
 * was, which is how an editor that shows only the locales a tenant supports keeps
 * the wording of one it no longer shows.
 */
public final class LocalizedLabels {

    public static final String RU = "ru";
    public static final String UZ_LATN = "uz-Latn";
    public static final String EN = "en";

    /** The platform triple, in canonical order — the locales that have a column. */
    public static final List<String> PLATFORM_TRIPLE = List.of(RU, UZ_LATN, EN);

    /** The same shape as the {@code ck_*_translation_locale} constraints: a well-formed BCP 47 tag. */
    private static final Pattern WELL_FORMED = Pattern.compile("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$");

    private LocalizedLabels() {}

    /** Whether the tag is one the translations tables' CHECK constraints accept. */
    public static boolean isWellFormed(String locale) {
        return WELL_FORMED.matcher(locale).matches();
    }

    /**
     * What a request supplies: the legacy three fields (each nullable) overlaid by
     * the per-locale map, blank values dropped, trimmed.
     *
     * <p>A bare {@code uz} is refused rather than passed through: the platform's
     * Uzbek locale is {@code uz-Latn} (ADR 0035, a bare {@code uz} is ambiguous
     * between scripts), and a row stored under {@code uz} would sit beside the
     * {@code label_uz} column as a second, never-read Uzbek. A tag that is a case
     * variant of a triple locale ({@code uz-latn}, {@code uz-LATN}) passes the
     * well-formedness pattern and is the same language, so it is written under the
     * canonical tag ({@link #canonical}) instead of becoming that second Uzbek.
     *
     * @param maxLength the column width each label must fit
     * @throws IllegalArgumentException a locale that is not a well-formed tag, is a
     *                                  bare {@code uz}, or a label over {@code maxLength}
     */
    public static Map<String, String> supplied(
            @Nullable String ru,
            @Nullable String uz,
            @Nullable String en,
            @Nullable Map<String, String> byLocale,
            int maxLength) {
        Map<String, String> labels = new LinkedHashMap<>();
        putIfPresent(labels, RU, ru);
        putIfPresent(labels, UZ_LATN, uz);
        putIfPresent(labels, EN, en);
        if (byLocale != null) {
            byLocale.forEach((locale, label) -> {
                if (locale == null || !isWellFormed(locale)) {
                    throw new IllegalArgumentException("'%s' is not a well-formed locale tag".formatted(locale));
                }
                if ("uz".equals(locale)) {
                    throw new IllegalArgumentException(
                            "Use 'uz-Latn' for Uzbek: a bare 'uz' does not say which script the wording is in");
                }
                putIfPresent(labels, canonical(locale), label);
            });
        }
        labels.forEach((locale, label) -> {
            if (label.length() > maxLength) {
                throw new IllegalArgumentException("The %s wording is %d characters; the limit is %d"
                        .formatted(locale, label.length(), maxLength));
            }
        });
        return labels;
    }

    /**
     * The tag a locale is stored and reported under: a triple locale's own spelling for
     * any case variant of it, every other tag as given. BCP 47 tags are case-insensitive,
     * the columns and the {@code ck_*_translation_locale} constraints are not.
     */
    public static String canonical(String locale) {
        for (String triple : PLATFORM_TRIPLE) {
            if (triple.equalsIgnoreCase(locale)) {
                return triple;
            }
        }
        return locale;
    }

    private static void putIfPresent(Map<String, String> labels, String locale, @Nullable String label) {
        if (label != null && !label.isBlank()) {
            labels.put(locale, label.trim());
        }
    }

    /**
     * The single answer a read gives: the platform triple from its columns, then any
     * other locale from the translations table, each locale once.
     *
     * <p>A translations row for a triple locale (the transition's mirror of the
     * column) is ignored — the column is the source, so a stale mirror cannot show
     * through — and so is one stored under a case variant of it ({@code uz-latn}),
     * which would otherwise be reported as a second language. A blank column is
     * skipped so the map never carries an empty wording.
     */
    public static Map<String, String> merge(String ru, String uz, String en, Map<String, String> translationRows) {
        Map<String, String> merged = new LinkedHashMap<>();
        putIfPresent(merged, RU, ru);
        putIfPresent(merged, UZ_LATN, uz);
        putIfPresent(merged, EN, en);
        List<String> others = new ArrayList<>(translationRows.keySet());
        others.removeIf(locale -> PLATFORM_TRIPLE.contains(canonical(locale)));
        others.sort(null);
        for (String locale : others) {
            putIfPresent(merged, locale, translationRows.get(locale));
        }
        return merged;
    }
}
