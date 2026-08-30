package uz.horecaos.platform.fulfillment.domain.tariff;

/**
 * How a fee lands on the tariff's rounding step (ADR 0037, added in V0032).
 *
 * <p>The rule is stored rather than assumed because the two answers differ on
 * exactly the values a delivery fee keeps producing. With a 500 so'm step, 1,250
 * rounds to 1,500 under {@link #HALF_UP} and to 1,000 under {@link #HALF_EVEN};
 * one of those is what a migrated branch was charging yesterday and the other is
 * not.
 */
public enum RoundingRule {

    /**
     * Ties go away from zero. The obvious rule, and the one to choose for a tariff
     * somebody is authoring today.
     */
    HALF_UP,

    /**
     * Ties go to the even multiple — Python's {@code round}, and therefore what
     * every fee the legacy dashboard ever quoted was rounded with.
     *
     * <p>It exists here for one reason: so a migrated branch charges what it
     * charged. Nobody should pick it for a new rate table.
     */
    HALF_EVEN
}
