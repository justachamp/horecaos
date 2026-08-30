/**
 * The onboarding step contract (ADR 0008), exposed for handlers that live
 * outside the {@code tenancy} module.
 *
 * <p>Needs its own named interface: a {@code @NamedInterface} on the parent
 * {@code tenancy.api} package does not cover this sub-package, so without this
 * file {@link uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler}
 * and {@link uz.horecaos.platform.tenancy.api.onboarding.OnboardingStep} are
 * internal to {@code tenancy} and no other module's {@code @Component} may
 * implement the handler interface — the same gap ADR 0009 records for {@code
 * iam.api.organizations}. {@code OnboardingService} discovers every handler
 * bean through ordinary Spring collection (a {@code List<OnboardingStepHandler>}
 * constructor parameter), so a handler is free to live in whichever module
 * owns the capability it validates; only the compile-time dependency on these
 * two types needs the module boundary opened.
 */
@org.springframework.modulith.NamedInterface("onboarding")
package uz.horecaos.platform.tenancy.api.onboarding;
