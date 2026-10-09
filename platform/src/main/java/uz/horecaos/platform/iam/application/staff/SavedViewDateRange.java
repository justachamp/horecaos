package uz.horecaos.platform.iam.application.staff;

/**
 * The relative period a saved view may carry (ADR 0160): a token the console resolves against today, never
 * an absolute date, because a view saved on Tuesday that opened on Tuesday's orders for ever would be a
 * log and not a view.
 */
public enum SavedViewDateRange {
    TODAY,
    YESTERDAY,
    LAST_7_DAYS,
    THIS_MONTH
}
