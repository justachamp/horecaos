package uz.qoida.platform.payments.application;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import uz.qoida.platform.payments.domain.PaymentProviderType;
import uz.qoida.platform.payments.domain.ProviderBinding;

/**
 * Which merchant account handles a payment, resolved per legal entity (ADR 0013).
 *
 * <p>Resolution runs order → location → legal entity → binding, on the order's
 * business date. A tenant-scoped singular binding cannot express it: a tenant
 * holding three legal entities holds three Click services and three Payme
 * cashboxes, because neither provider takes a seller identity as a per-request
 * field and one account serving all three would name Qoida as the seller on every
 * receipt.
 *
 * <p>The absence of a binding is a <strong>serviceability precondition</strong>
 * and not a runtime failure: if no binding exists for the resolved legal entity,
 * that payment method is not offered on any channel serving that location, and the
 * customer never chooses it. The same shape ADR 0038 uses for cash at a location
 * with no fiscal terminal.
 */
public interface PaymentBindingResolver {

    Optional<ProviderBinding> resolve(UUID tenantId, UUID legalEntityId,
            PaymentProviderType providerType, LocalDate businessDate);

    /** The inbound path lookup: an endpoint per binding, because the credential identifies the account. */
    Optional<ProviderBinding> byCallbackSegment(String callbackPathSegment);
}
