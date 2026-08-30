package uz.qoida.platform.integration.camel.sms;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import uz.qoida.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.qoida.platform.integration.api.provider.ProviderOutcome;
import uz.qoida.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.qoida.platform.integration.camel.common.ProviderHttpClient;
import uz.qoida.platform.integration.provider.SmsAccountLookup.SmsAccount;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What this adapter puts on the wire, and what it concludes from what comes back
 * (ADR 0007), against the codes in {@code docs/providers/sms-gateway-vas.md}.
 */
class VasSmsGatewayAdapterTests {

    private static final SmsAccount ACCOUNT = new SmsAccount("qoida", "16888");
    private static final String CODE = "482913";
    private static final Instant ISSUED = Instant.parse("2026-08-25T09:15:00Z");

    private final ProviderHttpClient http = new ProviderHttpClient(
            JsonMapper.builder().build(), new ProviderExceptionClassifier());
    private final VasSmsGatewayAdapter adapter = new VasSmsGatewayAdapter(http);

    @Test
    @DisplayName("a send carries login, key, sender, phone and text — and no weight")
    void sendCarriesTheDocumentedFieldsAndNoWeight() throws Exception {
        try (RecordingSmsGateway gateway = RecordingSmsGateway.start()) {
            gateway.reply("/send", """
                    {"status":{"code":0,"description":"success"},"id":"5981980","parts":1}""");

            ProviderOutcome outcome = adapter.send(sendOperation(), ACCOUNT, call(gateway));

            RecordingSmsGateway.Call sent = gateway.callTo("/send");
            assertThat(sent.body())
                    .containsEntry("login", "qoida")
                    .containsEntry("key", "the-key")
                    .containsEntry("sender", "16888")
                    .containsEntry("phone", "998901112233")
                    .containsEntry("text", "Qoida code " + CODE);

            // The document does not say which end of [0,10] is urgent, and every
            // example that sets it uses a different value from the default. A code
            // sent at the wrong priority is a code that arrives after its expiry,
            // so the field is not sent at all until somebody has asked.
            assertThat(sent.body()).doesNotContainKey("weight");
            // Nothing pretends this provider deduplicates. It documents no key.
            assertThat(sent.body()).doesNotContainKey("seq");

            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
            assertThat(outcome.externalReference()).isEqualTo("5981980");
        }
    }

    @Test
    @DisplayName("code 20 is a product fact with its own reason, not a generic failure")
    void blacklistedReceiverHasItsOwnOutcome() throws Exception {
        ProviderOutcome outcome = sendReturning("""
                {"status":{"code":20,"description":"receiver in blacklist"}}""");

        // A customer on the operator's blacklist can never receive a code and so
        // can never sign in by phone. That has to reach a person as itself.
        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(outcome.errorCode()).isEqualTo("SMS_RECEIVER_BLACKLISTED");
    }

    @Test
    @DisplayName("code 13 becomes the platform's authentication code so the secret is refreshed once")
    void wrongKeyIsAnAuthenticationFailure() throws Exception {
        ProviderOutcome outcome = sendReturning("""
                {"status":{"code":13,"description":"wrong key"}}""");

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(outcome.errorCode()).isEqualTo("PROVIDER_AUTHENTICATION");
    }

    @Test
    @DisplayName("code 1 is an alarm and carries no backoff")
    void spamIsNotBackedOff() throws Exception {
        ProviderOutcome outcome = sendReturning("""
                {"status":{"code":1,"description":"spam"}}""");

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(outcome.errorCode()).isEqualTo("SMS_SPAM_LIMIT");
        // A retry delay here would turn the one signal that our own limiter is
        // broken into patient background traffic.
        assertThat(outcome.retryDelay()).isEmpty();
    }

    @Test
    @DisplayName("success without a message id contradicts itself and stays uncertain")
    void acceptedWithoutAnIdIsUncertain() throws Exception {
        ProviderOutcome outcome = sendReturning("""
                {"status":{"code":0,"description":"success"},"id":0,"parts":0}""");

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
        assertThat(outcome.errorCode()).isEqualTo("SMS_ACCEPTED_WITHOUT_ID");
    }

    @Test
    @DisplayName("a code the document does not list is uncertain, not refused")
    void undocumentedCodeIsUncertain() throws Exception {
        ProviderOutcome outcome = sendReturning("""
                {"status":{"code":99,"description":"???"}}""");

        // Refusing would say "nothing was sent", which nobody knows.
        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
        assertThat(outcome.errorCode()).isEqualTo("SMS_RESPONSE_UNREADABLE");
    }

    @Test
    @DisplayName("a lost response is uncertain, and nothing re-sends")
    void aLostResponseIsUncertain() throws Exception {
        try (RecordingSmsGateway gateway = RecordingSmsGateway.start()) {
            gateway.stallAfterReceiving("/send", 400);

            ProviderOutcome outcome = adapter.send(sendOperation(), ACCOUNT,
                    new ProviderCall(gateway.baseUrl(), "the-key", null, Duration.ofMillis(150)));

            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
            assertThat(gateway.callsTo("/send")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the search finds our message by the code it carries")
    void searchCorrelatesOnTheCodeItself() throws Exception {
        try (RecordingSmsGateway gateway = RecordingSmsGateway.start()) {
            gateway.reply("/search", """
                    {"status":{"code":0,"description":"success"},
                     "data":[{"id":700,"msg":"another message","send_dt":1,"status":4},
                             {"id":723923,"msg":"Qoida code 482913","send_dt":2,"status":3}]}""");

            ProviderOutcome outcome = adapter.resolve(resolveOperation(), ACCOUNT, call(gateway));

            // There is no key to ask by. The text is the only correlator the API
            // offers, and it is compared in memory and never written down.
            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
            assertThat(outcome.externalReference()).isEqualTo("723923");
            // Status 3 is "handed to the operator", which is not a confirmation and
            // is not a failure. CDMA subscribers never report anything better.
            assertThat(outcome.normalized()).containsEntry("providerDeliveryState", "SENT");

            RecordingSmsGateway.Call searched = gateway.callTo("/search");
            assertThat(searched.body())
                    .containsEntry("login", "qoida")
                    .containsEntry("phone", "998901112233")
                    .containsEntry("date", (int) ISSUED.getEpochSecond());
            // Never the code, and never the message text.
            assertThat(searched.body()).doesNotContainKey("text");
        }
    }

    @Test
    @DisplayName("a search that finds nothing says unconfirmed, never 'not sent'")
    void searchMissIsNotProofOfAbsence() throws Exception {
        try (RecordingSmsGateway gateway = RecordingSmsGateway.start()) {
            gateway.reply("/search", """
                    {"status":{"code":0,"description":"success"},"data":[]}""");

            ProviderOutcome outcome = adapter.resolve(resolveOperation(), ACCOUNT, call(gateway));

            // The day's timezone is undocumented, so a miss is not evidence. The
            // one thing this must never do is license a second send.
            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
            assertThat(outcome.errorCode()).isEqualTo("SMS_SEND_UNCONFIRMED");
            assertThat(gateway.callsTo("/send")).isZero();
        }
    }

    @Test
    @DisplayName("a searched message in the blacklist state is the same product fact")
    void searchReportsBlacklistAsBlacklist() throws Exception {
        try (RecordingSmsGateway gateway = RecordingSmsGateway.start()) {
            gateway.reply("/search", """
                    {"status":{"code":0,"description":"success"},
                     "data":[{"id":9,"msg":"Qoida code 482913","send_dt":2,"status":7}]}""");

            ProviderOutcome outcome = adapter.resolve(resolveOperation(), ACCOUNT, call(gateway));

            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
            assertThat(outcome.errorCode()).isEqualTo("SMS_RECEIVER_BLACKLISTED");
        }
    }

    @Test
    @DisplayName("a message the gateway reports as Unknown is not treated as a failure")
    void terminalUnknownIsNotAFailure() throws Exception {
        try (RecordingSmsGateway gateway = RecordingSmsGateway.start()) {
            gateway.reply("/search", """
                    {"status":{"code":0,"description":"success"},
                     "data":[{"id":9,"msg":"Qoida code 482913","send_dt":2,"status":6}]}""");

            ProviderOutcome outcome = adapter.resolve(resolveOperation(), ACCOUNT, call(gateway));

            // Nothing may conclude "not delivered" from an absent or unresolved
            // receipt. CDMA subscribers produce none at all.
            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
        }
    }

    @Test
    @DisplayName("a refused search stays uncertain about the send")
    void aRefusedSearchDoesNotBecomeTheSendsAnswer() throws Exception {
        try (RecordingSmsGateway gateway = RecordingSmsGateway.start()) {
            gateway.reply("/search", """
                    {"status":{"code":13,"description":"wrong key"}}""");

            ProviderOutcome outcome = adapter.resolve(resolveOperation(), ACCOUNT, call(gateway));

            // A wrong key on the query is not a blacklisted recipient on the
            // message. What happened to the send is still unknown.
            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
            assertThat(outcome.errorCode()).isEqualTo("SMS_SEARCH_REFUSED");
        }
    }

    @Test
    @DisplayName("no outcome carries the destination, the code, or the provider's own words")
    void anOutcomeNeverCarriesPersonalData() throws Exception {
        try (RecordingSmsGateway gateway = RecordingSmsGateway.start()) {
            // A provider echoing the request back inside its error is exactly the
            // failure ADR 0029 names; this one echoes both fields.
            gateway.reply("/send", 400,
                    """
                    {"error":"rejected 998901112233 text 'Qoida code 482913'"}""");

            ProviderOutcome outcome = adapter.send(sendOperation(), ACCOUNT, call(gateway));

            assertThat(String.valueOf(outcome.errorCode()) + outcome.detail()
                    + outcome.normalized())
                    .doesNotContain("998901112233")
                    .doesNotContain(CODE);
        }
    }

    @Test
    @DisplayName("neither the operation nor a request body prints its own contents")
    void nothingCarryingTheCodePrintsIt() {
        SmsVerificationOperation operation = sendOperation();
        SmsGateBody.Send body = new SmsGateBody.Send("qoida", "the-key", "16888",
                "998901112233", "Qoida code " + CODE);
        SmsGateBody.Search search = new SmsGateBody.Search("qoida", "the-key", "998901112233", 1);

        // Camel prints exchange bodies into route logs and into the messages of the
        // exceptions it wraps, so a generated toString here is a credential and a
        // live one-time code in a log file for its whole retention period.
        assertThat(operation.toString()).doesNotContain(CODE).doesNotContain("998901112233");
        assertThat(body.toString()).doesNotContain(CODE).doesNotContain("the-key")
                .doesNotContain("998901112233");
        assertThat(search.toString()).doesNotContain("the-key").doesNotContain("998901112233");
    }

    private ProviderOutcome sendReturning(String json) throws Exception {
        try (RecordingSmsGateway gateway = RecordingSmsGateway.start()) {
            gateway.reply("/send", json);
            ProviderOutcome outcome = adapter.send(sendOperation(), ACCOUNT, call(gateway));
            assertThat(gateway.callsTo("/send"))
                    .as("an adapter must never repeat a send of its own accord")
                    .isEqualTo(1);
            return outcome;
        }
    }

    private static ProviderCall call(RecordingSmsGateway gateway) {
        return new ProviderCall(gateway.baseUrl(), "the-key", null, Duration.ofSeconds(5));
    }

    private static SmsVerificationOperation sendOperation() {
        return new SmsVerificationOperation(SmsVerificationOperation.Kind.SEND,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "998901112233", CODE, "Qoida code " + CODE, ISSUED);
    }

    private static SmsVerificationOperation resolveOperation() {
        return sendOperation().resolving();
    }
}
