package uz.qoida.platform.iam.infrastructure.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import uz.qoida.platform.iam.api.secrets.SecretCategory;
import uz.qoida.platform.iam.api.secrets.SecretReference;
import uz.qoida.platform.iam.api.secrets.SecretResolver;

/**
 * ADR 0028 against a real OpenBao.
 *
 * <p>Runs against the compose instance when one is reachable and skips
 * otherwise, so a developer without it running still gets a green build. The
 * adapter is thin enough that a mocked HTTP client would test almost nothing;
 * what is worth testing is that a real KV v2 response parses and that a missing
 * path fails rather than returning empty.
 */
class OpenBaoSecretResolverTests {

    private static final String URL = System.getenv().getOrDefault("QOIDA_OPENBAO_URL", "http://localhost:8200");
    private static final String TOKEN = System.getenv().getOrDefault("QOIDA_OPENBAO_TOKEN", "qoida-local-root");
    private static final String MOUNT = "qoida";

    private static final SecretReference KEK = new SecretReference(
            "local", SecretCategory.DATA_ENCRYPTION, "platform", "kek");

    private OpenBaoSecretResolver resolver;

    @BeforeAll
    static void requireOpenBao() {
        Assumptions.assumeTrue(reachable(), "OpenBao is not running; start it with docker compose up -d");
    }

    @BeforeEach
    void setUp() {
        resolver = new OpenBaoSecretResolver(
                client(), MOUNT, Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void readsASeededSecret() {
        assertThat(resolver.resolve(KEK).reveal())
                .isEqualTo("local-development-key-encryption-key-not-for-any-other-use");
    }

    @Test
    void aMissingSecretFailsRatherThanReturningEmpty() {
        SecretReference missing = new SecretReference(
                "local", SecretCategory.PROVIDER_PAYMENT, "nobody", "nothing");

        assertThatThrownBy(() -> resolver.resolve(missing))
                .isInstanceOf(SecretResolver.SecretNotFoundException.class)
                .hasMessageContaining("provider_payment");
    }

    @Test
    void theProviderPathIsDerivedRatherThanStored() {
        assertThat(OpenBaoSecretResolver.pathFor(KEK))
                .as("ADR 0034 phase two swaps the manager; no business row may hold a provider-shaped path")
                .isEqualTo("local/data_encryption/platform/kek");
    }

    @Test
    void theEnvelopeKeyProviderResolvesThroughOpenBao() {
        var keys = new uz.qoida.platform.iam.infrastructure.protection.DataEncryptionKeyProvider(
                resolver, "local");
        var protection = new uz.qoida.platform.iam.infrastructure.protection.EnvelopeFieldProtection(keys);

        var record = new uz.qoida.platform.iam.api.protection.FieldProtection.RecordRef(
                "customer.contact_points", "encrypted_value",
                java.util.UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120f01"));
        var tenant = java.util.UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120f02");

        var protectedValue = protection.protect(
                tenant, uz.qoida.platform.iam.api.protection.DataClass.PERSONAL, record, "+998901231076");

        assertThat(protection.reveal(tenant, protectedValue, record, "test"))
                .as("ADR 0029 key material comes from the ADR 0028 manager, not from configuration")
                .isEqualTo("+998901231076");
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
