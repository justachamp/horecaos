package uz.horecaos.platform.integration.camel.sms;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.customers.api.OtpWording;

/**
 * The sentence a customer reads, in the language they chose.
 *
 * <p>The text is {@link OtpWording}'s, in {@code customers.api}: the platform owns the sentence, a
 * tenant cannot edit it (ADR 0184, D19), and the console shows the very text this renders. Rendered
 * there rather than through the ADR 0020 template machinery, because customers cannot depend on
 * notifications (that direction is already taken and reversing it would make the two modules cyclic)
 * and because a one-time code is not a message a tenant should be able to edit.
 *
 * <p>This class stays as the adapter's seam so the SMS route and its tests do not change.
 */
final class VerificationCodeText {

    private VerificationCodeText() {}

    static String render(String code, Duration validFor, @Nullable String locale) {
        return OtpWording.render(code, validFor, locale);
    }
}
