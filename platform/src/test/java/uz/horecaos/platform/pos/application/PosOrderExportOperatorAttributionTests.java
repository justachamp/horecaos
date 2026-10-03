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
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.catalog.api.PackageCodeLookup;
import uz.horecaos.platform.integration.api.provider.ProviderActivityRecorder;
import uz.horecaos.platform.integration.api.provider.ProviderEntityMappingLookup;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.pos.application.port.PosOrderSource;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosBindingConfiguration;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosCapabilityStore;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosExportStore;
import uz.horecaos.platform.support.StaffDirectories;

/**
 * {@link PosOrderExportService#resolveOperatorExternalId} — the whole shape of
 * operations-gap-map.md's {@code 9.2c}: a provider-neutral contract field,
 * resolved through the one generic ADR 0026 mapping every other export entity
 * already uses, with no new table and no per-adapter special case.
 *
 * <p>Since ADR 0139 the mapping is keyed by the tenant's <em>staff member id</em>
 * and not by the Keycloak subject on the order: the subject is resolved to a
 * member id through the tenant-scoped directory first. These tests hold both
 * halves -- the member lookup is asked of this order's own tenant, and the
 * mapping is looked up under the member's id, never the subject's.
 *
 * <p>Deliberately not a full export-flow test: the resolution under test here
 * has no order-export machinery of its own — see {@link
 * PosOrderExportService#resolveOperatorExternalId}'s own doc for why it is
 * package-private rather than folded invisibly into {@code prepare}.
 */
class PosOrderExportOperatorAttributionTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-0000-7000-8000-000000000001");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-0000-7000-8000-000000000002");
    private static final UUID BINDING = UUID.fromString("018f6f4e-0001-7000-8000-000000000001");
    private static final String OPERATOR_SUBJECT = "operator-subject-1";
    private static final UUID OPERATOR_MEMBER = UUID.fromString("018f6f4e-0002-7000-8000-000000000002");

    private ProviderEntityMappingLookup mappings;
    private StaffDirectories.Fake directory;
    private PosOrderExportService service;

    @BeforeEach
    void setUp() {
        mappings = mock(ProviderEntityMappingLookup.class);
        directory = StaffDirectories.fake().member(TENANT, OPERATOR_SUBJECT, OPERATOR_MEMBER);
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
                mock(ProviderActivityRecorder.class),
                Clock.systemUTC(),
                mock(TransactionTemplate.class),
                directory);
    }

    @Test
    @DisplayName("a USER acceptance with a mapping resolves to the till-side operator id, under the member's id")
    void resolvesTheMappedOperator() {
        when(mappings.externalIdFor(eq(BINDING), eq("OPERATOR"), eq(OPERATOR_MEMBER)))
                .thenReturn(Optional.of("clopos-user-77"));

        String resolved = service.resolveOperatorExternalId(TENANT, BINDING, "USER", OPERATOR_SUBJECT);

        assertThat(resolved).isEqualTo("clopos-user-77");
    }

    @Test
    @DisplayName("the mapping is looked up under the member id, never under the Keycloak subject")
    void aMappingKeyedBySubjectIsNotTheKey() {
        // The pre-ADR-0139 reader keyed the lookup by the subject parsed as a UUID.
        // A row stored that way must not resolve now: the staff member id is the
        // HorecaOS entity, as it is for VARIANT and COURIER.
        UUID subjectAsUuid = UUID.fromString("018f6f4e-0003-7000-8000-000000000003");
        directory.member(TENANT, subjectAsUuid.toString(), OPERATOR_MEMBER);
        when(mappings.externalIdFor(eq(BINDING), eq("OPERATOR"), eq(subjectAsUuid)))
                .thenReturn(Optional.of("a-row-keyed-by-the-wrong-id"));
        when(mappings.externalIdFor(eq(BINDING), eq("OPERATOR"), eq(OPERATOR_MEMBER)))
                .thenReturn(Optional.empty());

        assertThat(service.resolveOperatorExternalId(TENANT, BINDING, "USER", subjectAsUuid.toString()))
                .isNull();
    }

    @Test
    @DisplayName("a USER acceptance with no mapping yet resolves to nothing, not an error")
    void noMappingIsNullNotAFailure() {
        when(mappings.externalIdFor(any(), any(), any())).thenReturn(Optional.empty());

        String resolved = service.resolveOperatorExternalId(TENANT, BINDING, "USER", OPERATOR_SUBJECT);

        assertThat(resolved).isNull();
    }

    @Test
    @DisplayName(
            "an accepting subject the order's tenant keeps no member for resolves to nothing, and no mapping is read")
    void aSubjectOfAnotherTenantIsNoOperatorHere() {
        // The member row belongs to TENANT; the export is for OTHER_TENANT.
        String resolved = service.resolveOperatorExternalId(OTHER_TENANT, BINDING, "USER", OPERATOR_SUBJECT);

        assertThat(resolved).isNull();
        verifyNoInteractions(mappings);
    }

    @Test
    @DisplayName("a non-USER acceptance never even attempts a lookup")
    void nonUserActorTypesAreNeverLookedUp() {
        String resolved = service.resolveOperatorExternalId(TENANT, BINDING, "SYSTEM_JOB", "pos:clopos");

        assertThat(resolved).isNull();
        verifyNoInteractions(mappings);
    }

    @Test
    @DisplayName("an order nobody has accepted yet has no operator")
    void anUnacceptedOrderHasNoOperator() {
        String resolved = service.resolveOperatorExternalId(TENANT, BINDING, null, null);

        assertThat(resolved).isNull();
        verifyNoInteractions(mappings);
    }
}
