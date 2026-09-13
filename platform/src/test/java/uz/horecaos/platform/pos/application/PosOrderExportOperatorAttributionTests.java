package uz.horecaos.platform.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import uz.horecaos.platform.catalog.api.PackageCodeLookup;
import uz.horecaos.platform.integration.api.provider.ProviderEntityMappingLookup;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.pos.application.port.PosOrderSource;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosBindingConfiguration;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosCapabilityStore;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosExportStore;

/**
 * {@link PosOrderExportService#resolveOperatorExternalId} — the whole shape of
 * operations-gap-map.md's {@code 9.2c}: a provider-neutral contract field,
 * resolved through the one generic ADR 0026 mapping every other export entity
 * already uses, with no new table and no per-adapter special case.
 *
 * <p>Deliberately not a full export-flow test: the resolution under test here
 * has no order-export machinery of its own — see {@link
 * PosOrderExportService#resolveOperatorExternalId}'s own doc for why it is
 * package-private rather than folded invisibly into {@code prepare}.
 */
class PosOrderExportOperatorAttributionTests {

    private static final UUID BINDING = UUID.fromString("018f6f4e-0001-7000-8000-000000000001");
    private static final UUID OPERATOR_SUBJECT = UUID.fromString("018f6f4e-0002-7000-8000-000000000002");

    private ProviderEntityMappingLookup mappings;
    private PosOrderExportService service;

    @BeforeEach
    void setUp() {
        mappings = mock(ProviderEntityMappingLookup.class);
        service = new PosOrderExportService(
                mock(PosAdapterRegistry.class),
                mock(ProviderInstallationLookup.class),
                mappings,
                mock(JdbcPosBindingConfiguration.class),
                mock(JdbcPosExportStore.class),
                mock(JdbcPosCapabilityStore.class),
                mock(PosOrderSource.class),
                mock(PackageCodeLookup.class),
                mock(ApplicationEventPublisher.class),
                Clock.systemUTC());
    }

    @Test
    @DisplayName("a USER acceptance with a mapping resolves to the till-side operator id")
    void resolvesTheMappedOperator() {
        when(mappings.externalIdFor(eq(BINDING), eq("OPERATOR"), eq(OPERATOR_SUBJECT)))
                .thenReturn(Optional.of("clopos-user-77"));

        String resolved = service.resolveOperatorExternalId(BINDING, "USER", OPERATOR_SUBJECT.toString());

        assertThat(resolved).isEqualTo("clopos-user-77");
    }

    @Test
    @DisplayName("a USER acceptance with no mapping yet resolves to nothing, not an error")
    void noMappingIsNullNotAFailure() {
        when(mappings.externalIdFor(any(), any(), any())).thenReturn(Optional.empty());

        String resolved = service.resolveOperatorExternalId(BINDING, "USER", OPERATOR_SUBJECT.toString());

        assertThat(resolved).isNull();
    }

    @Test
    @DisplayName("a non-USER acceptance never even attempts a lookup")
    void nonUserActorTypesAreNeverLookedUp() {
        String resolved = service.resolveOperatorExternalId(BINDING, "SYSTEM_JOB", "pos:clopos");

        assertThat(resolved).isNull();
        verifyNoInteractions(mappings);
    }

    @Test
    @DisplayName("an order nobody has accepted yet has no operator")
    void anUnacceptedOrderHasNoOperator() {
        String resolved = service.resolveOperatorExternalId(BINDING, null, null);

        assertThat(resolved).isNull();
        verifyNoInteractions(mappings);
    }

    @Test
    @DisplayName("a USER actor id that is not a UUID is a data fact, not a thrown exception")
    void aMalformedActorIdIsTreatedAsNoOperator() {
        String resolved = service.resolveOperatorExternalId(BINDING, "USER", "not-a-uuid");

        assertThat(resolved).isNull();
        verifyNoInteractions(mappings);
    }
}
