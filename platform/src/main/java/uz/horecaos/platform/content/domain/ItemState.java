package uz.horecaos.platform.content.domain;

/**
 * The stored lifecycle of an item (ADR 0157, Decision 2).
 *
 * <p>"Scheduled", "live" and "expired" are <strong>never</strong> stored; see {@link
 * ContentVisibility}. There is no state a sweeper has to move an item into, so there is no sweeper
 * that can fail and leave a banner up past its end date.
 */
public enum ItemState {
    /** Never published. */
    DRAFT,
    /** Has a live revision; whether customers see it right now is computed. */
    PUBLISHED,
    /** Taken down. Every revision is kept; a publish puts it back. */
    WITHDRAWN,
    /** Retired from lists. Withdrawn first if it was live; gives its slug back. */
    ARCHIVED
}
