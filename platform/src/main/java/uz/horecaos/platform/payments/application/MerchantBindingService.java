package uz.horecaos.platform.payments.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
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

    public MerchantBindingService(JdbcMerchantBindingStore store, MerchantLegalEntityGate legalEntities, Clock clock) {
        this.store = store;
        this.legalEntities = legalEntities;
        this.clock = clock;
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
            throw new ApiException(ErrorCode.VALIDATION_FAILED, malformed.getMessage());
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
            String merchantUserReference,
            String merchantIdReference,
            String secretReference,
            String callbackPathSegment,
            boolean supportsReversal,
            boolean supportsPartnerFiscalization,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil) {}
}
