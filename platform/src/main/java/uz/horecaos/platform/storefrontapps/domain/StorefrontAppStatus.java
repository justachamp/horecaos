package uz.horecaos.platform.storefrontapps.domain;

/** Where a registered app stands platform-wide (ADR 0070). */
public enum StorefrontAppStatus {

    /** Serves every tenant that has authorised it. */
    ACTIVE,

    /** Refused for every tenant at once; reversible. */
    SUSPENDED,

    /** Final. The row stays so an order naming the app still resolves; the app never serves again. */
    RETIRED
}
