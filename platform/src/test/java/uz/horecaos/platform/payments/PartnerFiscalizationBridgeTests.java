package uz.horecaos.platform.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.catalog.api.FiscalClassificationLookup;
import uz.horecaos.platform.catalog.api.FiscalClassificationLookup.FiscalClassificationFacts;
import uz.horecaos.platform.fiscal.api.PartnerFiscalizationPort.Outcome;
import uz.horecaos.platform.fiscal.api.SentReceiptEvidence;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.integration.api.payment.MerchantApiCall;
import uz.horecaos.platform.integration.api.payment.MerchantApiTransport;
import uz.horecaos.platform.integration.provider.JdbcProviderActivityRecorder;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.OrderMarksPort;
import uz.horecaos.platform.ordering.api.OrderReceiptSource;
import uz.horecaos.platform.payments.application.FiscalReceiptPort;
import uz.horecaos.platform.payments.application.PartnerFiscalizationBridge;
import uz.horecaos.platform.payments.application.PaymentBindingResolver;
import uz.horecaos.platform.payments.application.PaymentFiscalService;
import uz.horecaos.platform.payments.application.PresentedReceipts;
import uz.horecaos.platform.payments.application.ProtectedPresentedReceipts;
import uz.horecaos.platform.payments.application.ProtectedSentEvidenceRecorder;
import uz.horecaos.platform.payments.application.ReceiptLineBuilder;
import uz.horecaos.platform.payments.domain.CaptureTiming;
import uz.horecaos.platform.payments.domain.FiscalDocument;
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
import uz.horecaos.platform.payments.domain.SentReceipt;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.infrastructure.click.ClickFiscalAdapter;
import uz.horecaos.platform.payments.infrastructure.click.ClickMerchantApi;
import uz.horecaos.platform.payments.infrastructure.payme.PaymeFiscalAdapter;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcFiscalDocumentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentBindingResolver;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcReceiptPaymentFacts;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore;
import uz.horecaos.platform.support.RecordingProviderActivityRecorder;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.support.TestProtection;

/**
 * {@link PartnerFiscalizationBridge}'s own decisions, in isolation from the
 * fake-provider round trip (ADR 0013, ADR 0038).
 *
 * <p>Run against real Postgres, hand-wired like {@code PaymentCheckoutSurfaceTests}
 * and {@code FiscalObligationTests} rather than through Spring: what is under test
 * is the bridge's own branching, not the application context.
 */
class PartnerFiscalizationBridgeTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID CLICK_ENTITY = UUID.randomUUID();
    private static final UUID PAYME_ENTITY = UUID.randomUUID();
    private static final UUID UNBOUND_ENTITY = UUID.randomUUID();
    private static final UUID CLICK_INSTALLATION = UUID.randomUUID();
    private static final UUID PAYME_INSTALLATION = UUID.randomUUID();
    private static final UUID INTEGRATION_BINDING = UUID.randomUUID();

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T09:00:00Z"), ZoneOffset.UTC);

    private static TestDatabase.Handle db;
    private static JdbcClient jdbc;

    private JdbcFiscalDocumentStore documents;
    private JdbcPaymentIntentStore intents;
    private PaymentFiscalService fiscalService;
    private PaymentBindingResolver bindings;
    private OrderDirectory orders;
    private World world = new World();

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the fiscal bridge tests");
        db = TestDatabase.migrated();
        jdbc = JdbcClient.create(db.dataSource());
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void wire() {
        jdbc.sql("DELETE FROM fiscal.fiscal_documents").update();
        jdbc.sql("DELETE FROM payments.payment_attempts").update();
        jdbc.sql("DELETE FROM payments.merchant_bindings").update();
        jdbc.sql("DELETE FROM tenant.location_fiscal_assignments").update();
        jdbc.sql("DELETE FROM tenant.legal_entities").update();
        jdbc.sql("DELETE FROM payments.payment_intents").update();
        jdbc.sql("DELETE FROM integration.bindings").update();
        jdbc.sql("DELETE FROM integration.installations").update();
        // ADR 0096: HorecaOS's own e-invoicing accounts name an approved environment too.
        jdbc.sql("DELETE FROM commercial.statement_einvoices").update();
        jdbc.sql("DELETE FROM commercial.einvoicing_installations").update();
        jdbc.sql("DELETE FROM integration.provider_environments").update();
        jdbc.sql("DELETE FROM ordering.orders").update();
        jdbc.sql("DELETE FROM ordering.carts").update();
        jdbc.sql("DELETE FROM pricing.quotes").update();
        jdbc.sql("DELETE FROM catalog.publications").update();
        jdbc.sql("DELETE FROM catalog.catalogs").update();
        jdbc.sql("DELETE FROM tenant.sales_channels").update();
        jdbc.sql("DELETE FROM tenant.locations").update();
        jdbc.sql("DELETE FROM tenant.brands").update();
        jdbc.sql("DELETE FROM tenant.tenants").update();

        seedTenancy();

        documents = new JdbcFiscalDocumentStore(jdbc);
        intents = new JdbcPaymentIntentStore(jdbc);
        bindings = new JdbcPaymentBindingResolver(jdbc);
        orders = (tenantId, orderId) -> Optional.of(new OrderDirectory.OrderSummary(
                orderId, TENANT, BRAND, LOCATION, "BR-1", null, "guest-hash", "COMPLETED", "UZS", 15_000L, 1));

        ClickFiscalAdapter click = new ClickFiscalAdapter(
                new ClickMerchantApi(refusingTransport(), CLOCK), new JdbcPaymentAttemptStore(jdbc), CLOCK);
        PaymeFiscalAdapter payme = new PaymeFiscalAdapter(intents, CLOCK);
        fiscalService = new PaymentFiscalService(
                documents, List.of(click, payme), event -> {}, new RecordingProviderActivityRecorder());
    }

    // -----------------------------------------------------------------------
    // Blocked / no provider path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a legal entity with no active merchant binding is NO_PROVIDER_PATH, not an error")
    void noActiveBindingIsReportedAsNoProviderPath() {
        // A real legal entity — the fiscal_documents FK requires one — with
        // deliberately no payments.merchant_bindings row of its own.
        seedLegalEntity(UNBOUND_ENTITY, "UNBOUND");
        UUID intentId = seedIntent(PaymentProviderType.CLICK, UNBOUND_ENTITY);
        UUID documentId = seedPendingDocument(intentId);

        PartnerFiscalizationBridge bridge = bridge();
        Outcome outcome = bridge.retry(TENANT, documentId, "idem-1");

        assertThat(outcome).isEqualTo(Outcome.NO_PROVIDER_PATH);
        // Nothing was recorded as though the provider had answered.
        FiscalDocument document = fiscalService.find(TENANT, documentId).orElseThrow();
        assertThat(document.status()).isEqualTo(FiscalStatus.PENDING);
    }

    // -----------------------------------------------------------------------
    // Already issued
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a document already ISSUED is never asked again, and no binding is even resolved")
    void anAlreadyIssuedDocumentIsNeverAskedAgain() {
        seedClickBinding();
        UUID intentId = seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY);
        UUID documentId = seedPendingDocument(intentId);

        fiscalService.attachEvidence(
                TENANT,
                documentId,
                new FiscalDocument.FiscalEvidence(
                        "R-1",
                        "SIGN-1",
                        "TERM-1",
                        "R-1",
                        CLOCK.instant(),
                        "https://ofd.soliq.uz/epi?t=1&r=1&c=1&s=1",
                        null,
                        null),
                "protected-response-1",
                CLOCK.instant());

        // A resolver that fails the test the moment it is asked anything: the
        // whole point of the ALREADY_ISSUED shortcut is that nothing downstream of
        // "the document already holds a receipt" runs at all.
        PaymentBindingResolver refusingResolver = new PaymentBindingResolver() {
            @Override
            public Optional<uz.horecaos.platform.payments.domain.ProviderBinding> resolve(
                    UUID tenantId,
                    UUID legalEntityId,
                    PaymentProviderType providerType,
                    java.time.LocalDate businessDate) {
                throw new AssertionError("an already-ISSUED document must never resolve a binding");
            }

            @Override
            public Optional<uz.horecaos.platform.payments.domain.ProviderBinding> byCallbackSegment(String segment) {
                throw new AssertionError("not used by the bridge");
            }
        };

        PartnerFiscalizationBridge bridge =
                new PartnerFiscalizationBridge(fiscalService, refusingResolver, world.builder(fiscalService), CLOCK);

        Outcome outcome = bridge.retry(TENANT, documentId, "idem-2");

        assertThat(outcome).isEqualTo(Outcome.ALREADY_ISSUED);
    }

    // -----------------------------------------------------------------------
    // Gap map row 10.8c: the fiscal leg's own liveness watermark
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a successful submission writes the binding's own OUTBOUND watermark (gap map 10.8c)")
    void aSuccessfulSubmissionRecordsTheBindingsOwnActivityWatermark() {
        seedClickBinding();
        UUID intentId = seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY);
        UUID documentId = seedPendingDocument(intentId);
        FiscalDocument document = fiscalService.find(TENANT, documentId).orElseThrow();

        JdbcProviderActivityRecorder activity = new JdbcProviderActivityRecorder(jdbc);
        PaymentFiscalService serviceUnderTest = new PaymentFiscalService(
                documents,
                List.of(scriptedClickReceiptPort(FiscalSubmission.issued(
                        new FiscalDocument.FiscalEvidence(
                                "R-9",
                                "SIGN-9",
                                "TERM-9",
                                "R-9",
                                CLOCK.instant(),
                                "https://ofd.soliq.uz/r/9",
                                null,
                                null),
                        CLOCK.instant()))),
                event -> {},
                activity);
        uz.horecaos.platform.payments.domain.ProviderBinding binding = bindings.resolve(
                        TENANT,
                        CLICK_ENTITY,
                        PaymentProviderType.CLICK,
                        CLOCK.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate())
                .orElseThrow();

        serviceUnderTest.submit(document, binding, CLOCK.instant());

        assertThat(jdbc.sql("""
                        SELECT direction, alert_state, last_success_reference
                        FROM integration.provider_activity_watermarks
                        WHERE tenant_id = :tenantId AND binding_id = :bindingId
                        """)
                        .param("tenantId", TENANT)
                        .param("bindingId", INTEGRATION_BINDING)
                        .query((row, number) -> row.getString("direction") + ":" + row.getString("alert_state") + ":"
                                + row.getString("last_success_reference"))
                        .list())
                .containsExactly("OUTBOUND:HEALTHY:R-9");
    }

    @Test
    @DisplayName("a rejected submission writes a failure watermark, not a success")
    void aRejectedSubmissionRecordsAFailureWatermark() {
        seedClickBinding();
        UUID intentId = seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY);
        UUID documentId = seedPendingDocument(intentId);
        FiscalDocument document = fiscalService.find(TENANT, documentId).orElseThrow();

        JdbcProviderActivityRecorder activity = new JdbcProviderActivityRecorder(jdbc);
        PaymentFiscalService serviceUnderTest = new PaymentFiscalService(
                documents,
                List.of(scriptedClickReceiptPort(FiscalSubmission.rejected(
                        "FISCAL-RULE-7", "the till rejected this document", CLOCK.instant()))),
                event -> {},
                activity);
        uz.horecaos.platform.payments.domain.ProviderBinding binding = bindings.resolve(
                        TENANT,
                        CLICK_ENTITY,
                        PaymentProviderType.CLICK,
                        CLOCK.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate())
                .orElseThrow();

        serviceUnderTest.submit(document, binding, CLOCK.instant());

        assertThat(jdbc.sql("""
                        SELECT direction, last_failure_code, last_success_at IS NOT NULL AS has_success,
                               alert_state
                        FROM integration.provider_activity_watermarks
                        WHERE tenant_id = :tenantId AND binding_id = :bindingId
                        """)
                        .param("tenantId", TENANT)
                        .param("bindingId", INTEGRATION_BINDING)
                        .query((row, number) -> Map.of(
                                "direction", row.getString("direction"),
                                "failureCode", row.getString("last_failure_code"),
                                "hasSuccess", row.getBoolean("has_success"),
                                "alertState", row.getString("alert_state")))
                        .list())
                .containsExactly(Map.of(
                        "direction", "OUTBOUND",
                        "failureCode", "FISCAL-RULE-7",
                        "hasSuccess", false,
                        // A binding whose only evidence ever recorded is a failure must not
                        // read as HEALTHY: last_success_at is null forever on this row, so
                        // JdbcPartnerStore#markStale's sweep (gated on last_success_at IS NOT
                        // NULL) can never promote it out of a false HEALTHY later.
                        "alertState", "STALE"));
    }

    private static FiscalReceiptPort scriptedClickReceiptPort(FiscalSubmission answer) {
        return new FiscalReceiptPort() {
            @Override
            public FiscalSubmission submit(
                    FiscalDocument document, uz.horecaos.platform.payments.domain.ProviderBinding binding) {
                return answer;
            }

            @Override
            public PaymentProviderType providerType() {
                return PaymentProviderType.CLICK;
            }
        };
    }

    // -----------------------------------------------------------------------
    // ADR 0166, Decisions 6 and 9: every order is filed as the lines it was sold as
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "an order is filed as its own lines: the dish, the option, and the delivery fee as an ordinary item line of its own kind")
    void anOrderIsFiledAsItsOwnLines() {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        world.dishWithCheeseAndDeliveryFee();
        CapturingClickPort click = new CapturingClickPort();

        Outcome outcome = capturingBridge(click).retry(TENANT, documentId, "idem-lines");

        assertThat(outcome).isEqualTo(Outcome.ISSUED);
        List<FiscalReceiptLine> lines = click.sent.get(0).lines();
        assertThat(lines).extracting(FiscalReceiptLine::fiscalName).containsExactly("Plov", "Extra cheese", "Delivery");
        assertThat(lines)
                .extracting(FiscalReceiptLine::mxikCode)
                .as("each line carries its own node's classification, and none is a placeholder")
                .containsExactly("10901001001000000", "04001001001000000", "10701001001000000");
        assertThat(lines)
                .extracting(FiscalReceiptLine::kind)
                .containsExactly(
                        FiscalReceiptLine.Kind.ITEM, FiscalReceiptLine.Kind.ITEM, FiscalReceiptLine.Kind.DELIVERY_FEE);
        assertThat(lines)
                .extracting(line -> line.netTotal().value())
                .as("the receipt equals the payment to the minor unit")
                .containsExactly(10_000L, 3_000L, 2_000L);
        assertThat(lines.get(2).vatPercent())
                .as("the quote recorded no tax on the delivery fee, and the receipt does not invent one")
                .isZero();
        assertThat(lines.get(0).vatPercent()).isEqualTo(12);
        assertThat(lines).extracting(FiscalReceiptLine::commissionTin).containsOnly(taxpayerNumberOf(CLICK_ENTITY));
    }

    @Test
    @DisplayName(
            "what was filed is kept as filed: the delivery fee is recorded as its own kind and the stand-in flag is false")
    void theFiledLinesAreKeptWithTheirKind() {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        world.dishWithCheeseAndDeliveryFee();
        ScriptedClick click = new ScriptedClick();

        Outcome outcome = realBridge(click).retry(TENANT, documentId, "idem-kept");

        assertThat(outcome).isEqualTo(Outcome.UNCERTAIN);
        Map<String, Object> row = sentEvidenceOf(documentId);
        assertThat(row.get("lines_synthetic")).isEqualTo(false);
        assertThat(row.get("merchant_binding_id")).isNotNull();
        String lines = TestProtection.envelope()
                .reveal(
                        TENANT,
                        ProtectedValue.deserialize(
                                java.util.Objects.requireNonNull((String) row.get("protected_request_reference"))),
                        SentReceiptEvidence.requestRecord(documentId),
                        "test");
        assertThat(lines)
                .contains("\"synthetic\":false")
                .contains("\"kind\":\"DELIVERY_FEE\"")
                .contains("\"ikpu\":\"10701001001000000\"")
                .contains("\"packageCode\":\"7000001\"")
                .doesNotContain("00000000000000000")
                .doesNotContain("SYNTHETIC");
        String split = TestProtection.envelope()
                .reveal(
                        TENANT,
                        ProtectedValue.deserialize(
                                java.util.Objects.requireNonNull((String) row.get("sent_tender_split_reference"))),
                        SentReceiptEvidence.splitRecord(documentId),
                        "test");
        assertThat(split).contains("\"receivedCardMinor\":15000").contains("\"receivedCashMinor\":0");
    }

    @Test
    @DisplayName(
            "a weighed line is filed with its decimal count, the weighed total and the unit in the name, and is kept as filed")
    void aWeighedLineIsFiledWithItsDecimalCount() throws Exception {
        seedClickBinding();
        // 1.040 kg of lamb weighed at the scale: 83 199, which is what Click is asked to collect.
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY, 83_199L));
        world.classes.put(
                World.PLOV_VARIANT,
                new FiscalClassificationFacts("10101001001000000", "1500321", 166, "Lamb", null, false));
        world.lines = List.of(new OrderReceiptSource.Line(
                World.LINE,
                1,
                World.PLOV_VARIANT,
                "Lamb",
                BigDecimal.valueOf(2),
                83_199,
                83_199,
                8_914,
                new OrderReceiptSource.Catchweight(1_040),
                List.of(),
                0));
        ScriptedClick click = new ScriptedClick();

        realBridge(click).retry(TENANT, documentId, "idem-weighed");

        FiscalReceiptLine sent = click.submitted.getFirst().lines().getFirst();
        assertThat(sent.quantity()).isEqualByComparingTo("1.04");
        assertThat(sent.fiscalName()).isEqualTo("Lamb, kg");
        assertThat(sent.netTotal().value()).isEqualTo(83_199);

        List<Map<String, Object>> kept = sentRequestsOf(documentId);
        String plain = protection.reveal(
                TENANT,
                ProtectedValue.deserialize(java.util.Objects.requireNonNull(
                        (String) kept.getFirst().get("protected_request_reference"))),
                SentReceiptEvidence.requestRecord(documentId),
                "bridge-test");
        assertThat(plain).contains("\"quantity\":1.04").contains("\"discountMinor\":1");
        SentReceiptEvidence.Line read = tools.jackson.databind.json.JsonMapper.builder()
                .build()
                .readValue(plain, SentReceiptEvidence.Lines.class)
                .lines()
                .getFirst();
        assertThat(read.quantity()).isEqualByComparingTo("1.04");
        assertThat(read.unitPriceMinor()).isEqualTo(80_000);
    }

    @Test
    @DisplayName(
            "the paper receipt prints the lines as filed, with their VAT, and the one aggregate line only when nothing was kept")
    void thePaperReceiptPrintsTheLinesAsFiled() {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        world.dishWithCheeseAndDeliveryFee();
        ScriptedClick click = new ScriptedClick();
        realBridge(click).retry(TENANT, documentId, "idem-paper");
        fiscalService.attachEvidence(
                TENANT,
                documentId,
                new FiscalDocument.FiscalEvidence(
                        "R-P", "SIGN-P", "TERM-P", "R-P", CLOCK.instant(), "https://ofd.soliq.uz/epi?r=p", null, null),
                "protected-response-p",
                CLOCK.instant());
        uz.horecaos.platform.payments.api.FiscalReceiptSource paper =
                uz.horecaos.platform.payments.application.FiscalReceiptReaderFactory.create(
                        fiscalService,
                        documents,
                        intents,
                        orders,
                        TestProtection.envelope(),
                        tools.jackson.databind.json.JsonMapper.builder().build());

        var printed = paper.find(TENANT, documentId).orElseThrow();

        assertThat(printed.lines())
                .extracting(uz.horecaos.platform.payments.api.FiscalReceiptSource.Line::name)
                .containsExactly("Plov", "Extra cheese", "Delivery");
        assertThat(printed.totalMinor()).as("the paper equals the payment").isEqualTo(15_000);
        assertThat(printed.vatMinor())
                .as("and states the VAT the receipt was filed with, which the stand-in line never did")
                .isEqualTo(1_393);

        // Nothing kept of what was sent (a request that left and could not be recorded): the paper says less,
        // one line for the payment, and states no tax because none was kept to state.
        jdbc.sql("UPDATE fiscal.fiscal_documents SET protected_request_reference = NULL WHERE id = :id")
                .param("id", documentId)
                .update();
        var fallback = paper.find(TENANT, documentId).orElseThrow();
        assertThat(fallback.lines())
                .singleElement()
                .satisfies(line -> assertThat(line.name()).isEqualTo("Order BR-1"));
        assertThat(fallback.vatMinor()).isZero();
    }

    @Test
    @DisplayName(
            "a node with no complete classification files nothing: BLOCKED_CLASSIFICATION, never a placeholder code")
    void anUnclassifiedNodeBlocksTheDocumentAndSendsNothing() {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        world.dishWithCheeseAndDeliveryFee();
        world.classes.remove(World.CHEESE_OPTION);
        CapturingClickPort click = new CapturingClickPort();

        Outcome outcome = capturingBridge(click).retry(TENANT, documentId, "idem-unclassified");

        assertThat(outcome).isEqualTo(Outcome.BLOCKED_CLASSIFICATION);
        assertThat(click.sent).as("the provider was never asked").isEmpty();
        assertThat(sentEvidenceOf(documentId).get("protected_request_reference"))
                .isNull();
        assertThat(fiscalService.find(TENANT, documentId).orElseThrow().status())
                .as("still owed, and recoverable once the node is classified")
                .isEqualTo(FiscalStatus.PENDING);
    }

    @Test
    @DisplayName(
            "a classification missing one of its four fields is as good as none, and so is an unclassified delivery fee")
    void anIncompleteClassificationAndAnUnclassifiedFeeBlockToo() {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        world.dishWithCheeseAndDeliveryFee();
        CapturingClickPort click = new CapturingClickPort();

        world.classes.put(
                World.PLOV_VARIANT,
                new FiscalClassificationFacts("10901001001000000", "1500123", null, "Plov", null, false));
        assertThat(capturingBridge(click).retry(TENANT, documentId, "idem-no-unit"))
                .as("no unit code: Click demands the unit and a platform without one cannot build a line")
                .isEqualTo(Outcome.BLOCKED_CLASSIFICATION);

        world.dishWithCheeseAndDeliveryFee();
        world.feeClassification = Optional.empty();
        assertThat(capturingBridge(click).retry(TENANT, documentId, "idem-no-fee-class"))
                .isEqualTo(Outcome.BLOCKED_CLASSIFICATION);
        assertThat(click.sent).isEmpty();
    }

    @Test
    @DisplayName(
            "a rate that is not a whole percent, or prices that were not VAT-inclusive, are BLOCKED_TAX: no rounding")
    void aTaxRuleThatCannotBeStatedBlocks() {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        CapturingClickPort click = new CapturingClickPort();

        world.tax = new OrderReceiptSource.Tax(1250, true);
        assertThat(capturingBridge(click).retry(TENANT, documentId, "idem-12-5"))
                .isEqualTo(Outcome.BLOCKED_TAX);
        world.tax = new OrderReceiptSource.Tax(1200, false);
        assertThat(capturingBridge(click).retry(TENANT, documentId, "idem-exclusive"))
                .isEqualTo(Outcome.BLOCKED_TAX);
        world.tax = null;
        assertThat(capturingBridge(click).retry(TENANT, documentId, "idem-unrecorded"))
                .as("a quote that recorded no tax rule: a rate is never guessed")
                .isEqualTo(Outcome.BLOCKED_TAX);
        assertThat(click.sent).isEmpty();
    }

    @Test
    @DisplayName(
            "lines that do not add up to what the provider was asked to collect are BLOCKED_RECONCILIATION, and no line absorbs the gap")
    void aReceiptThatDoesNotEqualThePaymentBlocks() {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        world.plovGross = 14_000; // the payment is 15 000
        CapturingClickPort click = new CapturingClickPort();

        Outcome outcome = capturingBridge(click).retry(TENANT, documentId, "idem-mismatch");

        assertThat(outcome).isEqualTo(Outcome.BLOCKED_RECONCILIATION);
        assertThat(click.sent).isEmpty();
    }

    @Test
    @DisplayName(
            "points redeemed on the order are a per-line discount: the receipt equals the money tender and the VAT follows")
    void aPointsRedemptionIsADiscountOnTheLines() {
        seedClickBinding();
        // 20 000 of food, 5 000 of it paid in points: Click is asked for 15 000.
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY, 15_000L));
        world.plovGross = 20_000;
        world.redeemed = 5_000;
        CapturingClickPort click = new CapturingClickPort();

        Outcome outcome = capturingBridge(click).retry(TENANT, documentId, "idem-points");

        assertThat(outcome).isEqualTo(Outcome.ISSUED);
        FiscalReceiptLine line = click.sent.get(0).lines().getFirst();
        assertThat(line.netTotal().value()).isEqualTo(15_000);
        assertThat(java.util.Objects.requireNonNull(line.discount()).value()).isEqualTo(5_000);
        assertThat(line.taxAmount().value())
                .as("extracted again from what was paid: round(15 000 x 1200 / 11 200), not the quote's 2 143")
                .isEqualTo(1_607);
    }

    @Test
    @DisplayName(
            "a request that never left is not recorded as sent: a deferred submission keeps no evidence of a request")
    void aRequestThatNeverLeftIsNotRecorded() {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        ScriptedClick click = new ScriptedClick();
        click.sends = false;

        realBridge(click).retry(TENANT, documentId, "idem-deferred");

        assertThat(click.submitted).hasSize(1);
        assertThat(sentEvidenceOf(documentId).get("protected_request_reference"))
                .as("the adapter answered without sending, so there is no request to keep")
                .isNull();
        assertThat(sentEvidenceOf(documentId).get("lines_synthetic")).isEqualTo(false);
    }

    @Test
    @DisplayName("a late duplicate cannot replace the request recorded for a document that already holds its receipt")
    void sentEvidenceIsNotRewrittenOnceTheReceiptExists() {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        ScriptedClick click = new ScriptedClick();
        realBridge(click).retry(TENANT, documentId, "idem-first");
        String first = (String) sentEvidenceOf(documentId).get("protected_request_reference");
        fiscalService.attachEvidence(
                TENANT,
                documentId,
                new FiscalDocument.FiscalEvidence(
                        "R-1", "SIGN-1", "TERM-1", "R-1", CLOCK.instant(), "https://ofd.soliq.uz/epi?r=1", null, null),
                "protected-response-1",
                CLOCK.instant());

        boolean rewritten = documents.recordSentEvidence(
                TENANT, documentId, INTEGRATION_BINDING, "other", "other", CLOCK.instant());

        assertThat(rewritten).isFalse();
        assertThat(sentEvidenceOf(documentId).get("protected_request_reference"))
                .isEqualTo(first);
        assertThat(sentRequestsOf(documentId))
                .as("and a request refused is not added to the history either")
                .hasSize(1);
    }

    @Test
    @DisplayName(
            "a request that left and was never answered is kept when the document is asked again, beside the one that replaced it")
    void aRequestThatMayHaveReachedTheProviderIsKeptWhenTheDocumentIsSubmittedAgain() throws Exception {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        ScriptedClick click = new ScriptedClick();

        realBridge(click).retry(TENANT, documentId, "idem-lost-1");
        assertThat(fiscalService.find(TENANT, documentId).orElseThrow().status())
                .as("asked, and no answer: the request may well have arrived")
                .isEqualTo(FiscalStatus.SUBMITTED);
        // The tenant corrects the dish's fiscal name before the document is asked again, so the second
        // request is not the first.
        world.classes.put(
                World.PLOV_VARIANT,
                new FiscalClassificationFacts("10901001001000000", "1500123", 796, "Plov (revised)", null, false));
        realBridge(click).retry(TENANT, documentId, "idem-lost-2");

        List<Map<String, Object>> kept = sentRequestsOf(documentId);
        assertThat(kept).extracting(row -> row.get("attempt")).containsExactly(1, 2);
        assertThat(sentLineNames(documentId, kept.get(0)))
                .as("the first request is what Click may hold, and it is still here")
                .containsExactly("Plov");
        assertThat(sentLineNames(documentId, kept.get(1))).containsExactly("Plov (revised)");
        assertThat(sentEvidenceOf(documentId).get("protected_request_reference"))
                .as("the document's own column keeps meaning the latest request")
                .isEqualTo(kept.get(1).get("protected_request_reference"));
        assertThat(kept.get(0).get("protected_request_reference"))
                .isNotEqualTo(kept.get(1).get("protected_request_reference"));
    }

    // -----------------------------------------------------------------------
    // Payme's silence: a missing SetFiscalData is the documented normal case
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a fresh Payme submission is UNCERTAIN, never REJECTED — a missing SetFiscalData is "
            + "the documented normal case")
    void aFreshPaymeSubmissionIsUncertainNotRejected() {
        seedPaymeBinding();
        UUID intentId = seedIntent(PaymentProviderType.PAYME, PAYME_ENTITY);
        UUID documentId = seedPendingDocument(intentId);

        PartnerFiscalizationBridge bridge = bridge();
        Outcome outcome = bridge.retry(TENANT, documentId, "idem-3");

        assertThat(outcome)
                .as("Payme's adapter makes no call and reports SUBMITTED; the bridge must not "
                        + "read that as a failure")
                .isEqualTo(Outcome.UNCERTAIN);

        FiscalDocument document = fiscalService.find(TENANT, documentId).orElseThrow();
        assertThat(document.status())
                .as("still owed, not failed — the sweeper's reporting deadline is what eventually "
                        + "turns real silence into BLOCKED, never this call")
                .isEqualTo(FiscalStatus.SUBMITTED);
    }

    // -----------------------------------------------------------------------
    // ADR 0161: a marked order is never given the synthetic aggregate line
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "a marked order is submitted with its scanned codes on its own line, and never as the synthetic aggregate")
    void aMarkedOrderCarriesItsCodesToTheProvider() {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        CapturingClickPort click = new CapturingClickPort();
        PartnerFiscalizationBridge bridge = markedBridge(click, completeVodka(2));

        Outcome outcome = bridge.retry(TENANT, documentId, "idem-marked-1");

        assertThat(outcome).isEqualTo(Outcome.ISSUED);
        FiscalDocument sent = click.sent.get(0);
        assertThat(sent.lines()).hasSize(1);
        assertThat(sent.lines().get(0).fiscalName()).isEqualTo("Vodka");
        assertThat(sent.lines().get(0).quantity()).isEqualByComparingTo("2");
        assertThat(sent.lines().get(0).markingCodes()).containsExactly(MARK_CODE_1, MARK_CODE_2);
        assertThat(sent.lines().get(0).mxikCode())
                .as("a real classification, not the synthetic placeholder")
                .isEqualTo("22030000000000000");
    }

    @Test
    @DisplayName("a marked order short of a scanned unit sends nothing and reports BLOCKED_MARKS")
    void aMarkedOrderShortOfCodesIsBlockedNotSent() {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        CapturingClickPort click = new CapturingClickPort();
        PartnerFiscalizationBridge bridge = markedBridge(click, shortVodka());

        Outcome outcome = bridge.retry(TENANT, documentId, "idem-marked-2");

        assertThat(outcome).isEqualTo(Outcome.BLOCKED_MARKS);
        assertThat(click.sent).as("the provider was never asked").isEmpty();
        assertThat(fiscalService.find(TENANT, documentId).orElseThrow().status())
                .as("still owed, and not failed")
                .isEqualTo(FiscalStatus.PENDING);
    }

    @Test
    @DisplayName("a marked order on a provider with no field for a code is rejected, not sent as a fake aggregate")
    void aMarkedOrderOnAProviderThatCannotCarryCodesIsRejected() {
        seedPaymeBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.PAYME, PAYME_ENTITY));
        PartnerFiscalizationBridge bridge = markedBridge(new CapturingClickPort(), completeVodka(1));

        Outcome outcome = bridge.retry(TENANT, documentId, "idem-marked-3");

        assertThat(outcome).isEqualTo(Outcome.REJECTED);
        assertThat(fiscalService.find(TENANT, documentId).orElseThrow().lines())
                .as("no synthetic line was written for the marked good either")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "an order with no marked line is filed as its real lines when marked goods are wired, codes and all absent")
    void anOrdinaryOrderIsUnchangedByTheMarkingPath() {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        CapturingClickPort click = new CapturingClickPort();

        Outcome outcome = capturingBridge(click).retry(TENANT, documentId, "idem-marked-4");

        assertThat(outcome).isEqualTo(Outcome.ISSUED);
        assertThat(click.sent.get(0).lines()).hasSize(1);
        assertThat(click.sent.get(0).lines().get(0).markingCodes()).isEmpty();
        assertThat(click.sent.get(0).lines().get(0).mxikCode()).isEqualTo("10901001001000000");
    }

    // -----------------------------------------------------------------------
    // The TIN a receipt is filed under is the one its document was opened under (V0551's snapshot),
    // never today's resolution of the order's location
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "a marked order is filed under the seller its document was opened under, after the branch changed hands")
    void aMarkedOrderIsFiledUnderTheSnapshotSellerAfterTheBranchChangedHands() {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        String openedUnder = taxpayerNumberOf(CLICK_ENTITY);
        handTheBranchOverTo(SUCCESSOR_ENTITY, "SUCCESSOR");
        CapturingClickPort click = new CapturingClickPort();

        Outcome outcome = markedBridge(click, completeVodka(2)).retry(TENANT, documentId, "idem-handover");

        assertThat(outcome).isEqualTo(Outcome.ISSUED);
        assertThat(click.sent.get(0).lines())
                .as("the receipt names the taxpayer the sale was opened under, and is not zeros")
                .extracting(FiscalReceiptLine::commissionTin)
                .containsOnly(openedUnder);
    }

    @Test
    @DisplayName(
            "a marked order is filed under the TIN its document snapshotted when the entity's TIN has since changed")
    void aMarkedOrderIsFiledUnderTheSnapshotTinAfterTheEntityWasReRegistered() {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        String openedUnder = taxpayerNumberOf(CLICK_ENTITY);
        jdbc.sql("UPDATE tenant.legal_entities SET tin = '987654321' WHERE tenant_id = :t AND id = :id")
                .param("t", TENANT)
                .param("id", CLICK_ENTITY)
                .update();
        CapturingClickPort click = new CapturingClickPort();

        Outcome outcome = markedBridge(click, completeVodka(2)).retry(TENANT, documentId, "idem-reregistered");

        assertThat(outcome).isEqualTo(Outcome.ISSUED);
        assertThat(click.sent.get(0).lines())
                .extracting(FiscalReceiptLine::commissionTin)
                .containsOnly(openedUnder)
                .doesNotContain("987654321");
    }

    @Test
    @DisplayName("a marked order whose document holds no seller snapshot sends nothing, rather than filing zeros")
    void aMarkedOrderWithNoSellerSnapshotIsNotFiledUnderZeros() {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        withoutSellerSnapshot(documentId);
        CapturingClickPort click = new CapturingClickPort();

        Outcome outcome = markedBridge(click, completeVodka(2)).retry(TENANT, documentId, "idem-no-snapshot");

        assertThat(outcome).isEqualTo(Outcome.REJECTED);
        assertThat(click.sent)
                .as("a real receipt with real codes is never filed under a TIN of zeros")
                .isEmpty();
    }

    @Test
    @DisplayName("an ordinary order is filed under the snapshot seller too, after the branch changed hands")
    void anOrdinaryOrderIsFiledUnderTheSnapshotSellerToo() {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        String openedUnder = taxpayerNumberOf(CLICK_ENTITY);
        handTheBranchOverTo(SUCCESSOR_ENTITY, "SUCCESSOR");
        CapturingClickPort click = new CapturingClickPort();

        capturingBridge(click).retry(TENANT, documentId, "idem-ordinary-tin");

        assertThat(click.sent)
                .singleElement()
                .satisfies(sent -> assertThat(sent.lines())
                        .extracting(FiscalReceiptLine::commissionTin)
                        .containsOnly(openedUnder));
    }

    @Test
    @DisplayName("an order whose document holds no seller snapshot sends nothing, rather than filing zeros")
    void anOrderWithNoSellerSnapshotIsNotFiledUnderZeros() {
        seedClickBinding();
        UUID documentId = seedPendingDocument(seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY));
        withoutSellerSnapshot(documentId);
        CapturingClickPort click = new CapturingClickPort();

        Outcome outcome = capturingBridge(click).retry(TENANT, documentId, "idem-ordinary-no-snapshot");

        assertThat(outcome).isEqualTo(Outcome.REJECTED);
        assertThat(click.sent).isEmpty();
    }

    private static final UUID SUCCESSOR_ENTITY = UUID.randomUUID();

    private static String taxpayerNumberOf(UUID legalEntityId) {
        return jdbc.sql("SELECT tin FROM tenant.legal_entities WHERE tenant_id = :t AND id = :id")
                .param("t", TENANT)
                .param("id", legalEntityId)
                .query(String.class)
                .single();
    }

    /** The branch's assignment ends on 1 August and another company's starts: today resolves to the successor. */
    private static void handTheBranchOverTo(UUID successor, String code) {
        jdbc.sql("""
                UPDATE tenant.location_fiscal_assignments SET effective_until = DATE '2026-08-01'
                 WHERE tenant_id = :t AND location_id = :l AND effective_until IS NULL
                """).param("t", TENANT).param("l", LOCATION).update();
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, vat_registered, status)
                VALUES (:id, :t, :code, :name, '123456789', false, 'ACTIVE')
                """)
                .param("id", successor)
                .param("t", TENANT)
                .param("code", code)
                .param("name", code + " LLC")
                .update();
        jdbc.sql("""
                INSERT INTO tenant.location_fiscal_assignments (id, tenant_id, brand_id, location_id,
                    legal_entity_id, effective_from, approved_by)
                VALUES (:id, :t, :b, :l, :e, DATE '2026-08-01', 'PartnerFiscalizationBridgeTests')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("l", LOCATION)
                .param("e", successor)
                .update();
    }

    /** A row from before the snapshot existed: V0551's keep-trigger refuses a rewrite, so it is lifted for one statement. */
    private static void withoutSellerSnapshot(UUID documentId) {
        jdbc.sql("ALTER TABLE fiscal.fiscal_documents DISABLE TRIGGER trg_fiscal_document_keep_seller_snapshot")
                .update();
        try {
            jdbc.sql("""
                    UPDATE fiscal.fiscal_documents
                       SET seller_tin = NULL, seller_name = NULL, seller_snapshot_source = NULL
                     WHERE id = :id
                    """).param("id", documentId).update();
        } finally {
            jdbc.sql("ALTER TABLE fiscal.fiscal_documents ENABLE TRIGGER trg_fiscal_document_keep_seller_snapshot")
                    .update();
        }
    }

    private static final UUID VODKA_VARIANT = UUID.randomUUID();
    private static final String MARK_CODE_1 = "010460000000001521BridgeSerial0001";
    private static final String MARK_CODE_2 = "010460000000001521BridgeSerial0002";

    private OrderMarksPort.ReceiptSource completeVodka(int units) {
        List<OrderMarksPort.MarkRef> refs = new java.util.ArrayList<>();
        String[] codes = {MARK_CODE_1, MARK_CODE_2};
        for (int i = 0; i < units; i++) {
            refs.add(markRef(i + 1, codes[i]));
        }
        return new OrderMarksPort.ReceiptSource(
                BRAND,
                "UZS",
                List.of(new OrderMarksPort.ReceiptLine(
                        UUID.randomUUID(),
                        VODKA_VARIANT,
                        "Vodka",
                        java.math.BigDecimal.valueOf(units),
                        7_500L * units,
                        0L,
                        true,
                        units,
                        refs)));
    }

    private OrderMarksPort.ReceiptSource shortVodka() {
        return new OrderMarksPort.ReceiptSource(
                BRAND,
                "UZS",
                List.of(new OrderMarksPort.ReceiptLine(
                        UUID.randomUUID(),
                        VODKA_VARIANT,
                        "Vodka",
                        java.math.BigDecimal.valueOf(2),
                        15_000L,
                        0L,
                        true,
                        2,
                        List.of(markRef(1, MARK_CODE_1)))));
    }

    private static OrderMarksPort.ReceiptSource ordinaryOnly() {
        return new OrderMarksPort.ReceiptSource(
                BRAND,
                "UZS",
                List.of(new OrderMarksPort.ReceiptLine(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Plov",
                        java.math.BigDecimal.ONE,
                        15_000L,
                        0L,
                        false,
                        0,
                        List.of())));
    }

    private final uz.horecaos.platform.iam.api.protection.FieldProtection protection =
            uz.horecaos.platform.support.TestProtection.envelope();

    private OrderMarksPort.MarkRef markRef(int sequence, String code) {
        UUID id = UUID.randomUUID();
        String envelope = protection
                .protect(
                        TENANT,
                        uz.horecaos.platform.iam.api.protection.DataClass.FINANCIAL,
                        new uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef(
                                OrderMarksPort.CODE_TABLE, OrderMarksPort.CODE_COLUMN, id),
                        code)
                .serialize();
        return new OrderMarksPort.MarkRef(id, sequence, "04600000000015", envelope);
    }

    /** A bridge whose marking source is {@code source}, over an order made of the same lines. */
    private PartnerFiscalizationBridge markedBridge(CapturingClickPort click, OrderMarksPort.ReceiptSource source) {
        world.marks = source;
        world.orderFromMarks(source);
        world.classes.put(
                VODKA_VARIANT,
                new FiscalClassificationFacts("22030000000000000", "123456", 796, "Vodka", "4600000000015", true));
        return capturingBridge(click);
    }

    /** A bridge over {@code click}, which says it can carry codes and remembers every document it is sent. */
    private PartnerFiscalizationBridge capturingBridge(CapturingClickPort click) {
        PaymentFiscalService service = new PaymentFiscalService(
                documents,
                List.of(click, new PaymeFiscalAdapter(intents, CLOCK)),
                event -> {},
                new RecordingProviderActivityRecorder());
        fiscalService = service;
        return new PartnerFiscalizationBridge(service, bindings, world.builder(service), CLOCK);
    }

    /** A Click port that says it can carry codes and remembers every document it is asked to send. */
    private static final class CapturingClickPort implements FiscalReceiptPort {

        final List<FiscalDocument> sent = new java.util.ArrayList<>();

        @Override
        public FiscalSubmission submit(
                FiscalDocument document, uz.horecaos.platform.payments.domain.ProviderBinding binding) {
            sent.add(document);
            return FiscalSubmission.issued(
                    new FiscalDocument.FiscalEvidence(
                            "R-M", "SIGN-M", "TERM-M", "R-M", CLOCK.instant(), "https://ofd.soliq.uz/r/m", null, null),
                    CLOCK.instant());
        }

        @Override
        public PaymentProviderType providerType() {
            return PaymentProviderType.CLICK;
        }

        @Override
        public boolean carriesMarkingCodes() {
            return true;
        }
    }

    // -----------------------------------------------------------------------
    // Payme: the lines go with the checkout, and the filing sends exactly those (ADR 0166 Decisions 6 and 9)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "a Payme checkout keeps the lines it carried behind a protected reference, and a rebuild is not what is filed")
    void aPaymeCheckoutKeepsItsLinesAndTheFilingSendsThoseAsSent() throws Exception {
        seedPaymeBinding();
        UUID intentId = seedIntent(PaymentProviderType.PAYME, PAYME_ENTITY);
        PaymentFiscalService service = paymeService();
        ProtectedPresentedReceipts presented = presented(service);
        PaymentAttempt attempt = seedPaymeAttempt(intentId);

        // 1. The checkout is presented: the lines are built from the order and kept on the attempt.
        var prepared = presented.prepare(attempt, ORDER);
        assertThat(prepared).isInstanceOf(PresentedReceipts.Prepared.Ready.class);
        String reference = new JdbcPaymentAttemptStore(jdbc)
                .presentedReceiptReference(TENANT, attempt.id())
                .orElseThrow();
        assertThat(reference)
                .as("a protected reference, not the lines")
                .doesNotContain("Plov")
                .doesNotContain("10901001001000000");

        // 2. The catalogue moves before the order completes, as it can: a rebuild would now say something else.
        world.classes.put(
                World.PLOV_VARIANT,
                new FiscalClassificationFacts("99999999999999999", "9999999", 796, "Renamed plov", null, false));
        UUID documentId = seedPendingDocument(intentId);
        PartnerFiscalizationBridge bridge =
                new PartnerFiscalizationBridge(service, bindings, world.builder(service), presented, CLOCK);

        Outcome outcome = bridge.retry(TENANT, documentId, "idem-payme");

        // 3. What was filed is what Payme was given: the document keeps those lines as its sent request.
        assertThat(outcome).isEqualTo(Outcome.UNCERTAIN);
        List<Map<String, Object>> sent = sentRequestsOf(documentId);
        assertThat(sent).hasSize(1);
        assertThat(sentLineNames(documentId, sent.get(0))).containsExactly("Plov");
        assertThat(sentEvidenceOf(documentId).get("sent_tender_split_reference"))
                .as("the whole charge went through the provider as one payment")
                .isNotNull();
        assertThat(fiscalService.find(TENANT, documentId).orElseThrow().status())
                .isEqualTo(FiscalStatus.SUBMITTED);
    }

    @Test
    @DisplayName("a re-presentation of a Payme checkout carries the lines the first one fixed, not a rebuild")
    void aRePresentationCarriesTheFirstLines() {
        seedPaymeBinding();
        UUID intentId = seedIntent(PaymentProviderType.PAYME, PAYME_ENTITY);
        ProtectedPresentedReceipts presented = presented(paymeService());
        PaymentAttempt attempt = seedPaymeAttempt(intentId);
        presented.prepare(attempt, ORDER);

        world.classes.put(
                World.PLOV_VARIANT,
                new FiscalClassificationFacts("99999999999999999", "9999999", 796, "Renamed plov", null, false));
        var again = presented.prepare(attempt, ORDER);

        assertThat(again).isInstanceOfSatisfying(PresentedReceipts.Prepared.Ready.class, ready -> {
            assertThat(ready.lines()).hasSize(1);
            assertThat(ready.lines().get(0).fiscalName()).isEqualTo("Plov");
            assertThat(ready.lines().get(0).mxikCode()).isEqualTo("10901001001000000");
        });
        assertThat(presented.lines(TENANT, attempt.id()).orElseThrow().get(0).fiscalName())
                .isEqualTo("Plov");
    }

    @Test
    @DisplayName("a Payme checkout whose order has an unclassified node is refused with the obstacle and keeps nothing")
    void anUnbuildablePaymeReceiptKeepsNothing() {
        seedPaymeBinding();
        UUID intentId = seedIntent(PaymentProviderType.PAYME, PAYME_ENTITY);
        ProtectedPresentedReceipts presented = presented(paymeService());
        PaymentAttempt attempt = seedPaymeAttempt(intentId);
        world.classes.clear();

        var prepared = presented.prepare(attempt, ORDER);

        assertThat(prepared)
                .isInstanceOfSatisfying(
                        PresentedReceipts.Prepared.Refused.class,
                        refused -> assertThat(refused.code()).isEqualTo("CLASSIFICATION:CLASSIFICATION_MISSING"));
        assertThat(new JdbcPaymentAttemptStore(jdbc).presentedReceiptReference(TENANT, attempt.id()))
                .isEmpty();
        assertThat(presented.lines(TENANT, attempt.id())).isEmpty();
    }

    @Test
    @DisplayName("the kept lines are bound to their attempt: the same ciphertext does not open under another")
    void theKeptLinesAreBoundToTheirAttempt() {
        seedPaymeBinding();
        UUID intentId = seedIntent(PaymentProviderType.PAYME, PAYME_ENTITY);
        ProtectedPresentedReceipts presented = presented(paymeService());
        PaymentAttempt attempt = seedPaymeAttempt(intentId);
        presented.prepare(attempt, ORDER);
        String reference = new JdbcPaymentAttemptStore(jdbc)
                .presentedReceiptReference(TENANT, attempt.id())
                .orElseThrow();

        assertThatThrownBy(() -> protection.reveal(
                        TENANT,
                        ProtectedValue.deserialize(reference),
                        ProtectedPresentedReceipts.recordOf(UUID.randomUUID()),
                        "test"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName(
            "a Click document is built from the order as before: Payme's presented lines are never consulted for it")
    void clickIsUntouchedByPresentedLines() {
        seedClickBinding();
        UUID intentId = seedIntent(PaymentProviderType.CLICK, CLICK_ENTITY);
        UUID documentId = seedPendingDocument(intentId);
        ScriptedClick click = new ScriptedClick();
        PaymentFiscalService service = new PaymentFiscalService(
                documents, List.of(click), event -> {}, new RecordingProviderActivityRecorder());
        fiscalService = service;
        PresentedReceipts neverAsked = new PresentedReceipts() {
            @Override
            public Prepared prepare(PaymentAttempt attempt, UUID orderId) {
                throw new AssertionError("Click never prepares Payme lines");
            }

            @Override
            public Optional<List<FiscalReceiptLine>> lines(UUID tenantId, UUID attemptId) {
                throw new AssertionError("not for Click");
            }

            @Override
            public Optional<List<FiscalReceiptLine>> linesOfIntent(UUID tenantId, UUID intentId) {
                throw new AssertionError("not for Click");
            }
        };

        Outcome outcome = new PartnerFiscalizationBridge(service, bindings, world.builder(service), neverAsked, CLOCK)
                .retry(TENANT, documentId, "idem-click");

        assertThat(outcome).isNotEqualTo(Outcome.NO_PROVIDER_PATH);
        assertThat(click.submitted).hasSize(1);
        assertThat(click.submitted.get(0).lines()).hasSize(1);
    }

    private PaymentFiscalService paymeService() {
        PaymentFiscalService service = new PaymentFiscalService(
                documents,
                List.of(new PaymeFiscalAdapter(intents, CLOCK)),
                event -> {},
                new RecordingProviderActivityRecorder(),
                new ProtectedSentEvidenceRecorder(
                        TestProtection.envelope(),
                        documents,
                        tools.jackson.databind.json.JsonMapper.builder().build()));
        fiscalService = service;
        return service;
    }

    private ProtectedPresentedReceipts presented(PaymentFiscalService service) {
        return new ProtectedPresentedReceipts(
                world.builder(service),
                protection,
                new JdbcPaymentAttemptStore(jdbc),
                tools.jackson.databind.json.JsonMapper.builder().build(),
                CLOCK);
    }

    private PaymentAttempt seedPaymeAttempt(UUID intentId) {
        UUID merchantBinding = jdbc.sql("SELECT id FROM payments.merchant_bindings WHERE provider_type = 'PAYME'")
                .query(UUID.class)
                .single();
        PaymentAttempt attempt = new PaymentAttempt(
                UUID.randomUUID(),
                TENANT,
                intentId,
                PaymentProviderType.PAYME,
                merchantBinding,
                "mtid-" + UUID.randomUUID(),
                java.time.LocalDate.of(2026, 8, 24),
                null,
                null,
                new SomAmount(15_000L, "UZS"),
                PaymentAttemptStatus.INITIATED,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                CLOCK.instant(),
                null);
        new JdbcPaymentAttemptStore(jdbc).insert(attempt);
        return attempt;
    }

    // ------------------------------------------------------------------- helpers

    /**
     * The bridge as production wires it: the real builder reading the world's order, and the real recorder
     * behind the real envelope protection, over a Click port that records what it is asked to send and
     * answers like the real adapter does for a request that left.
     */
    private PartnerFiscalizationBridge realBridge(ScriptedClick click) {
        PaymentFiscalService service = new PaymentFiscalService(
                documents,
                List.of(click),
                event -> {},
                new RecordingProviderActivityRecorder(),
                new ProtectedSentEvidenceRecorder(
                        TestProtection.envelope(),
                        documents,
                        tools.jackson.databind.json.JsonMapper.builder().build()));
        fiscalService = service;
        return new PartnerFiscalizationBridge(service, bindings, world.builder(service), CLOCK);
    }

    /** Every request that left for the document, oldest first (V0643). */
    private List<Map<String, Object>> sentRequestsOf(UUID documentId) {
        return jdbc.sql("""
                        SELECT attempt, sent_at, protected_request_reference
                          FROM fiscal.fiscal_sent_requests WHERE document_id = :id ORDER BY attempt
                        """).param("id", documentId).query().listOfRows();
    }

    private List<String> sentLineNames(UUID documentId, Map<String, Object> kept) throws Exception {
        String plain = protection.reveal(
                TENANT,
                ProtectedValue.deserialize(
                        java.util.Objects.requireNonNull((String) kept.get("protected_request_reference"))),
                SentReceiptEvidence.requestRecord(documentId),
                "bridge-test");
        return tools.jackson.databind.json.JsonMapper.builder()
                .build()
                .readValue(plain, SentReceiptEvidence.Lines.class)
                .lines()
                .stream()
                .map(SentReceiptEvidence.Line::name)
                .toList();
    }

    private Map<String, Object> sentEvidenceOf(UUID documentId) {
        return jdbc.sql("""
                        SELECT lines_synthetic, protected_request_reference, sent_tender_split_reference,
                               merchant_binding_id
                          FROM fiscal.fiscal_documents WHERE id = :id
                        """).param("id", documentId).query().singleRow();
    }

    /** A Click port that records the receipts it is asked to file, and answers as the real adapter does. */
    private static final class ScriptedClick implements FiscalReceiptPort {

        final List<SentReceipt> submitted = new java.util.ArrayList<>();
        boolean sends = true;

        @Override
        public FiscalSubmission submit(
                FiscalDocument document, uz.horecaos.platform.payments.domain.ProviderBinding binding) {
            SomAmount total = document.lines().stream()
                    .map(FiscalReceiptLine::netTotal)
                    .reduce(SomAmount::plus)
                    .orElseThrow();
            SentReceipt wire = new SentReceipt(
                    document.lines(), total, new SomAmount(0, total.currency()), new SomAmount(0, total.currency()));
            submitted.add(wire);
            FiscalSubmission accepted = FiscalSubmission.accepted(CLOCK.instant());
            return sends ? accepted.withSent(wire) : accepted;
        }

        @Override
        public PaymentProviderType providerType() {
            return PaymentProviderType.CLICK;
        }
    }

    private PartnerFiscalizationBridge bridge() {
        return new PartnerFiscalizationBridge(fiscalService, bindings, world.builder(fiscalService), CLOCK);
    }

    /** Fails the test if the fake provider transport is ever reached. */
    private static MerchantApiTransport refusingTransport() {
        return (MerchantApiCall call) -> {
            throw new AssertionError("this test must never reach a provider transport: " + call);
        };
    }

    private UUID seedPendingDocument(UUID paymentIntentId) {
        // The FK from fiscal.fiscal_documents to payments.payment_intents means the
        // intent must already be a real row — see seedIntent, always called first.
        PaymentIntent intent = intents.find(TENANT, paymentIntentId).orElseThrow();
        return fiscalService.openPartnerObligation(intent, CLOCK.instant());
    }

    private UUID seedIntent(PaymentProviderType providerType, UUID legalEntityId) {
        return seedIntent(providerType, legalEntityId, 15_000L);
    }

    private UUID seedIntent(PaymentProviderType providerType, UUID legalEntityId, long amountMinor) {
        UUID intentId = UUID.randomUUID();
        intents.insert(new PaymentIntent(
                intentId,
                TENANT,
                ORDER,
                BRAND,
                LOCATION,
                null,
                legalEntityId,
                PaymentTender.PROVIDER,
                providerType == PaymentProviderType.CLICK ? PaymentMethod.CLICK : PaymentMethod.PAYME,
                providerType,
                new SomAmount(amountMinor, "UZS"),
                PaymentIntentStatus.PENDING,
                CaptureTiming.BEFORE_CONFIRMATION,
                UUID.randomUUID().toString(),
                1,
                CLOCK.instant(),
                null));
        // insert() never writes settled_at (see JdbcPaymentIntentStore); PAID
        // requires one, per ck_payment_intent_settled, so this moves it the same
        // way a real capture would.
        intents.transition(TENANT, intentId, PaymentIntentStatus.PENDING, PaymentIntentStatus.PAID, 1, CLOCK.instant());
        return intentId;
    }

    private static void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status)
                VALUES (:id, 'bridgetests', 'Bridge Tests LLC', 'Bridge Tests', 'UZS',
                    'Asia/Tashkent', 'ACTIVE')
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'BRAND1', 'brand-one', 'Brand One', 'ACTIVE')
                """).param("id", BRAND).param("tenantId", TENANT).update();
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

        // The order every intent in this file references. Only what payment_intents
        // itself needs by FK — no channel, catalog, or quote is read by anything
        // under test here.
        UUID channel = UUID.randomUUID();
        UUID catalog = UUID.randomUUID();
        UUID publication = UUID.randomUUID();
        UUID quote = UUID.randomUUID();
        UUID cart = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
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
        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency, status,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', 'ACTIVE', :publicationId, 1,
                    'hash', 15000, 0, 15000, now() + interval '1 day')
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
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'guest-hash', 'DELIVERY',
                    'UZS', 'CHECKOUT_IN_PROGRESS', :quoteId, 'hash', :publicationId,
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
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key,
                    confirmed_at, closed_at)
                VALUES (
                    :id, 'BR-1', :tenantId, :brandId, :locationId, :channelId, 'WEB', 'guest-hash',
                    'DELIVERY', 'AUTO_CONFIRM', 'NONE', 'COMPLETED', 'UZS', 15000, 0, 15000,
                    :quoteId, 'hash', :publicationId, :cartId, :idempotencyKey, now(), now())
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

    private void seedClickBinding() {
        seedLegalEntity(CLICK_ENTITY, "CLICKENT");
        seedInstallation(CLICK_INSTALLATION, "CLICK", "click-bridge-tests");
        seedIntegrationBinding(CLICK_INSTALLATION);
        jdbc.sql("""
                INSERT INTO payments.merchant_bindings (id, tenant_id, legal_entity_id,
                    provider_type, installation_id, binding_id, merchant_account_reference,
                    merchant_user_reference, secret_reference, callback_path_segment,
                    supports_reversal, supports_partner_fiscalization, status, effective_from)
                VALUES (:id, :tenantId, :legalEntityId, 'CLICK', :installationId, :bindingId,
                    'service-1', '4444', 'horecaos:test:provider_payment:tenant:click',
                    'bridge-tests-click', true, true, 'ACTIVE', DATE '2020-01-01')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("legalEntityId", CLICK_ENTITY)
                .param("installationId", CLICK_INSTALLATION)
                .param("bindingId", INTEGRATION_BINDING)
                .update();
    }

    private void seedPaymeBinding() {
        seedLegalEntity(PAYME_ENTITY, "PAYMEENT");
        seedInstallation(PAYME_INSTALLATION, "PAYME", "payme-bridge-tests");
        seedIntegrationBinding(PAYME_INSTALLATION);
        jdbc.sql("""
                INSERT INTO payments.merchant_bindings (id, tenant_id, legal_entity_id,
                    provider_type, installation_id, binding_id, merchant_account_reference,
                    secret_reference, callback_path_segment, supports_reversal,
                    supports_partner_fiscalization, status, effective_from)
                VALUES (:id, :tenantId, :legalEntityId, 'PAYME', :installationId, :bindingId,
                    'cashbox-1', 'horecaos:test:provider_payment:tenant:payme',
                    'bridge-tests-payme', false, true, 'ACTIVE', DATE '2020-01-01')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("legalEntityId", PAYME_ENTITY)
                .param("installationId", PAYME_INSTALLATION)
                .param("bindingId", INTEGRATION_BINDING)
                .update();
    }

    private void seedLegalEntity(UUID id, String code) {
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin,
                    vat_registered, status)
                VALUES (:id, :tenantId, :code, :name, :tin, false, 'ACTIVE')
                ON CONFLICT DO NOTHING
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("code", code)
                .param("name", code + " LLC")
                .param("tin", String.format("%09d", Math.floorMod(id.hashCode(), 1_000_000_000)))
                .update();
        jdbc.sql("""
                INSERT INTO tenant.location_fiscal_assignments (id, tenant_id, brand_id,
                    location_id, legal_entity_id, effective_from, approved_by)
                VALUES (:id, :tenantId, :brandId, :locationId, :legalEntityId, DATE '2020-01-01',
                    'PartnerFiscalizationBridgeTests')
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("legalEntityId", id)
                .update();
    }

    private void seedInstallation(UUID id, String providerType, String environmentCode) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'PAYMENT', :providerType, 'https://example.invalid', false, 'example.invalid')
                ON CONFLICT DO NOTHING
                """)
                .param("code", environmentCode)
                .param("providerType", providerType)
                .update();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status, secret_reference)
                VALUES (:id, :tenantId, 'PAYMENT', :providerType, :environmentCode, :name, 'ACTIVE',
                    :secretReference)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("providerType", providerType)
                .param("environmentCode", environmentCode)
                .param("name", providerType + " (bridge tests)")
                .param(
                        "secretReference",
                        "horecaos:test:provider_payment:tenant:" + providerType.toLowerCase(Locale.ROOT))
                .update();
    }

    private void seedIntegrationBinding(UUID installationId) {
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'ACTIVE')
                ON CONFLICT (id) DO UPDATE SET installation_id = EXCLUDED.installation_id
                """)
                .param("id", INTEGRATION_BINDING)
                .param("tenantId", TENANT)
                .param("installationId", installationId)
                .param("brandId", BRAND)
                .update();
    }
    // ------------------------------------------------------------------ the order under test

    /**
     * The order the builder reads, its classifications and its settlement, stated in one place and changed
     * by the test that needs a different order. The default is one dish for the whole payment of 15 000, at
     * 12 percent, classified.
     */
    private final class World {

        static final UUID PLOV_VARIANT = UUID.randomUUID();
        static final UUID CHEESE_OPTION = UUID.randomUUID();
        static final UUID LINE = UUID.randomUUID();

        /** The line's gross, modifier options included. */
        long plovGross = 15_000;

        List<OrderReceiptSource.Option> options = List.of();
        long fee = 0;
        long redeemed = 0;
        OrderReceiptSource.@Nullable Tax tax = new OrderReceiptSource.Tax(1200, true);
        Map<UUID, FiscalClassificationFacts> classes = new java.util.HashMap<>();
        Optional<FiscalClassificationFacts> feeClassification = Optional.empty();
        OrderMarksPort.ReceiptSource marks = new OrderMarksPort.ReceiptSource(BRAND, "UZS", List.of());

        @Nullable
        List<OrderReceiptSource.Line> lines = null;

        World() {
            classes.put(
                    PLOV_VARIANT,
                    new FiscalClassificationFacts("10901001001000000", "1500123", 796, "Plov", null, false));
            classes.put(
                    CHEESE_OPTION,
                    new FiscalClassificationFacts("04001001001000000", "1500456", 796, "Extra cheese", null, false));
        }

        /** Plov 10 000 with 3 000 of extra cheese and 2 000 of delivery: the whole payment of 15 000. */
        void dishWithCheeseAndDeliveryFee() {
            plovGross = 13_000;
            options = List.of(new OrderReceiptSource.Option(CHEESE_OPTION, "Extra cheese", 3_000, false));
            fee = 2_000;
            feeClassification = Optional.of(
                    new FiscalClassificationFacts("10701001001000000", "7000001", 796, "Delivery", null, false));
        }

        void orderFromMarks(OrderMarksPort.ReceiptSource source) {
            lines = source.lines().stream()
                    .map(line -> new OrderReceiptSource.Line(
                            line.lineId(),
                            1,
                            line.variantId(),
                            line.productName(),
                            line.quantity(),
                            line.finalAmountMinor(),
                            line.finalAmountMinor(),
                            line.taxAmountMinor(),
                            null,
                            List.of(),
                            0))
                    .toList();
        }

        OrderReceiptSource.ReceiptOrder order() {
            List<OrderReceiptSource.Line> built = lines != null
                    ? lines
                    : List.of(new OrderReceiptSource.Line(
                            LINE,
                            1,
                            PLOV_VARIANT,
                            "Plov",
                            java.math.BigDecimal.ONE,
                            plovGross,
                            plovGross,
                            Math.round(plovGross * 12 / 112.0),
                            null,
                            options,
                            0));
            long goods = built.stream()
                    .mapToLong(OrderReceiptSource.Line::finalMinor)
                    .sum();
            long taxMinor =
                    built.stream().mapToLong(OrderReceiptSource.Line::taxMinor).sum();
            return new OrderReceiptSource.ReceiptOrder(
                    ORDER, BRAND, LOCATION, "UZS", goods + fee, taxMinor, 0, fee, UUID.randomUUID(), tax, built);
        }

        ReceiptLineBuilder builder(PaymentFiscalService service) {
            OrderReceiptSource source = (tenantId, orderId) -> Optional.of(order());
            OrderMarksPort marksPort = new OrderMarksPort() {
                @Override
                public List<LineMarkCount> markCounts(UUID tenantId, UUID orderId) {
                    return List.of();
                }

                @Override
                public ReceiptSource receiptSource(UUID tenantId, UUID orderId) {
                    return marks;
                }
            };
            FiscalClassificationLookup lookup = new FiscalClassificationLookup() {
                @Override
                public Map<UUID, FiscalClassificationFacts> classifications(
                        UUID tenantId, UUID brandId, java.util.Set<UUID> priceableIds) {
                    return classes;
                }

                @Override
                public Optional<FiscalClassificationFacts> deliveryFee(UUID tenantId, UUID brandId) {
                    return feeClassification;
                }
            };
            JdbcReceiptPaymentFacts payments = new JdbcReceiptPaymentFacts(intents, new JdbcSettlementStore(jdbc)) {
                @Override
                public long pointsRedeemedMinor(UUID tenantId, UUID orderId) {
                    return redeemed;
                }
            };
            return new ReceiptLineBuilder(source, marksPort, lookup, payments, service, protection, fact -> {}, CLOCK);
        }
    }
}
