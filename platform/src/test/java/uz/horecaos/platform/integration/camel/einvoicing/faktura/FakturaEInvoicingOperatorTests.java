package uz.horecaos.platform.integration.camel.einvoicing.faktura;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
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
 * The Faktura.uz adapter against a scripted operator (ADR 0096). The shapes are the ones the
 * operator's published Swagger models give ({@code document_container}, {@code
 * DocumentImportResult}, {@code GetDocumentStatusResponse}); the values are invented.
 */
class FakturaEInvoicingOperatorTests {

    private static final String SECRET = EInvoiceFixtures.FAKTURA_SECRET;

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-07T09:00:00Z"));
    private final EInvoiceOperatorAccount account = EInvoiceFixtures.account("FAKTURA_UZ", "https://api.faktura.uz");

    private FakturaEInvoicingOperator operator(ScriptedTransport transport) {
        return new FakturaEInvoicingOperator(transport, JsonMapper.builder().build(), clock);
    }

    private static ProviderOutcome token(String token, int expiresIn) {
        return ProviderOutcome.success(
                Map.of("access_token", token, "token_type", "bearer", "expires_in", expiresIn), null);
    }

    private static ProviderOutcome token(String token) {
        return token(token, 604_799);
    }

    private static ProviderOutcome body(Map<String, Object> body) {
        return ProviderOutcome.success(body, null);
    }

    private static ProviderOutcome imported(String uniqueId) {
        return body(Map.of(
                "SuccessCount",
                1,
                "ErrorCount",
                0,
                "SuccessItems",
                List.of(Map.of("UniqueId", uniqueId, "Id", EInvoiceFixtures.CLIENT_REFERENCE, "ResultCode", 0)),
                "ErrorItems",
                List.of()));
    }

    // ------------------------------------------------------------------ mapping

    @Test
    @DisplayName("the invoice is laid out as the document_container's invoice model: head, document, our id")
    void theInvoiceFollowsTheContainerModel() {
        Map<String, Object> invoice = FakturaEInvoicingOperator.invoice(EInvoiceFixtures.document());

        assertThat(invoice).containsEntry("id", EInvoiceFixtures.CLIENT_REFERENCE);
        Map<String, Object> head = map(invoice.get("head"));
        Map<String, Object> sender = map(map(head.get("sender")).get("sender_info"));
        assertThat(sender)
                .containsEntry("INN", "305000001")
                .containsEntry("company_name", "HorecaOS MCHJ")
                .containsEntry("company_vat_code", "326000000001");
        assertThat(map(sender.get("address"))).containsEntry("street", "Toshkent, Amir Temur 1");
        assertThat(map(sender.get("bank_details")))
                .containsEntry("account_number", "20208000100000000001")
                .containsEntry("bank_code", "00014")
                .containsEntry("bank_name", "Test Bank");
        Map<String, Object> receiver = map(map(head.get("receiver")).get("receiver_info"));
        assertThat(receiver).containsEntry("INN", "301234567").doesNotContainKey("bank_details");
        assertThat((String) head.get("file_name")).isEqualTo("Invoice_305000001_301234567_20261007");

        Map<String, Object> document = map(invoice.get("document"));
        assertThat(document)
                .containsEntry("document_number", "S-2026-09-000001")
                .containsEntry("document_date", "2026-10-07")
                .containsEntry("contract_number", "S-2026-09-000001")
                .containsEntry("contract_date", "2026-10-01")
                .containsEntry("customer_system_id", EInvoiceFixtures.CLIENT_REFERENCE);
        List<Map<String, Object>> items = items(document);
        assertThat(items).hasSize(2);
        assertThat(items.get(0))
                .containsEntry("item_number", "1")
                .containsEntry("volume", "1")
                .containsEntry("unit_price", "500000.00")
                .containsEntry("subtotal", "500000.00")
                .containsEntry("subtotal_with_vat", "560000.00")
                .containsEntry("measurement_unit", "услуга")
                .containsEntry("measurement_unit_code", "1500002")
                .containsEntry("origin", "3");
        assertThat(map(items.get(0).get("vat"))).containsEntry("vat_rate", "12").containsEntry("vat_value", "60000.00");
        assertThat(map(items.get(0).get("excise"))).containsKeys("excise_rate", "excise_value");
        assertThat(map(items.get(0).get("catalog")))
                .containsEntry("code", "10305011001000000")
                .containsKey("name");
        assertThat(items.get(1)).containsEntry("volume", "3").containsEntry("unit_price", "150000.00");

        Map<String, Object> summary = map(document.get("column_summary_values"));
        assertThat(summary)
                .containsEntry("column_subtotal", "950000.00")
                .containsEntry("column_vat_value", "114000.00")
                .containsEntry("column_subtotal_with_taxes_total", "1064000.00");
        Map<String, Object> words = map(document.get("column_summary_values_in_words"));
        assertThat(words)
                .containsEntry("column_subtotal_in_words", "Девятьсот пятьдесят тысяч сум 00 тийин")
                .containsEntry(
                        "column_subtotal_with_taxes_in_words", "Один миллион шестьдесят четыре тысячи сум 00 тийин");
    }

    @Test
    @DisplayName("the document's state is read from the booleans Faktura documents, and the status text overrides them")
    void theStateReading() {
        assertThat(FakturaEInvoicingOperator.stateOf("", false, false)).isEqualTo(EInvoiceOperatorState.DRAFT);
        assertThat(FakturaEInvoicingOperator.stateOf("Отправлен", true, false)).isEqualTo(EInvoiceOperatorState.SENT);
        assertThat(FakturaEInvoicingOperator.stateOf("Опубликован", true, true))
                .isEqualTo(EInvoiceOperatorState.SIGNED);
        assertThat(FakturaEInvoicingOperator.stateOf("Отклонен контрагентом", true, false))
                .isEqualTo(EInvoiceOperatorState.REFUSED);
        assertThat(FakturaEInvoicingOperator.stateOf("Rejected", true, false)).isEqualTo(EInvoiceOperatorState.REFUSED);
        assertThat(FakturaEInvoicingOperator.stateOf("Аннулирован", true, true))
                .as("a cancelled document was signed once, and is still cancelled")
                .isEqualTo(EInvoiceOperatorState.CANCELLED);
        assertThat(FakturaEInvoicingOperator.stateOf(null, true, false)).isEqualTo(EInvoiceOperatorState.SENT);
    }

    @Test
    @DisplayName("the token is asked for at the account host, not the API host")
    void theAccountHost() {
        assertThat(FakturaEInvoicingOperator.accountHost("https://api.faktura.uz"))
                .isEqualTo("https://account.faktura.uz");
        assertThat(FakturaEInvoicingOperator.accountHost("http://127.0.0.1:8123"))
                .isEqualTo("http://127.0.0.1:8123");
    }

    // --------------------------------------------------------------------- send

    @Test
    @DisplayName("sending asks for a token with a form, then imports the invoice under our own identifier")
    void sendingSignsInThenImports() {
        ScriptedTransport transport =
                new ScriptedTransport().then(token("bearer-1")).then(imported("2ac56badbbb144738eaf4abb800507c9"));

        EInvoiceSendOutcome outcome = operator(transport).send(account, EInvoiceFixtures.document());

        assertThat(outcome)
                .isEqualTo(new EInvoiceSendOutcome.Accepted(
                        "2ac56badbbb144738eaf4abb800507c9", EInvoiceOperatorState.DRAFT, "imported"));
        EInvoicingApiCall tokenCall = transport.calls().get(0);
        assertThat(tokenCall.baseUrl()).isEqualTo("https://account.faktura.uz");
        assertThat(tokenCall.path()).isEqualTo("/token");
        EInvoicingApiCall.Request tokenRequest = tokenCall.request().apply(SECRET);
        assertThat(tokenRequest.form())
                .containsEntry("grant_type", "password")
                .containsEntry("username", "horeca")
                .containsEntry("password", "the-password-2")
                .containsEntry("client_id", "cid-1")
                .containsEntry("client_secret", "csecret-1");
        assertThat(tokenRequest.json()).isNull();

        EInvoicingApiCall create = transport.calls().get(1);
        assertThat(create.baseUrl()).isEqualTo("https://api.faktura.uz");
        assertThat(create.path()).isEqualTo("/Api/Document/ImportDocumentRegister?companyInn=305000001");
        EInvoicingApiCall.Request createRequest = create.request().apply(SECRET);
        assertThat(createRequest.headers()).containsEntry("Authorization", "Bearer bearer-1");
        assertThat(map(createRequest.json())).containsKey("invoices");
        assertThat((List<?>) map(createRequest.json()).get("invoices")).hasSize(1);
    }

    @Test
    @DisplayName("a token lives a day short of its life and is then asked for again")
    void theTokenIsKeptThenRenewed() {
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("t1", 604_799))
                .then(imported("u1"))
                .then(imported("u2"))
                .then(token("t2"))
                .then(imported("u3"));
        FakturaEInvoicingOperator operator = operator(transport);

        operator.send(account, EInvoiceFixtures.document());
        clock.advance(Duration.ofDays(5));
        operator.send(account, EInvoiceFixtures.document());
        assertThat(transport.operations()).containsExactly("token", "create", "create");

        clock.advance(Duration.ofDays(1));
        operator.send(account, EInvoiceFixtures.document());
        assertThat(transport.operations()).containsExactly("token", "create", "create", "token", "create");
    }

    @Test
    @DisplayName("an import the operator reports as an error is refused with its code and message")
    void anErrorItemIsRefused() {
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("t"))
                .then(body(Map.of(
                        "SuccessCount",
                        0,
                        "ErrorCount",
                        1,
                        "SuccessItems",
                        List.of(),
                        "ErrorItems",
                        List.of(Map.of("ResultCode", 1002, "Message", "Неправильный ИНН 301234567890123")))));

        EInvoiceSendOutcome outcome = operator(transport).send(account, EInvoiceFixtures.document());

        assertThat(outcome).isInstanceOf(EInvoiceSendOutcome.Refused.class);
        EInvoiceSendOutcome.Refused refused = (EInvoiceSendOutcome.Refused) outcome;
        assertThat(refused.code()).isEqualTo("OPERATOR_REJECTED_DOCUMENT");
        assertThat(refused.detail()).contains("1002").contains("Неправильный ИНН");
        assertThat(refused.detail()).as("long digit runs are masked").doesNotContain("301234567890123");
    }

    @Test
    @DisplayName("a success count with no identifier is not believed")
    void aSuccessWithoutAnIdentifierIsUncertain() {
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("t"))
                .then(body(Map.of("SuccessCount", 1, "ErrorCount", 0, "SuccessItems", List.of(Map.of("Name", "x")))));

        EInvoiceSendOutcome outcome = operator(transport).send(account, EInvoiceFixtures.document());

        assertThat(outcome).isInstanceOf(EInvoiceSendOutcome.Uncertain.class);
    }

    @Test
    @DisplayName("a lost answer is uncertain, and a refused bearer is renewed once and only then")
    void aLostAnswerAndARefusedBearer() {
        ScriptedTransport lost = new ScriptedTransport()
                .then(token("t"))
                .then(ProviderOutcome.uncertain("CONNECTION_RESET", "Connection lost after sending"));
        assertThat(operator(lost).send(account, EInvoiceFixtures.document()))
                .isInstanceOf(EInvoiceSendOutcome.Uncertain.class);
        assertThat(lost.count("create")).isEqualTo(1);

        ScriptedTransport renewed = new ScriptedTransport()
                .then(token("stale"))
                .then(ProviderOutcome.rejected("PROVIDER_AUTHENTICATION", "401"))
                .then(token("fresh"))
                .then(imported("u1"));
        assertThat(operator(renewed).send(account, EInvoiceFixtures.document()))
                .isInstanceOf(EInvoiceSendOutcome.Accepted.class);
        assertThat(renewed.operations()).containsExactly("token", "create", "token", "create");
    }

    @Test
    @DisplayName("a refused login is not sent and no import is attempted")
    void aRefusedLogin() {
        ScriptedTransport transport =
                new ScriptedTransport().then(ProviderOutcome.rejected("PROVIDER_REJECTED", "invalid_grant"));

        EInvoiceSendOutcome outcome = operator(transport).send(account, EInvoiceFixtures.document());

        assertThat(((EInvoiceSendOutcome.NotSent) outcome).code()).isEqualTo("OPERATOR_LOGIN_REFUSED");
        assertThat(transport.operations()).containsExactly("token");
    }

    // -------------------------------------------------------------------- state

    private static ProviderOutcome statusAnswer(int status, String description) {
        return body(Map.of(
                "Success", true,
                "Errors", List.of(),
                "Data",
                        Map.of(
                                "DocumentStatuses",
                                List.of(Map.of(
                                        "UniqueId", "u1", "Status", status, "StatusDescription", description)))));
    }

    @Test
    @DisplayName("a document's state is its status text and its three booleans, both kept as the operator wrote them")
    void stateByIdentifier() {
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("t"))
                .then(statusAnswer(12, "Отправлен"))
                .then(body(Map.of("isSent", true, "isOwnerSigned", true, "isContractorSigned", false)));

        EInvoiceStateOutcome outcome = operator(transport).state(account, EInvoiceFixtures.reference("u1"));

        assertThat(outcome).isEqualTo(new EInvoiceStateOutcome.Known("u1", EInvoiceOperatorState.SENT, "12:Отправлен"));
        assertThat(transport.operations()).containsExactly("token", "status", "get");
        EInvoicingApiCall status = transport.calls().get(1);
        assertThat(status.path()).isEqualTo("/Api/GetDocumentStatus?companyInn=305000001");
        assertThat(status.request().apply(SECRET).json()).containsEntry("DocumentUniqueIds", List.of("u1"));
        assertThat(transport.calls().get(2).path()).isEqualTo("/Api/Document/GetDetails/u1?companyInn=305000001");
    }

    @Test
    @DisplayName("a buyer who signed makes it signed, and one who refused makes it refused")
    void signedAndRefused() {
        ScriptedTransport signed = new ScriptedTransport()
                .then(token("t"))
                .then(statusAnswer(24, "Опубликован"))
                .then(body(Map.of("isSent", true, "isOwnerSigned", true, "isContractorSigned", true)));
        assertThat(operator(signed).state(account, EInvoiceFixtures.reference("u1")))
                .isEqualTo(new EInvoiceStateOutcome.Known("u1", EInvoiceOperatorState.SIGNED, "24:Опубликован"));

        ScriptedTransport refused = new ScriptedTransport()
                .then(token("t"))
                .then(statusAnswer(13, "Отклонен"))
                .then(body(Map.of("isSent", true, "isOwnerSigned", true, "isContractorSigned", false)));
        assertThat(operator(refused).state(account, EInvoiceFixtures.reference("u1")))
                .isEqualTo(new EInvoiceStateOutcome.Known("u1", EInvoiceOperatorState.REFUSED, "13:Отклонен"));
    }

    @Test
    @DisplayName("a send whose answer was lost is found by the client reference, and a 404 is not found")
    void stateByClientReference() {
        ScriptedTransport found = new ScriptedTransport()
                .then(token("t"))
                .then(body(Map.of("UniqueId", "u1", "Id", EInvoiceFixtures.CLIENT_REFERENCE)))
                .then(statusAnswer(10, "Создан"))
                .then(body(Map.of("isSent", false, "isOwnerSigned", false, "isContractorSigned", false)));
        assertThat(operator(found).state(account, EInvoiceFixtures.reference(null)))
                .isEqualTo(new EInvoiceStateOutcome.Known("u1", EInvoiceOperatorState.DRAFT, "10:Создан"));
        EInvoicingApiCall find = found.calls().get(1);
        assertThat(find.path())
                .isEqualTo("/Api/Document/GetDocumentByClientDocumentUid?clientId=" + EInvoiceFixtures.CLIENT_REFERENCE
                        + "&companyInn=305000001");
        assertThat(find.acceptedStatuses()).containsExactly(404);

        ScriptedTransport missing =
                new ScriptedTransport().then(token("t")).then(body(Map.of(ProviderHttpClient.STATUS_KEY, 404)));
        assertThat(operator(missing).state(account, EInvoiceFixtures.reference(null)))
                .isInstanceOf(EInvoiceStateOutcome.NotFound.class);
    }

    @Test
    @DisplayName("incorrect document uid (1007) is the operator saying it holds no such document")
    void anIncorrectUidIsNotFound() {
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("t"))
                .then(body(Map.of(
                        "Success", false,
                        "Errors", List.of(Map.of("Code", 1007, "Message", "IncorrectDocumentUid")),
                        "Data", Map.of("DocumentStatuses", List.of()))));

        assertThat(operator(transport).state(account, EInvoiceFixtures.reference("nope")))
                .isInstanceOf(EInvoiceStateOutcome.NotFound.class);
    }

    @Test
    @DisplayName("an operator that cannot be asked is unavailable and nothing is concluded")
    void unavailable() {
        ScriptedTransport transport = new ScriptedTransport()
                .then(token("t"))
                .then(ProviderOutcome.rejected("PROVIDER_REJECTED", "401 not a participant"));

        assertThat(operator(transport).state(account, EInvoiceFixtures.reference("u1")))
                .isEqualTo(new EInvoiceStateOutcome.Unavailable("PROVIDER_REJECTED", "401 not a participant"));
    }

    @Test
    @DisplayName("nothing sensitive is rendered by a call, a request or an account")
    void nothingSensitiveIsRendered() {
        ScriptedTransport transport =
                new ScriptedTransport().then(token("bearer-secret")).then(imported("u1"));
        operator(transport).send(account, EInvoiceFixtures.document());

        for (EInvoicingApiCall call : transport.calls()) {
            String rendered = call.toString() + call.request().apply(SECRET).toString();
            assertThat(rendered)
                    .doesNotContain("the-password-2")
                    .doesNotContain("csecret-1")
                    .doesNotContain("bearer-secret");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(@Nullable Object value) {
        return (Map<String, Object>) Objects.requireNonNull(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> document) {
        return (List<Map<String, Object>>) Objects.requireNonNull(document.get("items"));
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
