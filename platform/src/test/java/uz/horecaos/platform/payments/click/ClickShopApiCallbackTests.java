package uz.horecaos.platform.payments.click;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.payment.MerchantApiCall;
import uz.horecaos.platform.integration.api.payment.MerchantApiTransport;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.payments.application.CapturedMoneyPort;
import uz.horecaos.platform.payments.application.PaymentAttemptService;
import uz.horecaos.platform.payments.domain.CaptureTiming;
import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.FiscalDocumentType;
import uz.horecaos.platform.payments.domain.FiscalReason;
import uz.horecaos.platform.payments.domain.FiscalReceiptLine;
import uz.horecaos.platform.payments.domain.FiscalStatus;
import uz.horecaos.platform.payments.domain.FiscalSubmission;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentAttemptStatus;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PaymentIntentStatus;
import uz.horecaos.platform.payments.domain.PaymentMethod;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.PaymentTender;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.infrastructure.click.ClickCallbackProcessor;
import uz.horecaos.platform.payments.infrastructure.click.ClickFiscalAdapter;
import uz.horecaos.platform.payments.infrastructure.click.ClickMerchantApi;
import uz.horecaos.platform.payments.infrastructure.click.ClickPaymentAdapter;
import uz.horecaos.platform.payments.infrastructure.click.ClickPrepareId;
import uz.horecaos.platform.payments.infrastructure.click.ClickSignature;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentBindingResolver;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentTransactionStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcProviderCallbackStore;
import uz.horecaos.platform.payments.web.click.ClickShopApiController;
import uz.horecaos.platform.payments.web.click.ClickShopApiResponse;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Click's SHOP API, end to end against the real schema (ADR 0013).
 *
 * <p>These are the properties that decide whether a customer is charged correctly:
 * the signature is verified over the strings Click sent, the check order is the
 * documented one with {@code -4} before {@code -2}, a replay credits nothing twice,
 * and an order that cannot be fulfilled is answered {@code error: 0} and reversed
 * rather than refused.
 *
 * <p>Driven through the controller so that the JSON Click actually receives is part
 * of what is asserted, and against a real database so that the idempotency claims
 * are made by the constraints rather than by the test.
 */
class ClickShopApiCallbackTests {

    private static final String SECRET = "SECRET123";
    private static final String SERVICE_ID = "12345";
    private static final String SEGMENT = "click-brandone";
    private static final String CLICK_TRANS_ID = "3737503";
    private static final String CLICK_PAYDOC_ID = "987654321";
    private static final String AMOUNT = "1000.00";
    private static final long AMOUNT_SOM = 1000L;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID LEGAL_ENTITY = UUID.randomUUID();
    private static final UUID BINDING = UUID.randomUUID();
    private static final UUID OTHER_BINDING = UUID.randomUUID();
    private static final UUID OTHER_LEGAL_ENTITY = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();
    private static final UUID INTEGRATION_BINDING = UUID.randomUUID();

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-22T09:03:11Z"), ZoneOffset.UTC);
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 22);

    private static TestDatabase.Handle db;
    private static JdbcClient jdbc;
    private static TransactionTemplate unitOfWork;

    private JdbcPaymentIntentStore intents;
    private JdbcPaymentAttemptStore attempts;
    private JdbcProviderCallbackStore callbacks;
    private RecordingTransport transport;
    private ClickPaymentAdapter clickAdapter;
    private ClickFiscalAdapter fiscalAdapter;
    private ClickShopApiController controller;

    private static final UUID ORDER = UUID.randomUUID();

    private UUID intentId;
    private UUID attemptId;
    private String merchantTransId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the payments schema");
        db = TestDatabase.migrated();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
        jdbc = JdbcClient.create(dataSource);
        unitOfWork = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        seedTenantAndMerchantAccount();
        seedOrder();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void wire() {
        jdbc.sql("DELETE FROM payments.provider_callbacks").update();
        jdbc.sql("DELETE FROM payments.fiscal_documents").update();
        jdbc.sql("DELETE FROM payments.payment_transactions").update();
        jdbc.sql("DELETE FROM payments.payment_attempts").update();
        jdbc.sql("DELETE FROM payments.payment_intents").update();

        intents = new JdbcPaymentIntentStore(jdbc);
        attempts = new JdbcPaymentAttemptStore(jdbc);
        callbacks = new JdbcProviderCallbackStore(jdbc);
        JdbcPaymentTransactionStore transactions = new JdbcPaymentTransactionStore(jdbc);
        JdbcPaymentBindingResolver bindings = new JdbcPaymentBindingResolver(jdbc);

        transport = new RecordingTransport();
        ClickMerchantApi click = new ClickMerchantApi(transport, CLOCK);
        clickAdapter = new ClickPaymentAdapter(click, CLOCK);
        fiscalAdapter = new ClickFiscalAdapter(click, attempts, CLOCK);

        PaymentAttemptService attemptService = new PaymentAttemptService(
                intents,
                attempts,
                transactions,
                bindings,
                List.of(clickAdapter),
                CapturedMoneyPort.NONE,
                unitOfWork,
                CLOCK);

        controller = new ClickShopApiController(new ClickCallbackProcessor(
                bindings,
                new uz.horecaos.platform.payments.infrastructure.RotationAwareSecrets(new FixedSecretResolver(), CLOCK),
                attempts,
                intents,
                callbacks,
                attemptService,
                clickAdapter,
                CLOCK));

        seedIntentAndAttempt();
    }

    // -----------------------------------------------------------------------
    // Prepare
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Prepare reserves and answers a prepare id that is a function of the order")
    void prepareReserves() {
        ClickShopApiResponse response = controller.prepare(SEGMENT, signedPrepare(AMOUNT));

        assertThat(response.error()).isZero();
        assertThat(response.errorNote()).isEqualTo("Success");
        assertThat(response.merchantPrepareId()).isEqualTo(ClickPrepareId.forAttempt(attemptId));
        assertThat(response.merchantConfirmId()).isNull();
        assertThat(status()).isEqualTo(PaymentAttemptStatus.RESERVED);
        assertThat(signatureFailures()).isZero();
        assertThat(recordedCallbacks()).isEqualTo(1);
    }

    @Test
    @DisplayName("a signature over a reformatted amount is refused with -1")
    void reformattingTheAmountFailsTheSignature() {
        // The defect this whole integration is most likely to ship with. Click sent
        // "1000.00"; signing the same figure rendered as "1000" produces a different
        // MD5, and treating the amount as a number anywhere before the digest is
        // taken makes every callback fail with -1 SIGN CHECK FAILED!.
        Map<String, String> form = prepareForm(AMOUNT);
        form.put(
                "sign_string",
                ClickSignature.prepare(SECRET, CLICK_TRANS_ID, SERVICE_ID, merchantTransId, "1000", "0", signTime()));

        ClickShopApiResponse response = controller.prepare(SEGMENT, form);

        assertThat(response.error()).isEqualTo(-1);
        assertThat(response.errorNote()).isEqualTo("SIGN CHECK FAILED!");
        // Nothing was touched, and the failure is on the record: an endpoint whose
        // only authentication is this digest has no other warning available.
        assertThat(status()).isEqualTo(PaymentAttemptStatus.PRESENTED);
        assertThat(signatureFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("a repeated Prepare returns the same id and reserves once")
    void prepareIsIdempotent() {
        ClickShopApiResponse first = controller.prepare(SEGMENT, signedPrepare(AMOUNT));
        ClickShopApiResponse second = controller.prepare(SEGMENT, signedPrepare(AMOUNT));

        assertThat(second.merchantPrepareId()).isEqualTo(first.merchantPrepareId());
        assertThat(second.error()).isZero();
        assertThat(transactionsOfType("RESERVE")).isEqualTo(1);
    }

    @Test
    @DisplayName("an amount that is not the attempt's is refused with -2")
    void prepareEnforcesTheAmount() {
        // The payment link the customer followed is unsigned, so the figure Click
        // reports here is attacker-influenced. The attempt is the authority.
        ClickShopApiResponse response = controller.prepare(SEGMENT, signedPrepare("1.00"));

        assertThat(response.error()).isEqualTo(-2);
        assertThat(status()).isEqualTo(PaymentAttemptStatus.PRESENTED);
    }

    @Test
    @DisplayName("an unknown merchant_trans_id is -5 and a missing field is -8")
    void lookupAndShapeFailures() {
        Map<String, String> unknownOrder = prepareForm(AMOUNT);
        unknownOrder.put("merchant_trans_id", "nothing-here");
        unknownOrder.put(
                "sign_string",
                ClickSignature.prepare(SECRET, CLICK_TRANS_ID, SERVICE_ID, "nothing-here", AMOUNT, "0", signTime()));

        Map<String, String> missingSignTime = signedPrepare(AMOUNT);
        missingSignTime.remove("sign_time");

        assertThat(controller.prepare(SEGMENT, unknownOrder).error()).isEqualTo(-5);
        assertThat(controller.prepare(SEGMENT, missingSignTime).error()).isEqualTo(-8);
    }

    @Test
    @DisplayName("a Complete posted to the Prepare endpoint is -3")
    void theActionMustMatchTheEndpoint() {
        controller.prepare(SEGMENT, signedPrepare(AMOUNT));

        assertThat(controller.prepare(SEGMENT, signedComplete(AMOUNT)).error()).isEqualTo(-3);
        assertThat(status()).isEqualTo(PaymentAttemptStatus.RESERVED);
    }

    @Test
    @DisplayName("an unknown binding segment is answered without touching anything")
    void unknownBindingIsRefused() {
        assertThat(controller.prepare("not-a-binding", signedPrepare(AMOUNT)).error())
                .isEqualTo(-5);
        assertThat(recordedCallbacks()).isZero();
    }

    // -----------------------------------------------------------------------
    // Complete
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Complete credits the order and marks the intent paid")
    void completeCredits() {
        controller.prepare(SEGMENT, signedPrepare(AMOUNT));

        ClickShopApiResponse response = controller.complete(SEGMENT, signedComplete(AMOUNT));

        assertThat(response.error()).isZero();
        assertThat(response.merchantConfirmId()).isEqualTo(ClickPrepareId.forAttempt(attemptId));
        assertThat(response.merchantPrepareId()).isNull();
        assertThat(status()).isEqualTo(PaymentAttemptStatus.CAPTURED);
        assertThat(intents.find(TENANT, intentId).orElseThrow().status()).isEqualTo(PaymentIntentStatus.PAID);
        // click_paydoc_id is evidence and not a payment id: nothing documents that
        // it is what the reversal and fiscalization paths want.
        assertThat(attempts.find(TENANT, attemptId).orElseThrow().externalDocumentId())
                .isEqualTo(CLICK_PAYDOC_ID);
        assertThat(transport.calls()).isEmpty();
    }

    @Test
    @DisplayName("a replayed Complete answers -4 and credits nothing twice")
    void completeIsIdempotent() {
        controller.prepare(SEGMENT, signedPrepare(AMOUNT));
        controller.complete(SEGMENT, signedComplete(AMOUNT));

        ClickShopApiResponse replay = controller.complete(SEGMENT, signedComplete(AMOUNT));

        assertThat(replay.error()).isEqualTo(-4);
        assertThat(replay.errorNote()).isEqualTo("Already paid");
        // -4 carries the id the transaction was given the first time, so Click's
        // record of the replay matches its record of the original.
        assertThat(replay.merchantConfirmId()).isEqualTo(ClickPrepareId.forAttempt(attemptId));
        assertThat(transactionsOfType("CAPTURE")).isEqualTo(1);
    }

    @Test
    @DisplayName("-4 is answered before -2, so a replay survives an adjusted total")
    void alreadyPaidPrecedesTheAmountCheck() {
        controller.prepare(SEGMENT, signedPrepare(AMOUNT));
        controller.complete(SEGMENT, signedComplete(AMOUNT));

        // A replayed Complete for a credited order must report "settled", which
        // Click understands, rather than trip an amount comparison against a total
        // that has since moved.
        ClickShopApiResponse replay = controller.complete(SEGMENT, signedComplete("999.00"));

        assertThat(replay.error()).isEqualTo(-4);
        assertThat(transactionsOfType("CAPTURE")).isEqualTo(1);
    }

    @Test
    @DisplayName("a merchant_prepare_id this attempt was never told is -6")
    void completeChecksThePrepareId() {
        controller.prepare(SEGMENT, signedPrepare(AMOUNT));

        String foreignPrepareId = Integer.toString(ClickPrepareId.forAttempt(attemptId) + 1);
        Map<String, String> form = completeForm(AMOUNT);
        form.put("merchant_prepare_id", foreignPrepareId);
        form.put(
                "sign_string",
                ClickSignature.complete(
                        SECRET,
                        CLICK_TRANS_ID,
                        SERVICE_ID,
                        merchantTransId,
                        foreignPrepareId,
                        AMOUNT,
                        "1",
                        signTime()));

        assertThat(controller.complete(SEGMENT, form).error()).isEqualTo(-6);
        assertThat(status()).isEqualTo(PaymentAttemptStatus.RESERVED);
    }

    @Test
    @DisplayName("a negative error from Click voids the payment and answers -9")
    void clickSideFailureCancels() {
        controller.prepare(SEGMENT, signedPrepare(AMOUNT));

        Map<String, String> form = completeForm(AMOUNT);
        form.put("error", "-5017");
        form.put("error_note", "Something failed on Click's side");

        ClickShopApiResponse response = controller.complete(SEGMENT, form);

        assertThat(response.error()).isEqualTo(-9);
        assertThat(response.errorNote()).isEqualTo("Transaction cancelled");
        assertThat(status()).isEqualTo(PaymentAttemptStatus.CANCELLED);
        assertThat(transactionsOfType("CAPTURE")).isZero();
    }

    @Test
    @DisplayName("a Complete against a cancelled attempt stays -9")
    void cancelledStaysCancelled() {
        controller.prepare(SEGMENT, signedPrepare(AMOUNT));
        Map<String, String> failure = completeForm(AMOUNT);
        failure.put("error", "-5017");
        controller.complete(SEGMENT, failure);

        assertThat(controller.complete(SEGMENT, signedComplete(AMOUNT)).error()).isEqualTo(-9);
        assertThat(status()).isEqualTo(PaymentAttemptStatus.CANCELLED);
    }

    @Test
    @DisplayName("a Complete with no reservation recorded still credits the customer")
    void completeWithoutAPrepare() {
        // Click only sends Complete after a successful Prepare, so reaching here
        // means HorecaOS's own reservation write did not survive. Refusing would leave
        // a charged customer uncredited.
        ClickShopApiResponse response = controller.complete(SEGMENT, signedComplete(AMOUNT));

        assertThat(response.error()).isZero();
        assertThat(status()).isEqualTo(PaymentAttemptStatus.CAPTURED);
        assertThat(transactionsOfType("RESERVE")).isEqualTo(1);
    }

    @Test
    @DisplayName("an unfulfillable order is answered 0 and then reversed")
    void businessFailureIsNeverReportedThroughComplete() {
        controller.prepare(SEGMENT, signedPrepare(AMOUNT));
        // The order was cancelled while the customer was paying.
        intents.transition(
                TENANT, intentId, PaymentIntentStatus.AUTHORIZING, PaymentIntentStatus.CANCELLED, 1, CLOCK.instant());
        transport.answer(ProviderOutcome.success(Map.of("error_code", 0, "payment_id", 777L), null));
        transport.answer(ProviderOutcome.success(Map.of("error_code", 0), null));

        ClickShopApiResponse response = controller.complete(SEGMENT, signedComplete(AMOUNT));

        // Answer 0, then reverse. Returning an error here would leave the customer
        // charged and uncredited while Click retried and finally escalated to its
        // own support.
        assertThat(response.error()).isZero();
        assertThat(transport.paths()).contains("/payment/reversal/12345/777");
        assertThat(status()).isEqualTo(PaymentAttemptStatus.REVERSED);
        assertThat(transactionsOfType("CAPTURE")).isEqualTo(1);
        assertThat(transactionsOfType("REVERSE")).isEqualTo(1);
    }

    @Test
    @DisplayName("a lost reversal leaves the attempt uncertain rather than retried")
    void aLostReversalIsUncertain() {
        controller.prepare(SEGMENT, signedPrepare(AMOUNT));
        intents.transition(
                TENANT, intentId, PaymentIntentStatus.AUTHORIZING, PaymentIntentStatus.CANCELLED, 1, CLOCK.instant());
        transport.answer(ProviderOutcome.success(Map.of("error_code", 0, "payment_id", 777L), null));
        transport.answer(ProviderOutcome.uncertain("READ_TIMEOUT", "no response"));

        assertThat(controller.complete(SEGMENT, signedComplete(AMOUNT)).error()).isZero();
        assertThat(status()).isEqualTo(PaymentAttemptStatus.UNCERTAIN);
        assertThat(attempts.find(TENANT, attemptId).orElseThrow().uncertain()).isPresent();
    }

    // -----------------------------------------------------------------------
    // Fiscalization
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("submit_items sends tiyin, and Price is the line total")
    void fiscalLinesAreTiyinLineTotals() {
        captureWithPaymentId("777");
        transport.answer(ProviderOutcome.success(Map.of("error_code", 0), null));
        transport.answer(ProviderOutcome.success(
                Map.of(
                        "paymentId",
                        777L,
                        "qrCodeURL",
                        "https://ofd.soliq.uz/epi?t=EZ000000000030&r=123456789" + "&c=20221028171340&s=854971301623"),
                null));

        FiscalSubmission submission = fiscalAdapter.submit(document(), binding());

        MerchantApiCall submitted = transport.calls().getFirst();
        assertThat(submitted.path()).isEqualTo("/payment/ofd_data/submit_items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) submitted.body().get("items");
        // Two units at 400 som: the line total is 800 som, which is 80,000 tiyin.
        // Click's Price is the line total and Payme's price is the unit price — the
        // same word, a factor of quantity apart.
        assertThat(items.getFirst())
                .containsEntry("Price", 80_000L)
                .containsEntry("GoodPrice", 40_000L)
                .containsEntry("Amount", 2)
                .containsEntry("VAT", 8_000L)
                .containsEntry("SPIC", "01234567890123456");
        // The payment call for this same payment went out in som.
        assertThat(submitted.body())
                .containsEntry("received_card", 80_000L)
                .containsEntry("received_cash", 0L)
                .containsEntry("received_ecash", 0L);
        assertThat(submission.status()).isEqualTo(FiscalStatus.ISSUED);
        assertThat(submission.fiscalEvidence().orElseThrow().receiptReference()).isEqualTo("123456789");
    }

    @Test
    @DisplayName("a resubmission reads ofd_data back before sending items again")
    void aSecondSubmissionReadsBackFirst() {
        captureWithPaymentId("777");
        // Whether a second submit_items for one payment_id rejects, replaces or
        // duplicates the receipt is undocumented and is an open question with
        // CLICK. A populated qrCodeURL means the first submission worked, whatever
        // the lost response would have said, and a duplicate document with a tax
        // authority cannot be withdrawn afterwards.
        transport.answer(ProviderOutcome.success(
                Map.of(
                        "paymentId",
                        777L,
                        "qrCodeURL",
                        "https://ofd.soliq.uz/epi?t=EZ000000000030&r=123456789" + "&c=20221028171340&s=854971301623"),
                null));

        FiscalSubmission submission = fiscalAdapter.submit(submittedDocument(), binding());

        assertThat(transport.paths()).containsExactly("/payment/ofd_data/12345/777");
        assertThat(submission.status()).isEqualTo(FiscalStatus.ISSUED);
    }

    @Test
    @DisplayName("fiscalization cannot precede capture")
    void noPaymentIdMeansNoSubmission() {
        // submit_items takes Click's payment_id, which does not exist before the
        // payment does. Uncertain rather than failed: the provider has not been
        // asked, so it has not refused.
        FiscalSubmission submission = fiscalAdapter.submit(document(), binding());

        assertThat(submission.classification())
                .isEqualTo(uz.horecaos.platform.payments.domain.ProviderOutcome.Classification.UNCERTAIN);
        assertThat(transport.calls()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private void captureWithPaymentId(String paymentId) {
        controller.prepare(SEGMENT, signedPrepare(AMOUNT));
        controller.complete(SEGMENT, signedComplete(AMOUNT));
        jdbc.sql("""
                UPDATE payments.payment_attempts SET external_payment_id = :paymentId
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("paymentId", paymentId)
                .param("tenantId", TENANT)
                .param("id", attemptId)
                .update();
    }

    private FiscalDocument document() {
        return fiscalDocument(FiscalStatus.PENDING);
    }

    private FiscalDocument submittedDocument() {
        return fiscalDocument(FiscalStatus.SUBMITTED);
    }

    private FiscalDocument fiscalDocument(FiscalStatus status) {
        FiscalReceiptLine line = new FiscalReceiptLine(
                "Plov, portion",
                "01234567890123456",
                "1234567",
                796L,
                2,
                new SomAmount(400, "UZS"),
                new SomAmount(80, "UZS"),
                12,
                null,
                null,
                List.of(),
                "301234567",
                null);

        return new FiscalDocument(
                UUID.randomUUID(),
                TENANT,
                ORDER,
                LEGAL_ENTITY,
                intentId,
                null,
                PaymentProviderType.CLICK,
                FiscalDocumentType.SALE,
                null,
                status,
                FiscalReason.AWAITING_PROVIDER,
                "Submitted to Click",
                List.of(line),
                null,
                1,
                CLOCK.instant());
    }

    private uz.horecaos.platform.payments.domain.ProviderBinding binding() {
        return new JdbcPaymentBindingResolver(jdbc).byCallbackSegment(SEGMENT).orElseThrow();
    }

    private Map<String, String> signedPrepare(String amount) {
        Map<String, String> form = prepareForm(amount);
        form.put(
                "sign_string",
                ClickSignature.prepare(SECRET, CLICK_TRANS_ID, SERVICE_ID, merchantTransId, amount, "0", signTime()));
        return form;
    }

    private Map<String, String> signedComplete(String amount) {
        return completeForm(amount);
    }

    /**
     * One legal entity's Click secret must not be able to credit another's order.
     *
     * <p>V0027 gives a tenant a separate Click service, secret key and callback
     * segment per legal entity, so entity A's secret is held by A's own restaurant
     * staff by construction. If the attempt lookup filters on tenant alone, A can
     * POST a correctly signed Prepare to A's own segment carrying entity B's
     * {@code merchant_trans_id}: the service check compares against A's binding and
     * passes, the MD5 verifies under A's key, and B's order is captured and
     * fiscalized under B's legal entity on A's money — while B's own reversal path
     * points at a payment A made.
     *
     * <p>The binding is therefore part of the join key, not only the tenant. The
     * Payme side scopes every read by {@code merchant_binding_id} already; this is
     * the same rule on the other adapter.
     */
    @Test
    @DisplayName("a callback cannot reach an attempt belonging to another legal entity")
    void aCallbackCannotCreditAnotherLegalEntitysOrder() {
        UUID foreignAttemptId = UUID.randomUUID();
        String foreignMerchantTransId = UUID.randomUUID().toString().replace("-", "");

        // The other entity's order carries its own intent, because
        // ux_payment_attempt_open_per_intent permits one open attempt per intent and
        // two entities' attempts under one intent is not a shape the platform can
        // produce. What the test is about is the join key, and that is unchanged: a
        // correctly signed callback on this binding's segment naming that entity's
        // merchant_trans_id.
        // Written in SQL rather than through the store because it has to be settled:
        // ux_payment_intent_live_per_order permits one live intent per order, and
        // the store's insert has no settled_at to offer.
        UUID foreignIntentId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.payment_intents (
                    id, tenant_id, order_id, brand_id, location_id, legal_entity_id, tender,
                    payment_method_code, provider_type, requested_amount_minor, currency, status,
                    capture_timing, idempotency_key, settled_at)
                VALUES (:id, :tenantId, :orderId, :brandId, :locationId, :legalEntityId, 'PROVIDER',
                    'CLICK', 'CLICK', :amount, 'UZS', 'CANCELLED', 'BEFORE_CONFIRMATION',
                    :idempotencyKey, now())
                """)
                .param("id", foreignIntentId)
                .param("tenantId", TENANT)
                .param("orderId", ORDER)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("legalEntityId", OTHER_LEGAL_ENTITY)
                .param("amount", AMOUNT_SOM)
                .param("idempotencyKey", UUID.randomUUID().toString())
                .update();

        attempts.insert(new PaymentAttempt(
                foreignAttemptId,
                TENANT,
                foreignIntentId,
                PaymentProviderType.CLICK,
                OTHER_BINDING,
                foreignMerchantTransId,
                BUSINESS_DATE,
                null,
                null,
                new SomAmount(AMOUNT_SOM, "UZS"),
                PaymentAttemptStatus.PRESENTED,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                CLOCK.instant(),
                null));

        // Signed correctly, on this binding's own segment, under this binding's own
        // secret and service id — naming the other entity's transaction.
        Map<String, String> form = baseForm(AMOUNT, "0");
        form.put("merchant_trans_id", foreignMerchantTransId);
        form.put(
                "sign_string",
                ClickSignature.prepare(
                        SECRET, CLICK_TRANS_ID, SERVICE_ID, foreignMerchantTransId, AMOUNT, "0", signTime()));

        ClickShopApiResponse response = controller.prepare(SEGMENT, form);

        assertThat(response.error())
                .as("-5: as far as this binding is concerned that order does not exist")
                .isEqualTo(-5);
        assertThat(attempts.find(TENANT, foreignAttemptId).orElseThrow().status())
                .as("the other entity's attempt is untouched")
                .isEqualTo(PaymentAttemptStatus.PRESENTED);
    }

    private Map<String, String> prepareForm(String amount) {
        Map<String, String> form = baseForm(amount, "0");
        form.put("sign_string", "unsigned");
        return form;
    }

    /** A Complete form, signed: every caller of it wants the signature to be right. */
    private Map<String, String> completeForm(String amount) {
        String prepareId = Integer.toString(ClickPrepareId.forAttempt(attemptId));
        Map<String, String> form = baseForm(amount, "1");
        form.put("merchant_prepare_id", prepareId);
        form.put(
                "sign_string",
                ClickSignature.complete(
                        SECRET, CLICK_TRANS_ID, SERVICE_ID, merchantTransId, prepareId, amount, "1", signTime()));
        return form;
    }

    private Map<String, String> baseForm(String amount, String action) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("click_trans_id", CLICK_TRANS_ID);
        form.put("service_id", SERVICE_ID);
        form.put("click_paydoc_id", CLICK_PAYDOC_ID);
        form.put("merchant_trans_id", merchantTransId);
        form.put("amount", amount);
        form.put("action", action);
        form.put("error", "0");
        form.put("error_note", "Success");
        form.put("sign_time", signTime());
        return form;
    }

    private static String signTime() {
        return "2026-08-22 14:03:11";
    }

    private PaymentAttemptStatus status() {
        return attempts.find(TENANT, attemptId).orElseThrow().status();
    }

    private int transactionsOfType(String type) {
        return jdbc.sql("""
                SELECT count(*) FROM payments.payment_transactions
                WHERE tenant_id = :tenantId AND attempt_id = :attemptId AND transaction_type = :type
                """)
                .param("tenantId", TENANT)
                .param("attemptId", attemptId)
                .param("type", type)
                .query(Integer.class)
                .single();
    }

    private int recordedCallbacks() {
        return jdbc.sql("SELECT count(*) FROM payments.provider_callbacks")
                .query(Integer.class)
                .single();
    }

    private int signatureFailures() {
        return callbacks.signatureFailuresSince(TENANT, BINDING, CLOCK.instant().minusSeconds(3600));
    }

    private void seedIntentAndAttempt() {
        intentId = UUID.randomUUID();
        attemptId = UUID.randomUUID();
        merchantTransId = UUID.randomUUID().toString().replace("-", "");

        intents.insert(new PaymentIntent(
                intentId,
                TENANT,
                ORDER,
                BRAND,
                LOCATION,
                null,
                LEGAL_ENTITY,
                PaymentTender.PROVIDER,
                PaymentMethod.CLICK,
                PaymentProviderType.CLICK,
                new SomAmount(AMOUNT_SOM, "UZS"),
                PaymentIntentStatus.AUTHORIZING,
                CaptureTiming.BEFORE_CONFIRMATION,
                UUID.randomUUID().toString(),
                1,
                CLOCK.instant(),
                null));

        attempts.insert(new PaymentAttempt(
                attemptId,
                TENANT,
                intentId,
                PaymentProviderType.CLICK,
                BINDING,
                merchantTransId,
                BUSINESS_DATE,
                null,
                null,
                new SomAmount(AMOUNT_SOM, "UZS"),
                PaymentAttemptStatus.PRESENTED,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                CLOCK.instant(),
                null));
    }

    private static void seedTenantAndMerchantAccount() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status)
                VALUES (:id, 'clicktests', 'Click Tests LLC', 'Click Tests', 'UZS',
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
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'PAYMENT', 'CLICK', 'click-sandbox', 'Click', 'ACTIVE',
                    :secretReference)
                """)
                .param("id", INSTALLATION)
                .param("tenantId", TENANT)
                .param("secretReference", secretReference().toString())
                .update();

        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", INTEGRATION_BINDING)
                .param("tenantId", TENANT)
                .param("installationId", INSTALLATION)
                .param("brandId", BRAND)
                .update();

        // V0053 made legal_entity_id a real foreign key. The column existed before
        // it and referenced nothing, which is why a restaurant had no legal
        // identity to issue a receipt under. Both entities are seeded because the
        // fixture binds a merchant account per entity, which is the shape V0027's
        // per-entity uniqueness exists to enforce.
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :t, 'CLICK-LE', 'Click MCHJ', '123456789', 'ACTIVE')
                ON CONFLICT DO NOTHING
                """).param("id", LEGAL_ENTITY).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :t, 'CLICK-LE2', 'Boshqa MCHJ', '223456789', 'ACTIVE')
                ON CONFLICT DO NOTHING
                """).param("id", OTHER_LEGAL_ENTITY).param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO payments.merchant_bindings (id, tenant_id, legal_entity_id,
                    provider_type, installation_id, binding_id, merchant_account_reference,
                    merchant_user_reference, secret_reference, callback_path_segment,
                    supports_reversal, supports_partner_fiscalization, status, effective_from)
                VALUES (:id, :tenantId, :legalEntityId, 'CLICK', :installationId, :bindingId,
                    :serviceId, '3333', :secretReference, :segment, true, true, 'ACTIVE',
                    :effectiveFrom)
                """)
                .param("id", BINDING)
                .param("tenantId", TENANT)
                .param("legalEntityId", LEGAL_ENTITY)
                .param("installationId", INSTALLATION)
                .param("bindingId", INTEGRATION_BINDING)
                .param("serviceId", SERVICE_ID)
                .param("secretReference", secretReference().toString())
                .param("segment", SEGMENT)
                .param("effectiveFrom", LocalDate.of(2026, 1, 1))
                .update();

        // A second legal entity under the same tenant, with its own Click service,
        // its own secret and its own callback segment — which is exactly the shape
        // V0027's per-entity uniqueness produces, and the shape that makes a
        // tenant-only attempt lookup a cross-entity hole.
        jdbc.sql("""
                INSERT INTO payments.merchant_bindings (id, tenant_id, legal_entity_id,
                    provider_type, installation_id, binding_id, merchant_account_reference,
                    merchant_user_reference, secret_reference, callback_path_segment,
                    supports_reversal, supports_partner_fiscalization, status, effective_from)
                VALUES (:id, :tenantId, :legalEntityId, 'CLICK', :installationId, :bindingId,
                    :serviceId, '4444', :secretReference, :segment, true, true, 'ACTIVE',
                    :effectiveFrom)
                """)
                .param("id", OTHER_BINDING)
                .param("tenantId", TENANT)
                .param("legalEntityId", OTHER_LEGAL_ENTITY)
                .param("installationId", INSTALLATION)
                .param("bindingId", INTEGRATION_BINDING)
                .param("serviceId", "88888")
                .param("secretReference", secretReference().toString())
                .param("segment", "click-brandtwo")
                .param("effectiveFrom", LocalDate.of(2026, 1, 1))
                .update();
    }

    /**
     * The order the payment is for, with the rows its foreign keys insist on.
     *
     * <p>Written directly rather than by running a checkout: what is under test is
     * the payment callback, and an order built through ADR 0019's whole pipeline
     * would make this suite fail for reasons that have nothing to do with Click.
     */
    private static void seedOrder() {
        UUID channel = UUID.randomUUID();
        UUID catalog = UUID.randomUUID();
        UUID publication = UUID.randomUUID();
        UUID quote = UUID.randomUUID();
        UUID cart = UUID.randomUUID();

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
                VALUES (:id, :tenantId, :brandId, :catalogId, 'WEB', 'PUBLISHED', 'hash',
                    now())
                """)
                .param("id", publication)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalog)
                .update();

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency, status,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', 'ACTIVE', :publicationId,
                    1, 'hash', 1000, 0, 1000, now() + interval '1 day')
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
                    :id, '14', :tenantId, :brandId, :locationId, :channelId, 'WEB', 'guest-hash',
                    'DELIVERY', 'AUTO_CONFIRM', 'NONE', 'PAYMENT_AUTHORIZING', 'UZS',
                    1000, 0, 1000, :quoteId, 'hash', :publicationId, :cartId, :idempotencyKey)
                """)
                .param("id", ORDER)
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

    private static SecretReference secretReference() {
        return new SecretReference("test", SecretCategory.PROVIDER_PAYMENT, "tenant", "click");
    }

    /** Answers the one secret this suite's binding refers to, and never logs it. */
    private static final class FixedSecretResolver implements SecretResolver {

        @Override
        public SecretValue resolve(SecretReference reference) {
            return SecretValue.of(SECRET);
        }

        @Override
        public SecretValue resolveFresh(SecretReference reference) {
            return SecretValue.of(SECRET);
        }
    }

    /** The outbound half, faked: what the adapter sent, and what it was told. */
    private static final class RecordingTransport implements MerchantApiTransport {

        private final List<MerchantApiCall> calls = new CopyOnWriteArrayList<>();
        private final List<ProviderOutcome> answers = new CopyOnWriteArrayList<>();

        void answer(ProviderOutcome outcome) {
            answers.add(outcome);
        }

        List<MerchantApiCall> calls() {
            return List.copyOf(calls);
        }

        List<String> paths() {
            return calls.stream().map(MerchantApiCall::path).toList();
        }

        @Override
        public ProviderOutcome exchange(MerchantApiCall call) {
            calls.add(call);
            int index = calls.size() - 1;
            return index < answers.size()
                    ? answers.get(index)
                    : ProviderOutcome.uncertain("NO_ANSWER_QUEUED", "the test queued no answer");
        }
    }
}
