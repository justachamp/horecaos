package uz.horecaos.platform.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.staff.StaffMemberCards;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.MappingEntityType;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.pos.FakePosAdapter;
import uz.horecaos.platform.pos.application.PosMappingService.BulkAutoMatchResult;
import uz.horecaos.platform.pos.application.PosMappingService.UnmappedBothSides;
import uz.horecaos.platform.pos.application.port.PosAdapter;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosBindingConfiguration;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosMappingStore;

/**
 * Gap-map row 9.2c, the pane's two new facts: the operator pairing is chosen from the till's own list
 * ({@link PosAdapter#discoverOperators}), and the pane is told whether the till's order API receives
 * the pairing at all.
 *
 * <p>The second is the honest half. A till can list its users and still receive none on an order, so
 * "the list could be read" ({@code sourced}) and "the pairing goes anywhere" ({@code
 * receivedByProvider}) are different answers, and the console must not let the first stand in for
 * the second.
 */
class PosMappingServiceOperatorTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-0000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f6f4e-0000-7000-8000-0000000000a2");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-0000-7000-8000-0000000000a3");
    private static final UUID BINDING = UUID.fromString("018f6f4e-0000-7000-8000-0000000000a4");
    private static final UUID AZIZA = UUID.fromString("018f6f4e-0000-7000-8000-0000000000b1");
    private static final UUID BOBUR = UUID.fromString("018f6f4e-0000-7000-8000-0000000000b2");

    private JdbcPosMappingStore store;
    private JdbcPosBindingConfiguration configuration;
    private StaffMemberCards cards;

    @BeforeEach
    void setUp() {
        store = mock(JdbcPosMappingStore.class);
        configuration = mock(JdbcPosBindingConfiguration.class);
        cards = mock(StaffMemberCards.class);
        when(configuration.bindingRef(TENANT, BINDING))
                .thenReturn(Optional.of(new BindingRef(
                        BINDING,
                        INSTALLATION,
                        TENANT,
                        ProviderCategory.POS,
                        FakePosAdapter.PROVIDER_TYPE,
                        BRAND,
                        null)));
        when(configuration.resolve(any())).thenReturn(Optional.of(Map.of()));
        when(store.activelyMappedIds(any(), any(), any())).thenReturn(java.util.Set.of());
        when(cards.pickable(TENANT))
                .thenReturn(List.of(
                        new StaffMemberCards.Card(AZIZA, "s-aziza", "STAFF-1", "Aziza", null, "ACTIVE"),
                        new StaffMemberCards.Card(BOBUR, "s-bobur", "STAFF-2", "Bobur", null, "ACTIVE")));
    }

    private PosMappingService serviceWith(PosAdapter... adapters) {
        return new PosMappingService(
                store,
                configuration,
                new PosAdapterRegistry(List.of(adapters)),
                Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneOffset.UTC),
                cards);
    }

    @Test
    @DisplayName("the pairing is chosen from the till's own list, and the till is said to receive it")
    void theTillsOwnListIsOfferedAndTheCapabilityIsDeclared() {
        UnmappedBothSides sides =
                serviceWith(new FakePosAdapter()).listUnmapped(TENANT, BINDING, MappingEntityType.OPERATOR);

        assertThat(sides.external().sourced()).isTrue();
        assertThat(sides.external().entities())
                .extracting(entity -> entity.externalId() + "=" + entity.name())
                .containsExactly("fake-op-17=Aziza", "fake-op-18=Bobur");
        assertThat(sides.horecaos()).extracting(candidate -> candidate.name()).containsExactly("Aziza", "Bobur");
        assertThat(sides.receivedByProvider()).isTrue();
    }

    @Test
    @DisplayName(
            "a till that lists its users and receives none on an order is sourced and still says it does not receive one")
    void aTillThatListsButDoesNotReceiveIsSourcedAndNotReceived() {
        UnmappedBothSides sides = serviceWith(new FakePosAdapter().withoutOperatorAttribution())
                .listUnmapped(TENANT, BINDING, MappingEntityType.OPERATOR);

        assertThat(sides.external().sourced()).as("the list can be read").isTrue();
        assertThat(sides.receivedByProvider())
                .as("but a pairing for this till goes nowhere, and the console says so")
                .isFalse();
    }

    @Test
    @DisplayName(
            "a till that cannot list its users is not sourced, with the vendor's reason, and typing the id still works")
    void aTillThatCannotListIsNotSourced() {
        FakePosAdapter adapter = new FakePosAdapter()
                .failOperatorsWith(
                        ProviderOutcome.rejected("NOT_SUPPORTED", "This point of sale does not list its operators"));

        UnmappedBothSides sides = serviceWith(adapter).listUnmapped(TENANT, BINDING, MappingEntityType.OPERATOR);

        assertThat(sides.external().sourced()).isFalse();
        assertThat(sides.external().detail()).contains("does not list its operators");
        assertThat(sides.external().entities()).isEmpty();
        assertThat(sides.horecaos()).hasSize(2);
    }

    @Test
    @DisplayName(
            "an adapter written before this port method existed answers NOT_SUPPORTED by default, never an empty success")
    void theDefaultIsNotSupportedNotAnEmptyList() {
        PosAdapter bare = mock(PosAdapter.class, org.mockito.Mockito.CALLS_REAL_METHODS);

        PosAdapter.ReferenceListRead read =
                bare.discoverOperators(new PosAdapter.PosContext(TENANT, INSTALLATION, BINDING, "1", Map.of(), "c"));

        assertThat(read.outcome().status()).isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(read.outcome().errorCode()).isEqualTo("NOT_SUPPORTED");
        assertThat(read.entries()).isEmpty();
    }

    @Test
    @DisplayName("no adapter for the binding's provider: nothing sourced, nothing received")
    void noAdapterMeansNeitherSourcedNorReceived() {
        UnmappedBothSides sides = serviceWith().listUnmapped(TENANT, BINDING, MappingEntityType.OPERATOR);

        assertThat(sides.external().sourced()).isFalse();
        assertThat(sides.receivedByProvider()).isFalse();
    }

    @Test
    @DisplayName("the question is asked only of OPERATOR; every other entity type answers null")
    void otherEntityTypesAreNotAskedWhetherTheTillReceivesThem() {
        PosMappingService service = serviceWith(new FakePosAdapter());

        assertThat(service.listUnmapped(TENANT, BINDING, MappingEntityType.PAYMENT_TYPE)
                        .receivedByProvider())
                .isNull();
        assertThat(service.listUnmapped(TENANT, BINDING, MappingEntityType.COURIER)
                        .receivedByProvider())
                .isNull();
    }

    @Test
    @DisplayName("auto-match pairs colleagues with the till's operators of the same name, once each")
    void autoMatchPairsOperatorsByName() {
        when(store.create(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());

        BulkAutoMatchResult result =
                serviceWith(new FakePosAdapter()).bulkAutoMatch(TENANT, BINDING, MappingEntityType.OPERATOR);

        assertThat(result.sourced()).isTrue();
        assertThat(result.matchedCount()).isEqualTo(2);
        verify(store, times(1))
                .create(
                        eq(TENANT),
                        eq(INSTALLATION),
                        eq(BINDING),
                        eq(MappingEntityType.OPERATOR),
                        eq(AZIZA),
                        eq("fake-op-17"),
                        any(),
                        any());
        verify(store, times(1))
                .create(
                        eq(TENANT),
                        eq(INSTALLATION),
                        eq(BINDING),
                        eq(MappingEntityType.OPERATOR),
                        eq(BOBUR),
                        eq("fake-op-18"),
                        any(),
                        any());
    }
}
