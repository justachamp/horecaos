package uz.qoida.platform.catalog.domain;

/**
 * The publication lifecycle (ADR 0016).
 *
 * <p>{@link #REJECTED} is a recorded outcome rather than a failed operation that
 * leaves nothing behind. An operator asking why publishing failed an hour ago
 * needs the validation report to still exist.
 */
public enum PublicationStatus {
    VALIDATING,
    /** Validated clean and snapshotted, but not yet the live menu. */
    READY,
    REJECTED,
    PUBLISHED,
    RETIRED
}
