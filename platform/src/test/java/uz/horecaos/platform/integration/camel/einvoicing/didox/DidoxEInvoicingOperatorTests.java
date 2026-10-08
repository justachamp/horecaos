package uz.horecaos.platform.integration.camel.einvoicing.didox;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.commercial.api.EInvoiceDocumentReference;
import uz.horecaos.platform.commercial.api.EInvoiceOperatorAccount;
import uz.horecaos.platform.commercial.api.EInvoiceOperatorState;
import uz.horecaos.platform.commercial.api.EInvoiceSendOutcome;
import uz.horecaos.platform.commercial.api.EInvoiceStateOutcome;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.integration.camel.einvoicing.EInvoiceFixtures;
import uz.horecaos.platform.integration.camel.einvoicing.EInvoicingApiCall;
import uz.horecaos.platform.integration.camel.einvoicing.ScriptedTransport;

/**
 * The Didox adapter against a scripted operator (ADR 0096): every answer here is one the
 * partner SDK's types describe, because HorecaOS has no account to ask the real thing.
 *
 * <p>What matters is less that a happy path parses than what happens on the unhappy ones. A
 * create is never repeated -- a lost answer is uncertain and is asked about -- and the one repeat
 * the adapter does make, signing in again after a refused session, is made once and only because
 * a refused session means the operator refused before it acted.
 */
class DidoxEInvoicingOperatorTests {

    private static final String SECRET = EInvoiceFixtures.DIDOX_SECRET;

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-07T09:00:00Z"));
    private final EInvoiceOperatorAccount account = EInvoiceFixtures.account("DIDOX", "https://api-partners.didox.uz");

    private DidoxEInvoicingOperator operator(ScriptedTransport transport) {
        return new DidoxEInvoicingOperator(transport, JsonMapper.builder().build(), clock);
    }

    private static ProviderOutcome token(String token) {
        return ProviderOutcome.success(Map.of("token", token), null);
    }

    private static ProviderOutcome body(Map<String, Object> body) {
        return ProviderOutcome.success(body, null);
    }

    // ------------------------------------------------------------------ mapping

    @Test
    @DisplayName("the invoice is laid out as the partner SDK's builder lays it out, in sums and not in tiyin")
    void thePayloadFollowsTheSdkBuilder() {
        Map<String, Object> payload = DidoxEInvoicingOperator.payload(EInvoiceFixtures.document());

        assertThat(payload).containsEntry("Version", 1).containsEntry("FacturaType", 0);
        assertThat(payload.get("FacturaDoc"))
                .isEqualTo(Map.of("FacturaNo", "S-2026-09-000001", "FacturaDate", "2026-10-07"));
        assertThat(payload.get("ContractDoc"))
                .isEqualTo(Map.of("ContractNo", "S-2026-09-000001", "ContractDate", "2026-10-01"));
        assertThat(payload).containsEntry("SellerTin", "305000001").containsEntry("BuyerTin", "301234567");
        assertThat(map(payload.get("Seller")))
                .containsEntry("Name", "HorecaOS MCHJ")
                .containsEntry("VatRegCode", "326000000001")
                .containsEntry("Account", "20208000100000000001")
                .containsEntry("BankId", "00014")
                .containsEntry("Address", "Toshkent, Amir Temur 1");
        assertThat(map(payload.get("Buyer")))
                .as("a buyer with no VAT code or bank on file sends none, not empty strings")
                .containsOnlyKeys("Name", "Address");

        Map<String, Object> productList = map(payload.get("ProductList"));
        assertThat(productList)
                .containsEntry("Tin", "305000001")
                .containsEntry("HasVat", true)
                .containsEntry("HasExcise", false);
        List<Map<String, Object>> products = products(productList);
        assertThat(products).hasSize(2);
        Map<String, Object> plan = products.get(0);
        assertThat(plan)
                .containsEntry("OrdNo", 1)
                .containsEntry("CatalogCode", "10305011001000000")
                .containsEntry("PackageCode", "1500002")
                .containsEntry("Count", 1L)
                .containsEntry("WithoutVat", false);
        assertThat((BigDecimal) plan.get("DeliverySum")).isEqualByComparingTo("500000.00");
        assertThat((BigDecimal) plan.get("VatSum")).isEqualByComparingTo("60000.00");
        assertThat((BigDecimal) plan.get("DeliverySumWithVat")).isEqualByComparingTo("560000.00");
        assertThat((BigDecimal) plan.get("Summa"))
                .as("the SDK sends the line's gross as Summa")
                .isEqualByComparingTo("560000.00");
        assertThat((BigDecimal) plan.get("VatRate")).isEqualByComparingTo("12");
        assertThat(products.get(1)).containsEntry("Count", 3L);
        assertThat((BigDecimal) products.get(1).get("DeliverySum")).isEqualByComparingTo("450000.00");
    }

    @Test
    @DisplayName("a seller with no VAT registration sends without VAT")
    void aSellerWithoutVatSendsWithoutIt() {
        var document = EInvoiceFixtures.document();
        var unregistered = new uz.horecaos.platform.commercial.api.EInvoiceDocument(
                document.clientReference(),
                document.documentNumber(),
                document.documentDate(),
                document.currency(),
                document.seller(),
                document.buyer(),
                document.contractNumber(),
                document.contractDate(),
                false,
                List.of(new uz.horecaos.platform.commercial.api.EInvoiceDocument.Line(
                        1, "x", "1", "c", "2", "p", 1, 100_00, 100_00, 0, 0, 100_00)));

        Map<String, Object> productList =
                map(DidoxEInvoicingOperator.payload(unregistered).get("ProductList"));

        assertThat(productList).containsEntry("HasVat", false);
        assertThat(products(productList).getFirst()).containsEntry("WithoutVat", true);
    }

    @Test
    @DisplayName("only the status codes the SDK makes unambiguous are mapped, and the rest read as unknown")
    void theStatusTable() {
        assertThat(DidoxStatus.of(0)).isEqualTo(EInvoiceOperatorState.DRAFT);
        assertThat(DidoxStatus.of(1)).isEqualTo(EInvoiceOperatorState.SENT);
        assertThat(DidoxStatus.of(6)).isEqualTo(EInvoiceOperatorState.SENT);
        assertThat(DidoxStatus.of(7)).isEqualTo(EInvoiceOperatorState.SENT);
        assertThat(DidoxStatus.of(2)).isEqualTo(EInvoiceOperatorState.SIGNED);
        assertThat(DidoxStatus.of(3)).isEqualTo(EInvoiceOperatorState.REFUSED);
        assertThat(DidoxStatus.of(4)).isEqualTo(EInvoiceOperatorState.CANCELLED);
        for (Integer other : new Integer[] {5, 8, 9, 42, -1, null}) {
            assertThat(DidoxStatus.of(other)).as("code %s", other).isEqualTo(EInvoiceOperatorState.UNKNOWN);
        }
    }

    // --------------------------------------------------------------------- send

    @Test
    @DisplayName("sending signs in, then creates the invoice draft with the session and the partner token")
    void sendingSignsInThenCreates() {
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("session-1"))
                .then(body(Map.of("_id", "11F092E3428D10C68F7B1E0008000075")));

        EInvoiceSendOutcome outcome = operator(transport).send(account, EInvoiceFixtures.document());

        assertThat(outcome)
                .isEqualTo(new EInvoiceSendOutcome.Accepted(
                        "11F092E3428D10C68F7B1E0008000075", EInvoiceOperatorState.DRAFT, "created"));
        assertThat(transport.operations()).containsExactly("login", "create");

        EInvoicingApiCall login = transport.calls().get(0);
        assertThat(login.method()).isEqualTo("POST");
        assertThat(login.path()).isEqualTo("/v1/auth/305000001/password/ru");
        assertThat(login.baseUrl()).isEqualTo("https://api-partners.didox.uz");
        EInvoicingApiCall.Request loginRequest = login.request().apply(SECRET);
        assertThat(loginRequest.headers()).containsEntry("Partner-Authorization", "partner-token-1");
        assertThat(loginRequest.json()).containsEntry("password", "the-password-1");

        EInvoicingApiCall create = transport.calls().get(1);
        assertThat(create.path()).isEqualTo("/v1/documents/002/create");
        assertThat(create.secretReference()).isEqualTo(account.secretReference());
        EInvoicingApiCall.Request createRequest = create.request().apply(SECRET);
        assertThat(createRequest.headers())
                .containsEntry("user-key", "session-1")
                .containsEntry("Partner-Authorization", "partner-token-1");
        assertThat(createRequest.json()).containsEntry("SellerTin", "305000001");
    }

    @Test
    @DisplayName("the session is kept between sends and signed in again once it has expired")
    void theSessionIsKeptThenRenewed() {
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("session-1"))
                .then(body(Map.of("_id", "d1")))
                .then(body(Map.of("_id", "d2")))
                .then(token("session-2"))
                .then(body(Map.of("_id", "d3")));
        DidoxEInvoicingOperator operator = operator(transport);

        operator.send(account, EInvoiceFixtures.document());
        clock.advance(Duration.ofMinutes(200));
        operator.send(account, EInvoiceFixtures.document());
        assertThat(transport.operations()).containsExactly("login", "create", "create");

        clock.advance(Duration.ofMinutes(101));
        operator.send(account, EInvoiceFixtures.document());
        assertThat(transport.operations()).containsExactly("login", "create", "create", "login", "create");
        assertThat(transport.calls().get(4).request().apply(SECRET).headers()).containsEntry("user-key", "session-2");
    }

    @Test
    @DisplayName("a refused session is signed in again and the create repeated once: Didox refuses before it acts")
    void aRefusedSessionIsRenewedOnce() {
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("stale"))
                .then(ProviderOutcome.rejected("PROVIDER_AUTHENTICATION", "401"))
                .then(token("fresh"))
                .then(body(Map.of("_id", "d1")));

        EInvoiceSendOutcome outcome = operator(transport).send(account, EInvoiceFixtures.document());

        assertThat(outcome).isInstanceOf(EInvoiceSendOutcome.Accepted.class);
        assertThat(transport.operations()).containsExactly("login", "create", "login", "create");
        assertThat(transport.calls().get(3).request().apply(SECRET).headers()).containsEntry("user-key", "fresh");
    }

    @Test
    @DisplayName("a session refused twice is not sent, and the create is not made a third time")
    void aSessionRefusedTwiceIsGivenUp() {
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("a"))
                .then(ProviderOutcome.rejected("PROVIDER_AUTHENTICATION", "401"))
                .then(token("b"))
                .then(ProviderOutcome.rejected("PROVIDER_AUTHENTICATION", "401"));

        EInvoiceSendOutcome outcome = operator(transport).send(account, EInvoiceFixtures.document());

        assertThat(outcome).isInstanceOf(EInvoiceSendOutcome.NotSent.class);
        assertThat(((EInvoiceSendOutcome.NotSent) outcome).code()).isEqualTo("OPERATOR_AUTHENTICATION");
        assertThat(transport.count("create")).isEqualTo(2);
    }

    @Test
    @DisplayName("a create whose answer was lost is uncertain and is never repeated")
    void aLostAnswerIsUncertainAndNotRepeated() {
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("session-1"))
                .then(ProviderOutcome.uncertain("READ_TIMEOUT", "No response after the request was sent"));

        EInvoiceSendOutcome outcome = operator(transport).send(account, EInvoiceFixtures.document());

        assertThat(outcome).isInstanceOf(EInvoiceSendOutcome.Uncertain.class);
        assertThat(((EInvoiceSendOutcome.Uncertain) outcome).code()).isEqualTo("READ_TIMEOUT");
        assertThat(transport.count("create")).isEqualTo(1);
    }

    @Test
    @DisplayName("what is provably not sent is not sent, and what the operator may have acted on is uncertain")
    void theClassificationOfAFailedCreate() {
        assertThat(sendWith(ProviderOutcome.retryable("CONNECTION_FAILED", "x", null)))
                .isInstanceOf(EInvoiceSendOutcome.NotSent.class);
        assertThat(sendWith(ProviderOutcome.retryable("CIRCUIT_OPEN", "x", null)))
                .isInstanceOf(EInvoiceSendOutcome.NotSent.class);
        assertThat(sendWith(ProviderOutcome.retryable("RATE_LIMITED", "x", null)))
                .isInstanceOf(EInvoiceSendOutcome.NotSent.class);
        assertThat(sendWith(ProviderOutcome.rejected("EGRESS_NOT_APPROVED", "x")))
                .isInstanceOf(EInvoiceSendOutcome.NotSent.class);
        assertThat(sendWith(ProviderOutcome.rejected("SECRET_NOT_FOUND", "x")))
                .isInstanceOf(EInvoiceSendOutcome.NotSent.class);

        assertThat(sendWith(ProviderOutcome.retryable("PROVIDER_UNAVAILABLE", "502", null)))
                .as("a 5xx may well have created the invoice")
                .isInstanceOf(EInvoiceSendOutcome.Uncertain.class);
        assertThat(sendWith(ProviderOutcome.uncertain("PROVIDER_CONFLICT", "409")))
                .as("a conflict usually means it already holds what we tried to create")
                .isInstanceOf(EInvoiceSendOutcome.Uncertain.class);

        assertThat(sendWith(ProviderOutcome.rejected("PROVIDER_REJECTED", "validation failed")))
                .isInstanceOf(EInvoiceSendOutcome.Refused.class);
    }

    private EInvoiceSendOutcome sendWith(ProviderOutcome createAnswer) {
        ScriptedTransport transport = new ScriptedTransport().then(token("s")).then(createAnswer);
        return new DidoxEInvoicingOperator(transport, JsonMapper.builder().build(), clock)
                .send(account, EInvoiceFixtures.document());
    }

    @Test
    @DisplayName("an accept that names no document is uncertain, never a made-up identifier")
    void anAcceptWithNoIdIsUncertain() {
        ScriptedTransport transport = new ScriptedTransport().then(token("s")).then(body(Map.of("ok", true)));

        EInvoiceSendOutcome outcome = operator(transport).send(account, EInvoiceFixtures.document());

        assertThat(outcome)
                .isEqualTo(new EInvoiceSendOutcome.Uncertain(
                        "ACCEPTED_WITHOUT_ID", "Didox accepted the document without naming it"));
    }

    @Test
    @DisplayName("the identifier and status are found where the SDK's types could put them")
    void theAnswerIsReadTolerantly() {
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("s"))
                .then(body(Map.of("data", Map.of("id", "nested-1", "status", 1))));

        EInvoiceSendOutcome outcome = operator(transport).send(account, EInvoiceFixtures.document());

        assertThat(outcome).isEqualTo(new EInvoiceSendOutcome.Accepted("nested-1", EInvoiceOperatorState.SENT, "1"));
    }

    @Test
    @DisplayName("a login Didox refuses is not sent, and no create is attempted")
    void aRefusedLoginSendsNothing() {
        ScriptedTransport transport =
                new ScriptedTransport().then(ProviderOutcome.rejected("PROVIDER_REJECTED", "422 wrong password"));

        EInvoiceSendOutcome outcome = operator(transport).send(account, EInvoiceFixtures.document());

        assertThat(outcome).isInstanceOf(EInvoiceSendOutcome.NotSent.class);
        assertThat(((EInvoiceSendOutcome.NotSent) outcome).code()).isEqualTo("OPERATOR_LOGIN_REFUSED");
        assertThat(transport.operations()).containsExactly("login");
    }

    @Test
    @DisplayName("a login answer with no token is not sent")
    void aLoginWithNoTokenIsNotSent() {
        ScriptedTransport transport = new ScriptedTransport().then(body(Map.of("related_companies", List.of())));

        EInvoiceSendOutcome outcome = operator(transport).send(account, EInvoiceFixtures.document());

        assertThat(((EInvoiceSendOutcome.NotSent) outcome).code()).isEqualTo("OPERATOR_LOGIN_UNREADABLE");
    }

    @Test
    @DisplayName("an account with no taxpayer number cannot sign in and says so")
    void anUnconfiguredAccountIsNotSent() {
        EInvoiceOperatorAccount bare = new EInvoiceOperatorAccount(
                EInvoiceFixtures.INSTALLATION,
                "DIDOX",
                "didox_production",
                "https://api-partners.didox.uz",
                account.secretReference(),
                Map.of());

        EInvoiceSendOutcome outcome = operator(new ScriptedTransport()).send(bare, EInvoiceFixtures.document());

        assertThat(((EInvoiceSendOutcome.NotSent) outcome).code()).isEqualTo("OPERATOR_NOT_CONFIGURED");
    }

    // -------------------------------------------------------------------- state

    @Test
    @DisplayName("a document is read by its identifier, and its status is the operator's own, kept as written")
    void stateByIdentifier() {
        ScriptedTransport transport =
                new ScriptedTransport().then(token("s")).then(body(Map.of("_id", "doc-1", "status", 2)));

        EInvoiceStateOutcome outcome = operator(transport).state(account, EInvoiceFixtures.reference("doc-1"));

        assertThat(outcome).isEqualTo(new EInvoiceStateOutcome.Known("doc-1", EInvoiceOperatorState.SIGNED, "2"));
        EInvoicingApiCall call = transport.calls().get(1);
        assertThat(call.operation()).isEqualTo("get");
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.path()).isEqualTo("/v1/documents/doc-1");
        assertThat(call.acceptedStatuses()).as("404 is an answer to a lookup").containsExactly(404);
    }

    @Test
    @DisplayName("a 404 to a lookup is the operator saying it holds no such document")
    void aNotFoundAnswer() {
        ScriptedTransport transport =
                new ScriptedTransport().then(token("s")).then(body(Map.of(ProviderHttpClient.STATUS_KEY, 404)));

        assertThat(operator(transport).state(account, EInvoiceFixtures.reference("doc-1")))
                .isInstanceOf(EInvoiceStateOutcome.NotFound.class);
    }

    @Test
    @DisplayName("a status code that is not mapped reads as unknown and keeps the operator's number")
    void anUnmappedStatusIsUnknown() {
        ScriptedTransport transport =
                new ScriptedTransport().then(token("s")).then(body(Map.of("_id", "doc-1", "status", 5)));

        assertThat(operator(transport).state(account, EInvoiceFixtures.reference("doc-1")))
                .isEqualTo(new EInvoiceStateOutcome.Known("doc-1", EInvoiceOperatorState.UNKNOWN, "5"));
    }

    @Test
    @DisplayName("a document with no readable status is not concluded about")
    void aMissingStatusIsUnavailable() {
        ScriptedTransport transport = new ScriptedTransport().then(token("s")).then(body(Map.of("_id", "doc-1")));

        assertThat(operator(transport).state(account, EInvoiceFixtures.reference("doc-1")))
                .isInstanceOf(EInvoiceStateOutcome.Unavailable.class);
    }

    @Test
    @DisplayName("a send whose answer was lost is found by its number among the outgoing invoices")
    void stateByNumber() {
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("s"))
                .then(body(Map.of(
                        "data",
                        List.of(
                                Map.of("_id", "other", "name", "S-2026-08-000009", "status", 3),
                                Map.of("_id", "doc-9", "name", "S-2026-09-000001", "status", 1)))));

        EInvoiceStateOutcome outcome = operator(transport).state(account, EInvoiceFixtures.reference(null));

        assertThat(outcome).isEqualTo(new EInvoiceStateOutcome.Known("doc-9", EInvoiceOperatorState.SENT, "1"));
        EInvoicingApiCall call = transport.calls().get(1);
        assertThat(call.operation()).isEqualTo("find");
        assertThat(call.path()).isEqualTo("/v2/documents?owner=1&doctype=002&name=S-2026-09-000001&page=1&limit=10");
    }

    @Test
    @DisplayName("only a list that is positively there and empty says the operator holds nothing under our number")
    void stateByNumberNotFound() {
        assertThat(operator(new ScriptedTransport().then(token("s")).then(body(Map.of("data", List.of()))))
                        .state(account, EInvoiceFixtures.reference(null)))
                .isInstanceOf(EInvoiceStateOutcome.NotFound.class);
    }

    @Test
    @DisplayName("an answer with no list this adapter reads is not 'the operator holds nothing'")
    void anUnrecognisedListIsUnreadableAndNotAbsent() {
        // Didox nests its rows under a key other than `data`, answers an empty body, or answers `data` as a
        // thing that is not a list: none of them is the operator saying it holds nothing.
        for (Map<String, Object> answer : List.<Map<String, Object>>of(
                Map.of(),
                Map.of("documents", List.of()),
                Map.of("data", Map.of("rows", List.of())),
                Map.of("total", 0))) {
            EInvoiceStateOutcome outcome = operator(
                            new ScriptedTransport().then(token("s")).then(body(answer)))
                    .state(account, EInvoiceFixtures.reference(null));

            assertThat(outcome).as(answer.toString()).isInstanceOf(EInvoiceStateOutcome.Unavailable.class);
            assertThat(((EInvoiceStateOutcome.Unavailable) outcome).code()).isEqualTo("STATE_UNREADABLE");
        }
    }

    @Test
    @DisplayName("a list of other documents proves nothing about ours: the filter may have been ignored")
    void aListHoldingOnlyOtherNumbersIsNotAbsence() {
        EInvoiceStateOutcome outcome = operator(new ScriptedTransport()
                        .then(token("s"))
                        .then(body(Map.of("data", List.of(Map.of("_id", "x", "name", "S-1", "status", 1))))))
                .state(account, EInvoiceFixtures.reference(null));

        assertThat(outcome).isInstanceOf(EInvoiceStateOutcome.Unavailable.class);
        assertThat(((EInvoiceStateOutcome.Unavailable) outcome).code()).isEqualTo("STATE_UNREADABLE");
    }

    @Test
    @DisplayName("a row whose number this adapter cannot read keeps the list from proving anything")
    void aRowWithoutAReadableNumberIsUnreadable() {
        EInvoiceStateOutcome outcome = operator(new ScriptedTransport()
                        .then(token("s"))
                        .then(body(Map.of(
                                "data",
                                List.of(
                                        Map.of("_id", "x", "title", "S-2026-09-000001", "status", 1),
                                        Map.of("_id", "y", "name", "S-1", "status", 1))))))
                .state(account, EInvoiceFixtures.reference(null));

        assertThat(outcome).isInstanceOf(EInvoiceStateOutcome.Unavailable.class);
    }

    @Test
    @DisplayName("every attempt for a statement shares its number: a document of an earlier attempt is never adopted")
    void aDocumentOfAnEarlierAttemptIsNotAdopted() {
        // Attempt A was refused by the buyer and the statement was sent again as B under the same number;
        // B's answer was lost. The list holds both, A first.
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("s"))
                .then(body(Map.of(
                        "data",
                        List.of(
                                Map.of("_id", "doc-A", "name", EInvoiceFixtures.NUMBER, "status", 3),
                                Map.of("_id", "doc-B", "name", EInvoiceFixtures.NUMBER, "status", 0)))));

        EInvoiceStateOutcome outcome = operator(transport).state(account, lookup(Set.of("doc-A")));

        assertThat(outcome).isEqualTo(new EInvoiceStateOutcome.Known("doc-B", EInvoiceOperatorState.DRAFT, "0"));
    }

    @Test
    @DisplayName("when only earlier attempts' documents carry the number, nothing was created for this one")
    void onlyEarlierAttemptsDocumentsMeansNothingNew() {
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("s"))
                .then(body(
                        Map.of("data", List.of(Map.of("_id", "doc-A", "name", EInvoiceFixtures.NUMBER, "status", 3)))));

        assertThat(operator(transport).state(account, lookup(Set.of("doc-A"))))
                .isInstanceOf(EInvoiceStateOutcome.NotFound.class);
    }

    @Test
    @DisplayName("two documents under our number that no earlier attempt accounts for are ambiguous, never a guess")
    void twoUnaccountedDocumentsAreAmbiguous() {
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("s"))
                .then(body(Map.of(
                        "data",
                        List.of(
                                Map.of("_id", "doc-A", "name", EInvoiceFixtures.NUMBER, "status", 3),
                                Map.of("_id", "doc-B", "name", EInvoiceFixtures.NUMBER, "status", 0)))));

        EInvoiceStateOutcome outcome = operator(transport).state(account, EInvoiceFixtures.reference(null));

        assertThat(outcome).isInstanceOf(EInvoiceStateOutcome.Unavailable.class);
        assertThat(((EInvoiceStateOutcome.Unavailable) outcome).code()).isEqualTo("STATE_AMBIGUOUS");
    }

    @Test
    @DisplayName("a matching row with no readable identifier cannot be told from an earlier attempt's")
    void aMatchWithoutAnIdentifierIsUnreadable() {
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("s"))
                .then(body(Map.of("data", List.of(Map.of("name", EInvoiceFixtures.NUMBER, "status", 0)))));

        EInvoiceStateOutcome outcome = operator(transport).state(account, lookup(Set.of("doc-A")));

        assertThat(outcome).isInstanceOf(EInvoiceStateOutcome.Unavailable.class);
    }

    @Test
    @DisplayName("a full page of matches may hide the one we want on the next page")
    void aFullPageThatHoldsNothingNewIsIncomplete() {
        List<Map<String, Object>> page = new java.util.ArrayList<>();
        Set<String> known = new java.util.HashSet<>();
        for (int i = 0; i < 10; i++) {
            page.add(Map.of("_id", "doc-" + i, "name", EInvoiceFixtures.NUMBER, "status", 3));
            known.add("doc-" + i);
        }
        ScriptedTransport transport = new ScriptedTransport().then(token("s")).then(body(Map.of("data", page)));

        EInvoiceStateOutcome outcome = operator(transport).state(account, lookup(known));

        assertThat(outcome).isInstanceOf(EInvoiceStateOutcome.Unavailable.class);
    }

    private static EInvoiceDocumentReference lookup(Set<String> otherAttemptDocumentIds) {
        return new EInvoiceDocumentReference(
                null,
                EInvoiceFixtures.CLIENT_REFERENCE,
                EInvoiceFixtures.NUMBER,
                java.time.LocalDate.parse("2026-10-07"),
                otherAttemptDocumentIds);
    }

    @Test
    @DisplayName("an operator that cannot be asked is unavailable, with its code, and nothing is concluded")
    void anUnreachableOperatorIsUnavailable() {
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("s"))
                .then(ProviderOutcome.retryable("PROVIDER_UNAVAILABLE", "502", null));

        EInvoiceStateOutcome outcome = operator(transport).state(account, EInvoiceFixtures.reference("doc-1"));

        assertThat(outcome).isEqualTo(new EInvoiceStateOutcome.Unavailable("PROVIDER_UNAVAILABLE", "502"));
    }

    // ---------------------------------------------------------------- secrecy

    @Test
    @DisplayName("neither a call nor its request renders the credential, the session or the body")
    void nothingSensitiveIsRendered() {
        ScriptedTransport transport =
                new ScriptedTransport().then(token("session-secret")).then(body(Map.of("_id", "d1")));
        operator(transport).send(account, EInvoiceFixtures.document());

        for (EInvoicingApiCall call : transport.calls()) {
            String rendered = call.toString() + call.request().apply(SECRET).toString();
            assertThat(rendered)
                    .doesNotContain("partner-token-1")
                    .doesNotContain("the-password-1")
                    .doesNotContain("session-secret")
                    .doesNotContain("305000001");
        }
        assertThat(account.toString()).doesNotContain(account.secretReference());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(@Nullable Object value) {
        return (Map<String, Object>) Objects.requireNonNull(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> products(Map<String, Object> productList) {
        return (List<Map<String, Object>>) Objects.requireNonNull(productList.get("Products"));
    }

    /** A clock a test moves by hand. */
    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
