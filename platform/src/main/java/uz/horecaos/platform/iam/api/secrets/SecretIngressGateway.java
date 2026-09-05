package uz.horecaos.platform.iam.api.secrets;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The write-only secret door itself (ADR 0065), independent of any one HTTP
 * endpoint.
 *
 * <p>ADR 0028's reference format reserves the opaque id for the platform: "not a
 * path a caller can construct". This class is where that promise is kept — the
 * only place in the application that mints a fresh {@link SecretReference} for a
 * value a tenant just supplied, and it always mints one, never accepting an id
 * from a caller. Three call sites share it rather than each generating an id and
 * calling {@link SecretWriter} directly: the bare ingress endpoint
 * ({@code SecretIngressController}) and both value-rotation endpoints
 * ({@code ProviderInstallationController}, {@code MerchantBindingController}).
 * One seam, so "never tenant-chosen" and "never a value" are enforced once
 * instead of three times.
 *
 * <p><strong>Category is restricted to the four provider categories a tenant may
 * legitimately hold a credential for.</strong> {@link SecretCategory#IDENTITY_ADMIN},
 * {@link SecretCategory#DATA_ENCRYPTION}, {@link SecretCategory#DATABASE}, and
 * {@link SecretCategory#OBJECT_STORAGE} are platform-owned; a door that accepted
 * them would let a tenant admin overwrite the platform's own Keycloak service
 * account or envelope-encryption key. {@link SecretCategory#tenantWritable()}
 * is the single check this class trusts, so a new platform-only category added
 * later is closed by default rather than open until someone remembers this
 * class exists.
 *
 * <p>Deliberately not {@code final}: Spring Modulith's cross-module
 * observability support (every named-interface bean a call from another module
 * reaches) wraps this bean in a CGLIB proxy to record a span per call, which
 * fails outright against a final class rather than degrading — the bean simply
 * never constructs, and every controller that depends on it fails to start.
 */
public class SecretIngressGateway {

    private static final Set<SecretCategory> TENANT_WRITABLE = Set.of(
            SecretCategory.PROVIDER_POS,
            SecretCategory.PROVIDER_PAYMENT,
            SecretCategory.PROVIDER_DELIVERY,
            SecretCategory.PROVIDER_NOTIFICATION,
            SecretCategory.PROVIDER_VOICE);

    private final SecretWriter writer;
    private final String environment;

    public SecretIngressGateway(SecretWriter writer, String environment) {
        this.writer = Objects.requireNonNull(writer, "A secret writer is required");
        this.environment = Objects.requireNonNull(environment, "An environment is required");
    }

    /**
     * Mints a fresh, non-guessable reference in {@code category} for {@code
     * ownerScope} and writes {@code value} behind it.
     *
     * @param ownerScope platform-derived (a tenant scope string built from
     *                   authenticated path context, e.g. {@code "tenant-" +
     *                   tenantId}), never a raw caller-supplied string
     * @throws IllegalArgumentException if {@code category} is not one this door
     *                                  may write — the platform-only categories
     *                                  never reach a tenant caller through here
     */
    public SecretReference write(SecretCategory category, String ownerScope, SecretValue value) {
        if (!category.tenantWritable()) {
            throw new IllegalArgumentException(
                    "The secret door writes provider-owned categories only, not " + category);
        }
        SecretReference reference = new SecretReference(
                environment, category, ownerScope, UUID.randomUUID().toString());
        writer.write(reference, value);
        return reference;
    }
}
