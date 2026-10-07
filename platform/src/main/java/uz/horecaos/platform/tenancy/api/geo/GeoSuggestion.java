package uz.horecaos.platform.tenancy.api.geo;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * One line of the type-ahead list (ADR 0145's {@code suggest}).
 *
 * <p>Deliberately lighter than a {@link GeocodeResult}: most providers' suggest endpoint
 * names a place and does not locate it, so a point is optional and the way to get one is to
 * geocode {@code fullText}. The list is shown to the person who typed it and goes nowhere
 * else, which is why this too prints nothing from {@code toString}.
 *
 * @param title             the primary line, e.g. a street
 * @param subtitle          the secondary line, e.g. its city
 * @param fullText          what to submit to {@code geocode} when this line is chosen
 * @param providerReference the provider's opaque handle, for a provider that resolves one
 * @param point             present only when the provider located the suggestion itself
 */
public record GeoSuggestion(
        String title,
        @Nullable String subtitle,
        String fullText,
        String providerReference,
        @Nullable GeoPoint point) {

    public GeoSuggestion {
        Objects.requireNonNull(title, "A title is required");
        Objects.requireNonNull(fullText, "A full text is required");
        Objects.requireNonNull(providerReference, "A provider reference is required");
    }

    @Override
    public String toString() {
        return "GeoSuggestion[REDACTED]";
    }
}
