package uz.horecaos.platform.kitchen.domain;

/**
 * How a public-holiday date in the headcount plan's sample counts (ADR 0163 Decision 3), the same three
 * choices ADR 0043's branch forecast offers under {@code HolidayMode}. A separate type because {@code kitchen}
 * imports nothing from {@code reporting}.
 */
public enum HeadcountHolidayMode {

    /** Every qualifying date counts fully. The default. */
    INCLUDE,

    /** A flagged date is dropped before the sample is taken; an older, ordinary date fills its place. */
    EXCLUDE,

    /** A flagged date stays in the sample at {@link HeadcountPlanner#HOLIDAY_WEIGHT}, never full and never zero. */
    WEIGHT
}
