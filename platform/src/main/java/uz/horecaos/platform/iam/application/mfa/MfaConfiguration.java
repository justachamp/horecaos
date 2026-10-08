package uz.horecaos.platform.iam.application.mfa;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;

/** Wires the one piece of ADR 0148 that needs a secret: the key that seals an enrolment in progress. */
@Configuration(proxyBeanMethods = false)
class MfaConfiguration {

    /**
     * The sealing key is an ADR 0028 reference ({@code data_encryption/platform/mfa-enrolment-sealing-key}),
     * resolved on every use so a rotation takes effect without a restart. Rotating it ends the
     * enrolments in progress and nothing else: a token lives ten minutes and holds no state the
     * platform keeps.
     */
    @Bean
    SealedTokens mfaSealedTokens(
            SecretResolver secrets, Clock clock, @Value("${horecaos.environment:local}") String environment) {
        return new SealedTokens(
                secrets,
                new SecretReference(
                        environment, SecretCategory.DATA_ENCRYPTION, "platform", "mfa-enrolment-sealing-key"),
                clock);
    }
}
