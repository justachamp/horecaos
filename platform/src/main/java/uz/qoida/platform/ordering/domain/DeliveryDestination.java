package uz.qoida.platform.ordering.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a delivery order is going (ADR 0019, ADR 0015, ADR 0029).
 *
 * <p>Personal data throughout. This is the document that is envelope-encrypted
 * into {@code ordering.cart_fulfillment.address_encrypted} and copied on to
 * {@code ordering.order_customer_snapshots.address_encrypted} at checkout, and it
 * is never rendered anywhere else: {@link #toString()} prints nothing for the
 * reason {@code ShipmentBookingPort.Waypoint} does, because a record's generated
 * {@code toString} prints every component and one interpolated log line then puts
 * a customer's home address into the log aggregator.
 *
 * <p>The field set is deliberately the same as ADR 0015's {@code AddressFields}.
 * подъезд (entrance), этаж (floor), квартира (apartment) and ориентир (landmark)
 * are separate fields rather than free text because a courier standing in a
 * Soviet-era block cannot find a flat from a street line, and because a partner
 * adapter that has its own fields for them cannot fill them from a sentence.
 *
 * <p>The coordinate travels <em>inside</em> the document rather than beside it in
 * a clear column, which is where the two rows that carry this disagree and where
 * they are both right. A cart lives four hours and is measured from by ADR 0037's
 * fee resolver, so {@code cart_fulfillment} keeps a clear, range-checked pair that
 * a constraint can enforce. An order lives for years and is crypto-shredded (ADR
 * 0029): a clear coordinate on a seven-year-old order would survive the shred and
 * still point at the building somebody lived in, so the order's copy is inside the
 * ciphertext and dies with the key.
 *
 * @param latitude  never optional here. A destination with no point cannot be
 *                  priced by ADR 0037 or measured by ADR 0014, and
 *                  {@code Waypoint} takes primitives — a landmark-only address
 *                  admitted this far becomes a courier sent to 0,0 in the Gulf of
 *                  Guinea. The refusal belongs where the address is chosen
 *
 * <p>Serialized and deserialized by ordering alone — nothing outside this module
 * writes or reads the document — so the shape carries no Jackson annotation and
 * the domain layer keeps its rule of importing no framework types. The cost is
 * that the shape may only grow: a field added here is absent from older documents
 * and reads back as null, while a field <em>removed</em> would leave older
 * documents carrying a property the reader does not know, so removing one is a
 * migration rather than an edit.
 */
public record DeliveryDestination(
        String line1,
        String line2,
        String city,
        String district,
        String postalCode,
        String entrance,
        String floor,
        String apartment,
        String landmark,
        double latitude,
        double longitude) {

    public DeliveryDestination {
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            // Mirrors ck_cart_fulfillment_coordinates. Asserted here as well so a
            // bad point fails where it was supplied rather than as a driver error
            // at the end of a transaction that has already priced a basket.
            throw new IllegalArgumentException("A destination coordinate is out of range");
        }
    }

    /**
     * The single address string a partner booking carries.
     *
     * <p>Composed rather than stored, because the structured fields are the
     * authority and a stored line would drift from them. The landmark is appended
     * rather than dropped: for a large share of addresses in this market it is the
     * only thing that locates the building, and a partner whose API has one string
     * for the address is the case this method exists for.
     */
    public String addressLine() {
        List<String> parts = new ArrayList<>(5);
        addIfPresent(parts, line1);
        addIfPresent(parts, line2);
        addIfPresent(parts, district);
        addIfPresent(parts, city);
        addIfPresent(parts, postalCode);
        String composed = String.join(", ", parts);
        if (landmark == null || landmark.isBlank()) {
            return composed;
        }
        return composed.isEmpty() ? landmark.trim() : composed + " (" + landmark.trim() + ")";
    }

    private static void addIfPresent(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.trim());
        }
    }

    /** Names nothing about the person waiting at this door. */
    @Override
    public String toString() {
        return "DeliveryDestination[REDACTED]";
    }
}
