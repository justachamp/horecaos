package uz.horecaos.platform.legal.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One published version of a brand's terms of service (ADR 0068).
 *
 * <p>Immutable once it exists. Publishing a change never touches this row —
 * it inserts the next {@code version} — because a customer accepted the
 * exact words in {@link #contentsByLocale()} at a specific time, and
 * rewriting them under an acceptance already on record would make that
 * acceptance evidence of nothing.
 *
 * @param contentsByLocale keyed by {@link TermsLocale#tag()}; a tenant may
 *                          publish fewer than all three, and a locale absent
 *                          here falls back to the platform default for that
 *                          language, not to another language this map does
 *                          carry — see {@code TermsAcceptanceService}.
 */
public record TermsVersion(
        UUID id,
        UUID tenantId,
        UUID brandId,
        int version,
        Map<String, String> contentsByLocale,
        String publishedBy,
        Instant publishedAt) {

    public TermsVersion {
        contentsByLocale = Map.copyOf(contentsByLocale);
    }
}
