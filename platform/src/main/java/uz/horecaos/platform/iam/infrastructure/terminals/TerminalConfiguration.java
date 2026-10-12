package uz.horecaos.platform.iam.infrastructure.terminals;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.application.terminals.TerminalPinPepper;

/**
 * The one bean the terminals cannot construct from their own state (ADR 0155, ADR 0028): the PIN pepper.
 *
 * <p>Key material for a keyed hash, so its category is {@code DATA_ENCRYPTION}, on the same footing as the handover
 * pepper and ADR 0029's envelope keys, and the application <strong>fails to start without it</strong> rather than
 * falling back to something usable: a pepper with a default is a pepper every deployment shares, and a PIN keyed under
 * it protects nothing. This is {@code PartnerConfiguration}'s shape, deliberately.
 *
 * <p>Rotation (ADR 0028 procedure): the previous secret stays resolvable under its own version so a PIN keyed under it
 * still verifies; its next successful unlock re-keys it under the current one.
 */
@Configuration(proxyBeanMethods = false)
public class TerminalConfiguration {

    @Bean
    TerminalPinPepper terminalPinPepper(
            SecretResolver secrets,
            @Value("${horecaos.terminal.pin-pepper-reference}") String reference,
            @Value("${horecaos.terminal.pin-pepper-version:1}") int version,
            @Value("${horecaos.terminal.pin-pepper-previous-reference:}") String previousReference) {

        byte[] current = resolve(secrets, reference, "current");
        if (previousReference == null || previousReference.isBlank()) {
            return TerminalPinPepper.of(version, current, null, null);
        }
        byte[] previous = resolve(secrets, previousReference, "previous");
        // The previous key carries the version just below the current one: a rotation raises the number by one.
        return TerminalPinPepper.of(version, current, version - 1, previous);
    }

    private static byte[] resolve(SecretResolver secrets, String reference, String which) {
        SecretReference parsed = SecretReference.parse(reference);
        if (parsed.category() != SecretCategory.DATA_ENCRYPTION) {
            // A pepper resolved from, say, a provider credential's category would be readable by a runtime role that
            // has no business holding it. The category is part of the access decision in ADR 0028.
            throw new IllegalStateException(
                    "The " + which + " terminal PIN pepper must be a DATA_ENCRYPTION secret (ADR 0028)");
        }
        return secrets.resolve(parsed).reveal().getBytes(StandardCharsets.UTF_8);
    }
}
