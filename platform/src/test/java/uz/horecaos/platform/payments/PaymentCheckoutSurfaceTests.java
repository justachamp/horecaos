package uz.horecaos.platform.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.integration.api.payment.MerchantApiCall;
import uz.horecaos.platform.integration.api.payment.MerchantApiTransport;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.payments.application.CapturedMoneyPort;
import uz.horecaos.platform.payments.application.PaymentAttemptService;
import uz.horecaos.platform.payments.application.PaymentCheckoutService;
import uz.horecaos.platform.payments.domain.CaptureTiming;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentAttemptStatus;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PaymentIntentStatus;
import uz.horecaos.platform.payments.domain.PaymentMethod;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.PaymentTender;
import uz.horecaos.platform.payments.domain.PresentationFailure;
import uz.horecaos.platform.payments.domain.PresentationKind;
import uz.horecaos.platform.payments.domain.PresentationRequest;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.domain.UncertaintyResolver;
import uz.horecaos.platform.payments.infrastructure.click.ClickMerchantApi;
import uz.horecaos.platform.payments.infrastructure.click.ClickPaymentAdapter;
import uz.horecaos.platform.payments.infrastructure.payme.PaymeProviderAdapter;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentBindingResolver;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentBusinessCalendar;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentTransactionStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Opening an attempt and presenting a checkout surface (ADR 0013).
 *
 * <p>These are the properties that decide whether a payment-first order can ever
 * be paid. The attempt exists, with the {@code merchant_trans_id} Click's callback
 * will name and the business date its resolver will ask about, before anything is
 * asked of a provider. The two surfaces carry the amount in the unit each provider
 * expects, with the hundredfold happening exactly once and only on Payme. And a
 * customer who abandons a checkout and comes back gets the link they already have
 * rather than a second payable one.
 *
 * <p>Run against a real database, because the claim about the second link is made
 * by a partial unique index rather than by anything in Java, and a test with a
 * stubbed store would be asserting its own stub.
 */
class PaymentCheckoutSurfaceTests {

    private static final String UZS = "UZS";

    /**
     * The ADR's own crossing fixture: one 12 000-som quote is {@code 12000} on
     * Click's som-denominated surfaces and {@code 1200000} on Payme's tiyin ones.
     */
    private static final long AMOUNT_SOM = 12_000L;

    private static final String CLICK_SERVICE_ID = "12345";
    private static final String CLICK_MERCHANT_ID = "9999";
    private static final String CLICK_MERCHANT_USER_ID = "3333";
    private static final String PAYME_CASHBOX = "587f72c72cac0d162c722ae2";
    private static final String PAYME_CHECKOUT_HOST = "https://test.paycom.uz";

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID CLICK_ENTITY = UUID.randomUUID();
    private static final UUID PAYME_ENTITY = UUID.randomUUID();
    private static final UUID UNBOUND_ENTITY = UUID.randomUUID();
    private static final UUID CLICK_BINDING = UUID.randomUUID();
    private static final UUID CLICK_LINKLESS_BINDING = UUID.randomUUID();
    private static final UUID PAYME_BINDING = UUID.randomUUID();
    private static final UUID CLICK_INSTALLATION = UUID.randomUUID();
    private static final UUID PAYME_INSTALLATION = UUID.randomUUID();
    private static final UUID INTEGRATION_BINDING = UUID.randomUUID();

    private static final UUID CLICK_ORDER = UUID.randomUUID();
    private static final UUID PAYME_ORDER = UUID.randomUUID();

    /**
     * 04:00 UTC is 09:00 in Tashkent, so the business date is unambiguous — and a
     * calendar that answered UTC would still agree here, which is why the
     * cross-midnight case is asserted separately.
     */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-22T04:03:11Z"), ZoneOffset.UTC);

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 22);

    private static TestDatabase.Handle db;
    private static JdbcClient jdbc;
    private static TransactionTemplate unitOfWork;

    private JdbcPaymentIntentStore intents;
    private JdbcPaymentAttemptStore attempts;
    private RecordingTransport transport;
    private PaymentCheckoutService checkout;
    private uz.horecaos.platform.payments.application.TerminalOrderPaymentVoid endedOrders;
    private PaymentAttemptService attemptService;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the payments schema");
        db = TestDatabase.migrated();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
        jdbc = JdbcClient.create(dataSource);
        unitOfWork = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        seedTenantAndMerchantAccounts();
        seedOrders();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void wire() {
        jdbc.sql("DELETE FROM payments.payment_transactions").update();
        jdbc.sql("DELETE FROM payments.payment_attempts").update();
        jdbc.sql("DELETE FROM payments.payment_intents").update();

        intents = new JdbcPaymentIntentStore(jdbc);
        attempts = new JdbcPaymentAttemptStore(jdbc);
        JdbcPaymentTransactionStore transactions = new JdbcPaymentTransactionStore(jdbc);
        JdbcPaymentBindingResolver bindings = new JdbcPaymentBindingResolver(jdbc);

        transport = new RecordingTransport();
        ClickPaymentAdapter click = new ClickPaymentAdapter(new ClickMerchantApi(transport, CLOCK), CLOCK);
        PaymeProviderAdapter payme = new PaymeProviderAdapter(new FixedInstallations(), attempts, true);

        attemptService = new PaymentAttemptService(
                intents,
                attempts,
                transactions,
                bindings,
                List.of(click, payme),
                CapturedMoneyPort.NONE,
                unitOfWork,
                event -> {},
                CLOCK);

        checkout = new PaymentCheckoutService(
                intents,
                attempts,
                attemptService,
                bindings,
                new JdbcPaymentBusinessCalendar(jdbc),
                new SeededOrders(),
                CLOCK);
        endedOrders = new uz.horecaos.platform.payments.application.TerminalOrderPaymentVoid(
                intents, attempts, attemptService, bindings, List.of(click, payme), CLOCK);
    }

    // -----------------------------------------------------------------------
    // The order ends while the payment is still live
    // -----------------------------------------------------------------------

    /**
     * Nothing in {@code payments} listened for an order ending, and a live payment
     * outlived the order it belonged to.
     *
     * <p>Cancel an order in {@code PAYMENT_AUTHORIZING} and ordering does the whole
     * of its job: the settlement is failed, the points hold released. The provider
     * is never told, so the customer's checkout page is still payable — Payme gives
     * a transaction twelve hours — and a redirect completed an hour later captures
     * real money against an order the platform has written off.
     *
     * <p>This is the attempt to stop it, and it goes through ADR 0007's rules
     * rather than around them. Click's only reversal needs a {@code payment_id}
     * that {@code status_by_mti} has to resolve, and nothing documents that
     * Complete's {@code click_paydoc_id} is that id. A resolution that fails
     * therefore leaves the void <em>uncertain</em> rather than sending a DELETE at
     * a guessed identifier — and uncertain is owned by a resolver that only reads.
     */
    @Test
    @DisplayName("an order ending tries to void its live payment, and a void it cannot key is "
            + "uncertain rather than guessed")
    void anEndedOrderVoidsWhatItCanAndGuessesNothing() {
        givenIntent(CLICK_ORDER, PaymentProviderType.CLICK, CLICK_ENTITY);
        var session = checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link());
        transport.answer(uz.horecaos.platform.integration.api.provider.ProviderOutcome.uncertain(
                "TIMEOUT", "Click did not answer status_by_mti"));

        endedOrders.voidAnyLivePayment(TENANT, CLICK_ORDER, "CUSTOMER_UNREACHABLE");

        PaymentAttempt attempt = attempts.find(TENANT, session.attemptId()).orElseThrow();
        assertThat(attempt.status())
                .as("the void may or may not have happened, and that is a question rather than " + "a failure")
                .isEqualTo(PaymentAttemptStatus.UNCERTAIN);
        assertThat(transport.paths())
                .as("one lookup and no DELETE: a reversal sent at an identifier this platform "
                        + "guessed is a reversal of somebody else's payment")
                .hasSize(1);
        assertThat(transport.paths().getFirst()).contains("status_by_mti");

        int callsAfterTheFirstEnding = transport.calls().size();
        endedOrders.voidAnyLivePayment(TENANT, CLICK_ORDER, "CUSTOMER_UNREACHABLE");
        assertThat(transport.calls())
                .as("a redelivered cancellation must not become a second void: Click carries no "
                        + "idempotency key on any call, and the resolver is the only thing "
                        + "allowed to follow uncertainty")
                .hasSize(callsAfterTheFirstEnding);
    }

    /**
     * The other half, and the one that has to work: a provider with no void.
     *
     * <p>Payme's Merchant API is inbound only. HorecaOS cannot call
     * {@code CancelTransaction} — Payme calls it on HorecaOS — so the adapter answers
     * {@code REJECTED} and says so rather than pretending. What must <em>not</em>
     * follow is a tidy local lie: failing the attempt here would hide a
     * transaction the customer can still complete, and the capture that arrived
     * would land on an attempt the console had stopped showing.
     */
    @Test
    @DisplayName(
            "a provider with no void leaves the attempt open, so the capture that lands " + "anyway is still recorded")
    void aPaymentThatCannotBeVoidedIsLeftPayableRatherThanQuietlyFailed() {
        givenIntent(PAYME_ORDER, PaymentProviderType.PAYME, PAYME_ENTITY);
        var session = checkout.openOrRePresent(TENANT, PAYME_ORDER, ACCOUNT, PresentationRequest.link());

        endedOrders.voidAnyLivePayment(TENANT, PAYME_ORDER, "ORDER_EXPIRED");

        assertThat(attempts.find(TENANT, session.attemptId()).orElseThrow().status())
                .as("Payme has no outbound refund and no outbound cancel; marking this FAILED "
                        + "would be HorecaOS asserting something about Payme that Payme never said")
                .isEqualTo(PaymentAttemptStatus.PRESENTED);
        assertThat(attempts.findOpenForIntent(
                        TENANT,
                        attempts.find(TENANT, session.attemptId()).orElseThrow().intentId()))
                .as("still open, which is what lets the twelve-hour redirect be recorded when " + "it completes")
                .isPresent();
    }

    @Test
    @DisplayName("an attempt that was already uncertain when the order ended is left to its " + "resolver, untouched")
    void anEndedOrderNeverVoidsOnTopOfAnUncertainAttempt() {
        givenIntent(CLICK_ORDER, PaymentProviderType.CLICK, CLICK_ENTITY);
        var session = checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link());
        // Through the real path, because UNCERTAIN carries an obligation — a named
        // resolver and a deadline — that ck_payment_attempt_uncertain_has_obligation
        // refuses a row without.
        attemptService.markUncertain(attempts.find(TENANT, session.attemptId()).orElseThrow(), "CLICK_TIMEOUT");
        int callsBefore = transport.calls().size();

        endedOrders.voidAnyLivePayment(TENANT, CLICK_ORDER, "CUSTOMER_UNREACHABLE");

        assertThat(transport.calls())
                .as("it is already a question about money owned by a named resolver; a void "
                        + "issued on top of it is exactly the blind retry UNCERTAIN forbids")
                .hasSize(callsBefore);
        assertThat(attempts.find(TENANT, session.attemptId()).orElseThrow().status())
                .isEqualTo(PaymentAttemptStatus.UNCERTAIN);
    }

    // -----------------------------------------------------------------------
    // Opening the attempt
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("the attempt exists, with its resolver key and its business date, before the link")
    void openingCommitsTheResolverKeyFirst() {
        UUID intentId = givenIntent(CLICK_ORDER, PaymentProviderType.CLICK, CLICK_ENTITY);

        var session = checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link());

        PaymentAttempt attempt = attempts.find(TENANT, session.attemptId()).orElseThrow();
        assertThat(attempt.intentId()).isEqualTo(intentId);
        // The two fields an uncertain outcome is resolved with. Both are written by
        // the insert, before any provider could have been called, because a charge
        // nobody can ask about is a charge found in a settlement file a day later.
        assertThat(attempt.merchantTransId())
                .isEqualTo(session.merchantTransId())
                .isNotBlank();
        assertThat(attempt.businessDate()).isEqualTo(BUSINESS_DATE);
        assertThat(attempt.status()).isEqualTo(PaymentAttemptStatus.PRESENTED);
        assertThat(attempt.amount()).isEqualTo(new SomAmount(AMOUNT_SOM, UZS));

        // The intent follows the attempt into AUTHORIZING, which is what the order's
        // PAYMENT_AUTHORIZING wait is actually waiting on.
        assertThat(intents.find(TENANT, intentId).orElseThrow().status()).isEqualTo(PaymentIntentStatus.AUTHORIZING);
    }

    @Test
    @DisplayName("the merchant_trans_id is opaque and not derived from the order")
    void theResolverKeyRevealsNothing() {
        givenIntent(CLICK_ORDER, PaymentProviderType.CLICK, CLICK_ENTITY);

        var session = checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link());

        // It becomes Payme's account.order_id in an unsigned link, and
        // CheckPerformTransaction is unauthenticated from the customer's side, so a
        // value anyone could derive from an order id lets them walk other people's
        // orders.
        assertThat(session.merchantTransId())
                .doesNotContain(CLICK_ORDER.toString())
                .doesNotContain(CLICK_ORDER.toString().replace("-", ""))
                .hasSize(32);
    }

    // -----------------------------------------------------------------------
    // Click's surfaces
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Click's link is unsigned, som-denominated, and names the attempt")
    void clickPaymentLink() {
        givenIntent(CLICK_ORDER, PaymentProviderType.CLICK, CLICK_ENTITY);

        var session = checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link());

        Map<String, String> parameters = queryOf(session.checkoutUrl());
        assertThat(session.checkoutUrl()).startsWith("https://my.click.uz/services/pay/?");
        assertThat(parameters.get("merchant_id")).isEqualTo(CLICK_MERCHANT_ID);
        assertThat(parameters.get("service_id")).isEqualTo(CLICK_SERVICE_ID);
        assertThat(parameters.get("merchant_user_id")).isEqualTo(CLICK_MERCHANT_USER_ID);
        assertThat(parameters.get("transaction_param")).isEqualTo(session.merchantTransId());
        // Som, formatted N.NN as the button page documents — not tiyin, and not a
        // minor-unit figure divided by 100.0, which is how 12 000 som becomes
        // 11999.99.
        assertThat(parameters.get("amount")).isEqualTo("12000.00");
        // Unsigned, which is the reason the amount here is a suggestion and the
        // amount Prepare checks is the commitment.
        assertThat(parameters).doesNotContainKeys("sign_string", "sign_time", "SIGN_STRING");
        // A link is a string. Nothing was sent anywhere to produce it.
        assertThat(transport.calls()).isEmpty();
    }

    @Test
    @DisplayName("a Click binding with no merchant_id refuses to build a link")
    void clickLinkNeedsTheMerchantId() {
        givenIntent(CLICK_ORDER, PaymentProviderType.CLICK, UNBOUND_ENTITY);

        // Click documents merchant_id as mandatory. A link without it is either
        // rejected at my.click.uz or resolved against whichever merchant Click
        // infers, which would be another restaurant's account — so it is refused
        // rather than emitted.
        assertThatThrownBy(() -> checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link()))
                .isInstanceOfSatisfying(
                        PresentationFailure.Refused.class,
                        refused -> assertThat(refused.failureCode()).isEqualTo("CLICK_MERCHANT_ID_MISSING"));
    }

    @Test
    @DisplayName("Click's invoice push sends som, and stores the invoice id apart from a payment id")
    void clickInvoicePush() {
        givenIntent(CLICK_ORDER, PaymentProviderType.CLICK, CLICK_ENTITY);
        transport.answer(uz.horecaos.platform.integration.api.provider.ProviderOutcome.success(
                Map.of("error_code", 0, "error_note", "Success", "invoice_id", 4_444_555_666L), null));

        var session = checkout.openOrRePresent(
                TENANT,
                CLICK_ORDER,
                ACCOUNT,
                new PresentationRequest(PresentationKind.INVOICE_PUSH, null, null, "998901234567"));

        assertThat(session.presentationKind()).isEqualTo(PresentationKind.INVOICE_PUSH);
        assertThat(session.checkoutUrl()).isNull();
        assertThat(transport.paths()).containsExactly("/invoice/create");
        assertThat(transport.calls().getFirst().body()).containsEntry("amount", AMOUNT_SOM);

        // An invoice id names a request pushed to a phone; the payment_id that the
        // status, reversal and fiscal calls take arrives later and only if the
        // customer accepts. Writing the first where the second belongs would survive
        // every later COALESCE and send a reversal at an identifier Click does not
        // know.
        assertThat(attempts.find(TENANT, session.attemptId()).orElseThrow().externalPaymentId())
                .isNull();
        assertThat(invoiceIdOf(session.attemptId())).isEqualTo("4444555666");
    }

    @Test
    @DisplayName("a lost answer to the push is uncertain, is not retried, and keeps its key")
    void clickInvoicePushUncertainty() {
        givenIntent(CLICK_ORDER, PaymentProviderType.CLICK, CLICK_ENTITY);
        transport.answer(
                uz.horecaos.platform.integration.api.provider.ProviderOutcome.uncertain("TIMEOUT", "no answer in 30s"));

        assertThatThrownBy(() -> checkout.openOrRePresent(
                        TENANT,
                        CLICK_ORDER,
                        ACCOUNT,
                        new PresentationRequest(PresentationKind.INVOICE_PUSH, null, null, "998901234567")))
                .isInstanceOfSatisfying(
                        PresentationFailure.Uncertain.class,
                        lost -> assertThat(lost.failureCode()).isEqualTo("CLICK_INVOICE_UNCERTAIN"));

        // Exactly one call. invoice/create carries no idempotency key, so a retry is
        // a second invoice on somebody's phone against the same order.
        assertThat(transport.calls()).hasSize(1);

        PaymentAttempt attempt = onlyAttempt();
        assertThat(attempt.status()).isEqualTo(PaymentAttemptStatus.UNCERTAIN);
        assertThat(attempt.uncertain().orElseThrow().resolver()).isEqualTo(UncertaintyResolver.CLICK_STATUS_BY_MTI);
        // The resolver's two arguments survived, which is the entire reason the row
        // is committed before the call.
        assertThat(attempt.merchantTransId()).isNotBlank();
        assertThat(attempt.businessDate()).isEqualTo(BUSINESS_DATE);
    }

    // -----------------------------------------------------------------------
    // Payme's surface
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Payme's link is base64 of the documented payload, in tiyin, times one hundred once")
    void paymeCheckoutLink() {
        givenIntent(PAYME_ORDER, PaymentProviderType.PAYME, PAYME_ENTITY);

        var session = checkout.openOrRePresent(TENANT, PAYME_ORDER, ACCOUNT, PresentationRequest.link());

        assertThat(session.checkoutUrl()).startsWith(PAYME_CHECKOUT_HOST + "/");
        String encoded = session.checkoutUrl()
                .substring(PAYME_CHECKOUT_HOST.length() + 1)
                .replace("%2F", "/");
        String payload = new String(Base64.getDecoder().decode(encoded), StandardCharsets.US_ASCII);

        // The same fixture that produced 12000 on Click's som surface produces
        // 1200000 here, and the multiplication happens in exactly one place.
        assertThat(payload)
                .isEqualTo("m=" + PAYME_CASHBOX + ";ac.order_id=" + session.merchantTransId() + ";a=1200000");
        // The QR encodes the same string; there is no documented payme:// scheme.
        assertThat(session.qrPayload()).isEqualTo(session.checkoutUrl());
        assertThat(session.amountMinor()).isEqualTo(AMOUNT_SOM);
    }

    @Test
    @DisplayName("Payme refuses a push rather than quietly handing back a link")
    void paymeHasNoPush() {
        givenIntent(PAYME_ORDER, PaymentProviderType.PAYME, PAYME_ENTITY);

        // Downgrading silently would leave a caller believing a phone rang.
        assertThatThrownBy(() -> checkout.openOrRePresent(
                        TENANT,
                        PAYME_ORDER,
                        ACCOUNT,
                        new PresentationRequest(PresentationKind.INVOICE_PUSH, null, null, "998901234567")))
                .isInstanceOfSatisfying(
                        PresentationFailure.Refused.class,
                        refused -> assertThat(refused.failureCode()).isEqualTo("PAYME_PUSH_UNSUPPORTED"));
    }

    // -----------------------------------------------------------------------
    // Re-presentation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a customer who comes back gets the same attempt and the same link")
    void abandonedCheckoutIsRePresented() {
        givenIntent(CLICK_ORDER, PaymentProviderType.CLICK, CLICK_ENTITY);

        var first = checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link());
        var second = checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link());

        assertThat(second.attemptId()).isEqualTo(first.attemptId());
        assertThat(second.merchantTransId()).isEqualTo(first.merchantTransId());
        assertThat(second.checkoutUrl()).isEqualTo(first.checkoutUrl());
        assertThat(second.rePresented()).isTrue();
        assertThat(first.rePresented()).isFalse();

        // One row, and the count says the customer came back. Two attempts would be
        // two payable links against one order, which is a double charge waiting for
        // somebody to open both.
        assertThat(attempts.listForIntent(TENANT, onlyAttempt().intentId())).hasSize(1);
        assertThat(second.presentationCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a reserved attempt is re-presented; a captured one is not")
    void rePresentationStopsWhereMoneyStarts() {
        givenIntent(CLICK_ORDER, PaymentProviderType.CLICK, CLICK_ENTITY);
        var first = checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link());

        // The customer is on Click's page and Prepare has landed. Sending them back
        // to the same link is safe: Click's prepare id is a function of the order.
        moveTo(first.attemptId(), PaymentAttemptStatus.PRESENTED, PaymentAttemptStatus.RESERVED);
        assertThat(checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link())
                        .attemptId())
                .isEqualTo(first.attemptId());

        moveTo(first.attemptId(), PaymentAttemptStatus.RESERVED, PaymentAttemptStatus.CAPTURED);
        assertThatThrownBy(() -> checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link()))
                .isInstanceOfSatisfying(
                        PaymentCheckoutService.CheckoutRefusedException.class,
                        refused -> assertThat(refused.code()).isEqualTo("ALREADY_PAID"));
    }

    @Test
    @DisplayName("an uncertain attempt is never presented again")
    void uncertaintyBlocksTheSurface() {
        givenIntent(CLICK_ORDER, PaymentProviderType.CLICK, CLICK_ENTITY);
        transport.answer(
                uz.horecaos.platform.integration.api.provider.ProviderOutcome.uncertain("TIMEOUT", "no answer in 30s"));
        assertThatThrownBy(() -> checkout.openOrRePresent(
                        TENANT,
                        CLICK_ORDER,
                        ACCOUNT,
                        new PresentationRequest(PresentationKind.INVOICE_PUSH, null, null, "998901234567")))
                .isInstanceOf(PresentationFailure.Uncertain.class);

        // A charge that may already have happened. Showing another surface for it is
        // the retry the whole module exists to prevent.
        assertThatThrownBy(() -> checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link()))
                .isInstanceOfSatisfying(
                        PaymentCheckoutService.CheckoutRefusedException.class,
                        refused -> assertThat(refused.code()).isEqualTo("PAYMENT_IN_DOUBT"));
    }

    @Test
    @DisplayName("a push is never repeated on the customer's own request")
    void aPushIsNotRepeatable() {
        givenIntent(CLICK_ORDER, PaymentProviderType.CLICK, CLICK_ENTITY);
        transport.answer(uz.horecaos.platform.integration.api.provider.ProviderOutcome.success(
                Map.of("error_code", 0, "invoice_id", 1L), null));
        checkout.openOrRePresent(
                TENANT,
                CLICK_ORDER,
                ACCOUNT,
                new PresentationRequest(PresentationKind.INVOICE_PUSH, null, null, "998901234567"));

        assertThatThrownBy(() -> checkout.openOrRePresent(
                        TENANT,
                        CLICK_ORDER,
                        ACCOUNT,
                        new PresentationRequest(PresentationKind.INVOICE_PUSH, null, null, "998901234567")))
                .isInstanceOfSatisfying(
                        PaymentCheckoutService.CheckoutRefusedException.class,
                        refused -> assertThat(refused.code()).isEqualTo("PRESENTATION_NOT_REPEATABLE"));
        assertThat(transport.calls()).hasSize(1);
    }

    @Test
    @DisplayName("a merchant account that has moved under a live attempt refuses a second surface")
    void aRepointedBindingIsNotSilentlyUsed() {
        givenIntent(CLICK_ORDER, PaymentProviderType.CLICK, CLICK_ENTITY);
        var first = checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link());

        // The state a retired-and-replaced binding leaves behind: the attempt names
        // the account it was opened against, and resolution now answers a different
        // one. A surface built from the new account would send the callback to an
        // endpoint whose binding cannot match the id it carries, and the customer
        // would pay into an "unknown order".
        jdbc.sql("UPDATE payments.payment_attempts SET merchant_binding_id = :other " + "WHERE id = :id")
                .param("other", CLICK_LINKLESS_BINDING)
                .param("id", first.attemptId())
                .update();

        assertThatThrownBy(() -> checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link()))
                .isInstanceOfSatisfying(
                        PaymentCheckoutService.CheckoutRefusedException.class,
                        refused -> assertThat(refused.code()).isEqualTo("BINDING_CHANGED"));
    }

    @Test
    @DisplayName("a terminal attempt lets the customer try again")
    void aFailedAttemptIsNotABlock() {
        givenIntent(CLICK_ORDER, PaymentProviderType.CLICK, CLICK_ENTITY);
        var first = checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link());
        moveTo(first.attemptId(), PaymentAttemptStatus.PRESENTED, PaymentAttemptStatus.FAILED);

        var second = checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link());

        assertThat(second.attemptId()).isNotEqualTo(first.attemptId());
        assertThat(second.merchantTransId()).isNotEqualTo(first.merchantTransId());
        assertThat(second.rePresented()).isFalse();
    }

    @Test
    @DisplayName("the database, not this class, is what refuses the second open attempt")
    void theIndexIsTheAuthority() {
        UUID intentId = givenIntent(CLICK_ORDER, PaymentProviderType.CLICK, CLICK_ENTITY);
        checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link());

        // The race the read in PaymentAttemptService.open cannot cover on its own:
        // two concurrent requests both see no attempt and both insert.
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO payments.payment_attempts (
                    id, tenant_id, intent_id, provider_type, merchant_binding_id,
                    merchant_trans_id, business_date, requested_amount_minor, currency, status)
                VALUES (:id, :tenantId, :intentId, 'CLICK', :bindingId, :mti, :businessDate,
                    :amount, 'UZS', 'INITIATED')
                """)
                        .param("id", UUID.randomUUID())
                        .param("tenantId", TENANT)
                        .param("intentId", intentId)
                        .param("bindingId", CLICK_BINDING)
                        .param("mti", UUID.randomUUID().toString().replace("-", ""))
                        .param("businessDate", BUSINESS_DATE)
                        .param("amount", AMOUNT_SOM)
                        .update())
                .hasMessageContaining("ux_payment_attempt_open_per_intent");
    }

    // -----------------------------------------------------------------------
    // Refusals the storefront has to branch on
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("another customer's order reads as no such order")
    void anotherCustomersOrderIsInvisible() {
        givenIntent(CLICK_ORDER, PaymentProviderType.CLICK, CLICK_ENTITY);

        // Not "forbidden": an endpoint that distinguishes the two lets anyone probe
        // which order ids exist.
        assertThatThrownBy(() ->
                        checkout.openOrRePresent(TENANT, CLICK_ORDER, UUID.randomUUID(), PresentationRequest.link()))
                .isInstanceOfSatisfying(
                        PaymentCheckoutService.CheckoutRefusedException.class,
                        refused -> assertThat(refused.code()).isEqualTo("ORDER_NOT_FOUND"));
        assertThat(attempts.listForIntent(
                        TENANT,
                        intents.findLiveForOrder(TENANT, CLICK_ORDER)
                                .orElseThrow()
                                .id()))
                .isEmpty();
    }

    @Test
    @DisplayName("a cash order has no surface and says so")
    void cashHasNothingToPresent() {
        intents.insert(new PaymentIntent(
                UUID.randomUUID(),
                TENANT,
                CLICK_ORDER,
                BRAND,
                LOCATION,
                null,
                CLICK_ENTITY,
                PaymentTender.CASH,
                PaymentMethod.CASH,
                null,
                new SomAmount(AMOUNT_SOM, UZS),
                PaymentIntentStatus.PENDING,
                CaptureTiming.ON_HANDOVER,
                UUID.randomUUID().toString(),
                1,
                CLOCK.instant(),
                null));

        assertThatThrownBy(() -> checkout.openOrRePresent(TENANT, CLICK_ORDER, ACCOUNT, PresentationRequest.link()))
                .isInstanceOfSatisfying(
                        PaymentCheckoutService.CheckoutRefusedException.class,
                        refused -> assertThat(refused.code()).isEqualTo("NOT_PAYABLE_ONLINE"));
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private UUID givenIntent(UUID orderId, PaymentProviderType provider, UUID legalEntityId) {
        UUID intentId = UUID.randomUUID();
        intents.insert(new PaymentIntent(
                intentId,
                TENANT,
                orderId,
                BRAND,
                LOCATION,
                null,
                legalEntityId,
                PaymentTender.PROVIDER,
                provider == PaymentProviderType.CLICK ? PaymentMethod.CLICK : PaymentMethod.PAYME,
                provider,
                new SomAmount(AMOUNT_SOM, UZS),
                PaymentIntentStatus.PENDING,
                CaptureTiming.BEFORE_CONFIRMATION,
                UUID.randomUUID().toString(),
                1,
                CLOCK.instant(),
                null));
        return intentId;
    }

    private PaymentAttempt onlyAttempt() {
        List<UUID> ids = jdbc.sql("SELECT id FROM payments.payment_attempts")
                .query(UUID.class)
                .list();
        assertThat(ids).hasSize(1);
        return attempts.find(TENANT, ids.getFirst()).orElseThrow();
    }

    private String invoiceIdOf(UUID attemptId) {
        return jdbc.sql("SELECT external_invoice_id FROM payments.payment_attempts WHERE id = :id")
                .param("id", attemptId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private void moveTo(UUID attemptId, PaymentAttemptStatus from, PaymentAttemptStatus to) {
        assertThat(attempts.transition(TENANT, attemptId, from, to, null, null, null, null, CLOCK.instant()))
                .isPresent();
    }

    private static Map<String, String> queryOf(String url) {
        String query = url.substring(url.indexOf('?') + 1);
        return java.util.Arrays.stream(query.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        pair -> pair[0], pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    private static void seedTenantAndMerchantAccounts() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status)
                VALUES (:id, 'checkoutsurface', 'Checkout Surface LLC', 'Checkout Surface', 'UZS',
                    'Asia/Tashkent', 'ACTIVE')
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'BRAND1', 'brand-one', 'Brand One', 'ACTIVE')
                """).param("id", BRAND).param("tenantId", TENANT).update();

        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES ('click-sandbox', 'PAYMENT', 'CLICK', 'https://api.click.uz/v2/merchant',
                    false, 'api.click.uz')
                """).update();

        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES ('payme-sandbox', 'PAYMENT', 'PAYME', :baseUrl, false, 'test.paycom.uz')
                """).param("baseUrl", PAYME_CHECKOUT_HOST).update();

        installation(CLICK_INSTALLATION, "CLICK", "click-sandbox", "Click");
        installation(PAYME_INSTALLATION, "PAYME", "payme-sandbox", "Payme");

        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", INTEGRATION_BINDING)
                .param("tenantId", TENANT)
                .param("installationId", CLICK_INSTALLATION)
                .param("brandId", BRAND)
                .update();

        merchantBinding(
                CLICK_BINDING,
                CLICK_ENTITY,
                "CLICK",
                CLICK_INSTALLATION,
                CLICK_SERVICE_ID,
                CLICK_MERCHANT_USER_ID,
                CLICK_MERCHANT_ID,
                "click-brandone");
        // The same provider for a second legal entity, registered before the
        // merchant_id column existed. Its link cannot be built and its push can.
        merchantBinding(
                CLICK_LINKLESS_BINDING,
                UNBOUND_ENTITY,
                "CLICK",
                CLICK_INSTALLATION,
                "54321",
                "4444",
                null,
                "click-brandtwo");
        merchantBinding(
                PAYME_BINDING,
                PAYME_ENTITY,
                "PAYME",
                PAYME_INSTALLATION,
                PAYME_CASHBOX,
                null,
                null,
                "payme-cashbox-one");
    }

    private static void installation(UUID id, String providerType, String environment, String displayName) {
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'PAYMENT', :providerType, :environment, :displayName,
                    'ACTIVE', :secretReference)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("providerType", providerType)
                .param("environment", environment)
                .param("displayName", displayName)
                .param("secretReference", "horecaos:test:provider_payment:tenant:" + providerType.toLowerCase())
                .update();
    }

    /**
     * A legal entity for the fixture to hang bindings and documents from.
     *
     * <p>V0053 turned {@code legal_entity_id} into a real foreign key on both
     * {@code payments.merchant_bindings} and {@code fiscal.fiscal_documents}. The
     * columns existed before it and referenced nothing, which is precisely why a
     * restaurant had no legal identity to issue a receipt under. A fixture that
     * invents the id would be asserting against a world the schema no longer
     * permits.
     */
    private static void seedLegalEntity(UUID id, String code, String tin) {
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :tenantId, :code, :legalName, :tin, 'ACTIVE')
                ON CONFLICT DO NOTHING
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("code", code)
                .param("legalName", code + " MCHJ")
                .param("tin", tin)
                .update();
    }

    private static void merchantBinding(
            UUID id,
            UUID legalEntityId,
            String providerType,
            UUID installationId,
            String account,
            String user,
            String merchantId,
            String segment) {
        Map<String, Object> parameters = new java.util.HashMap<>();
        parameters.put("id", id);
        parameters.put("tenantId", TENANT);
        seedLegalEntity(
                legalEntityId,
                "LE-" + Integer.toHexString(legalEntityId.hashCode()).toUpperCase(java.util.Locale.ROOT),
                String.format("%09d", Math.floorMod(legalEntityId.hashCode(), 1_000_000_000)));
        parameters.put("legalEntityId", legalEntityId);
        parameters.put("providerType", providerType);
        parameters.put("installationId", installationId);
        parameters.put("bindingId", INTEGRATION_BINDING);
        parameters.put("account", account);
        parameters.put("user", user);
        parameters.put("merchantId", merchantId);
        parameters.put("segment", segment);
        parameters.put("secretReference", "horecaos:test:provider_payment:tenant:" + providerType.toLowerCase());
        parameters.put("effectiveFrom", LocalDate.of(2026, 1, 1));

        jdbc.sql("""
                INSERT INTO payments.merchant_bindings (id, tenant_id, legal_entity_id,
                    provider_type, installation_id, binding_id, merchant_account_reference,
                    merchant_user_reference, merchant_id_reference, secret_reference,
                    callback_path_segment, supports_reversal, supports_partner_fiscalization,
                    status, effective_from)
                VALUES (:id, :tenantId, :legalEntityId, :providerType, :installationId, :bindingId,
                    :account, :user, :merchantId, :secretReference, :segment, true, true, 'ACTIVE',
                    :effectiveFrom)
                """).params(parameters).update();
    }

    private static void seedOrders() {
        UUID channel = UUID.randomUUID();
        UUID catalog = UUID.randomUUID();
        UUID publication = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status)
                VALUES (:id, :tenantId, :brandId, 'LOC1', 'location-one', 'Location One',
                    'Asia/Tashkent', 'ACTIVE')
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status)
                VALUES (:id, :tenantId, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channel).param("tenantId", TENANT).update();

        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MENU', 'Menu', 'ACTIVE')
                """)
                .param("id", catalog)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publication)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalog)
                .update();

        order(CLICK_ORDER, "31", channel, publication);
        order(PAYME_ORDER, "32", channel, publication);
    }

    private static void order(UUID orderId, String number, UUID channel, UUID publication) {
        UUID quote = UUID.randomUUID();
        UUID cart = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency, status,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', 'ACTIVE', :publicationId,
                    1, 'hash', 12000, 0, 12000, now() + interval '1 day')
                """)
                .param("id", quote)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("publicationId", publication)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    guest_reference_hash, fulfillment_mode, currency, status, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'guest-hash',
                    'DELIVERY', 'UZS', 'CHECKOUT_IN_PROGRESS', :quoteId, 'hash', :publicationId,
                    now() + interval '1 day')
                """)
                .param("id", cart)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("channelId", channel)
                .param("quoteId", quote)
                .param("publicationId", publication)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (
                    id, public_order_number, tenant_id, brand_id, location_id, channel_id,
                    channel_code_snapshot, guest_reference_hash, fulfillment_mode,
                    acceptance_mode_snapshot, approval_channel_snapshot, status, currency,
                    subtotal_minor, tax_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key)
                VALUES (
                    :id, :number, :tenantId, :brandId, :locationId, :channelId, 'WEB',
                    'guest-hash', 'DELIVERY', 'AUTO_CONFIRM', 'NONE', 'PAYMENT_AUTHORIZING', 'UZS',
                    12000, 0, 12000, :quoteId, 'hash', :publicationId, :cartId, :idempotencyKey)
                """)
                .param("id", orderId)
                .param("number", number)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("channelId", channel)
                .param("quoteId", quote)
                .param("publicationId", publication)
                .param("cartId", cart)
                .param("idempotencyKey", UUID.randomUUID().toString())
                .update();
    }

    /** The ordering read, answered from the rows this suite seeded. */
    private static final class SeededOrders implements OrderDirectory {

        @Override
        public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
            if (!TENANT.equals(tenantId) || (!CLICK_ORDER.equals(orderId) && !PAYME_ORDER.equals(orderId))) {
                return Optional.empty();
            }
            return Optional.of(new OrderSummary(
                    orderId,
                    tenantId,
                    BRAND,
                    LOCATION,
                    "31",
                    ACCOUNT,
                    "guest-hash",
                    "PAYMENT_AUTHORIZING",
                    UZS,
                    AMOUNT_SOM,
                    1));
        }
    }

    /** Where the Payme cashbox points. Never defaulted to the production host. */
    private static final class FixedInstallations implements ProviderInstallationLookup {

        @Override
        public Optional<BindingRef> primaryBinding(
                UUID tenantId, UUID brandId, UUID locationId, String capabilityCode) {
            return Optional.empty();
        }

        @Override
        public List<BindingRef> candidateBindings(UUID tenantId, UUID brandId, UUID locationId, String capabilityCode) {
            return List.of();
        }

        @Override
        public Optional<InstallationSnapshot> installation(UUID tenantId, UUID installationId) {
            return Optional.of(new InstallationSnapshot(
                    installationId,
                    ProviderCategory.PAYMENT,
                    "PAYME",
                    "payme-sandbox",
                    PAYME_CHECKOUT_HOST,
                    "ACTIVE",
                    "horecaos:test:provider_payment:tenant:payme",
                    "1"));
        }
    }

    /** The outbound half, faked: what the adapter sent, and what it was told. */
    private static final class RecordingTransport implements MerchantApiTransport {

        private final List<MerchantApiCall> calls = new CopyOnWriteArrayList<>();
        private final List<uz.horecaos.platform.integration.api.provider.ProviderOutcome> answers =
                new CopyOnWriteArrayList<>();

        void answer(uz.horecaos.platform.integration.api.provider.ProviderOutcome outcome) {
            answers.add(outcome);
        }

        List<MerchantApiCall> calls() {
            return List.copyOf(calls);
        }

        List<String> paths() {
            return calls.stream().map(MerchantApiCall::path).toList();
        }

        @Override
        public uz.horecaos.platform.integration.api.provider.ProviderOutcome exchange(MerchantApiCall call) {
            calls.add(call);
            int index = calls.size() - 1;
            return index < answers.size()
                    ? answers.get(index)
                    : uz.horecaos.platform.integration.api.provider.ProviderOutcome.uncertain(
                            "NO_ANSWER_QUEUED", "the test queued no answer");
        }
    }
}
