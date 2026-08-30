package uz.horecaos.platform.pos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.pos.api.CapabilitySnapshot;
import uz.horecaos.platform.pos.api.CapabilitySupport;
import uz.horecaos.platform.pos.api.PosCapability;
import uz.horecaos.platform.pos.application.port.PosAdapter;
import uz.horecaos.platform.pos.application.port.PosAdapter.PosContext;
import uz.horecaos.platform.pos.domain.UncertainExportResolver;
import uz.horecaos.platform.pos.infrastructure.clopos.CloposAdapter;
import uz.horecaos.platform.pos.infrastructure.clopos.CloposConfig;
import uz.horecaos.platform.pos.infrastructure.clopos.CloposSession;

/**
 * The contract every POS adapter satisfies, run against both adapters in the
 * build (ADR 0007, ADR 0011).
 *
 * <p>Two adapters, deliberately unalike. One is a real vendor with no idempotency
 * key, no push, and no preparation feed; the other is a fake with all three.
 * ADR 0011's exit criterion is that a fake and one real adapter pass the same
 * provider-neutral tests, and the point of the pairing is that a contract only
 * exercised against one shape is a description of that shape.
 *
 * <p>Notice what is <em>not</em> asserted: that both adapters support the same
 * things. They do not, and an abstraction that required them to would have to
 * invent a preparation feed for a till that has none.
 */
class PosAdapterContractTests {

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121701");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121702");

    static Stream<PosAdapter> adapters() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        RecordingPosTransport transport = new RecordingPosTransport()
                .answerWith(ProviderOutcome.success(Map.of(
                        "success", true, "token", "t", "expires_at",
                        NOW.plusSeconds(3600).getEpochSecond(), "data", List.of()), null));
        return Stream.of(
                new FakePosAdapter(),
                new CloposAdapter(transport, new CloposSession(transport, clock), clock));
    }

    @ParameterizedTest
    @MethodSource("adapters")
    @DisplayName("a discovered capability never exceeds what the adapter declares")
    void discoveryNarrowsAndNeverWidens(PosAdapter adapter) {
        CapabilitySnapshot snapshot = adapter.discoverCapabilities(context());

        snapshot.entries().forEach((capability, entry) -> {
            if (entry.support() == CapabilitySupport.UNSUPPORTED) {
                return;
            }
            assertThat(adapter.declaredCapabilities())
                    .as("%s reported %s for %s without declaring it",
                            adapter.providerType(), entry.support(), capability)
                    .contains(capability);
        });
    }

    @ParameterizedTest
    @MethodSource("adapters")
    @DisplayName("every capability the adapter declares has an entry in the snapshot")
    void discoveryAnswersForEverythingItClaims(PosAdapter adapter) {
        CapabilitySnapshot snapshot = adapter.discoverCapabilities(context());

        assertThat(snapshot.entries().keySet())
                .as("a declared capability with no snapshot entry reads as 'not discovered', "
                        + "which is indistinguishable from 'not asked'")
                .containsAll(adapter.declaredCapabilities());
    }

    @ParameterizedTest
    @MethodSource("adapters")
    @DisplayName("the order export states what a repeat would do")
    void everyAdapterDeclaresItsExportIdempotency(PosAdapter adapter) {
        CapabilitySnapshot snapshot = adapter.discoverCapabilities(context());

        assertThat(snapshot.entry(PosCapability.ORDER_EXPORT))
                .as("the resolver's whole decision turns on this value, so an adapter that does "
                        + "not state it would default to the strictest reading by accident")
                .isPresent();
    }

    @ParameterizedTest
    @MethodSource("adapters")
    @DisplayName("a capability an adapter does not support is reported unusable, not absent")
    void anUnsupportedCapabilityIsStatedRatherThanOmitted(PosAdapter adapter) {
        CapabilitySnapshot snapshot = adapter.discoverCapabilities(context());

        for (PosCapability capability : PosCapability.values()) {
            if (adapter.declaredCapabilities().contains(capability)) {
                continue;
            }
            assertThat(snapshot.usable(capability))
                    .as("%s must not be usable on %s", capability, adapter.providerType())
                    .isFalse();
        }
    }

    @ParameterizedTest
    @MethodSource("adapters")
    @DisplayName("a cancellation on an unknown order is answered, never thrown")
    void nothingThrowsForAProviderFailure(PosAdapter adapter) {
        ProviderOutcome outcome = adapter.cancelExportedOrder(context(), "no-such-order", "test");

        assertThat(outcome).isNotNull();
        assertThat(outcome.status()).isNotNull();
    }

    @DisplayName("the fake deduplicates a repeat and the real adapter cannot")
    @org.junit.jupiter.api.Test
    void theTwoAdaptersDisagreeAboutRetryingAndTheContractCarriesTheDifference() {
        FakePosAdapter fake = new FakePosAdapter();
        CapabilitySnapshot fakeSnapshot = fake.discoverCapabilities(context());

        var fakeDecision = UncertainExportResolver.decide(List.of(),
                fakeSnapshot.entry(PosCapability.ORDER_EXPORT).orElseThrow().idempotency());
        assertThat(fakeDecision.outcome())
                .as("a provider that deduplicates is told to send again")
                .isEqualTo(UncertainExportResolver.Outcome.RETRY_UNDER_KEY);

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        RecordingPosTransport transport = new RecordingPosTransport()
                .answerWith(ProviderOutcome.success(Map.of(
                        "success", true, "token", "t", "data", List.of()), null));
        CloposAdapter clopos = new CloposAdapter(transport, new CloposSession(transport, clock), clock);
        var cloposSnapshot = clopos.discoverCapabilities(context());

        var cloposDecision = UncertainExportResolver.decide(List.of(),
                cloposSnapshot.entry(PosCapability.ORDER_EXPORT).orElseThrow().idempotency());
        assertThat(cloposDecision.outcome())
                .as("a provider that does not deduplicate sends a person a decision")
                .isEqualTo(UncertainExportResolver.Outcome.OPERATOR);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a repeat under one reference produces one order on a provider that has a key")
    void theFakeProvesWhatIdempotencyWouldBuyUs() {
        FakePosAdapter fake = new FakePosAdapter();
        var order = new PosAdapter.OrderExport(UUID.randomUUID(), "A-1024", "A-1024",
                new PosAdapter.OrderExport.Customer("1", "Anvar", "+998901234567", "Amir Temur 1"),
                List.of(new PosAdapter.OrderExport.Line("f-1", "Fake dish", 1, 10_000L, List.of())),
                10_000L, "UZS", "DELIVERY", false, NOW);

        fake.exportOrder(context(), order);
        fake.exportOrder(context(), order);
        fake.exportOrder(context(), order);

        assertThat(fake.sideEffectCount())
                .as("this is the guarantee Clopos does not offer, and the reason its uncertain "
                        + "exports go to a person instead of a retry queue")
                .isEqualTo(1);
    }

    private static PosContext context() {
        return new PosContext(TENANT, INSTALLATION, null, "3", Map.of(
                CloposConfig.BRAND, "openapitest",
                CloposConfig.CLIENT_ID, "client-1",
                CloposConfig.INTEGRATOR_ID, "horecaos-test",
                CloposConfig.CURRENCY, "UZS"), "correlation-1");
    }
}
