package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.commercial.api.CardEnrolment;
import uz.horecaos.platform.commercial.api.CardProviderAdapter;
import uz.horecaos.platform.commercial.domain.CardOnFile;
import uz.horecaos.platform.commercial.domain.CardTopUp;
import uz.horecaos.platform.commercial.domain.WalletEntry;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.camel.cardbilling.CamelCardBillingApiTransport;
import uz.horecaos.platform.integration.camel.cardbilling.CardBillingCircuitBreakers;
import uz.horecaos.platform.integration.camel.cardbilling.CardBillingGateway;
import uz.horecaos.platform.integration.camel.cardbilling.CardBillingProcessor;
import uz.horecaos.platform.integration.camel.cardbilling.CardBillingRouteBuilder;
import uz.horecaos.platform.integration.camel.cardbilling.click.ClickCardProviderAdapter;
import uz.horecaos.platform.integration.camel.cardbilling.click.fake.FakeClickCardHttpProvider;
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.integration.camel.einvoicing.JdbcEgressAllowlist;
import uz.horecaos.platform.web.api.ApiException;

/**
 * ADR 0095's card flows against PostgreSQL, the real Click adapter, the real Camel route and a controlled fake of
 * Click's HTTP boundary (row X.3, row 8.6): the same assertions {@code CardTopUpTests} and {@code
 * CardRecurringTokenFlowTests} make against the in-process double, through the code that will speak to Click.
 *
 * <p>Nothing is planted. The account is declared and activated through the installation service against the
 * approved environment V0689 seeded (only its address is pointed at the local fake, the way a sandbox walk points
 * it at Click's); a card reaches a tenant by the enrolment flow, with the cardholder's browser standing in as the
 * only party that ever holds a number; and what the provider really took is read from the provider.
 */
class ClickCardWalletFlowTests extends WalletBillingFixture {

    private static final String ENVIRONMENT = "click_card_sandbox";
    private static final String REFERENCE = "horecaos:test:provider_payment:platform:card-merchant";
    private static final String CREDENTIAL = "{\"merchantUserId\":\"" + FakeClickCardHttpProvider.MERCHANT_USER_ID
            + "\",\"secretKey\":\"" + FakeClickCardHttpProvider.SECRET_KEY + "\"}";

    private @Nullable FakeClickCardHttpProvider clickProvider;
    private @Nullable CamelContext camel;
    private final HttpClient browser = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private boolean credentialStored = true;

    @Override
    List<CardProviderAdapter> adapters() {
        clickProvider = new FakeClickCardHttpProvider(clock);
        clickProvider.start();
        jdbc.sql("UPDATE integration.provider_environments SET base_url = :url, egress_allowlist = 'localhost' "
                        + "WHERE code = :code")
                .param("url", click().baseUrl())
                .param("code", ENVIRONMENT)
                .update();
        SecretResolver secrets = new SecretResolver() {
            @Override
            public SecretValue resolve(SecretReference reference) {
                if (!credentialStored) {
                    throw new SecretNotFoundException(reference);
                }
                return SecretValue.of(CREDENTIAL);
            }

            @Override
            public SecretValue resolveFresh(SecretReference reference) {
                return resolve(reference);
            }
        };
        try {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            CardBillingGateway gateway = new CardBillingGateway(
                    new JdbcEgressAllowlist(jdbc),
                    secrets,
                    new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier()));
            DefaultCamelContext context = new DefaultCamelContext();
            context.addRoutes(new CardBillingRouteBuilder(
                    new CardBillingProcessor(gateway, new CardBillingCircuitBreakers(registry), registry)));
            context.start();
            camel = context;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        return List.of(
                fake,
                new ClickCardProviderAdapter(
                        new CamelCardBillingApiTransport(
                                Objects.requireNonNull(camel).createProducerTemplate()),
                        secrets,
                        JsonMapper.builder().build(),
                        clock));
    }

    @AfterEach
    void stopClick() {
        if (camel != null) {
            camel.stop();
        }
        if (clickProvider != null) {
            clickProvider.stop();
        }
    }

    private FakeClickCardHttpProvider click() {
        return Objects.requireNonNull(clickProvider);
    }

    /** Declares HorecaOS's Click account and activates it, as an operator would; the credential is in the manager. */
    private UUID connectClick() {
        UUID id = inTx(() -> installations.create(
                "CLICK_CARD",
                ENVIRONMENT,
                "Click sandbox",
                REFERENCE,
                FakeClickCardHttpProvider.SERVICE_ID,
                Map.of(),
                MAKER,
                "connecting the merchant account",
                "c"));
        inTx(() -> installations.activate(id, 0, MAKER, "the sandbox walk", "c"));
        return id;
    }

    /** The tenant's card on file, entered the way a tenant does it: the browser tokenises, HorecaOS confirms. */
    private void addCard(UUID tenantId, String number) {
        CardEnrolment.BeginOutcome.Begun begun = cardOnFile.beginEnrolment(tenantId);
        assertThat(begun.clientParameters()).containsEntry("handoff", "BROWSER_TOKENISE");
        String token = tokeniseInTheBrowser(number);
        cardOnFile.confirmEnrolment(
                tenantId,
                begun.sessionReference(),
                token,
                FakeClickCardHttpProvider.SMS_CODE,
                "1230",
                TENANT_USER,
                "c");
    }

    private String tokeniseInTheBrowser(String number) {
        try {
            String body = json.writeValueAsString(Map.of(
                    "service_id",
                    Long.parseLong(FakeClickCardHttpProvider.SERVICE_ID),
                    "card_number",
                    number,
                    "expire_date",
                    "1230",
                    "temporary",
                    0));
            HttpResponse<String> response = browser.send(
                    HttpRequest.newBuilder(URI.create(click().baseUrl() + "/card_token/request"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return String.valueOf(json.readValue(response.body(), Map.class).get("card_token"));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    // --------------------------------------------------------------------- the tests

    @Test
    void theApprovedEnvironmentsClickIsCalledAtAreSeededAndTheAccountIsDeclaredAgainstOne() {
        assertThat(jdbc.sql("SELECT code FROM integration.provider_environments "
                                + "WHERE provider_category = 'PAYMENT' AND provider_type = 'CLICK_CARD' ORDER BY code")
                        .query(String.class)
                        .list())
                .containsExactly("click_card_production", "click_card_sandbox");
        assertThat(jdbc.sql("SELECT is_production FROM integration.provider_environments WHERE code = :c")
                        .param("c", "click_card_production")
                        .query(Boolean.class)
                        .single())
                .isTrue();

        UUID id = connectClick();

        assertThat(installations.find(id).status()).isEqualTo("ACTIVE");
        assertThat(gateway.available()).isTrue();
        assertThat(gateway.configured()).isTrue();
    }

    @Test
    void aClickAccountNamesAnApprovedEnvironmentAndAProviderPaymentSecretOrIsNotDeclared() {
        assertThatThrownBy(() -> inTx(() -> installations.create(
                        "CLICK_CARD", "made_up", "x", REFERENCE, "100100", Map.of(), MAKER, "r", "c")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("field", "environmentCode"));
        assertThatThrownBy(() -> inTx(() -> installations.create(
                        "CLICK_CARD", ENVIRONMENT, "x", null, "100100", Map.of(), MAKER, "r", "c")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("field", "secretReference"));
        assertThatThrownBy(() -> inTx(() -> installations.create(
                        "CLICK_CARD",
                        ENVIRONMENT,
                        "x",
                        "horecaos:test:provider_pos:platform:wrong-category",
                        "100100",
                        Map.of(),
                        MAKER,
                        "r",
                        "c")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void anAccountWhoseCredentialIsNotInTheManagerOffersTenantsNothingToAddACardTo() {
        credentialStored = false;
        connectClick();

        assertThatThrownBy(() -> cardOnFile.beginEnrolment(PILOT))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "CARDS_NOT_AVAILABLE"));
        assertThat(click().requests()).isEmpty();
    }

    @Test
    void aTenantAddsACardAndTopsUpThroughTheRealAdapterAndTheLedgerCarriesClicksOwnReference() {
        startOnPlan(START, MONTHLY);
        connectClick();
        addCard(PILOT, FakeClickCardHttpProvider.APPROVING_CARD);

        CardOnFile card = wallet.cardOnFile(PILOT).orElseThrow();
        assertThat(card.last4()).isEqualTo("4242");
        assertThat(card.brand()).isEqualTo("UZCARD");
        assertThat(card.expiryYear()).isEqualTo(2030);
        assertThat(wallet.billing(PILOT).cardTokenReference())
                .as("bound to the account that minted it")
                .contains(":");

        issue(PILOT, "2026-10", CLOSE);
        CardTopUp topUp = topUps.requestTopUp(PILOT, MONTHLY + 500_000, TENANT_USER, "c");

        assertThat(topUp.outcome()).isEqualTo(CardTopUp.SUCCEEDED);
        assertThat(click().paidPayments()).singleElement().satisfies(payment -> {
            assertThat(payment.amountSom()).isEqualTo(MONTHLY + 500_000);
            assertThat(payment.merchantTransId())
                    .as("the attempt row's id is the key Click was handed")
                    .isEqualTo(topUp.id().toString());
            assertThat(wallet.ledger(PILOT, null, 10))
                    .filteredOn(entry -> WalletEntry.TOP_UP.equals(entry.entryType()))
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.externalReference())
                            .as("the ledger carries Click's own reference for the payment")
                            .isEqualTo(payment.paymentId()));
        });
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(500_000);
        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isZero();
        assertThat(wallet.ledger(PILOT, null, 10))
                .extracting(WalletEntry::entryType)
                .containsExactlyInAnyOrder(WalletEntry.TOP_UP, WalletEntry.STATEMENT_PAYMENT);
        assertThat(auditedActions("commercial.wallet.card_bound")).hasSize(1);
        assertThat(jdbc.sql("SELECT change_document::text FROM audit.audit_events WHERE action_code = :code")
                        .param("code", "commercial.wallet.card_bound")
                        .query(String.class)
                        .single())
                .as("the audit fact says which card, and carries neither Click's token nor the number")
                .contains("4242")
                .doesNotContain(FakeClickCardHttpProvider.APPROVING_CARD);
    }

    @Test
    void aDeclinedTopUpPutsNothingOnTheLedger() {
        connectClick();
        addCard(PILOT, FakeClickCardHttpProvider.DECLINING_CARD);

        CardTopUp declined = topUps.requestTopUp(PILOT, 300_000, TENANT_USER, "c");

        assertThat(declined.outcome()).isEqualTo(CardTopUp.FAILED);
        assertThat(declined.providerDetail()).startsWith("CLICK_ERROR_");
        assertThat(ledgerSize(PILOT)).isZero();
        assertThat(click().paidPayments()).isEmpty();
    }

    @Test
    void aLostAnswerIsRecordedOnceBySettlementAndClickIsNeverAskedToChargeAgain() {
        connectClick();
        addCard(PILOT, FakeClickCardHttpProvider.LOSING_CARD);

        CardTopUp pending = topUps.requestTopUp(PILOT, 400_000, TENANT_USER, "c");

        assertThat(pending.outcome()).isEqualTo(CardTopUp.PENDING);
        assertThat(click().paidPayments()).as("Click took the money").hasSize(1);
        assertThat(ledgerSize(PILOT)).as("and nothing is on the ledger yet").isZero();
        assertThatThrownBy(() -> topUps.requestTopUp(PILOT, 400_000, TENANT_USER, "c"))
                .as("an impatient second click is not a second charge")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "TOP_UP_IN_FLIGHT"));

        clock.advance(Duration.ofMinutes(3));
        WalletCardSettlementSweeper.Result swept = sweeper.runOnce();

        assertThat(swept.topUpsResolved()).isEqualTo(1);
        assertThat(click().paidPayments()).hasSize(1);
        assertThat(click().chargeRequests())
                .as("the sweep asked what Click holds and recorded it: it did not ask for a second charge")
                .isEqualTo(1);
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(400_000);
        assertThat(topUpStore.find(PILOT, pending.id()).orElseThrow().outcome()).isEqualTo(CardTopUp.SUCCEEDED);
        assertThat(sweeper.runOnce().topUpsResolved())
                .as("nothing left to resolve")
                .isZero();
        assertThat(ledgerSize(PILOT)).isEqualTo(1);
    }

    @Test
    void aCardTenantsStatementRemainderIsCollectedFromTheCardOnce() {
        startOnPlan(START, MONTHLY);
        connectClick();
        addCard(PILOT, FakeClickCardHttpProvider.APPROVING_CARD);
        chooseCard(PILOT);
        issue(PILOT, "2026-10", CLOSE);

        assertThat(wallet.settleCardRemainders(PILOT)).isEqualTo(MONTHLY);

        assertThat(click().paidPayments()).hasSize(1);
        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isZero();
        assertThat(wallet.settleCardRemainders(PILOT))
                .as("nothing is owed, nothing is charged")
                .isZero();
        assertThat(click().paidPayments()).hasSize(1);
    }

    @Test
    void replacingTheCardTakesTheOldOneOffClick() {
        connectClick();
        addCard(PILOT, FakeClickCardHttpProvider.APPROVING_CARD);
        assertThat(click().tokensHeld()).isEqualTo(1);

        addCard(PILOT, FakeClickCardHttpProvider.APPROVING_CARD);

        assertThat(click().tokensHeld())
                .as("the replaced reference was revoked at Click, so nobody can charge it")
                .isEqualTo(1);
    }

    @Test
    void suspendingTheAccountPutsEveryCardTenantBackOnInvoicesWithoutTouchingClick() {
        UUID id = connectClick();
        addCard(PILOT, FakeClickCardHttpProvider.APPROVING_CARD);
        int before = click().requests().size();

        inTx(() -> installations.suspend(id, 1, MAKER, "stop charging", "c"));

        assertThat(gateway.available()).isFalse();
        assertThatThrownBy(() -> cardOnFile.beginEnrolment(PILOT)).isInstanceOf(ApiException.class);
        assertThat(click().requests()).hasSize(before);
    }
}
