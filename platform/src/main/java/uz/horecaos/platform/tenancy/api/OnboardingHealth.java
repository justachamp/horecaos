package uz.horecaos.platform.tenancy.api;

/**
 * Platform-wide onboarding run counts, for a control-plane digest (ADR 0008,
 * ADR 0058).
 *
 * @param runsWaiting runs not yet {@code ACTIVE}, {@code CANCELLED}, or {@code FAILED}
 * @param runsFailed  runs that exhausted their attempts and need a person
 */
public record OnboardingHealth(long runsWaiting, long runsFailed) {}
