package uz.horecaos.platform.iam.infrastructure.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
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
 * The shipped application policy, loaded into a real OpenBao and exercised
 * through a token that carries nothing else (ADR 0028, ADR 0065).
 *
 * <p>{@link OpenBaoSecretWriterTests} and {@link OpenBaoSecretResolverTests}
 * prove the KV v2 wire format, but they do so as the dev root token, which
 * OpenBao never runs through an ACL — so until 2026-09-16 nothing in this
 * suite could notice that {@code deploy/infra/openbao/policies/horecaos-platform.hcl}
 * granted only {@code read}. Pre-production noticed instead: every "Connect
 * provider" in Settings › Integrations failed with
 * {@link OpenBaoSecretWriter.SecretWriteFailedException} because the write-only
 * secret door had no {@code create}. This class renders that policy file for
 * a throwaway environment segment, loads it under a throwaway name, mints a
 * token whose only policy is that one, and asserts what ADR 0028's testing
 * section promised all along: the role can write exactly the tenant-writable
 * provider categories, can still read everything in its environment, and
 * cannot write a platform-owned category or reach another environment.
 *
 * <p>Same posture as its siblings: runs against the compose OpenBao when one is
 * reachable and skips otherwise. The policy is deleted afterwards and the token
 * expires on its own.
 */
class OpenBaoPlatformPolicyTests {

    private static final String URL = System.getenv().getOrDefault("HORECAOS_OPENBAO_URL", "http://localhost:8200");
    private static final String ROOT_TOKEN =
            System.getenv().getOrDefault("HORECAOS_OPENBAO_TOKEN", "horecaos-local-root");
    private static final String MOUNT = "horecaos";
    private static final Path POLICY_FILE =
            Path.of("..", "deploy", "infra", "openbao", "policies", "horecaos-platform.hcl");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T15:00:00Z"), ZoneOffset.UTC);

    /** A segment no other test or seed writes under, so nothing here can collide or leak. */
    private static final String ENVIRONMENT =
            "policytest-" + UUID.randomUUID().toString().substring(0, 8);

    private static final String POLICY_NAME = "horecaos-platform-" + ENVIRONMENT;

    private static String scopedToken;

    @BeforeAll
    static void loadThePolicyAndMintAScopedToken() throws IOException {
        Assumptions.assumeTrue(reachable(), "OpenBao is not running; start it with docker compose up -d");
        String rendered = Files.readString(POLICY_FILE).replace("@ENVIRONMENT@", ENVIRONMENT);
        assertThat(rendered)
                .as("the rendered policy must not still carry the placeholder")
                .doesNotContain("@ENVIRONMENT@");
        root().put()
                .uri("/v1/sys/policies/acl/{name}", POLICY_NAME)
                .body(Map.of("policy", rendered))
                .retrieve()
                .toBodilessEntity();
        TokenResponse minted = java.util.Objects.requireNonNull(
                root().post()
                        .uri("/v1/auth/token/create")
                        .body(Map.of("policies", List.of(POLICY_NAME), "no_default_policy", true, "ttl", "10m"))
                        .retrieve()
                        .body(TokenResponse.class),
                "auth/token/create answered without a body");
        scopedToken = minted.auth().client_token();
        assertThat(minted.auth().policies())
                .as("the token under test carries the shipped policy and nothing else")
                .containsExactly(POLICY_NAME);
    }

    @AfterAll
    static void removeThePolicy() {
        if (scopedToken == null) {
            return;
        }
        root().delete()
                .uri("/v1/sys/policies/acl/{name}", POLICY_NAME)
                .retrieve()
                .toBodilessEntity();
    }

    @Test
    void theDoorCanStoreEveryTenantWritableCategoryAndReadItBack() {
        SecretIngressGateway door = new SecretIngressGateway(new OpenBaoSecretWriter(scoped(), MOUNT), ENVIRONMENT);
        SecretResolver resolver = new OpenBaoSecretResolver(scoped(), MOUNT, CLOCK);
        String ownerScope = "tenant-" + UUID.randomUUID();

        for (SecretCategory category : SecretCategory.values()) {
            if (!category.tenantWritable()) {
                continue;
            }
            String value = category.name().toLowerCase() + "-" + UUID.randomUUID();
            SecretReference reference = door.write(category, ownerScope, SecretValue.of(value));
            assertThat(resolver.resolveFresh(reference).reveal())
                    .as(
                            "%s: the role that wrote the secret must read it back — the specific path block "
                                    + "wins over the blanket read, so it has to repeat `read` itself",
                            category)
                    .isEqualTo(value);
        }
    }

    @Test
    void writingTwiceUnderOneReferenceIsAllowedBecauseRotationNeedsUpdate() {
        OpenBaoSecretWriter writer = new OpenBaoSecretWriter(scoped(), MOUNT);
        SecretResolver resolver = new OpenBaoSecretResolver(scoped(), MOUNT, CLOCK);
        SecretReference reference = new SecretReference(
                ENVIRONMENT,
                SecretCategory.PROVIDER_PAYMENT,
                "tenant-" + UUID.randomUUID(),
                "rotate-" + UUID.randomUUID());

        writer.write(reference, SecretValue.of("first"));
        assertThatCode(() -> writer.write(reference, SecretValue.of("second")))
                .as("a second version of an existing path needs `update`, not just `create`")
                .doesNotThrowAnyException();
        assertThat(resolver.resolveFresh(reference).reveal()).isEqualTo("second");
    }

    @Test
    void thePlatformOwnedCategoriesStayReadOnlyForTheApplication() {
        OpenBaoSecretWriter writer = new OpenBaoSecretWriter(scoped(), MOUNT);
        SecretResolver resolver = new OpenBaoSecretResolver(scoped(), MOUNT, CLOCK);

        for (SecretCategory category : SecretCategory.values()) {
            if (category.tenantWritable()) {
                continue;
            }
            SecretReference reference =
                    new SecretReference(ENVIRONMENT, category, "platform", "guard-" + UUID.randomUUID());
            assertThatThrownBy(() -> writer.write(reference, SecretValue.of("should-never-land")))
                    .as("%s is platform-owned: the application's role must not be able to write it", category)
                    .isInstanceOf(OpenBaoSecretWriter.SecretWriteFailedException.class);
        }

        // The blanket read on the environment is untouched: a value an operator
        // put there (the runbook's bootstrap secrets) still resolves for the role.
        SecretReference operatorWritten = new SecretReference(
                ENVIRONMENT, SecretCategory.DATABASE, "platform", "app-password-" + UUID.randomUUID());
        new OpenBaoSecretWriter(root(), MOUNT).write(operatorWritten, SecretValue.of("operator-set"));
        assertThat(resolver.resolveFresh(operatorWritten).reveal()).isEqualTo("operator-set");
    }

    @Test
    void anotherEnvironmentIsOutOfReachEvenForAProviderCategory() {
        OpenBaoSecretWriter writer = new OpenBaoSecretWriter(scoped(), MOUNT);
        SecretReference elsewhere = new SecretReference(
                ENVIRONMENT + "-other", SecretCategory.PROVIDER_NOTIFICATION, "tenant-" + UUID.randomUUID(), "x");

        assertThatThrownBy(() -> writer.write(elsewhere, SecretValue.of("leak")))
                .isInstanceOf(OpenBaoSecretWriter.SecretWriteFailedException.class);
    }

    private static RestClient root() {
        return client(ROOT_TOKEN);
    }

    private static RestClient scoped() {
        return client(scopedToken);
    }

    private static RestClient client(String token) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(2));
        factory.setReadTimeout(java.time.Duration.ofSeconds(5));
        return RestClient.builder()
                .baseUrl(URL)
                .defaultHeader("X-Vault-Token", token)
                .requestFactory(factory)
                .build();
    }

    private static boolean reachable() {
        try {
            root().get().uri("/v1/sys/health").retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException unreachable) {
            return false;
        }
    }

    /** The subset of {@code auth/token/create}'s response this test reads. */
    record TokenResponse(Auth auth) {
        record Auth(String client_token, List<String> policies) {}
    }
}
