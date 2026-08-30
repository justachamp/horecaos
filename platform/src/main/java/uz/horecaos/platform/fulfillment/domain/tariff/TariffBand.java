package uz.horecaos.platform.fulfillment.domain.tariff;

/**
 * One distance band of a rate table (ADR 0037).
 *
 * <p>Half-open: {@code [fromMeters, toMeters)}. Stated once, here, and matched by
 * the database's exclusion constraint, because a band set where one implementation
 * treats the boundary as inclusive and another as exclusive prices 3,000 m twice
 * or not at all.
 *
 * <p><strong>Bands accumulate, and {@code baseMinor} is local to this band.</strong>
 * V0025 read only the band containing the distance, which forced every band's base
 * to be authored as the cumulative charge for reaching its floor. That is wrong for
 * two separate reasons and V0032 corrects it. It cannot express the legacy
 * dashboard's stepped tariff without loss, because a step's contribution
 * ({@code width * perKm / 1000}) need not be a whole som and a cumulative base has
 * nowhere to keep the fraction. And it makes a rate table unsafe to edit: changing
 * the first step's rate silently leaves every later base stating a total that no
 * longer adds up, with nothing to detect it.
 *
 * @param bandSet    which table this band belongs to. A time rule may substitute a
 *                   whole set for the base one — see {@link TariffTimeRule#bandSet()}
 * @param baseMinor  the flat charge for entering this band, not the cumulative
 *                   charge for reaching it
 * @param perKmMinor charged over the stretch of this band the journey actually
 *                   covers, by {@link DistanceAccrual}
 */
public record TariffBand(int sequence, String bandSet, int fromMeters, int toMeters, long baseMinor, long perKmMinor) {

    /** The table used when no time rule substitutes another. */
    public static final String BASE_SET = "BASE";

    /** A band of the base table, which is what almost every band is. */
    public TariffBand(int sequence, int fromMeters, int toMeters, long baseMinor, long perKmMinor) {
        this(sequence, BASE_SET, fromMeters, toMeters, baseMinor, perKmMinor);
    }

    public TariffBand {
        if (bandSet == null || bandSet.isBlank()) {
            throw new IllegalArgumentException("A band must name the set it belongs to");
        }
        if (fromMeters < 0 || toMeters <= fromMeters) {
            throw new IllegalArgumentException(
                    "A band must cover a positive range, was [%d, %d)".formatted(fromMeters, toMeters));
        }
        if (baseMinor < 0 || perKmMinor < 0) {
            throw new IllegalArgumentException("A band cannot charge a negative amount");
        }
    }

    public boolean contains(int meters) {
        return meters >= fromMeters && meters < toMeters;
    }
}
