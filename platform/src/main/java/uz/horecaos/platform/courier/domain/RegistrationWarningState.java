package uz.horecaos.platform.courier.domain;

/**
 * What the platform knows about a registration's remaining life (ADR 0042).
 *
 * <p>Only {@link #LAPSED} changes what dispatch may do. {@link #EXPIRING} exists
 * so that the change is not a surprise: it drives the ADR 0020 ladder to the
 * courier and, from day fourteen, to the branch manager, because a courier who
 * ignores the message is the tenant's problem and not only their own.
 */
public enum RegistrationWarningState {

    VALID,
    EXPIRING,
    LAPSED
}
