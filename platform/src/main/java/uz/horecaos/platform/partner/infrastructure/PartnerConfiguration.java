package uz.horecaos.platform.partner.infrastructure;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.partner.domain.HandoverCodeHasher;

/**
 * The beans this module cannot construct from its own state (ADR 0040).
 *
 * <p>Only one, and it exists because of ADR 0028: the handover pepper is key
 * material and must never appear in a committed file, a property with a default,
 * or a database column. It is resolved from the secret store at startup, through
 * a reference the deployment supplies, and the application fails to start
 * without it rather than falling back to something usable — a pepper with a
 * default is a pepper every deployment shares, which is a pepper that protects
 * nothing.
 *
 * <p>The category is {@code DATA_ENCRYPTION} because that is what this is: key
 * material for a keyed hash, on the same rotation footing as ADR 0029's envelope
 * keys. Rotating it invalidates the hashes of open challenges, which is why
 * rotation is an ADR 0028 procedure and not a config change — every handover
 * open at that instant would otherwise need a bypass.
 */
@Configuration(proxyBeanMethods = false)
public class PartnerConfiguration {

    @Bean
    HandoverCodeHasher handoverCodeHasher(
            SecretResolver secrets, @Value("${horecaos.partner.handover-pepper-reference}") String peppperReference) {

        SecretReference reference = SecretReference.parse(peppperReference);
        if (reference.category() != SecretCategory.DATA_ENCRYPTION) {
            // A pepper resolved from, say, a provider credential's category would
            // be readable by a runtime role that has no business holding it. The
            // category is part of the access decision in ADR 0028, so it is
            // checked here rather than assumed.
            throw new IllegalStateException("The handover pepper must be a DATA_ENCRYPTION secret (ADR 0028)");
        }
        return new HandoverCodeHasher(secrets.resolve(reference).reveal().getBytes(StandardCharsets.UTF_8));
    }
}
