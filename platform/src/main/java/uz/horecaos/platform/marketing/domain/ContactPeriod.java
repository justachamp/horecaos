package uz.horecaos.platform.marketing.domain;

/** The window a contact-policy cap counts over (ADR 0112, V0495). */
public enum ContactPeriod {
    DAILY,
    WEEKLY,
    ROLLING_7D,
    ROLLING_30D;

    /**
     * The most a tenant may ask for, which is the ADR 0044 platform default for the
     * period. A cap may be tightened and never loosened, and this is the number
     * "loosened" is measured against: the same figure the table's CHECK states.
     */
    public int platformCeiling() {
        return this == ROLLING_30D
                ? EngagementPolicy.DEFAULT_MESSAGES_PER_30_DAYS
                : EngagementPolicy.DEFAULT_MESSAGES_PER_7_DAYS;
    }
}
