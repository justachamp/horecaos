package uz.horecaos.platform.tenancy.api;

/** Onboarding run health, exposed to another module (ADR 0008, ADR 0058). */
public interface OnboardingHealthQuery {

    OnboardingHealth onboardingHealth();
}
