package uz.qoida.platform.payments.payme;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import uz.qoida.platform.iam.api.secrets.SecretCategory;
import uz.qoida.platform.iam.api.secrets.SecretReference;
import uz.qoida.platform.ordering.api.OrderDirectory;
import uz.qoida.platform.payments.application.PaymentAttemptService;
import uz.qoida.platform.payments.domain.CaptureTiming;
import uz.qoida.platform.payments.domain.FiscalDocument;
import uz.qoida.platform.payments.domain.FiscalDocumentType;
import uz.qoida.platform.payments.domain.FiscalReason;
import uz.qoida.platform.payments.domain.FiscalReceiptLine;
import uz.qoida.platform.payments.domain.FiscalStatus;
import uz.qoida.platform.payments.domain.FiscalSubmission;
import uz.qoida.platform.payments.domain.PaymentAttempt;
import uz.qoida.platform.payments.domain.PaymentAttemptStatus;
import uz.qoida.platform.payments.domain.PaymentIntent;
import uz.qoida.platform.payments.domain.PaymentIntentStatus;
import uz.qoida.platform.payments.domain.PaymentMethod;
import uz.qoida.platform.payments.domain.PaymentProviderType;
import uz.qoida.platform.payments.domain.PaymentTender;
import uz.qoida.platform.payments.domain.PresentationKind;
import uz.qoida.platform.payments.domain.ProviderBinding;
import uz.qoida.platform.payments.domain.ProviderOutcome;
import uz.qoida.platform.payments.domain.SomAmount;
import uz.qoida.platform.payments.infrastructure.payme.JdbcPaymeTransactionView;
import uz.qoida.platform.payments.infrastructure.payme.PaymeFiscalAdapter;
import uz.qoida.platform.payments.infrastructure.payme.PaymeMerchantApi;
import uz.qoida.platform.payments.infrastructure.payme.PaymeRpcException;
import uz.qoida.platform.payments.infrastructure.persistence.JdbcFiscalDocumentStore;
import uz.qoida.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.qoida.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fiscalization on Payme, in both directions.
 *
 * <p>Payme's timing is the inverse of Click's. The lines are fixed <em>before</em>
 * the customer pays, and the outcome comes back <em>afterwards</em>, inbound and
 * asynchronously, through {@code SetFiscalData} — a method Payme is not obliged to
 * call and which offers no merchant-initiated retry. Three consequences are tested
 * here, and all three are ways the happy-path example in the docs misleads.
 */
class PaymeFiscalTests {

    private static final String UZS = "UZS";
    private static final String ORDER_REFERENCE = "149d439536b3216fdaeeb975729fae92";
    private static final String PAYME_ID = "61396aaed8b87a4c215ae556";
    private static final long AMOUNT_SOM = 50_000L;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final UUID tenantId = UUID.randomUUID();
    private final UUID bindingId = UUID.randomUUID();
    private final UUID intentId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID saleDocumentId = UUID.randomUUID();

    private final Instant now = Instant.parse("2026-08-22T09:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private JdbcPaymentAttemptStore attempts;
    private JdbcPaymentIntentStore intents;
    private JdbcFiscalDocumentStore fiscalDocuments;
    private PaymeMerchantApi api;
    private ProviderBinding binding;

    @BeforeEach
    void setUp() {
        attempts = mock(JdbcPaymentAttemptStore.class);
        intents = mock(JdbcPaymentIntentStore.class);
        fiscalDocuments = mock(JdbcFiscalDocumentStore.class);

        api = new PaymeMerchantApi(attempts, intents, fiscalDocuments,
                mock(JdbcPaymeTransactionView.class), mock(PaymentAttemptService.class),
                mock(OrderDirectory.class), clock);
        binding = binding();

        when(attempts.findByExternalPaymentId(eq(tenantId), eq(PaymentProviderType.PAYME),
                eq(PAYME_ID))).thenReturn(Optional.of(attempt()));
        when(intents.find(eq(tenantId), eq(intentId))).thenReturn(Optional.of(intent()));
        when(fiscalDocuments.listForOrder(tenantId, orderId)).thenReturn(List.of(sale()));
    }

    // -----------------------------------------------------------------------
    // SetFiscalData, inbound
    // -----------------------------------------------------------------------

    /**
     * The happy path, and the two identifiers a document needs before it may be
     * called issued.
     *
     * <p>{@code date} is {@code yyyyMMddHHmmss} in every example, with no timezone
     * stated anywhere (U16). It is read as Tashkent local time pending an answer from
     * Payme: a receipt registered at 23:10 local would otherwise be filed on the
     * previous business date, which is the kind of error a tax inspection finds.
     */
    @Test
    @DisplayName("a PERFORM with status_code 0 issues the sale document")
    void issuesTheSaleReceipt() {
        api.dispatch(binding, "SetFiscalData", fiscalParams("PERFORM", 0, "accepted"));

        ArgumentCaptor<FiscalDocument.FiscalEvidence> evidence =
                ArgumentCaptor.forClass(FiscalDocument.FiscalEvidence.class);
        verify(fiscalDocuments).recordEvidence(eq(tenantId), eq(saleDocumentId),
                eq(FiscalStatus.ISSUED), eq(FiscalReason.PARTNER_FISCALIZED), evidence.capture(),
                any(), eq(now));

        assertThat(evidence.getValue().fiscalSign()).isEqualTo("800031554082");
        assertThat(evidence.getValue().terminalId()).isEqualTo("EP000000000025");
        assertThat(evidence.getValue().registeredAt())
                // 2022-07-06 22:10:21 in Tashkent, which is UTC+5.
                .isEqualTo(Instant.parse("2022-07-06T17:10:21Z"));
    }

    /**
     * <strong>The callback arriving is not proof of a receipt.</strong>
     *
     * <p>{@code status_code} is a status, and a non-zero one reports a failure to
     * register with the ОФД. The full enumeration is missing from the docs — they say
     * "list of codes below" and there is no list, on either the Merchant API or the
     * Subscribe API page (U18) — so zero is the only value treated as success and
     * everything else needs an operator. That is the safe direction to be wrong in.
     */
    @Test
    @DisplayName("a non-zero status_code fails the document rather than issuing it")
    void aNonZeroStatusCodeIsAFailure() {
        api.dispatch(binding, "SetFiscalData",
                fiscalParams("PERFORM", 14, "ОФД недоступен"));

        verify(fiscalDocuments).recordEvidence(eq(tenantId), eq(saleDocumentId),
                eq(FiscalStatus.FAILED), eq(FiscalReason.PROVIDER_REJECTED), any(), any(),
                eq(now));
    }

    /**
     * A zero that arrives without the identifiers is also a failure.
     *
     * <p>An evidence record that is only a status code is not evidence: the fiscal
     * sign is what the tax authority recognises.
     */
    @Test
    @DisplayName("status_code 0 with no fiscal sign is not a receipt")
    void aZeroWithoutAFiscalSignIsNotAReceipt() {
        api.dispatch(binding, "SetFiscalData", params("""
                {"id":"%s","type":"PERFORM",
                 "fiscal_data":{"status_code":0,"message":"accepted"}}""".formatted(PAYME_ID)));

        verify(fiscalDocuments).recordEvidence(eq(tenantId), eq(saleDocumentId),
                eq(FiscalStatus.FAILED), any(), any(), any(), eq(now));
    }

    /**
     * <strong>{@code CANCEL} is a second document, not an update.</strong>
     *
     * <p>The tax authority forms two separate receipts for a payment and its
     * reversal, and the docs show them side by side as {@code perform_data} and
     * {@code cancel_data}. Writing the cancel over the sale destroys the only record
     * that the sale was ever fiscalized — which is why there is deliberately no
     * unique index on the order in the schema.
     */
    @Test
    @DisplayName("a CANCEL creates a refund document and leaves the sale alone")
    void cancelIsASecondDocument() {
        api.dispatch(binding, "SetFiscalData", fiscalParams("CANCEL", 0, "accepted"));

        ArgumentCaptor<FiscalDocument> inserted = ArgumentCaptor.forClass(FiscalDocument.class);
        verify(fiscalDocuments).insert(inserted.capture());

        assertThat(inserted.getValue().documentType()).isEqualTo(FiscalDocumentType.REFUND);
        assertThat(inserted.getValue().correctsDocumentId()).isEqualTo(saleDocumentId);
        verify(fiscalDocuments, never()).recordEvidence(any(), eq(saleDocumentId), any(), any(),
                any(), any(), any());
    }

    /** A repeated CANCEL finds the document the first one created. */
    @Test
    @DisplayName("a repeated CANCEL does not create a second refund document")
    void cancelIsIdempotent() {
        FiscalDocument refund = new FiscalDocument(UUID.randomUUID(), tenantId, orderId,
                UUID.randomUUID(), intentId, null, PaymentProviderType.PAYME,
                FiscalDocumentType.REFUND, saleDocumentId, FiscalStatus.SUBMITTED,
                FiscalReason.AWAITING_PROVIDER, "already here", List.of(), null, 1, now);
        when(fiscalDocuments.listForOrder(tenantId, orderId)).thenReturn(List.of(sale(), refund));

        api.dispatch(binding, "SetFiscalData", fiscalParams("CANCEL", 0, "accepted"));

        verify(fiscalDocuments, never()).insert(any());
        verify(fiscalDocuments).recordEvidence(eq(tenantId), eq(refund.id()), any(), any(), any(),
                any(), eq(now));
    }

    @Test
    @DisplayName("an unrecognised type is -32602 naming the parameter")
    void refusesAnUnknownType() {
        assertThatThrownBy(() ->
                api.dispatch(binding, "SetFiscalData", fiscalParams("REVERSE", 0, "accepted")))
                .isInstanceOfSatisfying(PaymeRpcException.class,
                        failure -> assertThat(failure.code()).isEqualTo(-32602));
    }

    @Test
    @DisplayName("a receipt for a transaction Qoida does not hold is -32001")
    void unknownReceipt() {
        when(attempts.findByExternalPaymentId(eq(tenantId), eq(PaymentProviderType.PAYME),
                eq(PAYME_ID))).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                api.dispatch(binding, "SetFiscalData", fiscalParams("PERFORM", 0, "accepted")))
                .isInstanceOfSatisfying(PaymeRpcException.class,
                        failure -> assertThat(failure.code()).isEqualTo(-32001));
    }

    // -----------------------------------------------------------------------
    // The outbound port, which has nothing to send
    // -----------------------------------------------------------------------

    /**
     * A well-formed receipt is accepted, and stays {@code SUBMITTED}.
     *
     * <p>There is nothing to send: the lines travel with the checkout and the outcome
     * arrives later, so the honest state is "Payme has them and the tax authority has
     * not answered". Reaching {@code ISSUED} from here would be a claim nobody made.
     */
    @Test
    @DisplayName("a receipt that adds up is accepted and awaits SetFiscalData")
    void acceptsAWellFormedReceipt() {
        PaymeFiscalAdapter adapter = new PaymeFiscalAdapter(intents, clock);

        FiscalSubmission submission = adapter.submit(saleWith(
                line("Лагман", 2, 25_000, List.of())), binding);

        assertThat(submission.classification()).isEqualTo(ProviderOutcome.Classification.SUCCESS);
        assertThat(submission.status()).isEqualTo(FiscalStatus.SUBMITTED);
    }

    /**
     * A marked good is refused, permanently and by the provider's own shape.
     *
     * <p>Click has {@code Labels}; Payme's {@code detail} object has no field for a
     * marking code anywhere. So this is not a transient failure to retry — a tenant
     * selling marked goods must have Payme removed from the channel's payment
     * methods, and this is where that is discovered rather than at an inspection.
     */
    @Test
    @DisplayName("a marked good is rejected rather than fiscalized incompletely")
    void rejectsAMarkedGood() {
        PaymeFiscalAdapter adapter = new PaymeFiscalAdapter(intents, clock);

        FiscalSubmission submission = adapter.submit(saleWith(
                line("Вода", 2, 25_000, List.of("0104870123456789"))), binding);

        assertThat(submission.classification()).isEqualTo(ProviderOutcome.Classification.REJECTED);
        assertThat(submission.providerStatusCode()).isEqualTo("MARKING_CODES_UNSUPPORTED");
    }

    /** Lines that do not sum to the charge are refused before Payme can accept them. */
    @Test
    @DisplayName("a receipt that disagrees with the charge is rejected")
    void rejectsAMismatchedReceipt() {
        PaymeFiscalAdapter adapter = new PaymeFiscalAdapter(intents, clock);

        FiscalSubmission submission = adapter.submit(saleWith(
                line("Лагман", 1, 25_000, List.of())), binding);

        assertThat(submission.classification()).isEqualTo(ProviderOutcome.Classification.REJECTED);
        assertThat(submission.providerStatusCode()).isEqualTo("RECEIPT_DOES_NOT_MATCH_CHARGE");
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private FiscalDocument sale() {
        return new FiscalDocument(saleDocumentId, tenantId, orderId, UUID.randomUUID(), intentId,
                null, PaymentProviderType.PAYME, FiscalDocumentType.SALE, null,
                FiscalStatus.SUBMITTED, FiscalReason.AWAITING_PROVIDER, "awaiting Payme",
                List.of(), null, 1, now.minus(Duration.ofHours(1)));
    }

    private FiscalDocument saleWith(FiscalReceiptLine line) {
        return new FiscalDocument(saleDocumentId, tenantId, orderId, UUID.randomUUID(), intentId,
                null, PaymentProviderType.PAYME, FiscalDocumentType.SALE, null,
                FiscalStatus.PENDING, FiscalReason.AWAITING_CAPTURE, "awaiting capture",
                List.of(line), null, 1, now.minus(Duration.ofHours(1)));
    }

    private static FiscalReceiptLine line(String name, int quantity, long unitPriceSom,
            List<String> markingCodes) {
        return new FiscalReceiptLine(name, "00702001001000001", "1234", 241092L, quantity,
                new SomAmount(unitPriceSom, UZS), new SomAmount(unitPriceSom / 10, UZS), 12,
                null, null, markingCodes, "123456789", null);
    }

    private PaymentAttempt attempt() {
        return new PaymentAttempt(attemptId, tenantId, intentId, PaymentProviderType.PAYME,
                bindingId, ORDER_REFERENCE, LocalDate.of(2026, 8, 22), PAYME_ID, null,
                new SomAmount(AMOUNT_SOM, UZS), PaymentAttemptStatus.CAPTURED,
                PresentationKind.PAYMENT_LINK, null, now.minus(Duration.ofHours(2)),
                now.plus(Duration.ofHours(10)), null, null, 1, now.minus(Duration.ofHours(2)),
                now);
    }

    private PaymentIntent intent() {
        return new PaymentIntent(intentId, tenantId, orderId, UUID.randomUUID(), UUID.randomUUID(),
                null, UUID.randomUUID(), PaymentTender.PROVIDER, PaymentMethod.PAYME,
                PaymentProviderType.PAYME, new SomAmount(AMOUNT_SOM, UZS),
                PaymentIntentStatus.PAID, CaptureTiming.BEFORE_CONFIRMATION, "idem-" + intentId,
                1, now.minus(Duration.ofHours(2)), now);
    }

    private ProviderBinding binding() {
        return new ProviderBinding(bindingId, tenantId, UUID.randomUUID(),
                PaymentProviderType.PAYME, UUID.randomUUID(), UUID.randomUUID(),
                "587f72c72cac0d162c722ae2", null, null,
                new SecretReference("test", SecretCategory.PROVIDER_PAYMENT, "payme", "cashbox"),
                "payme-cashbox-one", false, true, LocalDate.of(2026, 1, 1), null);
    }

    /** The docs' own {@code SetFiscalData} example, with the status code varied. */
    private static JsonNode fiscalParams(String type, int statusCode, String message) {
        return params("""
                {"id":"%s","type":"%s",
                 "fiscal_data":{"receipt_id":121,"status_code":%d,"message":"%s",
                                "terminal_id":"EP000000000025","fiscal_sign":"800031554082",
                                "qr_code_url":"https://ofd.soliq.uz/check?t=EP000000000025",
                                "date":"20220706221021"}}"""
                .formatted(PAYME_ID, type, statusCode, message));
    }

    private static JsonNode params(String json) {
        return JSON.readTree(json);
    }
}
