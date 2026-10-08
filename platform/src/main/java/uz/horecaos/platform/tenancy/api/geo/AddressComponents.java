package uz.horecaos.platform.tenancy.api.geo;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The normalized parts of an address a provider recognised (ADR 0015).
 *
 * <p>Every part but {@code formatted} may be absent: a provider that placed a district
 * does not invent a street. Entrance, floor, flat and landmark are deliberately not here;
 * they are what the <em>person</em> adds in the address form and no geocoder knows them.
 *
 * <p>This is a customer's address in everything but name. {@code toString} prints nothing
 * of it, because a record's generated one is a log line away from ADR 0029's first rule.
 */
public record AddressComponents(
        @Nullable String country,
        @Nullable String locality,
        @Nullable String district,
        @Nullable String street,
        @Nullable String house,
        String formatted) {

    public AddressComponents {
        Objects.requireNonNull(formatted, "A formatted address is required");
    }

    @Override
    public String toString() {
        return "AddressComponents[REDACTED]";
    }
}
