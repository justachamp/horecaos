package uz.horecaos.platform.legal.domain;

import org.jspecify.annotations.Nullable;

/**
 * What a storefront should show right now for one brand and one locale, and
 * the label an acceptance of it is recorded against (ADR 0068).
 *
 * @param policyVersionLabel opaque evidence key stored as
 *                            {@code customer.consent_decisions.policy_version}
 *                            — {@code "v3:ru"} for the tenant's own third
 *                            version in Russian, {@code "default-v1:en"} for
 *                            the platform default in English. Carries the
 *                            locale so that accepting the Uzbek words is never
 *                            confused with accepting the Russian ones, even
 *                            when both happen to be the same version number.
 * @param isPlatformDefault true when no tenant-authored text exists for this
 *                          locale and {@link PlatformDefaultTerms} was served
 *                          instead
 * @param documentVersion the tenant's own version number, null when
 *                        {@code isPlatformDefault}
 */
public record EffectiveTerms(
        String policyVersionLabel,
        String locale,
        String body,
        boolean isPlatformDefault,
        @Nullable Integer documentVersion) {}
