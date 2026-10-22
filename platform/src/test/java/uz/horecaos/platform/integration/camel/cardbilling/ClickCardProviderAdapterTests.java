package uz.horecaos.platform.integration.camel.cardbilling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.commercial.api.CardAccount;
import uz.horecaos.platform.commercial.api.CardChargeUnansweredException;
import uz.horecaos.platform.commercial.api.CardCharger;
import uz.horecaos.platform.commercial.api.CardEnrolment;
import uz.horecaos.platform.commercial.api.CardProviderAdapter;
import uz.horecaos.platform.commercial.api.CardProviderContract;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.cardbilling.click.ClickCardProviderAdapter;
import uz.horecaos.platform.integration.camel.cardbilling.click.fake.FakeClickCardHttpProvider;
import uz.horecaos.platform.integration.camel.cardbilling.click.fake.FakeClickCardHttpProvider.NotHeldAnswer;
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;

/**
 * The real Click adapter through the whole real stack on a real socket (adapter, Camel route, circuit
 * breaker, gateway, {@code ProviderHttpClient}) against a controlled fake of Click's HTTP boundary (ADR 0007,
 * ADR 0095).
 *
 * <p>It runs {@link CardProviderContract}, the suite {@code FakeCardProviderTests} runs against the in-process
 * double, so the double's behaviour is evidence about the adapter and not the other way round. What follows is
 * what only this provider has to prove: Click documents no idempotency key on {@code card_token/payment}, so the
 * adapter keeps the charge contract itself, and every undocumented answer is read as "cannot say" rather than
 * "nothing happened".
 */
class ClickCardProviderAdapterTests extends CardProviderContract {

    private static final String REFERENCE = "horecaos:production:provider_payment:platform:card-merchant";
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-3300-7000-8000-0000000000c1");
    private static final String CREDENTIAL = "{\"merchantUserId\":\"" + FakeClickCardHttpProvider.MERCHANT_USER_ID
            + "\",\"secretKey\":\"" + FakeClickCardHttpProvider.SECRET_KEY + "\"}";

    private static final String BROWSER_EXPIRY = "1230";

    private MutableClock clock;
    private FakeClickCardHttpProvider provider;
    private CamelContext camel;
    private InjectingTransport transport;
    private ClickCardProviderAdapter adapter;
    private Set<String> approvedHosts;
    private Map<String, String> secrets;
    private final HttpClient browser = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock(Instant.parse("2026-10-08T09:00:00Z"));
        provider = new FakeClickCardHttpProvider(clock);
        provider.start();
        approvedHosts = Set.of("localhost");
        secrets = new HashMap<>(Map.of(REFERENCE, CREDENTIAL));
        CardBillingGateway gateway = new CardBillingGateway(
                environment -> Optional.of(approvedHosts),
                new SecretResolver() {
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
                        return resolve(reference);
                    }
                },
                new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier()),
                Duration.ofMillis(900));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CardBillingProcessor processor =
                new CardBillingProcessor(gateway, new CardBillingCircuitBreakers(meters), meters);
        camel = new DefaultCamelContext();
        camel.addRoutes(new CardBillingRouteBuilder(processor));
        camel.start();
        transport = new InjectingTransport(new CamelCardBillingApiTransport(camel.createProducerTemplate()));
        adapter = new ClickCardProviderAdapter(
                transport, resolver(), JsonMapper.builder().build(), clock);
    }

    @AfterEach
    void tearDown() {
        camel.stop();
        provider.stop();
    }

    private SecretResolver resolver() {
        return new SecretResolver() {
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
                return resolve(reference);
            }
        };
    }

    private CardAccount accountWith(@Nullable String serviceId, Map<String, Object> configuration) {
        return accountWith(INSTALLATION, serviceId, configuration);
    }

    private CardAccount accountWith(UUID installation, @Nullable String serviceId, Map<String, Object> configuration) {
        return new CardAccount(
                installation,
                ClickCardProviderAdapter.PROVIDER_TYPE,
                "env",
                provider.baseUrl(),
                REFERENCE,
                serviceId,
                configuration);
    }

    /** A different installation per sick provider, because the breaker is per installation and must not carry over. */
    private CardAccount sickAccount(String serviceId) {
        return accountWith(UUID.randomUUID(), serviceId, Map.of());
    }

    // ------------------------------------------------------------ the contract's hooks

    @Override
    protected CardProviderAdapter adapter() {
        return adapter;
    }

    @Override
    protected CardAccount account() {
        return accountWith(FakeClickCardHttpProvider.SERVICE_ID, Map.of());
    }

    @Override
    protected Instant now() {
        return clock.now;
    }

    @Override
    protected void advanceClock(Duration by) {
        clock.now = clock.now.plus(by);
    }

    @Override
    protected String bankCode() {
        return FakeClickCardHttpProvider.SMS_CODE;
    }

    @Override
    protected CardEnrolment.ConfirmOutcome confirm(
            CardEnrolment.BeginOutcome.Begun begun, UUID tenant, Card card, String bankCode) {
        String number =
                switch (card) {
                    case APPROVING -> FakeClickCardHttpProvider.APPROVING_CARD;
                    case DECLINING -> FakeClickCardHttpProvider.DECLINING_CARD;
                    case LOSING_THE_ANSWER -> FakeClickCardHttpProvider.LOSING_CARD;
                };
        return adapter.confirm(
                account(), tenant, begun.sessionReference(), tokeniseInTheBrowser(number), bankCode, BROWSER_EXPIRY);
    }

    @Override
    protected int moneyMoved() {
        return provider.paidPayments().size();
    }

    /** The cardholder's browser: the number goes to Click and nowhere else, and only a token comes back. */
    private String tokeniseInTheBrowser(String number) {
        try {
            String body = json.writeValueAsString(Map.of(
                    "service_id",
                    Long.parseLong(FakeClickCardHttpProvider.SERVICE_ID),
                    "card_number",
                    number,
                    "expire_date",
                    BROWSER_EXPIRY,
                    "temporary",
                    0));
            HttpResponse<String> response = browser.send(
                    HttpRequest.newBuilder(URI.create(provider.baseUrl() + "/card_token/request"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            Object token = json.readValue(response.body(), Map.class).get("card_token");
            assertThat(token).as("Click answered the browser with a card token").isNotNull();
            return String.valueOf(token);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    // ------------------------------------------------------------------ the wire

    // ------------------------------------------------------------ the shared contract, one test per rule

    @Test
    @DisplayName("every rule of the shared contract has a test in this class")
    void everyRuleOfTheContractHasATestHere() {
        assertEveryRuleHasATestHere();
    }

    @Test
    @DisplayName("an enrolment opens a session that lasts a while and is never a secret")
    void anEnrolmentOpensASession() {
        assertAnEnrolmentOpensASession();
    }

    @Test
    @DisplayName("an enrolled card says which card, never which reference")
    void anEnrolledCardNeverShowsItsReferenceInItsOwnDescription() {
        assertAnEnrolledCardNeverShowsItsReferenceInItsOwnDescription();
    }

    @Test
    @DisplayName("a session nobody opened, or another tenant's, is refused as unknown")
    void aSessionIsOnlyEverUsableByTheTenantItWasOpenedFor() {
        assertASessionIsOnlyEverUsableByTheTenantItWasOpenedFor();
    }

    @Test
    @DisplayName("a wrong bank code is refused and the session survives, so the cardholder can try again")
    void aWrongCodeIsRefusedAndTheSessionSurvives() {
        assertAWrongCodeIsRefusedAndTheSessionSurvives();
    }

    @Test
    @DisplayName("a session expires with the clock")
    void aSessionExpiresWithTheClock() {
        assertASessionExpiresWithTheClock();
    }

    @Test
    @DisplayName("the same key is the same attempt: one charge, however often it is asked, and the same answer")
    void theSameKeyIsOneCharge() {
        assertTheSameKeyIsOneCharge();
    }

    @Test
    @DisplayName("a declined charge moves no money and says it was declined")
    void aDeclineMovesNothing() {
        assertADeclineMovesNothing();
    }

    @Test
    @DisplayName(
            "an answer lost after the money moved is not an answer, status says it succeeded, and a replay is the same attempt")
    void aLostAnswerMovedTheMoneyAndStatusSaysSo() {
        assertALostAnswerMovedTheMoneyAndStatusSaysSo();
    }

    @Test
    @DisplayName("no card on file is a refusal, never a charge")
    void noTokenIsNoCharge() {
        assertNoTokenIsNoCharge();
    }

    @Test
    @DisplayName("a revoked card cannot be charged and cannot be revoked twice")
    void aRevokedTokenCannotBeChargedAndCannotBeRevokedTwice() {
        assertARevokedTokenCannotBeChargedAndCannotBeRevokedTwice();
    }

    @Test
    @DisplayName(
            "a charge stays answerable and replayable after its card is revoked: what a card paid is a record of the account")
    void aChargeStaysAnswerableAndReplayableAfterItsCardIsRevoked() {
        assertAChargeStaysAnswerableAndReplayableAfterItsCardIsRevoked();
    }

    @Test
    @DisplayName("Auth is sha1(timestamp + secret) behind the merchant user id, exactly as Click documents it")
    void authFollowsClicksFormula() {
        // The expected digest was computed independently of this code (python hashlib).
        assertThat(ClickCardProviderAdapter.authHeader("user-1", "s3cr3t", 1_700_000_000L))
                .isEqualTo("user-1:61316f3c0fe36a32346ce94ed39b4a91aa022f65:1700000000");
    }

    @Test
    @DisplayName("the browser is told where to tokenise and with which public id, and nothing secret")
    void theBrowserIsToldWhereToTokenise() {
        CardEnrolment.BeginOutcome.Begun begun = begin(TENANT);

        assertThat(begun.hostedFormUrl()).isNull();
        assertThat(begun.clientParameters())
                .containsEntry("handoff", "BROWSER_TOKENISE")
                .containsEntry("tokeniseUrl", provider.baseUrl() + "/card_token/request")
                .containsEntry("serviceId", FakeClickCardHttpProvider.SERVICE_ID)
                .containsEntry("temporary", "0")
                .containsEntry("expiryFormat", "MMYY");
        assertThat(begun.clientParameters().toString())
                .doesNotContain(FakeClickCardHttpProvider.SECRET_KEY)
                .doesNotContain(FakeClickCardHttpProvider.MERCHANT_USER_ID);
        assertThat(provider.requests())
                .as("opening an enrolment asks Click nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("an account that cannot be used is not configured, at the earliest point a tenant can be told")
    void anUnusableAccountIsNotConfigured() {
        CardAccount noServiceId = accountWith(null, Map.of());
        CardAccount notANumber = accountWith("12 34", Map.of());
        assertThat(adapter.begin(noServiceId, TENANT)).isInstanceOf(CardEnrolment.BeginOutcome.NotConfigured.class);
        assertThat(adapter.begin(notANumber, TENANT)).isInstanceOf(CardEnrolment.BeginOutcome.NotConfigured.class);

        secrets.clear();
        assertThat(adapter.begin(account(), TENANT))
                .as("no credential behind the reference")
                .isInstanceOf(CardEnrolment.BeginOutcome.NotConfigured.class);
        assertThat(adapter.charge(account(), TENANT, "TOKEN-1234", 1_000, "UZS", "key-1"))
                .isInstanceOf(CardCharger.Outcome.NotConfigured.class);
        assertThat(provider.requests()).isEmpty();
    }

    @Test
    @DisplayName("a host the environment does not approve is never called")
    void anUnapprovedHostIsNeverCalled() {
        approvedHosts = Set.of("api.click.uz");

        assertThat(adapter.charge(account(), TENANT, "TOKEN-1234", 1_000, "UZS", "key-1"))
                .isInstanceOf(CardCharger.Outcome.NotConfigured.class);
        assertThat(adapter.status(account(), "key-1")).isInstanceOf(CardCharger.StatusOutcome.NotSucceeded.class);
        assertThat(provider.requests()).isEmpty();
    }

    @Test
    @DisplayName("the card number never reaches anything the adapter sends")
    void theNumberNeverReachesHorecaOS() {
        String token = enrol(TENANT, Card.APPROVING);
        adapter.charge(account(), TENANT, token, 250_000, "UZS", "key-1");
        adapter.status(account(), "key-1");
        adapter.revoke(account(), token);

        List<FakeClickCardHttpProvider.Received> fromHorecaOS = provider.requests().stream()
                .filter(request -> request.auth() != null)
                .toList();
        assertThat(fromHorecaOS).as("verify, status reads, charge and delete").hasSizeGreaterThanOrEqualTo(5);
        assertThat(fromHorecaOS)
                .allSatisfy(request -> assertThat(request.path() + " " + request.body())
                        .doesNotContainPattern("\\d{13,19}")
                        .doesNotContain("card_number"));
        assertThat(provider.requests().stream().filter(request -> request.auth() == null))
                .as(
                        "the only request without our Auth header is the browser's own, and it is the one that carried the number")
                .hasSize(1)
                .allSatisfy(request -> assertThat(request.path()).endsWith("/card_token/request"));
    }

    @Test
    @DisplayName("a wrong secret is the account not being usable: nothing is charged and nothing is called declined")
    void aRefusedCredentialIsNotAnAccount() {
        String token = enrol(TENANT, Card.APPROVING);
        secrets.put(
                REFERENCE,
                "{\"merchantUserId\":\"" + FakeClickCardHttpProvider.MERCHANT_USER_ID
                        + "\",\"secretKey\":\"rotated-away\"}");

        assertThat(adapter.charge(account(), TENANT, token, 1_000, "UZS", "key-1"))
                .isInstanceOf(CardCharger.Outcome.NotConfigured.class);
        assertThat(provider.chargeRequests()).isZero();
        assertThat(adapter.confirm(
                        account(), TENANT, begin(TENANT).sessionReference(), token, bankCode(), BROWSER_EXPIRY))
                .isEqualTo(new CardEnrolment.ConfirmOutcome.Refused("PROVIDER_NOT_CONNECTED"));
    }

    // ----------------------------------------------------------------- enrolment

    @Test
    @DisplayName("a code with a leading zero is sent as the text it was given, a plain one as the number Click shows")
    void theBankCodeKeepsItsShape() {
        String token = tokeniseInTheBrowser(FakeClickCardHttpProvider.APPROVING_CARD);
        CardEnrolment.BeginOutcome.Begun begun = begin(TENANT);

        adapter.confirm(account(), TENANT, begun.sessionReference(), token, "012345", BROWSER_EXPIRY);
        adapter.confirm(account(), TENANT, begun.sessionReference(), token, bankCode(), BROWSER_EXPIRY);

        List<String> verifies = provider.requests().stream()
                .filter(request -> request.path().endsWith("/card_token/verify"))
                .map(FakeClickCardHttpProvider.Received::body)
                .toList();
        assertThat(verifies.get(0)).contains("\"sms_code\":\"012345\"");
        assertThat(verifies.get(1)).contains("\"sms_code\":246810");
    }

    @Test
    @DisplayName(
            "the card's last four, scheme and expiry are what is kept; Click returns the first two and the cardholder the third")
    void whatIsKeptOfACard() {
        String token = tokeniseInTheBrowser(FakeClickCardHttpProvider.APPROVING_CARD);

        CardEnrolment.ConfirmOutcome outcome =
                adapter.confirm(account(), TENANT, begin(TENANT).sessionReference(), token, bankCode(), "0731");

        assertThat(outcome).isInstanceOf(CardEnrolment.ConfirmOutcome.Enrolled.class);
        CardEnrolment.ConfirmOutcome.Enrolled enrolled = (CardEnrolment.ConfirmOutcome.Enrolled) outcome;
        assertThat(enrolled.last4()).isEqualTo("4242");
        assertThat(enrolled.brand()).isEqualTo("UZCARD");
        assertThat(enrolled.expiryMonth()).isEqualTo(7);
        assertThat(enrolled.expiryYear()).isEqualTo(2031);
        assertThat(enrolled.cardTokenReference()).isEqualTo(token);
    }

    @Test
    @DisplayName(
            "a confirmation without a usable expiry, with a lapsed one, or with a malformed token or code never reaches Click")
    void badInputIsRefusedBeforeAnythingIsSent() {
        String token = tokeniseInTheBrowser(FakeClickCardHttpProvider.APPROVING_CARD);
        String session = begin(TENANT).sessionReference();
        int before = provider.requests().size();

        assertThat(adapter.confirm(account(), TENANT, session, token, bankCode()))
                .isEqualTo(new CardEnrolment.ConfirmOutcome.Refused("CARD_EXPIRY_REQUIRED"));
        assertThat(adapter.confirm(account(), TENANT, session, token, bankCode(), null))
                .isEqualTo(new CardEnrolment.ConfirmOutcome.Refused("CARD_EXPIRY_REQUIRED"));
        assertThat(adapter.confirm(account(), TENANT, session, token, bankCode(), "1330"))
                .isEqualTo(new CardEnrolment.ConfirmOutcome.Refused("CARD_EXPIRY_REQUIRED"));
        assertThat(adapter.confirm(account(), TENANT, session, token, bankCode(), "0120"))
                .isEqualTo(new CardEnrolment.ConfirmOutcome.Refused("CARD_EXPIRED"));
        assertThat(adapter.confirm(account(), TENANT, session, "bad token/../x", bankCode(), BROWSER_EXPIRY))
                .isEqualTo(new CardEnrolment.ConfirmOutcome.Refused("UNKNOWN_CARD_TOKEN"));
        assertThat(adapter.confirm(account(), TENANT, session, token, "12", BROWSER_EXPIRY))
                .isEqualTo(new CardEnrolment.ConfirmOutcome.Refused("WRONG_CODE"));
        assertThat(adapter.revoke(account(), "bad token/../x"))
                .isEqualTo(new CardEnrolment.RevokeOutcome.Failed("UNKNOWN_TOKEN"));

        assertThat(provider.requests()).hasSize(before);
    }

    // -------------------------------------------------------------------- charge

    @Test
    @DisplayName("an amount in som goes to Click whole, as a number, with our key as its transaction parameter")
    void theChargeBodyIsWhatClickDocuments() {
        String token = enrol(TENANT, Card.APPROVING);

        adapter.charge(account(), TENANT, token, 94_000, "UZS", "attempt-7");

        String body = provider.requests().stream()
                .filter(request -> request.path().endsWith("/card_token/payment"))
                .map(FakeClickCardHttpProvider.Received::body)
                .findFirst()
                .orElseThrow();
        assertThat(body)
                .contains("\"amount\":94000")
                .contains("\"transaction_parameter\":\"attempt-7\"")
                .contains("\"service_id\":" + FakeClickCardHttpProvider.SERVICE_ID);
        assertThat(provider.paidPayments().get(0).amountSom()).isEqualTo(94_000);
        assertThat(adapter.charge(account(), TENANT, token, 1_000, "USD", "attempt-8"))
                .isEqualTo(new CardCharger.Outcome.Failed("CURRENCY_NOT_SUPPORTED"));
        assertThat(adapter.charge(account(), TENANT, token, 0, "UZS", "attempt-9"))
                .isEqualTo(new CardCharger.Outcome.Failed("AMOUNT_NOT_POSITIVE"));
    }

    @Test
    @DisplayName(
            "Click has no idempotency key, so the adapter asks before it charges: a lost answer is charged once, not twice")
    void aLostAnswerIsNeverChargedASecondTime() {
        String token = enrol(TENANT, Card.LOSING_THE_ANSWER);

        assertThatThrownBy(() -> adapter.charge(account(), TENANT, token, 500_000, "UZS", "key-1"))
                .isInstanceOf(CardChargeUnansweredException.class);
        assertThat(provider.chargeRequests()).isEqualTo(1);

        CardCharger.Outcome replay = adapter.charge(account(), TENANT, token, 500_000, "UZS", "key-1");

        assertThat(replay).isInstanceOf(CardCharger.Outcome.Succeeded.class);
        assertThat(((CardCharger.Outcome.Succeeded) replay).providerReference())
                .isEqualTo(provider.paidPayments().get(0).paymentId());
        assertThat(provider.chargeRequests())
                .as("the replay found the payment and asked Click for no second one")
                .isEqualTo(1);
        assertThat(provider.paidPayments()).hasSize(1);
    }

    @Test
    @DisplayName("a charge is never sent while Click cannot be asked what it holds under the key")
    void noChargeWhileTheLookupIsUnreadable() {
        String token = enrol(TENANT, Card.APPROVING);
        transport.fail("payment.status_by_mti", ProviderOutcome.retryable("CONNECTION_FAILED", "refused", null));

        assertThatThrownBy(() -> adapter.charge(account(), TENANT, token, 500_000, "UZS", "key-1"))
                .isInstanceOf(CardChargeUnansweredException.class)
                .hasMessage("PAYMENT_LOOKUP_UNAVAILABLE");

        assertThat(provider.chargeRequests()).isZero();
        assertThat(transport.operations()).doesNotContain("card_token.payment");
    }

    @Test
    @DisplayName(
            "a failure on the charge itself is uncertain unless Click plainly refused it: a 4xx is a refusal, a credential is not an account")
    void aFailedChargeIsUncertainUnlessRefused() {
        String token = enrol(TENANT, Card.APPROVING);

        transport.fail("card_token.payment", ProviderOutcome.uncertain("READ_TIMEOUT", "no answer"));
        assertThatThrownBy(() -> adapter.charge(account(), TENANT, token, 1_000, "UZS", "key-1"))
                .isInstanceOf(CardChargeUnansweredException.class)
                .hasMessage("READ_TIMEOUT");

        transport.fail("card_token.payment", ProviderOutcome.retryable("PROVIDER_UNAVAILABLE", "502", null));
        assertThatThrownBy(() -> adapter.charge(account(), TENANT, token, 1_000, "UZS", "key-1"))
                .isInstanceOf(CardChargeUnansweredException.class);

        transport.fail("card_token.payment", ProviderOutcome.rejected("PROVIDER_REJECTED", "400"));
        assertThat(adapter.charge(account(), TENANT, token, 1_000, "UZS", "key-1"))
                .isEqualTo(new CardCharger.Outcome.Failed("PROVIDER_REJECTED"));

        transport.fail("card_token.payment", ProviderOutcome.rejected("PROVIDER_AUTHENTICATION", "401"));
        assertThat(adapter.charge(account(), TENANT, token, 1_000, "UZS", "key-1"))
                .isInstanceOf(CardCharger.Outcome.NotConfigured.class);
        assertThat(provider.paidPayments()).isEmpty();
    }

    @Test
    @DisplayName(
            "a payment Click reports as processing is not money: the attempt waits, and is recorded only once it is paid")
    void processingIsNotMoney() {
        String token = enrol(TENANT, Card.APPROVING);
        provider.answerChargesWithStatus(1);
        provider.reportPaymentsAsProcessing(true);

        assertThatThrownBy(() -> adapter.charge(account(), TENANT, token, 500_000, "UZS", "key-1"))
                .isInstanceOf(CardChargeUnansweredException.class)
                .hasMessage("PAYMENT_IN_PROCESSING");
        assertThat(adapter.status(account(), "key-1")).isInstanceOf(CardCharger.StatusOutcome.NotSucceeded.class);
        assertThatThrownBy(() -> adapter.charge(account(), TENANT, token, 500_000, "UZS", "key-1"))
                .as("asked again: still processing, and never charged again")
                .isInstanceOf(CardChargeUnansweredException.class);
        assertThat(provider.chargeRequests()).isEqualTo(1);

        provider.reportPaymentsAsProcessing(false);
        assertThat(adapter.status(account(), "key-1")).isInstanceOf(CardCharger.StatusOutcome.Succeeded.class);
        assertThat(adapter.charge(account(), TENANT, token, 500_000, "UZS", "key-1"))
                .isInstanceOf(CardCharger.Outcome.Succeeded.class);
        assertThat(provider.paidPayments()).hasSize(1);
    }

    @Test
    @DisplayName(
            "the date of status_by_mti is not documented: the lookup reads today and the days before it, and the reach is the account's to widen")
    void aPaymentIsFoundOnTheDaysTheLookupReaches() {
        String token = enrol(TENANT, Card.LOSING_THE_ANSWER);
        assertThatThrownBy(() -> adapter.charge(account(), TENANT, token, 500_000, "UZS", "key-1"))
                .isInstanceOf(CardChargeUnansweredException.class);

        advanceClock(Duration.ofDays(2));
        assertThat(adapter.status(account(), "key-1"))
                .as("two days on, still inside the default reach")
                .isInstanceOf(CardCharger.StatusOutcome.Succeeded.class);

        advanceClock(Duration.ofDays(1));
        assertThat(adapter.status(account(), "key-1"))
                .as("three days on the default reach no longer sees it; this is the documented limit, and why a long "
                        + "suspension widens lookbackDays first")
                .isInstanceOf(CardCharger.StatusOutcome.NotSucceeded.class);
        CardAccount wider = accountWith(FakeClickCardHttpProvider.SERVICE_ID, Map.of("lookbackDays", 7));
        assertThat(adapter.status(wider, "key-1")).isInstanceOf(CardCharger.StatusOutcome.Succeeded.class);
        assertThat(adapter.charge(wider, TENANT, token, 500_000, "UZS", "key-1"))
                .isInstanceOf(CardCharger.Outcome.Succeeded.class);
        assertThat(provider.paidPayments()).hasSize(1);
    }

    @Test
    @DisplayName(
            "how Click says it holds nothing is not documented: a 404 or an empty success is read as nothing; any other code is not, until the account names it")
    void anUndocumentedNotFoundFailsClosed() {
        String token = enrol(TENANT, Card.APPROVING);

        provider.answerNotHeldWith(NotHeldAnswer.HTTP_404);
        assertThat(adapter.charge(account(), TENANT, token, 1_000, "UZS", "key-404"))
                .isInstanceOf(CardCharger.Outcome.Succeeded.class);

        provider.answerNotHeldWith(NotHeldAnswer.ERROR_CODE);
        assertThatThrownBy(() -> adapter.charge(account(), TENANT, token, 1_000, "UZS", "key-code"))
                .as("a code nobody has said means 'none' is not proof of none")
                .isInstanceOf(CardChargeUnansweredException.class)
                .hasMessage("PAYMENT_LOOKUP_UNAVAILABLE");
        assertThat(provider.chargeRequests()).isEqualTo(1);

        CardAccount named = accountWith(
                FakeClickCardHttpProvider.SERVICE_ID,
                Map.of("notFoundErrorCodes", List.of(NotHeldAnswer.NOT_FOUND_CODE)));
        assertThat(adapter.charge(named, TENANT, token, 1_000, "UZS", "key-code"))
                .isInstanceOf(CardCharger.Outcome.Succeeded.class);
        assertThat(provider.paidPayments()).hasSize(2);
    }

    // ----------------------------------------------------- an unhealthy provider

    @Test
    @DisplayName(
            "a provider answering 500, nonsense or nothing is never charged against, never called declined, and never believed")
    void anUnhealthyProviderIsNeverChargedAgainst() {
        for (String service : List.of(
                FakeClickCardHttpProvider.SERVICE_ID_HTTP_500,
                FakeClickCardHttpProvider.SERVICE_ID_MALFORMED,
                FakeClickCardHttpProvider.SERVICE_ID_TIMEOUT)) {
            CardAccount sick = sickAccount(service);

            assertThatThrownBy(() -> adapter.charge(sick, TENANT, "TOKEN-1234", 1_000, "UZS", "key-" + service))
                    .as("service %s", service)
                    .isInstanceOf(CardChargeUnansweredException.class);
            assertThat(adapter.confirm(
                            sick, TENANT, begin(sick).sessionReference(), "TOKEN-1234", bankCode(), BROWSER_EXPIRY))
                    .as("service %s", service)
                    .isEqualTo(new CardEnrolment.ConfirmOutcome.Refused("PROVIDER_UNAVAILABLE"));
            assertThat(adapter.status(sick, "key-" + service))
                    .isInstanceOf(CardCharger.StatusOutcome.NotSucceeded.class);
        }
        assertThat(provider.chargeRequests()).isZero();
        assertThat(provider.paidPayments()).isEmpty();
    }

    private CardEnrolment.BeginOutcome.Begun begin(CardAccount account) {
        return (CardEnrolment.BeginOutcome.Begun) adapter.begin(account, TENANT);
    }

    @Test
    @DisplayName("a provider that keeps failing opens the circuit for that account, and the calls stop reaching it")
    void theCircuitOpensForAFailingProvider() {
        CardAccount sick = sickAccount(FakeClickCardHttpProvider.SERVICE_ID_HTTP_500);

        for (int i = 0; i < 4; i++) {
            adapter.status(sick, "key-" + i);
        }

        assertThat(provider.requests())
                .as("the breaker counts five failures and then refuses without calling")
                .hasSize(5);
    }

    // ------------------------------------------------------------------ helpers

    /** Wraps the real transport so a test can make one operation fail the way a sick network would. */
    private static final class InjectingTransport implements CardBillingApiTransport {

        private final CardBillingApiTransport delegate;
        private final Map<String, ProviderOutcome> failures = new LinkedHashMap<>();
        private final List<String> operations = new ArrayList<>();

        InjectingTransport(CardBillingApiTransport delegate) {
            this.delegate = delegate;
        }

        void fail(String operation, ProviderOutcome outcome) {
            failures.put(operation, outcome);
        }

        List<String> operations() {
            return operations;
        }

        @Override
        public ProviderOutcome exchange(CardBillingApiCall call) {
            operations.add(call.operation());
            ProviderOutcome injected = failures.get(call.operation());
            return injected != null ? injected : delegate.exchange(call);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
