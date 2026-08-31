package uz.horecaos.platform.payments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.payments.application.PaymentBindingResolver;
import uz.horecaos.platform.payments.application.PaymentIntentService;
import uz.horecaos.platform.payments.application.PaymentLegalEntityResolver;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.tenancy.api.FiscalSeller;
import uz.horecaos.platform.tenancy.api.LegalEntityDirectory;

/**
 * The single unblock for every non-cash payment (ADR 0038, ADR 0013).
 *
 * <p>Before this class existed, {@code PaymentLegalEntityConfiguration}'s
 * {@code @ConditionalOnMissingBean} stand-in was the only
 * {@link PaymentLegalEntityResolver} in any assembly, it always answered
 * {@link Optional#empty()}, and {@code canAcceptPayment} therefore refused CLICK
 * and PAYME everywhere. These tests prove the wiring, not the directory's own
 * resolution rules — {@code LegalEntityAssignmentTests} covers those — and prove
 * it at the level that actually matters to a checkout: whether
 * {@code canAcceptPayment} flips from false to true once a legal entity is
 * assigned and a merchant binding exists for it.
 */
class TenancyLegalEntityResolverTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID LEGAL_ENTITY = UUID.randomUUID();
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 30);

    // ------------------------------------------------------------- delegation

    @Test
    void sellerForDelegatesToTheDirectoryAndUnwrapsTheLegalEntityId() {
        FiscalSeller seller = fiscalSeller(LEGAL_ENTITY);
        LegalEntityDirectory directory = (tenantId, locationId, businessDate) -> Optional.of(seller);

        var resolver = new TenancyLegalEntityResolver(directory);

        assertThat(resolver.sellerFor(TENANT, LOCATION, BUSINESS_DATE)).contains(LEGAL_ENTITY);
    }

    @Test
    void sellerForIsEmptyWhenTheDirectoryHasNoAssignment() {
        LegalEntityDirectory directory = (tenantId, locationId, businessDate) -> Optional.empty();

        var resolver = new TenancyLegalEntityResolver(directory);

        assertThat(resolver.sellerFor(TENANT, LOCATION, BUSINESS_DATE)).isEmpty();
    }

    @Test
    void isWiredMirrorsTheDirectory() {
        LegalEntityDirectory unwired = new LegalEntityDirectory() {
            @Override
            public Optional<FiscalSeller> sellerFor(UUID tenantId, UUID locationId, LocalDate businessDate) {
                return Optional.empty();
            }

            @Override
            public boolean isWired() {
                return false;
            }
        };

        assertThat(new TenancyLegalEntityResolver(unwired).isWired()).isFalse();
        assertThat(new TenancyLegalEntityResolver((t, l, d) -> Optional.empty()).isWired())
                .as("the default LegalEntityDirectory.isWired() is true, and the resolver must not "
                        + "hard-code its own answer over it")
                .isTrue();
    }

    // ------------------------------------------------ canAcceptPayment, end to end

    @Test
    void aClickMethodIsAcceptedOnceALegalEntityIsAssignedAndBound() {
        LegalEntityDirectory directory =
                (tenantId, locationId, businessDate) -> Optional.of(fiscalSeller(LEGAL_ENTITY));
        PaymentLegalEntityResolver resolver = new TenancyLegalEntityResolver(directory);
        PaymentBindingResolver bindings = boundResolverFor(LEGAL_ENTITY);

        var intents = serviceUnderTest(resolver, bindings);

        assertThat(intents.canAcceptPayment(TENANT, LOCATION, "CLICK", BUSINESS_DATE))
                .as("a legal entity is assigned and a merchant account exists for it, so the "
                        + "one thing that used to refuse every provider method is gone")
                .isTrue();
    }

    @Test
    void aClickMethodIsStillRefusedWithNoAssignment() {
        LegalEntityDirectory directory = (tenantId, locationId, businessDate) -> Optional.empty();
        PaymentLegalEntityResolver resolver = new TenancyLegalEntityResolver(directory);
        PaymentBindingResolver bindings = boundResolverFor(LEGAL_ENTITY);

        var intents = serviceUnderTest(resolver, bindings);

        assertThat(intents.canAcceptPayment(TENANT, LOCATION, "CLICK", BUSINESS_DATE))
                .as("no seller is assigned to this location, so there is still nothing for a "
                        + "card payment to be settled into")
                .isFalse();
    }

    @Test
    void cashNeedsNoLegalEntityAtAll() {
        LegalEntityDirectory directory = (tenantId, locationId, businessDate) -> Optional.empty();
        var intents = serviceUnderTest(new TenancyLegalEntityResolver(directory), boundResolverFor(LEGAL_ENTITY));

        assertThat(intents.canAcceptPayment(TENANT, LOCATION, "CASH", BUSINESS_DATE))
                .isTrue();
    }

    /**
     * Wired with only what {@code canAcceptPayment}'s four-argument overload
     * touches: the legal-entity resolver and the binding resolver.
     *
     * <p>The other three collaborators are concrete JDBC/fiscal types with no
     * interface to fake here, and this suite never calls a method that would
     * reach them — so they are deliberately left null rather than stood up for
     * real, which is not the same thing as the constructor's own contract
     * allowing null.
     */
    @SuppressWarnings("NullAway")
    private static PaymentIntentService serviceUnderTest(
            PaymentLegalEntityResolver resolver, PaymentBindingResolver bindings) {
        return new PaymentIntentService(null, null, resolver, bindings, null, null, null);
    }

    private static FiscalSeller fiscalSeller(UUID legalEntityId) {
        return new FiscalSeller(
                legalEntityId,
                TENANT,
                "LE-1",
                "Sinov MCHJ",
                "123456789",
                true,
                UUID.randomUUID(),
                true,
                UUID.randomUUID(),
                1,
                LocalDate.of(2020, 1, 1),
                null);
    }

    private static PaymentBindingResolver boundResolverFor(UUID legalEntityId) {
        return new PaymentBindingResolver() {

            @Override
            public Optional<ProviderBinding> resolve(
                    UUID tenantId, UUID entityId, PaymentProviderType providerType, LocalDate businessDate) {
                if (!entityId.equals(legalEntityId)) {
                    return Optional.empty();
                }
                return Optional.of(new ProviderBinding(
                        UUID.randomUUID(),
                        tenantId,
                        entityId,
                        providerType,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "merchant-account",
                        null,
                        null,
                        SecretReference.parse("horecaos:local:provider_payment:tenant:secret"),
                        "callback-segment",
                        true,
                        true,
                        LocalDate.of(2020, 1, 1),
                        null));
            }

            @Override
            public Optional<ProviderBinding> byCallbackSegment(String callbackPathSegment) {
                return Optional.empty();
            }
        };
    }
}
