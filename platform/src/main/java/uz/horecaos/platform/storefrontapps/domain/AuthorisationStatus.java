package uz.horecaos.platform.storefrontapps.domain;

/** A brand's standing decision about one app (ADR 0070). */
public enum AuthorisationStatus {

    /** The brand lets this app serve it. */
    ACTIVE,

    /** The brand withdrew it, effective on the next request. */
    REVOKED
}
