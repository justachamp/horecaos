package uz.qoida.platform.fulfillment.domain.tariff;

/**
 * How the distance inside a band turns into money (ADR 0037, corrected in V0032).
 *
 * <p>ADR 0037 originally had only one answer and stated it as a fact about the
 * world: partial kilometres round up, because a fraction of a som is not
 * explainable at the door. That is a good rule and it is still the default. It is
 * not, however, what the legacy Qoida dashboard does, and the difference is money
 * rather than presentation — at 3,100 m with a 2,000 so'm per-kilometre rate,
 * rounding up charges 2,000 and pro-rating charges 200.
 *
 * <p>So the accrual is a property of the rate table and not of the codebase. A
 * tariff imported from a legacy branch is {@link #PRORATED_METRE} and reproduces
 * the price that branch was charging; a tariff authored in Qoida is
 * {@link #STARTED_KILOMETRE} unless somebody deliberately chooses otherwise.
 */
public enum DistanceAccrual {

    /**
     * Every started kilometre inside the band is charged whole.
     *
     * <p>Counted per band, from that band's own floor. A distance spanning two
     * bands therefore rounds up twice, which is the honest consequence of saying
     * each band prices its own stretch: the alternative — ceiling the whole
     * journey once and then apportioning it — has no defensible answer when two
     * bands charge different rates.
     */
    STARTED_KILOMETRE,

    /**
     * Metres are charged pro rata: {@code metres * perKm / 1000}.
     *
     * <p>What the legacy dashboard does. Fractions survive to the end of the
     * computation and are settled once by the tariff's rounding step, which for an
     * imported branch is 500 so'm — so no customer ever sees the fraction, and no
     * intermediate rounding moves the total.
     */
    PRORATED_METRE
}
