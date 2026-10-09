package uz.horecaos.platform.storefrontapps.domain;

/**
 * What the conformance suite last said about an app (ADR 0070).
 *
 * <p>{@code EXPIRED} is never stored: a {@code PASSED} result stops counting when
 * the contract's major version moves past the one it was recorded against, and a
 * stored status that nothing would ever flip is how a stale pass keeps certifying
 * an app for a contract it never ran against.
 */
public enum ConformanceStatus {
    NOT_RUN,
    PASSED,
    FAILED,
    EXPIRED
}
