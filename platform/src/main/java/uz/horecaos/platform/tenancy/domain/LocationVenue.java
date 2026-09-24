package uz.horecaos.platform.tenancy.domain;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Facts about a branch as a place to be, beyond where it physically is (row 10.2b).
 *
 * <p>Distinct from {@link LocationPlace}: the place is how to find and reach the
 * building; this is what a guest deciding whether to go, or an operator planning
 * capacity, would want to know once they are standing in front of it — roughly
 * how many seats, what a cheque runs, and whether there is somewhere to park or
 * leave a child. None of it is required — most of the fleet was registered long
 * before this screen existed to ask.
 *
 * @param sortOrder the branch list's own manual ordering, lowest first, ties
 *                  broken by display name — see {@code JdbcTenantControlPlaneStore
 *                  .findLocations}. Defaults to 0, so an unordered fleet reads
 *                  exactly as it always has: alphabetically
 */
public record LocationVenue(
        int sortOrder,
        @Nullable Integer seats,
        @Nullable Long averageChequeAmount,
        @Nullable String averageChequeCurrency,
        boolean hasParking,
        boolean hasPlayground,
        @Nullable String virtualTourUrl) {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern HTTP_URL = Pattern.compile("^https?://.+");
    private static final int VIRTUAL_TOUR_URL_MAX_LENGTH = 500;

    public LocationVenue {
        if (seats != null && seats < 0) {
            throw new IllegalArgumentException("Seats cannot be negative");
        }
        // A pairing check, the same shape LocationPlace's own coordinate
        // invariant uses: an amount without a currency cannot be displayed,
        // and a currency without an amount is not an average of anything.
        if ((averageChequeAmount == null) != (averageChequeCurrency == null)) {
            throw new IllegalArgumentException("An average cheque needs both an amount and a currency, or neither");
        }
        if (averageChequeAmount != null && averageChequeAmount < 0) {
            throw new IllegalArgumentException("Average cheque amount cannot be negative");
        }
        if (averageChequeCurrency != null
                && !CURRENCY.matcher(averageChequeCurrency).matches()) {
            throw new IllegalArgumentException("Average cheque currency must be an ISO 4217 code");
        }
        virtualTourUrl = blankToNull(virtualTourUrl);
        if (virtualTourUrl != null
                && (virtualTourUrl.length() > VIRTUAL_TOUR_URL_MAX_LENGTH
                        || !HTTP_URL.matcher(virtualTourUrl).matches())) {
            throw new IllegalArgumentException("Virtual tour URL must be an http(s) URL of at most "
                    + VIRTUAL_TOUR_URL_MAX_LENGTH + " characters");
        }
    }

    /**
     * Blank collapses to absent rather than being stored — the same rule
     * {@link LocationPlace}'s own text fields follow, so a virtual tour link
     * cleared to an empty string reads as "no link" rather than a blank one.
     */
    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** A branch nobody has described this way yet: unordered, no venue facts on file. */
    public static LocationVenue unknown() {
        return new LocationVenue(0, null, null, null, false, false, null);
    }
}
