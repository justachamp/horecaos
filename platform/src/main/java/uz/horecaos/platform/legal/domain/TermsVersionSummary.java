package uz.horecaos.platform.legal.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * One row of a brand's publishing history, without the bodies — the
 * operations screen's version list shows who published what, when, and in
 * which languages, and fetches one version's actual text only when an
 * operator opens it.
 */
public record TermsVersionSummary(UUID id, int version, Set<String> locales, String publishedBy, Instant publishedAt) {

    public TermsVersionSummary {
        locales = Set.copyOf(locales);
    }
}
