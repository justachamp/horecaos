package uz.horecaos.platform.pos.infrastructure.clopos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.horecaos.platform.integration.api.pos.PosApiCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.pos.RecordingPosTransport;
import uz.horecaos.platform.pos.api.CapabilitySnapshot;
import uz.horecaos.platform.pos.api.CapabilitySupport;
import uz.horecaos.platform.pos.api.PosCapability;
import uz.horecaos.platform.pos.application.port.PosAdapter.ExportProbe;
import uz.horecaos.platform.pos.application.port.PosAdapter.ExportResult;
import uz.horecaos.platform.pos.application.port.PosAdapter.OrderExport;
import uz.horecaos.platform.pos.application.port.PosAdapter.PosContext;
import uz.horecaos.platform.pos.application.port.PosAdapter.RecoveryRead;
import uz.horecaos.platform.pos.domain.LineFingerprint;

/**
 * The Clopos adapter's wire behaviour, asserted against the four facts about the
 * vendor that the whole design turns on (ADR 0011).
 */
class CloposAdapterTests {

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121601");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121602");
    private static final UUID BINDING = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121603");
    private static final UUID ORDER = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121604");

    private RecordingPosTransport transport;
    private CloposAdapter adapter;

    @BeforeEach
    void setUp() {
        transport = new RecordingPosTransport();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        adapter = new CloposAdapter(transport, new CloposSession(transport, clock), clock);
    }

    @Test
    @DisplayName("preparation status is not declared, because nothing reports preparation")
    void thePreparationCapabilityIsAbsentFromTheDeclaration() {
        assertThat(adapter.declaredCapabilities())
                .as("the only preparation-shaped field is one we write, and reading our own "
                        + "writes back would be telemetry we invented")
                .doesNotContain(PosCapability.PREPARATION_STATUS);
    }

    @Test
    @DisplayName("discovery states preparation status as unsupported rather than omitting it")
    void discoveryAnswersNoRatherThanSayingNothing() {
        transport.answerWith(RecordingPosTransport.list(List.of()));

        CapabilitySnapshot snapshot = adapter.discoverCapabilities(context());

        assertThat(snapshot.entry(PosCapability.PREPARATION_STATUS))
                .get()
                .satisfies(entry -> {
                    assertThat(entry.support()).isEqualTo(CapabilitySupport.UNSUPPORTED);
                    assertThat(entry.evidence()).contains("we write");
                });
        assertThat(snapshot.usable(PosCapability.PREPARATION_STATUS)).isFalse();
    }

    @Test
    @DisplayName("customer upsert is partial — our policy, not the vendor's incapability")
    void ourOwnPolicyIsNotRecordedAsAProviderIncapability() {
        transport.answerWith(RecordingPosTransport.list(List.of()));

        CapabilitySnapshot snapshot = adapter.discoverCapabilities(context());

        assertThat(snapshot.entry(PosCapability.CUSTOMER_UPSERT))
                .get()
                .satisfies(entry -> {
                    assertThat(entry.support())
                            .as("UNSUPPORTED means the provider cannot; Clopos can, and it is the "
                                    + "ADR 0029 consent basis we are missing")
                            .isEqualTo(CapabilitySupport.PARTIAL);
                    assertThat(entry.evidence())
                            .as("PARTIAL is only honest if the rationale says which part is missing")
                            .contains("consent basis");
                    assertThat(entry.idempotency())
                            .isEqualTo(CapabilitySnapshot.IdempotencyBehaviour.NONE);
                });
        // The consequence that made the wrong value more than a wording problem:
        // UNSUPPORTED is the one answer a binding can never override, so encoding
        // our own policy as it made a reversible decision look permanent. The
        // provider ceiling V0036 seeds is PARTIAL, so nothing capped it back.
        assertThat(snapshot.usable(PosCapability.CUSTOMER_UPSERT))
                .as("the consent basis is what gates this, not the vendor's API surface")
                .isTrue();
    }

    @Test
    @DisplayName("the order export declares that a repeat creates a second order")
    void exportIdempotencyIsRecordedAsNone() {
        transport.answerWith(RecordingPosTransport.list(List.of()));

        CapabilitySnapshot snapshot = adapter.discoverCapabilities(context());

        assertThat(snapshot.entry(PosCapability.ORDER_EXPORT))
                .get()
                .satisfies(entry -> assertThat(entry.idempotency())
                        .isEqualTo(CapabilitySnapshot.IdempotencyBehaviour.NONE));
    }

    @Test
    @DisplayName("the export is classified as an unkeyed create, which is what makes a lost "
            + "response uncertain rather than retryable")
    void theExportDeclaresItsEffect() {
        transport
                .enqueue(authOk())
                .enqueue(RecordingPosTransport.object(Map.of("id", 771, "status", "PENDING")));

        adapter.exportOrder(context(), order());

        PosApiCall exportCall = transport.lastCall();
        assertThat(exportCall.method()).isEqualTo("POST");
        assertThat(exportCall.path()).isEqualTo("/orders");
        assertThat(exportCall.effect()).isEqualTo(PosApiCall.Effect.UNKEYED_CREATE);
    }

    @Test
    @DisplayName("every authenticated call carries x-token and the venue header")
    void theVenueHeaderSelectsTheRestaurant() {
        transport.enqueue(authOk()).answerWith(RecordingPosTransport.list(List.of()));

        adapter.readAvailability(context());

        assertThat(transport.lastHeaders())
                .as("the value in x-venue is the single field that decides which kitchen "
                        + "prints a customer's dinner")
                .containsEntry("x-venue", "3")
                .containsKey("x-token");
        assertThat(transport.lastHeaders())
                .as("despite the auth response saying Bearer, an Authorization header earns a "
                        + "401 reading \"Headers are missing\"")
                .doesNotContainKey("Authorization");
    }

    @Test
    @DisplayName("the clerk is asked by default, so a failure leaves an order rather than food")
    void autoAcceptIsOffUnlessConfigured() {
        transport
                .enqueue(authOk())
                .enqueue(RecordingPosTransport.object(Map.of("id", 771, "status", "PENDING")));

        ExportResult result = adapter.exportOrder(context(), order());

        Map<String, Object> body = transport.bodies().getLast();
        assertThat(body).containsEntry("auto_order_accept", false);
        assertThat(body).containsEntry("auto_order_sent_to_station", false);
        assertThat(result.approvalPending())
                .as("an order awaiting a clerk is recoverable and visible; an order "
                        + "auto-accepted and auto-sent to a station is already food")
                .isTrue();
    }

    @Test
    @DisplayName("the correlation reference is sent even though the schema omits the field")
    void theCorrelationReferenceIsSentAnyway() {
        transport
                .enqueue(authOk())
                .enqueue(RecordingPosTransport.object(Map.of("id", 771, "status", "PENDING")));

        adapter.exportOrder(context(), order());

        assertThat(transport.bodies().getLast())
                .as("if it is honoured the recovery read is deterministic, and if it is dropped "
                        + "nothing is lost")
                .containsEntry("order_number", "A-1024");
    }

    @Test
    @DisplayName("the recovery read does not filter by status")
    void filteringOnPendingWouldMissAnOrderTheClerkJustAccepted() {
        transport
                .enqueue(authOk())
                .answerWith(RecordingPosTransport.list(List.of()));

        adapter.findExportedOrder(context(), probe());

        String path = transport.lastCall().path();
        assertThat(path).startsWith("/orders");
        assertThat(path)
                .as("an order accepted between our timeout and this read has already left "
                        + "PENDING, and a filtered read would report it absent")
                .doesNotContain("status");
        assertThat(path).contains("date%5B0%5D=2026-08-23");
    }

    @Test
    @DisplayName("a candidate is returned with its evidence, and echoed correlation is false "
            + "when the provider carried nothing back")
    void theRecoveryReadReturnsEvidenceRatherThanAVerdict() {
        transport
                .enqueue(authOk())
                .enqueue(RecordingPosTransport.list(List.of(cloposOrder())));

        RecoveryRead read = adapter.findExportedOrder(context(), probe());

        assertThat(read.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.externalOrderId()).isEqualTo("771");
            assertThat(candidate.phoneMatches())
                    .as("the phone is normalised before hashing, so the till's own formatting "
                            + "does not defeat the match")
                    .isTrue();
            assertThat(candidate.fingerprintMatches()).isTrue();
            assertThat(candidate.correlationEchoed())
                    .as("the create request has no documented field that sets any of the "
                            + "correlation columns, so this is false until Clopos answers")
                    .isFalse();
            assertThat(candidate.timeDeltaSeconds()).isEqualTo(5);
        });
    }

    @Test
    @DisplayName("a cancellation is only the pre-acceptance one, and it is idempotent")
    void cancellationIsTheIgnoreTransitionAndNothingElse() {
        transport.enqueue(authOk()).answerWith(RecordingPosTransport.object(Map.of("id", 771)));

        adapter.cancelExportedOrder(context(), "771", "customer changed their mind");

        assertThat(transport.lastCall().method()).isEqualTo("PUT");
        assertThat(transport.lastCall().path()).isEqualTo("/orders/771");
        assertThat(transport.bodies().getLast()).containsEntry("status", "IGNORE");
        assertThat(transport.lastCall().effect())
                .as("setting a terminal state converges, so a lost response really is safe "
                        + "to send again")
                .isEqualTo(PosApiCall.Effect.IDEMPOTENT_WRITE);
    }

    @Test
    @DisplayName("the fiscal write-back is idempotent by construction")
    void writingAFiscalIdentifierIsSafeToRepeat() {
        transport.enqueue(authOk()).answerWith(RecordingPosTransport.object(Map.of("id", 900)));

        adapter.writeFiscalIdentifier(context(), "900", "Twrewr89fnscvj22");

        assertThat(transport.lastCall().method()).isEqualTo("PATCH");
        assertThat(transport.lastCall().effect()).isEqualTo(PosApiCall.Effect.IDEMPOTENT_WRITE);
        assertThat(transport.bodies().getLast()).containsEntry("fiscal_id", "Twrewr89fnscvj22");
    }

    @Test
    @DisplayName("a fulfilment status write is outbound and is not a kitchen report")
    void thereIsNoReadPathBackFromTheStatusWeWrite() {
        transport.enqueue(authOk()).answerWith(RecordingPosTransport.object(Map.of("id", 900)));

        adapter.writeFulfillmentStatus(context(), "900", "IN_PROGRESS");

        assertThat(transport.lastCall().operation()).isEqualTo("receipt.order-status");
        assertThat(transport.bodies().getLast()).containsEntry("order_status", "IN_PROGRESS");
        assertThat(adapter.declaredCapabilities())
                .contains(PosCapability.FULFILLMENT_STATUS_WRITE)
                .doesNotContain(PosCapability.PREPARATION_STATUS);
    }

    @Test
    @DisplayName("the client secret enters only the authentication body")
    void theCredentialNeverReachesTheAdapter() {
        transport.answerWith(authOk());

        adapter.readAvailability(context());

        Map<String, Object> authBody = transport.bodies().getFirst();
        assertThat(authBody)
                .as("the secret is applied inside the gateway, at send time, from a function "
                        + "the adapter cannot read")
                .containsEntry("client_secret", "test-secret")
                .containsEntry("brand", "openapitest")
                .containsEntry("integrator_id", "horecaos-test");
        assertThat(transport.calls().getFirst().effect())
                .as("minting a token twice costs a token, not a side effect")
                .isEqualTo(PosApiCall.Effect.IDEMPOTENT_WRITE);
    }

    @Test
    @DisplayName("a truncated catalog read is refused rather than staged")
    void aFailedPageStopsTheWholeRead() {
        transport
                .enqueue(authOk())
                .answerWith(ProviderOutcome.retryable("PROVIDER_UNAVAILABLE", "500", null));

        var read = adapter.readCatalog(context());

        assertThat(read.snapshot())
                .as("half a menu diffed against a whole one reports the missing half as removals")
                .isNull();
        assertThat(read.outcome().status()).isNotEqualTo(ProviderOutcome.Status.SUCCESS);
    }

    /**
     * A Clopos order row as the list endpoint returns it, with the correlation
     * columns explicitly null — which is what they are on every response today,
     * because the create request has no documented field that sets them.
     */
    private static Map<String, Object> cloposOrder() {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", 771);
        row.put("status", "PENDING");
        row.put("created_at", "2026-08-23 12:00:05");
        row.put("integration_uuid", null);
        row.put("integration_id", null);
        row.put("payload", Map.of(
                "customer", Map.of("phone", "+998 90 123 45 67"),
                "products", List.of(Map.of("product_id", 41, "count", 2, "price", 32000))));
        return row;
    }

    private static ProviderOutcome authOk() {
        return ProviderOutcome.success(Map.of(
                "success", true,
                "token", "eyJhbGciOiJIUzI1NiJ9.test",
                "token_type", "Bearer",
                "expires_at", NOW.plusSeconds(3600).getEpochSecond()), null);
    }

    private static PosContext context() {
        return new PosContext(TENANT, INSTALLATION, BINDING, "3", Map.of(
                CloposConfig.BRAND, "openapitest",
                CloposConfig.CLIENT_ID, "client-1",
                CloposConfig.INTEGRATOR_ID, "horecaos-test",
                CloposConfig.SALE_TYPE_ID, "2",
                CloposConfig.CURRENCY, "UZS"), "correlation-1");
    }

    private static OrderExport order() {
        return new OrderExport(ORDER, "A-1024", "A-1024",
                new OrderExport.Customer("55", "Anvar", "+998901234567", "Amir Temur 1"),
                List.of(new OrderExport.Line("41", "Lagman", 2, 32000L, List.of())),
                64000L, "UZS", "DELIVERY", true, NOW);
    }

    private static ExportProbe probe() {
        List<LineFingerprint.Line> lines =
                List.of(new LineFingerprint.Line("41", 2, 32000L));
        return new ExportProbe("A-1024", "+998901234567", LineFingerprint.of(lines), lines,
                NOW, NOW.plusSeconds(1800));
    }
}
