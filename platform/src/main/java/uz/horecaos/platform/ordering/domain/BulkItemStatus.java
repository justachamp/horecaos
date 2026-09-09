package uz.horecaos.platform.ordering.domain;

/**
 * Where one order named in a bulk request stands (ADR 0039).
 *
 * <p>{@code PENDING} exists as a real, persisted state and not merely an
 * implementation detail: a row created but never settled is what a crashed
 * worker leaves behind, and a re-run of the same bulk key must find it and
 * retry rather than treat an unfinished item as done.
 */
public enum BulkItemStatus {
    PENDING,
    APPLIED,
    FAILED
}
