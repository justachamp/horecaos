package uz.horecaos.platform.payments.infrastructure;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.provider.ProviderCapabilityCatalog;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.payments.application.FiscalReceiptPort;
import uz.horecaos.platform.payments.application.PaymentProviderPort;
import uz.horecaos.platform.payments.domain.PaymentProviderType;

/**
 * Payment's adapter declarations for ADR 0026.
 *
 * <p>The payment module has its own merchant-binding facts, but a control-plane
 * binding may still only name operations a wired payment adapter implements.
 */
@Component
public class PaymentProviderCapabilityCatalog implements ProviderCapabilityCatalog {

    public static final String PAYMENT_PRESENT = "PAYMENT_PRESENT";
    public static final String PAYMENT_QUERY = "PAYMENT_QUERY";
    public static final String PAYMENT_REVERSE = "PAYMENT_REVERSE";
    public static final String FISCAL_RECEIPT = "FISCAL_RECEIPT";

    private final Map<PaymentProviderType, PaymentProviderPort> payments;
    private final Set<PaymentProviderType> fiscalProviders;

    public PaymentProviderCapabilityCatalog(
            List<PaymentProviderPort> paymentProviders, List<FiscalReceiptPort> fiscalProviders) {
        payments = paymentProviders.stream()
                .collect(Collectors.toUnmodifiableMap(PaymentProviderPort::providerType, provider -> provider));
        this.fiscalProviders =
                fiscalProviders.stream().map(FiscalReceiptPort::providerType).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public ProviderCategory category() {
        return ProviderCategory.PAYMENT;
    }

    @Override
    public Optional<Declaration> declarationFor(String providerType) {
        PaymentProviderType type;
        try {
            type = PaymentProviderType.valueOf(providerType);
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
        if (!payments.containsKey(type)) {
            return Optional.empty();
        }

        Set<String> capabilities = new LinkedHashSet<>(Set.of(PAYMENT_PRESENT, PAYMENT_QUERY));
        if (type.reversalIsOutbound()) {
            capabilities.add(PAYMENT_REVERSE);
        }
        if (fiscalProviders.contains(type)) {
            capabilities.add(FISCAL_RECEIPT);
        }
        return Optional.of(new Declaration(capabilities, "payment/%s/v1".formatted(providerType)));
    }
}
