package uz.horecaos.platform.payments.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretIngressGateway;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.payments.domain.MerchantBinding;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcMerchantBindingStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Registering, activating, suspending and retiring a legal entity's merchant
 * account (ADR 0013).
 *
 * <p>Until this class, nothing wrote {@code payments.merchant_bindings} over
 * HTTP: {@code JdbcPaymentBindingResolver} only reads it, so every binding in
 * every environment was hand-written SQL. This is the write side that was
 * missing, modelled on {@code tenancy.application.LegalEntityService}'s
 * register/activate/archive shape — the same class of decision, held by the
 * same capability's own javadoc calls "the highest-consequence configuration
 * action in the module".
 *
 * <p><strong>The legal entity is checked here, not only by the database's
 * foreign key.</strong> Both checks exist for the same reason
 * {@code LegalEntityService.assign} keeps both: this one produces a sentence an
 * operator can act on — which tenant the id belongs to, and whether it is
 * currently active — and the constraint is what still holds when two operators
 * register a binding for the same entity in the same second.
 */
@Service
public class MerchantBindingService {

    private final JdbcMerchantBindingStore store;
    private final MerchantLegalEntityGate legalEntities;
    private final Clock clock;
    private final SecretIngressGateway door;

    public MerchantBindingService(
            JdbcMerchantBindingStore store,
            MerchantLegalEntityGate legalEntities,
            Clock clock,
            SecretIngressGateway door) {
        this.store = store;
        this.legalEntities = legalEntities;
        this.clock = clock;
        this.door = door;
    }

    @Transactional
    public MerchantBinding register(UUID tenantId, RegisterMerchantBindingCommand command) {
        requireActiveLegalEntity(tenantId, command.legalEntityId());
        SecretReference secretReference = parseSecretReference(command.secretReference());

        MerchantBinding binding = MerchantBinding.draft(
                UUID.randomUUID(),
                tenantId,
                command.legalEntityId(),
                command.providerType(),
                command.installationId(),
                command.integrationBindingId(),
                command.merchantAccountReference(),
                command.merchantUserReference(),
                command.merchantIdReference(),
                secretReference,
                command.callbackPathSegment(),
                command.supportsReversal(),
                command.supportsPartnerFiscalization(),
                command.effectiveFrom(),
                command.effectiveUntil());

        try {
            store.insert(binding, clock.instant());
        } catch (DataIntegrityViolationException violation) {
            throw JdbcMerchantBindingStore.explain(violation);
        }
        return binding;
    }

    @Transactional
    public MerchantBinding activate(UUID tenantId, UUID bindingId, int expectedVersion) {
        return transition(tenantId, bindingId, expectedVersion, MerchantBinding::activate);
    }

    @Transactional
    public MerchantBinding suspend(UUID tenantId, UUID bindingId, int expectedVersion) {
        return transition(tenantId, bindingId, expectedVersion, MerchantBinding::suspend);
    }

    @Transactional
    public MerchantBinding archive(UUID tenantId, UUID bindingId, int expectedVersion) {
        return transition(tenantId, bindingId, expectedVersion, MerchantBinding::archive);
    }

    /**
     * Rotates this binding's credential through the ADR 0065 door.
     *
     * <p>Unlike {@code ProviderInstallationController}'s Telegram path, there is
     * no pre-flip verification here: Click's Merchant API and Payme's Merchant
     * API both offer HorecaOS no outbound, side-effect-free call that would
     * prove a rotated key before committing to it — the same absence {@code
     * ProviderCapabilityReconciliationService}'s own doc comment records for its
     * non-POS preflight, and the reason ADR 0026 never invented a generic
     * provider ping. The value is written and the reference is swapped; whether
     * it was right is proven the next time a real payment settles through it,
     * the same posture ADR 0065 describes for any provider with no harmless
     * call to verify against.
     *
     * @throws uz.horecaos.platform.web.api.ApiException {@code STALE_VERSION}
     *         when the row moved on since {@code expectedVersion} was read.
     *         The write to the secrets manager already happened by then, under
     *         a reference this binding never gets pointed at — orphaned, never
     *         a leak, the same accepted trade-off ADR 0028's rollback section
     *         names ("never returns secret values to the database")
     */
    @Transactional
    public MerchantBinding rotateSecret(UUID tenantId, UUID bindingId, int expectedVersion, String newValue) {
        // Confirms the binding exists (and belongs to this tenant) before a
        // door write is spent on a rotation that could never land anywhere.
        require(tenantId, bindingId);
        SecretReference newReference =
                door.write(SecretCategory.PROVIDER_PAYMENT, "tenant-" + tenantId, SecretValue.of(newValue));

        if (!store.updateSecretReference(tenantId, bindingId, newReference, expectedVersion, clock.instant())) {
            throw new ApiException(
                    ErrorCode.STALE_VERSION,
                    "Merchant binding %s has moved on from version %d".formatted(bindingId, expectedVersion));
        }
        return require(tenantId, bindingId);
    }

    @Transactional(readOnly = true)
    public List<MerchantBinding> list(UUID tenantId) {
        return store.listForTenant(tenantId);
    }

    @Transactional(readOnly = true)
    public MerchantBinding require(UUID tenantId, UUID bindingId) {
        return store.find(tenantId, bindingId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No merchant binding " + bindingId + " for this tenant"));
    }

    private MerchantBinding transition(
            UUID tenantId, UUID bindingId, int expectedVersion, Consumer<MerchantBinding> change) {
        MerchantBinding binding = require(tenantId, bindingId);
        change.accept(binding);
        try {
            if (!store.update(binding, expectedVersion, clock.instant())) {
                throw new ApiException(
                        ErrorCode.STALE_VERSION,
                        "Merchant binding %s has moved on from version %d".formatted(bindingId, expectedVersion));
            }
        } catch (DataIntegrityViolationException violation) {
            throw JdbcMerchantBindingStore.explain(violation);
        }
        return binding;
    }

    /**
     * @throws ApiException {@code RESOURCE_NOT_FOUND} when the id does not belong
     *                       to this tenant at all, {@code RESOURCE_CONFLICT} when
     *                       it does but is not currently active — a legal entity
     *                       a branch cannot yet sell under cannot settle a payment
     *                       either
     */
    private void requireActiveLegalEntity(UUID tenantId, UUID legalEntityId) {
        boolean active = legalEntities
                .activeFor(tenantId, legalEntityId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No legal entity " + legalEntityId + " for this tenant"));
        if (!active) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Legal entity %s is not active and cannot be named as a merchant binding's seller"
                            .formatted(legalEntityId));
        }
    }

    private static SecretReference parseSecretReference(String reference) {
        SecretReference parsed;
        try {
            parsed = SecretReference.parse(reference);
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    Objects.requireNonNullElse(malformed.getMessage(), "Malformed secret reference"));
        }
        if (parsed.category() != SecretCategory.PROVIDER_PAYMENT) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A merchant binding's secret reference must be in the provider_payment category, not "
                            + parsed.category());
        }
        return parsed;
    }

    /**
     * What an operator submits to register a merchant binding.
     *
     * @param secretReference an ADR 0028 reference. The value it points at is
     *                        written to the secrets manager directly and never
     *                        passes through this API
     * @param effectiveFrom   when this binding starts resolving. Backdating is
     *                        permitted; a binding effective before it was
     *                        registered is how the first binding on a
     *                        newly-migrated legal entity is recorded honestly
     */
    public record RegisterMerchantBindingCommand(
            UUID legalEntityId,
            PaymentProviderType providerType,
            UUID installationId,
            UUID integrationBindingId,
            String merchantAccountReference,
            @Nullable String merchantUserReference,
            @Nullable String merchantIdReference,
            String secretReference,
            String callbackPathSegment,
            boolean supportsReversal,
            boolean supportsPartnerFiscalization,
            LocalDate effectiveFrom,
            @Nullable LocalDate effectiveUntil) {}
}
