package uz.horecaos.platform.partner.domain;

/**
 * What proves a bag went to the right person (ADR 0040).
 *
 * <p>{@link #NONE} is an explicit configured value per binding and never the
 * absence of a value. A branch must not be able to skip verification because a
 * form field happened to be empty, which is why the column is not nullable and
 * this constant exists.
 */
public enum HandoverChallengeType {

    /** A numeric or alphanumeric code the courier reads out. */
    CODE,

    /** A code the courier presents on a screen and the pass scans. */
    QR,

    /** A signature captured at the pass. */
    SIGNATURE,

    /** Verification deliberately not required here, decided in advance. */
    NONE
}
