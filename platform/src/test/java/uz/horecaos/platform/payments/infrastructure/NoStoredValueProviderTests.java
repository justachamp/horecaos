package uz.horecaos.platform.payments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.payments.application.FiscalReceiptPort;
import uz.horecaos.platform.payments.application.PaymentProviderPort;
import uz.horecaos.platform.payments.domain.PaymentProviderType;

/**
 * ADR 0165, new guard 2: the payment provider vocabulary names no wallet and no deposit.
 *
 * <p>HorecaOS holds no customer funds. A tenant that needs stored value gets it, if ever,
 * through its own licensed provider as an ordinary payment adapter (Decision 4), and
 * that adapter arrives as a <em>new</em> {@link PaymentProviderType} value with its own
 * assertion that its tender declares {@code settles_from_balance = false}. This test makes
 * the arrival visible: the set of provider types is compared for equality, not for
 * containment, so adding one is a diff in this file and a reason to read ADR 0165.
 *
 * <p>What it would not catch, stated rather than hoped: a provider called {@code ACME} that
 * happens to be a wallet. The names are checked, and the exact set is pinned so that a human
 * is asked the question.
 */
class NoStoredValueProviderTests {

    private static final List<String> STEMS =
            List.of("deposit", "wallet", "storedvalue", "prepaid", "balance", "giftcard", "topup");

    private static String folded(String value) {
        return value.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    private static boolean namesStoredValue(String value) {
        String folded = folded(value);
        return STEMS.stream().anyMatch(folded::contains);
    }

    @Test
    @DisplayName("PaymentProviderType is exactly the three providers ADR 0013 knows, none of them a wallet")
    void paymentProviderTypeIsPinnedAndNamesNoWallet() {
        List<String> names =
                Arrays.stream(PaymentProviderType.values()).map(Enum::name).toList();

        assertThat(names)
                .as("a new provider type is a decision: if it is a stored-value instrument, "
                        + "ADR 0165 Decision 4 applies (a PAYMENT installation, settles_from_balance "
                        + "false, responsibility PARTNER) and it needs its own assertion here")
                .containsExactlyInAnyOrder("CLICK", "PAYME", "TELEGRAM");
        assertThat(names).noneMatch(NoStoredValueProviderTests::namesStoredValue);
    }

    @Test
    @DisplayName("the PAYMENT capability catalogue declares no capability that names a wallet or a deposit")
    void thePaymentCatalogueDeclaresNoStoredValueCapability() {
        List<PaymentProviderPort> payments = new ArrayList<>();
        List<FiscalReceiptPort> fiscal = new ArrayList<>();
        for (PaymentProviderType type : PaymentProviderType.values()) {
            PaymentProviderPort payment = mock(PaymentProviderPort.class);
            when(payment.providerType()).thenReturn(type);
            payments.add(payment);
            if (type.canFiscalize()) {
                FiscalReceiptPort receipt = mock(FiscalReceiptPort.class);
                when(receipt.providerType()).thenReturn(type);
                fiscal.add(receipt);
            }
        }
        PaymentProviderCapabilityCatalog catalogue = new PaymentProviderCapabilityCatalog(payments, fiscal);

        assertThat(catalogue.category()).isEqualTo(ProviderCategory.PAYMENT);

        Set<String> declared = Arrays.stream(PaymentProviderType.values())
                .map(type -> catalogue.declarationFor(type.name()))
                .flatMap(java.util.Optional::stream)
                .flatMap(declaration -> declaration.capabilities().stream())
                .collect(Collectors.toSet());

        assertThat(declared)
                .as("the four operations a payment adapter has: present, query, reverse and "
                        + "issue a fiscal receipt. Nothing that credits, holds or reads a balance")
                .containsExactlyInAnyOrder(
                        PaymentProviderCapabilityCatalog.PAYMENT_PRESENT,
                        PaymentProviderCapabilityCatalog.PAYMENT_QUERY,
                        PaymentProviderCapabilityCatalog.PAYMENT_REVERSE,
                        PaymentProviderCapabilityCatalog.FISCAL_RECEIPT);
        assertThat(declared).noneMatch(NoStoredValueProviderTests::namesStoredValue);

        // The declaration is also read off the class's public constants, so a capability
        // added there without a provider declaring it yet is still seen.
        List<String> constants = Arrays.stream(PaymentProviderCapabilityCatalog.class.getFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()) && field.getType() == String.class)
                .map(field -> {
                    try {
                        return (String) field.get(null);
                    } catch (IllegalAccessException unreadable) {
                        throw new IllegalStateException(unreadable);
                    }
                })
                .toList();
        assertThat(constants).hasSize(4).noneMatch(NoStoredValueProviderTests::namesStoredValue);
    }

    @Test
    @DisplayName("the detector fires on each stem, so a green run above means something")
    void theDetectorFires() {
        for (String stem : List.of(
                "CUSTOMER_DEPOSIT",
                "TENANT_WALLET",
                "STORED_VALUE",
                "PREPAID_CARD",
                "ACCOUNT_BALANCE",
                "GIFT_CARD",
                "TOP_UP",
                "WALLET_PRESENT")) {
            assertThat(namesStoredValue(stem)).as(stem).isTrue();
        }
        assertThat(namesStoredValue("PAYMENT_PRESENT")).isFalse();
    }
}
