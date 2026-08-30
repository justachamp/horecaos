package uz.horecaos.platform.customers.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * <p><strong>Three locks, because one is not enough for what this is.</strong> A
 * fixed one-time code reaching a deployment is not a weakened control — it is a
 * complete authentication bypass for every customer of every tenant, available to
 * anybody who can type a phone number into a form.
 *
 * <ol>
 *   <li>The bean exists only under a local profile — the same set
 *       {@code SecretsProfileGuard} and {@code VerificationTransportGuard} use,
 *       and the same binding {@code db/local-fixtures} has.</li>
 *   <li>It exists only when a number is configured, and the only thing that
 *       configures one is {@code application-local.yml}, which is itself
 *       profile-activated.</li>
 *   <li>{@link PresetVerificationCodeGuard} refuses to <em>start</em> a non-local
 *       profile that has the property set at all. So the failure mode of somebody
 *       copying a local environment file into a deployment is a container that
 *       will not come up, naming the variable, rather than a platform that quietly
 *       accepts one code from everybody.</li>
 * </ol>
 *
 * <p>Every other number on a local profile still goes to the random source and
 * still needs a transport, so the preset cannot hide a broken SMS path: asking for
 * a code for any other number fails exactly as it did before this class existed.
 *
 * <p>The configured number is canonicalised at construction and compared against
 * an already-canonicalised destination, so the preset cannot be missed by being
 * typed as {@code 901112233} in one place and {@code +998 90 111 22 33} in
 * another. Neither the number nor the code is ever logged: a test number is still
 * ADR 0029 personal data, and a fixed code is still a credential.
 */
@Component
@Primary
@Profile({"local", "test", "default"})
@ConditionalOnProperty(name = PresetVerificationCodeSource.PHONE_PROPERTY)
public class PresetVerificationCodeSource implements VerificationCodeSource {

    /** The property this bean and its guard are both keyed on. One spelling, one place. */
    public static final String PHONE_PROPERTY = "horecaos.customers.verification.preset.phone";

    public static final String CODE_PROPERTY = "horecaos.customers.verification.preset.code";

    private final String presetDestination;
    private final String presetCode;
    private final RandomVerificationCodeSource everybodyElse;

    public PresetVerificationCodeSource(
            @Value("${" + PHONE_PROPERTY + "}") String presetPhone,
            @Value("${" + CODE_PROPERTY + ":000000}") String presetCode,
            RandomVerificationCodeSource everybodyElse) {

        // Both refusals happen at construction, so a mistyped number or code is a
        // startup failure naming the property rather than a sign-in that silently
        // never works.
        this.presetDestination = PhoneNumber.requireDeliverableMobile(presetPhone);
        if (!VerificationCode.isWellFormed(presetCode)) {
            throw new IllegalStateException(
                    CODE_PROPERTY + " must be " + VerificationCode.LENGTH + " digits");
        }
        this.presetCode = presetCode;
        this.everybodyElse = everybodyElse;
    }

    @Override
    public Code codeFor(String destination) {
        // A plain equals. Constant time is not the point and would imply a threat
        // this does not have: the value compared is a phone number the caller
        // supplied, not a secret, and the code behind it is configuration on a
        // laptop.
        return presetDestination.equals(destination)
                ? new Code(presetCode, false)
                : everybodyElse.codeFor(destination);
    }
}
