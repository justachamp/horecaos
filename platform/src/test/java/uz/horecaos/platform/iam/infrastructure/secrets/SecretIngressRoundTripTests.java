package uz.horecaos.platform.iam.infrastructure.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretIngressGateway;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;

/**
 * The write-only door's core round trip (ADR 0065), independent of any one HTTP
 * controller: {@link SecretIngressGateway#write} mints a reference and writes a
 * value; {@link SecretResolver#resolveFresh} reads exactly that value back
 * through the same reference.
 *
 * <p>Deliberately not a {@code @SpringBootTest}, and deliberately not gated on
 * Docker or a running OpenBao. Every {@code @SpringBootTest} in this codebase
 * runs under the {@code environment} secrets provider (nothing overrides {@code
 * horecaos.secrets.provider}), so {@link EnvironmentSecretWriter} and {@link
 * EnvironmentSecretResolver} sharing one {@link MutableSecretStore} — exactly
 * what {@link SecretsConfiguration} wires those two beans to in that mode — is
 * the round trip every full-context test actually exercises, and this proves it
 * fast and deterministically rather than only implicitly through an endpoint
 * test. {@link OpenBaoSecretWriterTests} proves the same contract against the
 * real KV v2 wire format, skippably, the way {@link OpenBaoSecretResolverTests}
 * already does for reads.
 */
class SecretIngressRoundTripTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void aValueWrittenThroughTheDoorResolvesToExactlyItself() {
        MutableSecretStore store = new MutableSecretStore();
        SecretIngressGateway door = new SecretIngressGateway(new EnvironmentSecretWriter(store), "local");
        SecretResolver resolver = new EnvironmentSecretResolver(store::get, CLOCK);

        SecretReference reference =
                door.write(SecretCategory.PROVIDER_NOTIFICATION, "tenant-42", SecretValue.of("a-bot-token"));

        assertThat(reference.environment()).isEqualTo("local");
        assertThat(reference.category()).isEqualTo(SecretCategory.PROVIDER_NOTIFICATION);
        assertThat(reference.ownerScope()).isEqualTo("tenant-42");
        assertThat(resolver.resolveFresh(reference).reveal()).isEqualTo("a-bot-token");
    }

    @Test
    void rotatingThroughTheDoorMintsADifferentReferenceEachTime() {
        MutableSecretStore store = new MutableSecretStore();
        SecretIngressGateway door = new SecretIngressGateway(new EnvironmentSecretWriter(store), "local");

        SecretReference first = door.write(SecretCategory.PROVIDER_PAYMENT, "tenant-1", SecretValue.of("v1"));
        SecretReference second = door.write(SecretCategory.PROVIDER_PAYMENT, "tenant-1", SecretValue.of("v2"));

        assertThat(first.opaqueId())
                .as("the door never reuses an id, so a caller cannot construct a rotation by guessing one")
                .isNotEqualTo(second.opaqueId());
    }

    @Test
    void theDoorRefusesAPlatformOnlyCategory() {
        MutableSecretStore store = new MutableSecretStore();
        SecretIngressGateway door = new SecretIngressGateway(new EnvironmentSecretWriter(store), "local");

        assertThatThrownBy(() -> door.write(SecretCategory.IDENTITY_ADMIN, "tenant-1", SecretValue.of("nope")))
                .as("a tenant action must never be able to overwrite the platform's own Keycloak credential")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> door.write(SecretCategory.DATA_ENCRYPTION, "tenant-1", SecretValue.of("nope")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> door.write(SecretCategory.DATABASE, "tenant-1", SecretValue.of("nope")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> door.write(SecretCategory.OBJECT_STORAGE, "tenant-1", SecretValue.of("nope")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyProviderCategoryIsWritable() {
        MutableSecretStore store = new MutableSecretStore();
        SecretIngressGateway door = new SecretIngressGateway(new EnvironmentSecretWriter(store), "local");
        SecretResolver resolver = new EnvironmentSecretResolver(store::get, CLOCK);

        for (SecretCategory category : new SecretCategory[] {
            SecretCategory.PROVIDER_POS,
            SecretCategory.PROVIDER_PAYMENT,
            SecretCategory.PROVIDER_DELIVERY,
            SecretCategory.PROVIDER_NOTIFICATION
        }) {
            SecretReference reference = door.write(category, "tenant-9", SecretValue.of("v-" + category));
            assertThat(resolver.resolveFresh(reference).reveal()).isEqualTo("v-" + category);
        }
    }
}
