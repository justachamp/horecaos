package uz.qoida.platform.customers.application;

import org.springframework.stereotype.Component;

import uz.qoida.platform.customers.domain.VerificationCode;

/**
 * The only code source a deployment ever has (ADR 0015).
 *
 * <p>Six digits from a CSPRNG, and always sent. Registered unconditionally so
 * that a profile which refuses to create the preset source still has a source —
 * the fallback is the strict one, never the convenient one.
 */
@Component
public class RandomVerificationCodeSource implements VerificationCodeSource {

    @Override
    public Code codeFor(String destination) {
        return new Code(VerificationCode.issue(), true);
    }
}
