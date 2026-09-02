package uz.horecaos.platform.iam.infrastructure.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretIngressGateway;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;

/**
 * ADR 0065's door against a real OpenBao — the write-side twin of {@link
 * OpenBaoSecretResolverTests}, same posture: "runs against the compose instance
 * when one is reachable and skips otherwise, so a developer without it running
 * still gets a green build". {@link SecretIngressRoundTripTests} proves the
 * door's contract deterministically against the in-process store every
 * {@code @SpringBootTest} in this codebase actually uses; this class proves the
 * real KV v2 write wire format the production {@code openbao} profile uses,
 * which nothing else in the suite exercises.
 */
class OpenBaoSecretWriterTests {

    private static final String URL = System.getenv().getOrDefault("HORECAOS_OPENBAO_URL", "http://localhost:8200");
    private static final String TOKEN = System.getenv().getOrDefault("HORECAOS_OPENBAO_TOKEN", "horecaos-local-root");
    private static final String MOUNT = "horecaos";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC);

    @BeforeAll
    static void requireOpenBao() {
        Assumptions.assumeTrue(reachable(), "OpenBao is not running; start it with docker compose up -d");
    }

    @Test
    void aValueWrittenThroughTheDoorResolvesThroughTheRealKvApi() {
        SecretIngressGateway door = new SecretIngressGateway(new OpenBaoSecretWriter(client(), MOUNT), "local");
        SecretResolver resolver = new OpenBaoSecretResolver(client(), MOUNT, CLOCK);
        String value = "door-test-" + UUID.randomUUID();

        SecretReference reference =
                door.write(SecretCategory.PROVIDER_NOTIFICATION, "door-write-test", SecretValue.of(value));

        assertThat(resolver.resolveFresh(reference).reveal()).isEqualTo(value);
    }

    @Test
    void writingTwiceUnderOneReferenceIsRotationNotDuplication() {
        SecretReference reference = new SecretReference(
                "local", SecretCategory.PROVIDER_PAYMENT, "door-write-test", "rotation-" + UUID.randomUUID());
        OpenBaoSecretWriter writer = new OpenBaoSecretWriter(client(), MOUNT);
        SecretResolver resolver = new OpenBaoSecretResolver(client(), MOUNT, CLOCK);

        writer.write(reference, SecretValue.of("first-value"));
        assertThat(resolver.resolveFresh(reference).reveal()).isEqualTo("first-value");

        writer.write(reference, SecretValue.of("second-value"));
        assertThat(resolver.resolveFresh(reference).reveal())
                .as("ADR 0028: rotation changes the value behind a stable reference")
                .isEqualTo("second-value");
    }

    private static RestClient client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(2));
        factory.setReadTimeout(java.time.Duration.ofSeconds(5));
        return RestClient.builder()
                .baseUrl(URL)
                .defaultHeader("X-Vault-Token", TOKEN)
                .requestFactory(factory)
                .build();
    }

    private static boolean reachable() {
        try {
            client().get().uri("/v1/sys/health").retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException unreachable) {
            return false;
        }
    }
}
