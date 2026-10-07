package uz.horecaos.platform.integration.camel.einvoicing;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.commercial.api.EInvoiceOperatorAccount;
import uz.horecaos.platform.commercial.api.EInvoiceOperatorState;
import uz.horecaos.platform.commercial.api.EInvoiceSendOutcome;
import uz.horecaos.platform.commercial.api.EInvoiceStateOutcome;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.integration.camel.einvoicing.didox.DidoxEInvoicingOperator;
import uz.horecaos.platform.integration.camel.einvoicing.faktura.FakturaEInvoicingOperator;

/**
 * Both adapters through the whole real stack on a real socket -- adapter, Camel route, circuit
 * breaker, gateway, {@code ProviderHttpClient} -- against a fake operator that remembers what
 * it was sent (ADR 0007, ADR 0096).
 *
 * <p>The adapters' unit tests prove the mapping; this proves the things only the wire shows: a
 * form body is form-encoded, a lookup's 404 reaches the adapter as an answer rather than a
 * fault, a create whose answer was lost is made exactly once, a host the environment does not
 * approve is never called and a missing secret sends nothing.
 */
class EInvoicingRouteTests {

    private static final String REFERENCE = "horecaos:production:provider_einvoicing:platform:operator-1";

    private FakeOperatorServer server;
    private CamelContext camel;
    private EInvoicingApiTransport transport;
    private Set<String> approvedHosts;
    private Map<String, String> secrets;
    private AtomicInteger freshReads;

    @BeforeEach
    void setUp() throws Exception {
        server = FakeOperatorServer.start();
        approvedHosts = Set.of("127.0.0.1");
        secrets = Map.of(REFERENCE, EInvoiceFixtures.DIDOX_SECRET);
        freshReads = new AtomicInteger();
        camel = camelWith(gateway(Duration.ofMillis(900)));
        transport = new CamelEInvoicingApiTransport(camel.createProducerTemplate());
    }

    @AfterEach
    void tearDown() {
        camel.stop();
        server.close();
    }

    private EInvoicingGateway gateway(Duration timeout) {
        SecretResolver resolver = new SecretResolver() {
            @Override
            public SecretValue resolve(SecretReference reference) {
                String value = secrets.get(reference.toString());
                if (value == null) {
                    throw new SecretNotFoundException(reference);
                }
                return SecretValue.of(value);
            }

            @Override
            public SecretValue resolveFresh(SecretReference reference) {
                freshReads.incrementAndGet();
                return resolve(reference);
            }
        };
        return new EInvoicingGateway(
                environment -> Optional.of(approvedHosts),
                resolver,
                new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier()),
                timeout);
    }

    private CamelContext camelWith(EInvoicingGateway gateway) throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EInvoicingProcessor processor = new EInvoicingProcessor(gateway, new EInvoicingCircuitBreakers(meters), meters);
        CamelContext context = new DefaultCamelContext();
        context.addRoutes(new EInvoicingRouteBuilder(processor));
        context.start();
        return context;
    }

    private EInvoiceOperatorAccount account(String providerType) {
        return new EInvoiceOperatorAccount(
                EInvoiceFixtures.INSTALLATION,
                providerType,
                "env",
                server.baseUrl(),
                REFERENCE,
                Map.of("sellerTaxpayerNumber", "305000001", "sellerName", "HorecaOS MCHJ", "locale", "ru"));
    }

    private DidoxEInvoicingOperator didox() {
        return new DidoxEInvoicingOperator(transport, JsonMapper.builder().build(), Clock.systemUTC());
    }

    private FakturaEInvoicingOperator faktura() {
        return new FakturaEInvoicingOperator(transport, JsonMapper.builder().build(), Clock.systemUTC());
    }

    // ------------------------------------------------------------------- Didox

    @Test
    @DisplayName("Didox: a send signs in with the partner token and posts the invoice with the session, over HTTP")
    void didoxSendsOverHttp() {
        server.reply("POST", "/v1/auth/305000001/password/ru", 200, "{\"token\":\"session-1\"}")
                .reply("POST", "/v1/documents/002/create", 200, "{\"_id\":\"DOC-1\"}");

        EInvoiceSendOutcome outcome = didox().send(account("DIDOX"), EInvoiceFixtures.document());

        assertThat(outcome)
                .isEqualTo(new EInvoiceSendOutcome.Accepted("DOC-1", EInvoiceOperatorState.DRAFT, "created"));
        FakeOperatorServer.Received login = server.last("POST", "/v1/auth/305000001/password/ru");
        assertThat(login.headers()).containsEntry("partner-authorization", "partner-token-1");
        assertThat(login.headers().get("content-type")).isEqualTo("application/json");
        assertThat(login.body()).contains("the-password-1");
        FakeOperatorServer.Received create = server.last("POST", "/v1/documents/002/create");
        assertThat(create.headers())
                .containsEntry("user-key", "session-1")
                .containsEntry("partner-authorization", "partner-token-1");
        assertThat(create.body())
                .contains("\"FacturaNo\":\"S-2026-09-000001\"")
                .contains("\"SellerTin\":\"305000001\"")
                .contains("\"BuyerTin\":\"301234567\"")
                .contains("\"DeliverySum\":500000.00")
                .contains("\"VatRate\":12");
    }

    @Test
    @DisplayName("Didox: a lookup's 404 reaches the adapter as the operator saying it holds no such document")
    void didoxNotFoundIsAnAnswer() {
        server.reply("POST", "/v1/auth/305000001/password/ru", 200, "{\"token\":\"s\"}")
                .reply("GET", "/v1/documents/GONE", 404, "{\"message\":\"not found\"}");

        assertThat(didox().state(account("DIDOX"), EInvoiceFixtures.reference("GONE")))
                .isInstanceOf(EInvoiceStateOutcome.NotFound.class);
    }

    @Test
    @DisplayName("Didox: a create whose answer never arrives is uncertain, and it was made exactly once")
    void didoxLostAnswerIsMadeOnce() {
        server.reply("POST", "/v1/auth/305000001/password/ru", 200, "{\"token\":\"s\"}")
                .reply("POST", "/v1/documents/002/create", 200, "{\"_id\":\"DOC-1\"}")
                .stall("POST", "/v1/documents/002/create", 3_000);

        EInvoiceSendOutcome outcome = didox().send(account("DIDOX"), EInvoiceFixtures.document());

        assertThat(outcome).isInstanceOf(EInvoiceSendOutcome.Uncertain.class);
        assertThat(server.count("POST", "/v1/documents/002/create"))
                .as("the operator may well have created it; sending again could create a second invoice")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Didox: a 502 on the create is uncertain too, because a 5xx may have created the invoice")
    void didoxA502IsUncertain() {
        server.reply("POST", "/v1/auth/305000001/password/ru", 200, "{\"token\":\"s\"}")
                .reply("POST", "/v1/documents/002/create", 502, "{\"error\":\"bad gateway\"}");

        assertThat(didox().send(account("DIDOX"), EInvoiceFixtures.document()))
                .isInstanceOf(EInvoiceSendOutcome.Uncertain.class);
        assertThat(server.count("POST", "/v1/documents/002/create")).isEqualTo(1);
    }

    @Test
    @DisplayName("Didox: a business refusal is refused and says what the operator said, with digit runs masked")
    void didoxRefusal() {
        server.reply("POST", "/v1/auth/305000001/password/ru", 200, "{\"token\":\"s\"}")
                .reply(
                        "POST",
                        "/v1/documents/002/create",
                        422,
                        "{\"message\":\"Buyer 301234567890123 is not registered\",\"error\":\"validation\"}");

        EInvoiceSendOutcome outcome = didox().send(account("DIDOX"), EInvoiceFixtures.document());

        assertThat(outcome).isInstanceOf(EInvoiceSendOutcome.Refused.class);
        assertThat(((EInvoiceSendOutcome.Refused) outcome).detail()).doesNotContain("301234567890123");
    }

    // ----------------------------------------------------------------- Faktura

    @Test
    @DisplayName("Faktura.uz: the token is asked for form-encoded and the container is imported with the bearer")
    void fakturaSendsOverHttp() {
        secrets = Map.of(REFERENCE, EInvoiceFixtures.FAKTURA_SECRET);
        server.reply(
                        "POST",
                        "/token",
                        200,
                        "{\"access_token\":\"bearer-1\",\"token_type\":\"bearer\",\"expires_in\":604799}")
                .reply(
                        "POST",
                        "/Api/Document/ImportDocumentRegister",
                        200,
                        "{\"SuccessCount\":1,\"ErrorCount\":0,\"SuccessItems\":[{\"UniqueId\":\"U-1\",\"ResultCode\":0}],\"ErrorItems\":[]}");

        EInvoiceSendOutcome outcome = faktura().send(account("FAKTURA_UZ"), EInvoiceFixtures.document());

        assertThat(outcome).isEqualTo(new EInvoiceSendOutcome.Accepted("U-1", EInvoiceOperatorState.DRAFT, "imported"));
        FakeOperatorServer.Received token = server.last("POST", "/token");
        assertThat(token.headers().get("content-type")).isEqualTo("application/x-www-form-urlencoded");
        assertThat(token.body())
                .contains("grant_type=password")
                .contains("username=horeca")
                .contains("client_id=cid-1")
                .contains("client_secret=csecret-1");
        FakeOperatorServer.Received imported = server.last("POST", "/Api/Document/ImportDocumentRegister");
        assertThat(imported.query()).isEqualTo("companyInn=305000001");
        assertThat(imported.headers()).containsEntry("authorization", "Bearer bearer-1");
        assertThat(imported.body())
                .contains("\"document_number\":\"S-2026-09-000001\"")
                .contains("\"invoices\"")
                .contains("\"id\":\"" + EInvoiceFixtures.CLIENT_REFERENCE + "\"")
                .contains("\"unit_price\":\"500000.00\"");
    }

    @Test
    @DisplayName("Faktura.uz: a send whose answer was lost is found by its client reference, and a 404 is not found")
    void fakturaLookupByClientReference() {
        secrets = Map.of(REFERENCE, EInvoiceFixtures.FAKTURA_SECRET);
        server.reply("POST", "/token", 200, "{\"access_token\":\"b\",\"expires_in\":604799}")
                .reply("GET", "/Api/Document/GetDocumentByClientDocumentUid", 404, "{\"Message\":\"not found\"}");

        assertThat(faktura().state(account("FAKTURA_UZ"), EInvoiceFixtures.reference(null)))
                .isInstanceOf(EInvoiceStateOutcome.NotFound.class);
        assertThat(server.last("GET", "/Api/Document/GetDocumentByClientDocumentUid")
                        .query())
                .isEqualTo("clientId=" + EInvoiceFixtures.CLIENT_REFERENCE + "&companyInn=305000001");
    }

    // ------------------------------------------------------------------ gateway

    @Test
    @DisplayName("a host the environment does not approve is never called")
    void anUnapprovedHostIsNeverCalled() {
        approvedHosts = Set.of("api-partners.didox.uz");

        EInvoiceSendOutcome outcome = didox().send(account("DIDOX"), EInvoiceFixtures.document());

        assertThat(outcome).isInstanceOf(EInvoiceSendOutcome.NotSent.class);
        assertThat(((EInvoiceSendOutcome.NotSent) outcome).code()).isEqualTo("EGRESS_NOT_APPROVED");
        assertThat(server.requests()).isEmpty();
    }

    @Test
    @DisplayName("plain HTTP is refused for any host but a loopback one, even an approved one")
    void plainHttpToARealHostIsRefused() {
        approvedHosts = Set.of("api.faktura.uz");
        EInvoicingApiCall call =
                call("http://api.faktura.uz", "/Api/ping", ping -> EInvoicingApiCall.Request.of(Map.of()));

        ProviderOutcome outcome = transport.exchange(call);

        assertThat(outcome.errorCode()).isEqualTo("EGRESS_NOT_APPROVED");
    }

    @Test
    @DisplayName("a missing credential sends nothing and says so")
    void aMissingSecretSendsNothing() {
        secrets = Map.of();

        EInvoiceSendOutcome outcome = didox().send(account("DIDOX"), EInvoiceFixtures.document());

        assertThat(((EInvoiceSendOutcome.NotSent) outcome).code()).isEqualTo("SECRET_NOT_FOUND");
        assertThat(server.requests()).isEmpty();
    }

    @Test
    @DisplayName("a malformed reference sends nothing")
    void aMalformedReferenceSendsNothing() {
        EInvoicingApiCall call = new EInvoicingApiCall(
                EInvoiceFixtures.INSTALLATION,
                "DIDOX",
                "env",
                "x",
                "GET",
                server.baseUrl(),
                "/ping",
                "not-a-reference",
                ping -> EInvoicingApiCall.Request.of(Map.of()),
                Set.of(),
                null,
                null);

        assertThat(transport.exchange(call).errorCode()).isEqualTo("SECRET_REFERENCE_INVALID");
        assertThat(server.requests()).isEmpty();
    }

    @Test
    @DisplayName("a request that cannot be built is refused without echoing the message the credential was in")
    void anUnbuildableRequestDropsTheMessage() {
        EInvoicingApiCall call = call(server.baseUrl(), "/ping", credential -> {
            throw new IllegalStateException("login " + credential + " could not be read");
        });

        ProviderOutcome outcome = transport.exchange(call);

        assertThat(outcome.errorCode()).isEqualTo("REQUEST_UNBUILDABLE");
        assertThat(outcome.detail()).isEqualTo("IllegalStateException").doesNotContain("partner-token-1");
        assertThat(server.requests()).isEmpty();
    }

    @Test
    @DisplayName("a refused credential is read once more past the cache and the call repeated once")
    void aRefusedCredentialIsRefreshedOnce() {
        server.reply("GET", "/ping", 401, "{\"error\":\"unauthorized\"}").reply("GET", "/ping", 200, "{\"ok\":true}");

        ProviderOutcome outcome = transport.exchange(
                call(server.baseUrl(), "/ping", credential -> EInvoicingApiCall.Request.of(Map.of())));

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
        assertThat(freshReads).hasValue(1);
        assertThat(server.count("GET", "/ping")).isEqualTo(2);
    }

    @Test
    @DisplayName("a bare JSON array answer is readable, not an unreadable response")
    void aBareArrayIsReadable() {
        server.reply("GET", "/list", 200, "[{\"id\":1,\"code\":\"Archived\"}]");

        ProviderOutcome outcome = transport.exchange(
                call(server.baseUrl(), "/list", credential -> EInvoicingApiCall.Request.of(Map.of())));

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
        assertThat(outcome.normalized().get(ProviderHttpClient.ARRAY_BODY)).isInstanceOf(List.class);
    }

    @Test
    @DisplayName(
            "five failures open the installation's breaker, the sixth call is not made, and another account is unaffected")
    void theBreakerIsPerInstallation() {
        server.reply("POST", "/boom", 502, "{\"error\":\"down\"}").reply("GET", "/ok", 200, "{}");
        UUID other = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            ProviderOutcome failed = transport.exchange(post(EInvoiceFixtures.INSTALLATION, "/boom"));
            assertThat(failed.errorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
        }
        ProviderOutcome open = transport.exchange(post(EInvoiceFixtures.INSTALLATION, "/boom"));

        assertThat(open.errorCode()).isEqualTo("CIRCUIT_OPEN");
        assertThat(server.count("POST", "/boom"))
                .as("the open circuit made no call")
                .isEqualTo(5);
        assertThat(transport.exchange(post(other, "/boom")).errorCode())
                .as("another account has its own breaker")
                .isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(server.count("POST", "/boom")).isEqualTo(6);
    }

    @Test
    @DisplayName("anything that escapes classification is uncertain, never retryable")
    void aRouteFailureIsUncertain() throws Exception {
        EInvoicingGateway exploding =
                new EInvoicingGateway(
                        environment -> Optional.of(approvedHosts),
                        new SecretResolver() {
                            @Override
                            public SecretValue resolve(SecretReference reference) {
                                return SecretValue.of("x");
                            }

                            @Override
                            public SecretValue resolveFresh(SecretReference reference) {
                                return SecretValue.of("x");
                            }
                        },
                        new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier())) {
                    @Override
                    public ProviderOutcome invoke(EInvoicingApiCall call) {
                        throw new IllegalStateException("something nobody classified");
                    }
                };
        CamelContext broken = camelWith(exploding);
        try {
            ProviderOutcome outcome = new CamelEInvoicingApiTransport(broken.createProducerTemplate())
                    .exchange(call(server.baseUrl(), "/ping", credential -> EInvoicingApiCall.Request.of(Map.of())));

            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
            assertThat(outcome.errorCode()).isEqualTo("UNCLASSIFIED");
        } finally {
            broken.stop();
        }
    }

    private EInvoicingApiCall call(
            String baseUrl, String path, java.util.function.Function<String, EInvoicingApiCall.Request> request) {
        return new EInvoicingApiCall(
                EInvoiceFixtures.INSTALLATION,
                "DIDOX",
                "env",
                "get",
                "GET",
                baseUrl,
                path,
                REFERENCE,
                request,
                Set.of(),
                null,
                null);
    }

    private EInvoicingApiCall post(UUID installation, String path) {
        return new EInvoicingApiCall(
                installation,
                "DIDOX",
                "env",
                "create",
                "POST",
                server.baseUrl(),
                path,
                REFERENCE,
                credential -> EInvoicingApiCall.Request.json(Map.of(), Map.of("a", 1)),
                Set.of(),
                null,
                null);
    }

    @SuppressWarnings("unused")
    private static void touch() throws IOException {}
}
