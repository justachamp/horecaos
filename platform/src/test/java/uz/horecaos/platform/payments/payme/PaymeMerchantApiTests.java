package uz.horecaos.platform.payments.payme;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.payments.application.PaymentAttemptService;
import uz.horecaos.platform.payments.domain.CaptureTiming;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentAttemptStatus;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PaymentIntentStatus;
import uz.horecaos.platform.payments.domain.PaymentMethod;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.PaymentTender;
import uz.horecaos.platform.payments.domain.PaymentTransactionType;
import uz.horecaos.platform.payments.domain.PresentationKind;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.infrastructure.payme.JdbcPaymeTransactionView;
import uz.horecaos.platform.payments.infrastructure.payme.PaymeMerchantApi;
import uz.horecaos.platform.payments.infrastructure.payme.PaymeRpcException;
import uz.horecaos.platform.payments.infrastructure.payme.PaymeTransactionView;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcFiscalDocumentStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The seven methods, against the mapping table in the provider notes.
 *
 * <p>The account-to-order mapping is most of this integration, because Payme's
 * model is inbound: the only thing Payme knows about an order is the opaque
 * {@code account} object the checkout link carried, and the only thing that stops a
 * bad payment is the error code returned. The notes carry a docs-versus-reference
 * table for every condition, and this class is that table executed.
 *
 * <p>The stores are mocked rather than backed by a database on purpose. What is
 * under test here is the decision — which code, in which state, after which write —
 * and a schema in the way makes a wrong code look like a wrong query.
 */
class PaymeMerchantApiTests {

    private static final String UZS = "UZS";
    private static final String ORDER_REFERENCE = "149d439536b3216fdaeeb975729fae92";
    private static final String PAYME_ID = "5305e3bab097f420a62ced0b";
    private static final long AMOUNT_SOM = 150_000L;
    private static final long AMOUNT_TIYIN = 15_000_000L;

    /** 43,200,000 ms, verbatim from the {@code CreateTransaction} page. */
    private static final Duration TWELVE_HOURS = Duration.ofMillis(43_200_000L);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final UUID tenantId = UUID.randomUUID();
    private final UUID bindingId = UUID.randomUUID();
    private final UUID installationId = UUID.randomUUID();
    private final UUID integrationBindingId = UUID.randomUUID();
    private final UUID intentId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    private final Instant now = Instant.parse("2026-08-22T09:00:00Z");

    private JdbcPaymentAttemptStore attempts;
    private JdbcPaymentIntentStore intents;
    private JdbcFiscalDocumentStore fiscalDocuments;
    private JdbcPaymeTransactionView view;
    private PaymentAttemptService attemptService;
    private OrderDirectory orders;

    private PaymeMerchantApi api;
    private ProviderBinding binding;

    @BeforeEach
    void setUp() {
        attempts = mock(JdbcPaymentAttemptStore.class);
        intents = mock(JdbcPaymentIntentStore.class);
        fiscalDocuments = mock(JdbcFiscalDocumentStore.class);
        view = mock(JdbcPaymeTransactionView.class);
        attemptService = mock(PaymentAttemptService.class);
        orders = mock(OrderDirectory.class);

        api = new PaymeMerchantApi(attempts, intents, fiscalDocuments, view, attemptService, orders,
                Clock.fixed(now, ZoneOffset.UTC));
        binding = binding();

        when(intents.find(eq(tenantId), eq(intentId)))
                .thenReturn(Optional.of(intent(PaymentIntentStatus.PENDING)));
    }

    // -----------------------------------------------------------------------
    // The dispatcher
    // -----------------------------------------------------------------------

    /**
     * A method HorecaOS does not implement is {@code -32601} with the name in
     * {@code data}.
     *
     * <p>{@code ChangePassword} is the live case: both of Payme's own templates
     * implement it, and it appears in neither the current method index nor the
     * current error tables. Key rotation belongs to the secret manager, where the
     * reference on the binding stays stable and the value behind it changes — so the
     * method falls to the dispatcher's default branch, and that branch must answer
     * rather than fault.
     */
    @Test
    @DisplayName("an unimplemented method is -32601 with its name in data")
    void answersMethodNotFound() {
        assertThatThrownBy(() -> api.dispatch(binding, "ChangePassword", params("{}")))
                .isInstanceOfSatisfying(PaymeRpcException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(-32601);
                    assertThat(failure.data()).contains("ChangePassword");
                });
    }

    // -----------------------------------------------------------------------
    // CheckPerformTransaction
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("CheckPerformTransaction")
    class CheckPerform {

        @Test
        @DisplayName("allows a payable order for the right amount")
        void allows() {
            given(attempt(PaymentAttemptStatus.PRESENTED, null, null));

            assertThat(api.dispatch(binding, "CheckPerformTransaction", checkParams(AMOUNT_TIYIN)))
                    .containsExactly(Map.entry("allow", true));
        }

        /**
         * An order that does not exist is an account error, and the field name is
         * mandatory in {@code data}.
         *
         * <p>Payme's PHP template returns {@code -31050} with {@code data:
         * "order_id"} for this, and the docs require the localised message and the
         * field name for everything in {@code -31050…-31099}. Payme's Java template
         * emits a bare English string instead, which shows a Russian-speaking payer
         * an English sentence at the checkout.
         */
        @Test
        @DisplayName("an unknown order is an account error naming order_id")
        void unknownOrder() {
            when(attempts.findByMerchantTransId(eq(tenantId), eq(PaymentProviderType.PAYME),
                    anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    api.dispatch(binding, "CheckPerformTransaction", checkParams(AMOUNT_TIYIN)))
                    .isInstanceOfSatisfying(PaymeRpcException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(-31050);
                        assertThat(failure.data()).contains("order_id");
                        assertThat(failure.localised().ru()).isNotBlank();
                        assertThat(failure.localised().uz()).isNotBlank();
                        assertThat(failure.localised().en()).isNotBlank();
                    });
        }

        /**
         * An order reference of the wrong shape gets the answer an unknown one gets.
         *
         * <p>{@code CheckPerformTransaction} is reachable from Payme's checkout page
         * and is unauthenticated from the customer's side. Distinguishing "malformed"
         * from "no such order" would make this endpoint an oracle for which order
         * references exist.
         */
        @Test
        @DisplayName("a malformed order reference is indistinguishable from an unknown one")
        void malformedOrderReference() {
            assertThatThrownBy(() -> api.dispatch(binding, "CheckPerformTransaction",
                    params("{\"amount\":%d,\"account\":{\"order_id\":\"7\"}}".formatted(AMOUNT_TIYIN))))
                    .isInstanceOfSatisfying(PaymeRpcException.class,
                            failure -> assertThat(failure.code()).isEqualTo(-31050));
        }

        @Test
        @DisplayName("a missing account field is an account error, not a crash")
        void missingAccountField() {
            assertThatThrownBy(() -> api.dispatch(binding, "CheckPerformTransaction",
                    params("{\"amount\":%d}".formatted(AMOUNT_TIYIN))))
                    .isInstanceOfSatisfying(PaymeRpcException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(-31050);
                        assertThat(failure.data()).contains("order_id");
                    });
        }

        /**
         * The amount is recomputed and compared as integers.
         *
         * <p>The checkout link is unsigned, so {@code params.amount} is
         * attacker-controlled: this comparison is the only thing standing between the
         * platform and a customer paying one som for a hundred-thousand-som order.
         * Django's reference implementation lets underpayment through a misplaced
         * parenthesis, which is exactly this check written carelessly.
         */
        @Test
        @DisplayName("one tiyin short is -31001")
        void wrongAmount() {
            given(attempt(PaymentAttemptStatus.PRESENTED, null, null));

            assertThatThrownBy(() -> api.dispatch(binding, "CheckPerformTransaction",
                    checkParams(AMOUNT_TIYIN - 1)))
                    .isInstanceOfSatisfying(PaymeRpcException.class,
                            failure -> assertThat(failure.code()).isEqualTo(-31001));
        }

        /**
         * The one genuinely disputed mapping in the integration.
         *
         * <p>The {@code CheckPerformTransaction} page's error table permits only
         * {@code -31001} and the account range; Payme's own PHP template returns
         * {@code -31008} here, and the sandbox tests exactly that code for the same
         * condition on {@code CreateTransaction}. The notes recommend {@code -31008}
         * with a fully localised message so that whichever way Payme's validator
         * reads it, the payer still sees a sensible sentence. <strong>Unproven until
         * a sandbox exists.</strong>
         */
        @Test
        @DisplayName("an order already paid is -31008, which is the disputed code")
        void alreadyPaid() {
            given(attempt(PaymentAttemptStatus.CAPTURED, PAYME_ID, null));

            assertThatThrownBy(() ->
                    api.dispatch(binding, "CheckPerformTransaction", checkParams(AMOUNT_TIYIN)))
                    .isInstanceOfSatisfying(PaymeRpcException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(-31008);
                        assertThat(failure.localised().ru()).isNotBlank();
                    });
        }

        @Test
        @DisplayName("a live transaction on the order is -31008")
        void anotherTransactionActive() {
            given(attempt(PaymentAttemptStatus.RESERVED, PAYME_ID, now.plus(TWELVE_HOURS)));

            assertThatThrownBy(() ->
                    api.dispatch(binding, "CheckPerformTransaction", checkParams(AMOUNT_TIYIN)))
                    .isInstanceOfSatisfying(PaymeRpcException.class,
                            failure -> assertThat(failure.code()).isEqualTo(-31008));
        }

        /**
         * A cashbox may not be told about another cashbox's order.
         *
         * <p>The binding is in the predicate because an endpoint belongs to one
         * cashbox, and an order reference belonging to another must answer exactly
         * what an unknown one answers.
         */
        @Test
        @DisplayName("an order belonging to another cashbox reads as unknown")
        void otherCashbox() {
            PaymentAttempt elsewhere = new PaymentAttempt(attemptId, tenantId, intentId,
                    PaymentProviderType.PAYME, UUID.randomUUID(), ORDER_REFERENCE,
                    LocalDate.of(2026, 8, 22), null, null, new SomAmount(AMOUNT_SOM, UZS),
                    PaymentAttemptStatus.PRESENTED, PresentationKind.PAYMENT_LINK, null, null,
                    null, null, null, 1, now, null);
            given(elsewhere);

            assertThatThrownBy(() ->
                    api.dispatch(binding, "CheckPerformTransaction", checkParams(AMOUNT_TIYIN)))
                    .isInstanceOfSatisfying(PaymeRpcException.class,
                            failure -> assertThat(failure.code()).isEqualTo(-31050));
        }
    }

    // -----------------------------------------------------------------------
    // CreateTransaction
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("CreateTransaction")
    class Create {

        @Test
        @DisplayName("reserves the order and answers state 1")
        void creates() {
            PaymentAttempt presented = attempt(PaymentAttemptStatus.PRESENTED, null, null);
            given(presented);
            when(attempts.findByExternalPaymentId(eq(tenantId), eq(PaymentProviderType.PAYME),
                    eq(PAYME_ID))).thenReturn(Optional.empty());
            when(attempts.recordProviderCreation(eq(tenantId), eq(attemptId), eq(PAYME_ID),
                    any(), any())).thenReturn(true);
            when(attempts.find(eq(tenantId), eq(attemptId))).thenReturn(Optional.of(
                    attempt(PaymentAttemptStatus.RESERVED, PAYME_ID, now.plus(TWELVE_HOURS))));

            Map<String, Object> result =
                    api.dispatch(binding, "CreateTransaction", createParams(now, AMOUNT_TIYIN));

            assertThat(result).containsEntry("state", 1);
            assertThat(result).containsEntry("create_time", now.toEpochMilli());
            assertThat(result).containsEntry("transaction", attemptId.toString());

            // The deadline is Payme's clock plus twelve hours, written now so the
            // expiry sweep can find it without re-reading params.time — and keyed
            // on this caller's Payme id, so a concurrent loser cannot stamp its own
            // longer window onto the row it did not win.
            verify(attempts).recordProviderCreation(tenantId, attemptId, PAYME_ID, now,
                    now.plus(TWELVE_HOURS));
        }

        /**
         * A second Payme id against one order is refused.
         *
         * <p>Payme's Java template looks up only by Payme id, so a second id for the
         * same order creates a second transaction against the same goods. That is a
         * double-charge bug rather than an untidiness, and the sandbox tests for the
         * code that prevents it.
         */
        @Test
        @DisplayName("a second Payme transaction for one order is -31008")
        void refusesASecondTransactionForOneOrder() {
            given(attempt(PaymentAttemptStatus.RESERVED, "another-payme-id",
                    now.plus(TWELVE_HOURS)));
            when(attempts.findByExternalPaymentId(eq(tenantId), eq(PaymentProviderType.PAYME),
                    eq(PAYME_ID))).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    api.dispatch(binding, "CreateTransaction", createParams(now, AMOUNT_TIYIN)))
                    .isInstanceOfSatisfying(PaymeRpcException.class,
                            failure -> assertThat(failure.code()).isEqualTo(-31008));
        }

        /**
         * The repeat Payme sends is answered with the first answer.
         *
         * <p>The sandbox sends {@code CreateTransaction}, {@code PerformTransaction}
         * and {@code CancelTransaction} twice each and requires the second response to
         * be identical to the first. The replay is derived from persisted state rather
         * than from a stored response body, which is what stops it from writing
         * anything.
         */
        @Test
        @DisplayName("a repeated create replays the stored create_time and writes nothing")
        void replaysTheStoredAnswer() {
            Instant created = now.minus(Duration.ofMinutes(3));
            PaymentAttempt reserved =
                    attempt(PaymentAttemptStatus.RESERVED, PAYME_ID, created.plus(TWELVE_HOURS));
            when(attempts.findByExternalPaymentId(eq(tenantId), eq(PaymentProviderType.PAYME),
                    eq(PAYME_ID))).thenReturn(Optional.of(reserved));
            when(view.find(tenantId, bindingId, PAYME_ID))
                    .thenReturn(Optional.of(transactionView(PaymentAttemptStatus.RESERVED,
                            created, created, null, null)));

            Map<String, Object> result =
                    api.dispatch(binding, "CreateTransaction", createParams(created, AMOUNT_TIYIN));

            assertThat(result)
                    .containsEntry("state", 1)
                    .containsEntry("create_time", created.toEpochMilli())
                    .containsEntry("transaction", attemptId.toString());
            verify(attemptService, never()).recordProviderEvent(any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any());
        }

        /**
         * Twelve hours measured from {@code params.time}, and never from HorecaOS's own
         * clock.
         *
         * <p>Payme's Java template measures from the merchant's creation time and its
         * PHP template inverts the comparison so the guard only fires for a timestamp
         * twelve hours in the future. Both are wrong in the direction that keeps an
         * expired transaction alive.
         */
        @Test
        @DisplayName("a create past the twelve-hour window cancels with reason 4, then answers -31008")
        void expiresAndRefuses() {
            Instant paymeCreated = now.minus(TWELVE_HOURS).minus(Duration.ofMinutes(1));
            PaymentAttempt reserved = attempt(PaymentAttemptStatus.RESERVED, PAYME_ID,
                    paymeCreated.plus(TWELVE_HOURS));
            when(attempts.findByExternalPaymentId(eq(tenantId), eq(PaymentProviderType.PAYME),
                    eq(PAYME_ID))).thenReturn(Optional.of(reserved));

            assertThatThrownBy(() -> api.dispatch(binding, "CreateTransaction",
                    createParams(paymeCreated, AMOUNT_TIYIN)))
                    .isInstanceOfSatisfying(PaymeRpcException.class,
                            failure -> assertThat(failure.code()).isEqualTo(-31008));

            // The cancellation is committed and the error is the answer, which is
            // why the dispatch does not roll back on a business error.
            verify(attemptService).recordProviderEvent(eq(reserved),
                    eq(PaymentTransactionType.EXPIRE), eq(PaymentAttemptStatus.EXPIRED),
                    any(), any(), argThat(evidence ->
                            "-1".equals(evidence.state()) && "4".equals(evidence.reason())),
                    any(), any(), eq(now), any(), any());
        }
    }

    // -----------------------------------------------------------------------
    // PerformTransaction
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("PerformTransaction")
    class Perform {

        @Test
        @DisplayName("an unknown transaction is -31003")
        void unknownTransaction() {
            when(attempts.findByExternalPaymentId(eq(tenantId), eq(PaymentProviderType.PAYME),
                    eq(PAYME_ID))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> api.dispatch(binding, "PerformTransaction", idParams()))
                    .isInstanceOfSatisfying(PaymeRpcException.class,
                            failure -> assertThat(failure.code()).isEqualTo(-31003));
        }

        @Test
        @DisplayName("performs a live transaction into state 2")
        void performs() {
            PaymentAttempt reserved =
                    attempt(PaymentAttemptStatus.RESERVED, PAYME_ID, now.plus(Duration.ofHours(1)));
            when(attempts.findByExternalPaymentId(eq(tenantId), eq(PaymentProviderType.PAYME),
                    eq(PAYME_ID))).thenReturn(Optional.of(reserved));

            Map<String, Object> result = api.dispatch(binding, "PerformTransaction", idParams());

            assertThat(result)
                    .containsEntry("state", 2)
                    .containsEntry("perform_time", now.toEpochMilli())
                    .containsEntry("transaction", attemptId.toString());
            verify(attemptService).recordProviderEvent(eq(reserved),
                    eq(PaymentTransactionType.CAPTURE), eq(PaymentAttemptStatus.CAPTURED),
                    any(), eq(PAYME_ID), any(), eq(PAYME_ID), any(), eq(now), any(), any());
        }

        /** Never perform an expired transaction. It is cancelled instead, and refused. */
        @Test
        @DisplayName("an expired transaction is never performed")
        void neverPerformsAnExpiredTransaction() {
            PaymentAttempt stale = attempt(PaymentAttemptStatus.RESERVED, PAYME_ID,
                    now.minus(Duration.ofMinutes(1)));
            when(attempts.findByExternalPaymentId(eq(tenantId), eq(PaymentProviderType.PAYME),
                    eq(PAYME_ID))).thenReturn(Optional.of(stale));

            assertThatThrownBy(() -> api.dispatch(binding, "PerformTransaction", idParams()))
                    .isInstanceOfSatisfying(PaymeRpcException.class,
                            failure -> assertThat(failure.code()).isEqualTo(-31008));

            verify(attemptService, never()).recordProviderEvent(any(),
                    eq(PaymentTransactionType.CAPTURE), any(), any(), any(), any(), any(), any(),
                    any(), any(), any());
            verify(attemptService).recordProviderEvent(any(), eq(PaymentTransactionType.EXPIRE),
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        /**
         * A repeat of a performed transaction is not an error.
         *
         * <p>And the {@code perform_time} it answers is the moment of the first
         * capture, read back from the appended transaction row rather than taken from
         * the clock — which is what makes the second response identical to the first.
         */
        @Test
        @DisplayName("a repeated perform replays the first perform_time")
        void replaysAPerformedTransaction() {
            Instant performed = now.minus(Duration.ofMinutes(7));
            when(attempts.findByExternalPaymentId(eq(tenantId), eq(PaymentProviderType.PAYME),
                    eq(PAYME_ID))).thenReturn(Optional.of(
                            attempt(PaymentAttemptStatus.CAPTURED, PAYME_ID, null)));
            when(view.find(tenantId, bindingId, PAYME_ID)).thenReturn(Optional.of(
                    transactionView(PaymentAttemptStatus.CAPTURED, now.minus(Duration.ofHours(1)),
                            now.minus(Duration.ofHours(1)), performed, null)));

            Map<String, Object> result = api.dispatch(binding, "PerformTransaction", idParams());

            assertThat(result)
                    .containsEntry("state", 2)
                    .containsEntry("perform_time", performed.toEpochMilli());
            verify(attemptService, never()).recordProviderEvent(any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("performing a cancelled transaction is -31008")
        void refusesACancelledTransaction() {
            when(attempts.findByExternalPaymentId(eq(tenantId), eq(PaymentProviderType.PAYME),
                    eq(PAYME_ID))).thenReturn(Optional.of(
                            attempt(PaymentAttemptStatus.CANCELLED, PAYME_ID, null)));

            assertThatThrownBy(() -> api.dispatch(binding, "PerformTransaction", idParams()))
                    .isInstanceOfSatisfying(PaymeRpcException.class,
                            failure -> assertThat(failure.code()).isEqualTo(-31008));
        }
    }

    // -----------------------------------------------------------------------
    // CancelTransaction
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("CancelTransaction")
    class Cancel {

        @Test
        @DisplayName("a created transaction cancels to -1")
        void cancelsBeforePerform() {
            PaymentAttempt reserved =
                    attempt(PaymentAttemptStatus.RESERVED, PAYME_ID, now.plus(Duration.ofHours(1)));
            when(attempts.findByExternalPaymentId(eq(tenantId), eq(PaymentProviderType.PAYME),
                    eq(PAYME_ID))).thenReturn(Optional.of(reserved));

            Map<String, Object> result =
                    api.dispatch(binding, "CancelTransaction", cancelParams(1));

            assertThat(result)
                    .containsEntry("state", -1)
                    .containsEntry("cancel_time", now.toEpochMilli());
        }

        /**
         * A refund pressed in the Payme cabinet arrives here, and takes a performed
         * transaction to {@code -2}.
         *
         * <p>The sign carries the meaning: {@code -2} means money moved and then went
         * back, which is not the same thing as {@code -1}. It is recorded as a
         * {@code REFUND} rather than a {@code REVERSE} because {@code REVERSE} names a
         * reversal HorecaOS initiated, and Payme has no outbound refund at all.
         */
        @Test
        @DisplayName("a refund of a performed transaction is -2, not -1")
        void cancelsAfterPerform() {
            PaymentAttempt captured = attempt(PaymentAttemptStatus.CAPTURED, PAYME_ID, null);
            when(attempts.findByExternalPaymentId(eq(tenantId), eq(PaymentProviderType.PAYME),
                    eq(PAYME_ID))).thenReturn(Optional.of(captured));
            when(orders.summary(tenantId, orderId)).thenReturn(Optional.of(orderSummary("READY")));

            Map<String, Object> result =
                    api.dispatch(binding, "CancelTransaction", cancelParams(5));

            assertThat(result).containsEntry("state", -2);
            verify(attemptService).recordProviderEvent(eq(captured),
                    eq(PaymentTransactionType.REFUND), eq(PaymentAttemptStatus.REVERSED), any(),
                    any(), any(), any(), any(), eq(now), any(), any());
        }

        /**
         * {@code -31007} is the only veto available, and it is a policy rather than a
         * lookup.
         *
         * <p>Payme's own templates ship this as {@code return false} with a todo, and
         * as {@code order.delivered}. Neither is a policy. The rule here is the literal
         * one the code states — the goods were delivered in full — so only a completed
         * order refuses; an order still out with a courier can still be refunded.
         */
        @Test
        @DisplayName("a delivered order vetoes the refund with -31007")
        void vetoesARefundOfADeliveredOrder() {
            when(attempts.findByExternalPaymentId(eq(tenantId), eq(PaymentProviderType.PAYME),
                    eq(PAYME_ID))).thenReturn(Optional.of(
                            attempt(PaymentAttemptStatus.CAPTURED, PAYME_ID, null)));
            when(orders.summary(tenantId, orderId))
                    .thenReturn(Optional.of(orderSummary("COMPLETED")));

            assertThatThrownBy(() -> api.dispatch(binding, "CancelTransaction", cancelParams(5)))
                    .isInstanceOfSatisfying(PaymeRpcException.class,
                            failure -> assertThat(failure.code()).isEqualTo(-31007));

            verify(attemptService, never()).recordProviderEvent(any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any());
        }

        /**
         * Idempotent, which Payme's Java template is not.
         *
         * <p>That template's {@code else} branch re-cancels an already-cancelled
         * transaction, overwriting the cancel time and the reason, and can rewrite a
         * {@code -2} back to a {@code -1} — destroying the only record that money had
         * moved before it went back.
         */
        @Test
        @DisplayName("a repeated cancel replays and does not rewrite a -2 into a -1")
        void isIdempotent() {
            Instant cancelled = now.minus(Duration.ofMinutes(20));
            when(attempts.findByExternalPaymentId(eq(tenantId), eq(PaymentProviderType.PAYME),
                    eq(PAYME_ID))).thenReturn(Optional.of(
                            attempt(PaymentAttemptStatus.REVERSED, PAYME_ID, null)));
            when(view.find(tenantId, bindingId, PAYME_ID)).thenReturn(Optional.of(
                    transactionView(PaymentAttemptStatus.REVERSED, now.minus(Duration.ofHours(2)),
                            now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)),
                            cancelled)));

            Map<String, Object> result =
                    api.dispatch(binding, "CancelTransaction", cancelParams(5));

            assertThat(result)
                    .containsEntry("state", -2)
                    .containsEntry("cancel_time", cancelled.toEpochMilli());
            verify(attemptService, never()).recordProviderEvent(any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any());
        }

        /**
         * A missing reason does not cost the customer their refund.
         *
         * <p>The docs type it as required and Payme has always sent it, but refusing a
         * cancellation over a field that changes nothing about what has to happen
         * would leave money held. An absent reason is recorded as {@code 10},
         * "unknown", which is a value Payme itself defines.
         */
        @Test
        @DisplayName("a cancel with no reason still cancels")
        void toleratesAMissingReason() {
            when(attempts.findByExternalPaymentId(eq(tenantId), eq(PaymentProviderType.PAYME),
                    eq(PAYME_ID))).thenReturn(Optional.of(attempt(PaymentAttemptStatus.RESERVED,
                            PAYME_ID, now.plus(Duration.ofHours(1)))));

            assertThat(api.dispatch(binding, "CancelTransaction", idParams()))
                    .containsEntry("state", -1);
        }
    }

    // -----------------------------------------------------------------------
    // CheckTransaction and GetStatement
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("CheckTransaction")
    class Check {

        /**
         * Reports and never mutates, even when it can see the window has closed.
         *
         * <p>A read that mutates makes the answer depend on who asked last, and this
         * is the method the platform's own uncertainty resolver leans on.
         */
        @Test
        @DisplayName("does not expire a transaction it can see has expired")
        void neverMutates() {
            Instant created = now.minus(Duration.ofHours(20));
            when(view.find(tenantId, bindingId, PAYME_ID)).thenReturn(Optional.of(
                    transactionView(PaymentAttemptStatus.RESERVED, created, created, null, null)));

            Map<String, Object> result = api.dispatch(binding, "CheckTransaction", idParams());

            assertThat(result)
                    .containsEntry("state", 1)
                    // The docs' own example shows an unset timestamp as 0 beside a
                    // null reason, so the two unset markers are genuinely different.
                    // Both templates emit null for the timestamp.
                    .containsEntry("perform_time", 0L)
                    .containsEntry("cancel_time", 0L)
                    .containsEntry("reason", null);
            verify(attemptService, never()).recordProviderEvent(any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("an unknown transaction is -31003")
        void unknown() {
            when(view.find(tenantId, bindingId, PAYME_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> api.dispatch(binding, "CheckTransaction", idParams()))
                    .isInstanceOfSatisfying(PaymeRpcException.class,
                            failure -> assertThat(failure.code()).isEqualTo(-31003));
        }
    }

    @Nested
    @DisplayName("GetStatement")
    class Statement {

        /**
         * Every state goes in the statement, not just the completed ones.
         *
         * <p>Payme's Java template filters to state {@code 2}, which silently deletes
         * cancelled and pending transactions from the one report whose entire purpose
         * is to agree with Payme's ledger. The docs are explicit: everything created
         * after a successful {@code CreateTransaction}, whatever became of it.
         */
        @Test
        @DisplayName("returns every state, under the plural key")
        void returnsEveryState() {
            Instant early = now.minus(Duration.ofHours(6));
            when(view.between(eq(tenantId), eq(bindingId), any(), any())).thenReturn(List.of(
                    transactionView(PaymentAttemptStatus.RESERVED, early, early, null, null),
                    transactionView(PaymentAttemptStatus.CAPTURED, early, early, early, null),
                    transactionView(PaymentAttemptStatus.EXPIRED, early, early, null, early),
                    transactionView(PaymentAttemptStatus.REVERSED, early, early, early, early)));

            Map<String, Object> result = api.dispatch(binding, "GetStatement",
                    params("{\"from\":%d,\"to\":%d}"
                            .formatted(early.toEpochMilli(), now.toEpochMilli())));

            // The docs' response table names the field `transaction`; every example
            // and both reference implementations use the plural. The plural is what
            // Payme reads.
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("transactions");
            assertThat(rows).extracting(row -> row.get("state"))
                    .containsExactly(1, 2, -1, -2);
            assertThat(rows.getFirst())
                    .containsEntry("amount", AMOUNT_TIYIN)
                    .containsEntry("account", Map.of("order_id", ORDER_REFERENCE));
        }

        /**
         * A structurally invalid period is {@code -32600}.
         *
         * <p>{@code GetStatement} has no documented errors at all. Payme's PHP
         * template improvises {@code -31050} — an account-error code, for a period
         * that has nothing to do with an account, and one whose range obliges it to
         * name an account field it does not have. {@code -32600} is the code for a
         * request that parsed and is structurally wrong.
         */
        @Test
        @DisplayName("an inverted period is -32600 rather than a reused account code")
        void refusesAnInvertedPeriod() {
            assertThatThrownBy(() -> api.dispatch(binding, "GetStatement",
                    params("{\"from\":%d,\"to\":%d}"
                            .formatted(now.toEpochMilli(), now.minusSeconds(1).toEpochMilli()))))
                    .isInstanceOfSatisfying(PaymeRpcException.class,
                            failure -> assertThat(failure.code()).isEqualTo(-32600));
        }
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private void given(PaymentAttempt attempt) {
        when(attempts.findByMerchantTransId(eq(tenantId), eq(PaymentProviderType.PAYME),
                eq(ORDER_REFERENCE))).thenReturn(Optional.of(attempt));
    }

    private PaymentAttempt attempt(PaymentAttemptStatus status, String externalPaymentId,
            Instant expiresAt) {
        return new PaymentAttempt(attemptId, tenantId, intentId, PaymentProviderType.PAYME,
                bindingId, ORDER_REFERENCE, LocalDate.of(2026, 8, 22), externalPaymentId, null,
                new SomAmount(AMOUNT_SOM, UZS), status, PresentationKind.PAYMENT_LINK, null,
                expiresAt == null ? null : expiresAt.minus(TWELVE_HOURS), expiresAt, null, null,
                1, now.minus(Duration.ofHours(1)), null);
    }

    private PaymeTransactionView transactionView(PaymentAttemptStatus status, Instant paymeCreated,
            Instant createTime, Instant performTime, Instant cancelTime) {
        return new PaymeTransactionView(attemptId, tenantId, PAYME_ID, ORDER_REFERENCE,
                paymeCreated, new SomAmount(AMOUNT_SOM, UZS), status, null, createTime,
                performTime, cancelTime);
    }

    private PaymentIntent intent(PaymentIntentStatus status) {
        return new PaymentIntent(intentId, tenantId, orderId, UUID.randomUUID(), UUID.randomUUID(),
                null, UUID.randomUUID(), PaymentTender.PROVIDER, PaymentMethod.PAYME,
                PaymentProviderType.PAYME, new SomAmount(AMOUNT_SOM, UZS), status,
                CaptureTiming.BEFORE_CONFIRMATION, "idem-" + intentId, 1,
                now.minus(Duration.ofHours(1)), null);
    }

    private OrderDirectory.OrderSummary orderSummary(String status) {
        return new OrderDirectory.OrderSummary(orderId, tenantId, UUID.randomUUID(),
                UUID.randomUUID(), "A-0042", null, null, status, UZS, AMOUNT_SOM, 1);
    }

    private ProviderBinding binding() {
        return new ProviderBinding(bindingId, tenantId, UUID.randomUUID(),
                PaymentProviderType.PAYME, installationId, integrationBindingId,
                "587f72c72cac0d162c722ae2", null, null,
                new SecretReference("test", SecretCategory.PROVIDER_PAYMENT, "payme", "cashbox"),
                "payme-cashbox-one", false, true, LocalDate.of(2026, 1, 1), null);
    }

    private static JsonNode params(String json) {
        return JSON.readTree(json);
    }

    private static JsonNode checkParams(long amountTiyin) {
        return params("{\"amount\":%d,\"account\":{\"order_id\":\"%s\"}}"
                .formatted(amountTiyin, ORDER_REFERENCE));
    }

    private static JsonNode createParams(Instant paymeCreatedAt, long amountTiyin) {
        return params("{\"id\":\"%s\",\"time\":%d,\"amount\":%d,\"account\":{\"order_id\":\"%s\"}}"
                .formatted(PAYME_ID, paymeCreatedAt.toEpochMilli(), amountTiyin, ORDER_REFERENCE));
    }

    private static JsonNode idParams() {
        return params("{\"id\":\"%s\"}".formatted(PAYME_ID));
    }

    private static JsonNode cancelParams(int reason) {
        return params("{\"id\":\"%s\",\"reason\":%d}".formatted(PAYME_ID, reason));
    }

    private static <T> T argThat(org.mockito.ArgumentMatcher<T> matcher) {
        return org.mockito.ArgumentMatchers.argThat(matcher);
    }
}
