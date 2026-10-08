package uz.horecaos.platform.reviews.domain;

/** A tag is retired, never deleted: it stops being offered and stays on every review that carries it. */
public enum TagStatus {
    ACTIVE,
    RETIRED
}
