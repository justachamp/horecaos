package uz.horecaos.platform.payments.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.secrets.SecretReference;

/**
 * The write side of {@link ProviderBinding} (ADR 0013, ADR 0026).
 *
 * <p>{@link ProviderBinding} is a resolver's answer: it is what
 * {@code JdbcPaymentBindingResolver} returns for a row that is already {@code
 * ACTIVE} and effective, and it deliberately carries neither a status nor a
 * version because a checkout has no use for either. This is the row itself,
 * mutable in the same narrow way {@code tenancy.domain.LegalEntity} is — an
 * application service holding an aggregate under an expected version, not a
 * record replaced wholesale — because registering, activating, suspending and
 * retiring a merchant account is a lifecycle with rules, not four columns a
 * client may set directly.
 *
 * <p>What is deliberately <em>not</em> mutable here, for the same reason
 * {@code LegalEntity.tin} is not: {@link #legalEntityId()},
 * {@link #providerType()}, {@link #merchantAccountReference()} and
 * {@link #secretReference()} name the account this row <em>is</em>. Changing any
 * of them in place would silently repoint every payment this binding has ever
 * settled at a different seller or a different credential; a re-registration is
 * a new binding, retiring the old one, not an edit of this one.
 */
public final class MerchantBinding {

    private final UUID id;
    private final UUID tenantId;
    private final UUID legalEntityId;
    private final PaymentProviderType providerType;
    private final UUID installationId;
    private final UUID integrationBindingId;
    private final String merchantAccountReference;
    private final @Nullable String merchantUserReference;
    private final @Nullable String merchantIdReference;
    private final SecretReference secretReference;
    private final String callbackPathSegment;
    private final boolean supportsReversal;
    private final boolean supportsPartnerFiscalization;
    private final LocalDate effectiveFrom;
    private final @Nullable LocalDate effectiveUntil;
    private MerchantBindingStatus status;
    private final int version;

    private MerchantBinding(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            PaymentProviderType providerType,
            UUID installationId,
            UUID integrationBindingId,
            String merchantAccountReference,
            @Nullable String merchantUserReference,
            @Nullable String merchantIdReference,
            SecretReference secretReference,
            String callbackPathSegment,
            boolean supportsReversal,
            boolean supportsPartnerFiscalization,
            LocalDate effectiveFrom,
            @Nullable LocalDate effectiveUntil,
            MerchantBindingStatus status,
            int version) {
        this.id = Objects.requireNonNull(id, "A merchant binding ID is required");
        this.tenantId = Objects.requireNonNull(tenantId, "A tenant ID is required");
        this.legalEntityId = Objects.requireNonNull(legalEntityId, "A legal entity is required: it is the seller");
        this.providerType = Objects.requireNonNull(providerType, "A provider type is required");
        this.installationId = Objects.requireNonNull(installationId, "The ADR 0026 installation is required");
        this.integrationBindingId = Objects.requireNonNull(integrationBindingId, "The ADR 0026 binding is required");
        this.merchantAccountReference = Objects.requireNonNull(
                        merchantAccountReference, "A merchant account reference is required")
                .strip();
        this.merchantUserReference =
                merchantUserReference == null || merchantUserReference.isBlank() ? null : merchantUserReference.strip();
        this.merchantIdReference =
                merchantIdReference == null || merchantIdReference.isBlank() ? null : merchantIdReference.strip();
        this.secretReference = Objects.requireNonNull(secretReference, "A secret reference is required");
        this.callbackPathSegment = Objects.requireNonNull(callbackPathSegment, "A callback path segment is required")
                .strip();
        this.supportsReversal = supportsReversal;
        this.supportsPartnerFiscalization = supportsPartnerFiscalization;
        this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "An effective-from date is required");
        if (effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveUntil must be after effectiveFrom");
        }
        this.effectiveUntil = effectiveUntil;
        this.status = Objects.requireNonNull(status, "A status is required");
        this.version = version;
    }

    /**
     * Registers a binding in {@code DRAFT}.
     *
     * <p>Draft rather than active, the same reason {@code LegalEntity.draft} is:
     * {@code JdbcPaymentBindingResolver} only ever returns a row whose status is
     * {@code ACTIVE}, so a binding that could settle a payment the instant it is
     * typed in is one that will settle a payment before anyone has confirmed the
     * account, the callback segment and the secret reference are all correct.
     * Activation is the separate step where that confirmation has a place to sit.
     */
    public static MerchantBinding draft(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            PaymentProviderType providerType,
            UUID installationId,
            UUID integrationBindingId,
            String merchantAccountReference,
            @Nullable String merchantUserReference,
            @Nullable String merchantIdReference,
            SecretReference secretReference,
            String callbackPathSegment,
            boolean supportsReversal,
            boolean supportsPartnerFiscalization,
            LocalDate effectiveFrom,
            @Nullable LocalDate effectiveUntil) {
        return new MerchantBinding(
                id,
                tenantId,
                legalEntityId,
                providerType,
                installationId,
                integrationBindingId,
                merchantAccountReference,
                merchantUserReference,
                merchantIdReference,
                secretReference,
                callbackPathSegment,
                supportsReversal,
                supportsPartnerFiscalization,
                effectiveFrom,
                effectiveUntil,
                MerchantBindingStatus.DRAFT,
                1);
    }

    public static MerchantBinding reconstitute(
            UUID id,
            UUID tenantId,
            UUID legalEntityId,
            PaymentProviderType providerType,
            UUID installationId,
            UUID integrationBindingId,
            String merchantAccountReference,
            @Nullable String merchantUserReference,
            @Nullable String merchantIdReference,
            SecretReference secretReference,
            String callbackPathSegment,
            boolean supportsReversal,
            boolean supportsPartnerFiscalization,
            LocalDate effectiveFrom,
            @Nullable LocalDate effectiveUntil,
            MerchantBindingStatus status,
            int version) {
        return new MerchantBinding(
                id,
                tenantId,
                legalEntityId,
                providerType,
                installationId,
                integrationBindingId,
                merchantAccountReference,
                merchantUserReference,
                merchantIdReference,
                secretReference,
                callbackPathSegment,
                supportsReversal,
                supportsPartnerFiscalization,
                effectiveFrom,
                effectiveUntil,
                status,
                version);
    }

    public void activate() {
        requireStatus(MerchantBindingStatus.DRAFT, MerchantBindingStatus.SUSPENDED);
        status = MerchantBindingStatus.ACTIVE;
    }

    /**
     * Suspends the binding. It stops resolving for a new payment; nothing about
     * a payment it already settled changes.
     */
    public void suspend() {
        requireStatus(MerchantBindingStatus.ACTIVE);
        status = MerchantBindingStatus.SUSPENDED;
    }

    /**
     * Ends the binding's life on the platform. Permitted from {@code DRAFT} and
     * {@code SUSPENDED} only — an {@code ACTIVE} binding is suspended first, so
     * retiring one is never the first anyone hears that it stopped resolving.
     *
     * <p>The row survives retirement, the same reason a legal entity is archived
     * rather than deleted: every payment attempt this binding ever settled
     * points at this id, and it must still resolve an account reference years
     * later for a reconciliation to explain.
     */
    public void archive() {
        requireStatus(MerchantBindingStatus.DRAFT, MerchantBindingStatus.SUSPENDED);
        status = MerchantBindingStatus.RETIRED;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID legalEntityId() {
        return legalEntityId;
    }

    public PaymentProviderType providerType() {
        return providerType;
    }

    public UUID installationId() {
        return installationId;
    }

    public UUID integrationBindingId() {
        return integrationBindingId;
    }

    public String merchantAccountReference() {
        return merchantAccountReference;
    }

    public Optional<String> merchantUserReference() {
        return Optional.ofNullable(merchantUserReference);
    }

    public Optional<String> merchantIdReference() {
        return Optional.ofNullable(merchantIdReference);
    }

    /** An ADR 0028 reference. The value it points at never reaches this object. */
    public SecretReference secretReference() {
        return secretReference;
    }

    public String callbackPathSegment() {
        return callbackPathSegment;
    }

    public boolean supportsReversal() {
        return supportsReversal;
    }

    public boolean supportsPartnerFiscalization() {
        return supportsPartnerFiscalization;
    }

    public LocalDate effectiveFrom() {
        return effectiveFrom;
    }

    public @Nullable LocalDate effectiveUntil() {
        return effectiveUntil;
    }

    public MerchantBindingStatus status() {
        return status;
    }

    public int version() {
        return version;
    }

    private void requireStatus(MerchantBindingStatus... allowed) {
        for (MerchantBindingStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException("Merchant binding cannot transition from " + status);
    }

    /**
     * Deliberately omits the secret reference and every account identifier, the
     * same reason {@link ProviderBinding#toString()} does: this ends up in
     * exception messages and log lines, and the account reference is the one
     * field that names a specific restaurant's merchant account. The id is
     * enough to find the row.
     */
    @Override
    public String toString() {
        return "MerchantBinding[" + providerType + " id=" + id + " legalEntity=" + legalEntityId + " status=" + status
                + "]";
    }
}
