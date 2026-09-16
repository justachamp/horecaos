package uz.horecaos.platform.customers.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.customers.application.RandomVerificationCodeSource;
import uz.horecaos.platform.customers.application.VerificationCodeSource;
import uz.horecaos.platform.customers.domain.PhoneNumber;
import uz.horecaos.platform.customers.domain.VerificationCode;

/**
 * One number whose code is fixed and whose message is never sent (ADR 0051,
 * ADR 0015).
 *
 * <p>This exists so that the platform can be signed into on a laptop, where no SMS
 * gateway is bound and no message can leave. It is the smallest thing that makes
 * the whole customer journey exercisable end to end without either lying about
 * delivery or writing a live credential somewhere it must not be.
 *
 * <p><strong>Four locks, because one is not enough for what this is.</strong> A
 * fixed one-time code reaching a deployment is not a weakened control — it is a
 * complete authentication bypass for every customer of every tenant, available to
 * anybody who can type a phone number into a form.
 *
 * <ol>
 *   <li>The bean exists only under a local or {@code preprod} profile — the local
 *       set is the same one {@code SecretsProfileGuard} and
 *       {@code VerificationTransportGuard} use, and the same binding
 *       {@code db/local-fixtures} has; {@code preprod} is the one non-local
 *       profile this class admits, added so the pre-production host — which has
 *       no SMS gateway bound — can still exercise the storefront customer
 *       journey end to end. {@code production} alone is never in this list, on
 *       purpose: nothing about the real production deployment ever adds it.</li>
 *   <li>It exists only when a number is configured — see
 *       {@link PresetVerificationCodePhoneConfiguredCondition} for why a plain
 *       {@code @ConditionalOnProperty} is not enough — and the only things that
 *       configure one are {@code application-local.yml} (profile-activated) and
 *       {@code deploy/compose.production.yml}'s pre-production-only environment
 *       variables.</li>
 *   <li>{@link PresetVerificationCodeGuard} refuses to <em>start</em> a profile
 *       that is neither local nor {@code preprod} but has the property set at
 *       all. So the failure mode of somebody copying a local environment file
 *       into a real deployment is a container that will not come up, naming the
 *       variable, rather than a platform that quietly accepts one code from
 *       everybody.</li>
 *   <li>This constructor itself refuses to build the bean when
 *       {@value #ENVIRONMENT_PROPERTY} says {@code production} — the OpenBao
 *       path segment and data-encryption salt for the real Sarkor deployment,
 *       a completely different signal from the Spring profile above. The
 *       {@code preprod} Spring profile alone cannot catch the one deploy-time
 *       mistake that matters most: the same image, with {@code preprod} added
 *       to {@code SPRING_PROFILES_ACTIVE} by hand, started against the real
 *       production environment. This lock is what turns that into a startup
 *       failure naming the property instead of a live bypass.</li>
 * </ol>
 *
 * <p>Every other number on an admitted profile still goes to the random source
 * and still needs a transport, so the preset cannot hide a broken SMS path:
 * asking for a code for any other number fails exactly as it did before this
 * class existed.
 *
 * <p>The configured number is canonicalised at construction and compared against
 * an already-canonicalised destination, so the preset cannot be missed by being
 * typed as {@code 901112233} in one place and {@code +998 90 111 22 33} in
 * another. Neither the number nor the code is ever logged: a test number is still
 * ADR 0029 personal data, and a fixed code is still a credential.
 */
@Component
@Primary
@Profile({"local", "test", "default", "preprod"})
@Conditional(PresetVerificationCodePhoneConfiguredCondition.class)
public class PresetVerificationCodeSource implements VerificationCodeSource {

    /** The property this bean and its guard are both keyed on. One spelling, one place. */
    public static final String PHONE_PROPERTY = "horecaos.customers.verification.preset.phone";

    public static final String CODE_PROPERTY = "horecaos.customers.verification.preset.code";

    /**
     * What {@link #CODE_PROPERTY} means when nobody has typed a code —
     * including the case {@code @Value}'s own annotation default cannot
     * catch, see the constructor.
     */
    private static final String DEFAULT_CODE = "000000";

    /**
     * The fourth lock's own property. Same spelling every other
     * {@code @Value("${horecaos.environment:local}")} injection point in this
     * codebase uses, named here as a constant only because this class's
     * refusal message needs to quote it.
     */
    public static final String ENVIRONMENT_PROPERTY = "horecaos.environment";

    private static final String REAL_PRODUCTION = "production";

    private final String presetDestination;
    private final String presetCode;
    private final RandomVerificationCodeSource everybodyElse;

    public PresetVerificationCodeSource(
            @Value("${" + PHONE_PROPERTY + "}") String presetPhone,
            @Value("${" + CODE_PROPERTY + ":" + DEFAULT_CODE + "}") String presetCode,
            @Value("${" + ENVIRONMENT_PROPERTY + ":local}") String environment,
            RandomVerificationCodeSource everybodyElse) {

        // The fourth lock, checked before anything else: a preprod Spring
        // profile does not by itself prove this is not the real production
        // environment, and this is the one bean where that distinction is
        // everything.
        if (REAL_PRODUCTION.equalsIgnoreCase(environment)) {
            throw new IllegalStateException(ENVIRONMENT_PROPERTY + " is " + REAL_PRODUCTION + ", so " + PHONE_PROPERTY
                    + " must not be configured here. A fixed one-time code on the real production "
                    + "environment is a complete authentication bypass for every customer of every "
                    + "tenant (ADR 0051). Unset " + PHONE_PROPERTY + ", or set " + ENVIRONMENT_PROPERTY
                    + " to the pre-production segment this host actually is.");
        }

        // Both refusals below happen at construction too, so a mistyped number
        // or code is a startup failure naming the property rather than a
        // sign-in that silently never works.
        this.presetDestination = PhoneNumber.requireDeliverableMobile(presetPhone);

        // Blank, not just absent, falls back to the default. @Value's own
        // ":000000" only fires when Spring's Environment has no entry for
        // CODE_PROPERTY at all — true when nobody ever exported the variable,
        // which is how a laptop leaves it. deploy/compose.production.yml
        // instead writes HORECAOS_CUSTOMERS_VERIFICATION_PRESET_CODE:
        // ${HORECAOS_VERIFICATION_PRESET_CODE:-} into the container, which
        // sets the OS environment variable to the empty string rather than
        // leaving it unset whenever the operator has not filled it in — a
        // *present*, blank property that the annotation default never sees.
        // Without this, an operator who filled in only the phone number and
        // left the code at its documented default would get a container that
        // refuses to start, over a variable they never touched.
        String effectiveCode = presetCode.isBlank() ? DEFAULT_CODE : presetCode;
        if (!VerificationCode.isWellFormed(effectiveCode)) {
            throw new IllegalStateException(CODE_PROPERTY + " must be " + VerificationCode.LENGTH + " digits");
        }
        this.presetCode = effectiveCode;
        this.everybodyElse = everybodyElse;
    }

    @Override
    public Code codeFor(String destination) {
        // A plain equals. Constant time is not the point and would imply a threat
        // this does not have: the value compared is a phone number the caller
        // supplied, not a secret, and the code behind it is configuration on a
        // laptop.
        return presetDestination.equals(destination) ? new Code(presetCode, false) : everybodyElse.codeFor(destination);
    }
}
