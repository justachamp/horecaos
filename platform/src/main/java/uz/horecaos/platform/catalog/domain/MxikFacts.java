package uz.horecaos.platform.catalog.domain;

import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the national ИКПУ/MXIK list says about one code (ADR 0173): the window it is valid in, the package codes
 * the dataset lists for it and a successor it names for a withdrawn code.
 *
 * @param defaultPackageCodes suggestions the dataset carries; evidence for a person, never a rule
 * @param replacedByCode      a successor for a person to click, never an automatic rewrite
 */
public record MxikFacts(
        LocalDate validFrom,
        @Nullable LocalDate validUntil,
        List<String> defaultPackageCodes,
        @Nullable String replacedByCode) {

    public boolean validOn(LocalDate on) {
        return MxikValidity.isValidOn(validFrom, validUntil, on);
    }

    /** Withdrawn on {@code on}: it was listed, and its end date has been reached. */
    public boolean withdrawnOn(LocalDate on) {
        return validUntil != null && !on.isBefore(validUntil);
    }
}
