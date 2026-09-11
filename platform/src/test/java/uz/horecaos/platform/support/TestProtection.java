package uz.horecaos.platform.support;

import java.time.Clock;
import java.util.Map;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;

/** The real envelope protection (ADR 0029) on a fixed test key, for suites that need to encrypt a field. */
public final class TestProtection {

    private TestProtection() {}

    public static FieldProtection envelope() {
        return new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")::get,
                        Clock.systemUTC()),
                "local"));
    }
}
