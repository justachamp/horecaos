package uz.qoida.platform.payments.click;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.iam.api.secrets.SecretCategory;
import uz.qoida.platform.iam.api.secrets.SecretReference;
import uz.qoida.platform.integration.api.payment.MerchantApiCall;
import uz.qoida.platform.integration.api.payment.MerchantApiTransport;
import uz.qoida.platform.integration.api.provider.ProviderOutcome;
import uz.qoida.platform.payments.domain.PaymentAttempt;
import uz.qoida.platform.payments.domain.PaymentAttemptStatus;
import uz.qoida.platform.payments.domain.PaymentProviderType;
import uz.qoida.platform.payments.domain.ProviderBinding;
import uz.qoida.platform.payments.domain.SomAmount;
import uz.qoida.platform.payments.infrastructure.click.ClickMerchantApi;
import uz.qoida.platform.payments.infrastructure.click.ClickPaymentAdapter;
import uz.qoida.platform.payments.infrastructure.click.ClickSignature;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbound half: what goes on the wire, and what an unknown answer means
 * (ADR 0013, ADR 0007).
 *
 * <p>The transport is faked rather than the HTTP server, because the properties
 * under test are the adapter's: the units, the paths, the verbs, and — the one that
 * costs money — that a lost response is resolved by asking rather than by sending
 * the call again. The route's own classification is tested in
 * {@code integration.camel}, and this suite assumes it.
 */
class ClickMerchantApiTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID INTENT = UUID.randomUUID();
    private static final String SERVICE_ID = "12345";
    private static final String MERCHANT_USER_ID = "3333";

    /** Click's third account identifier, which only the payment link uses. */
    private static final String MERCHANT_ID = "9999";
    private static final String SECRET = "SECRET123";

    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochSecond(1712345678L), ZoneOffset.UTC);

    private final RecordingTransport transport = new RecordingTransport();
    private final ClickMerchantApi click = new ClickMerchantApi(transport, CLOCK);
    private final ClickPaymentAdapter adapter = new ClickPaymentAdapter(click, CLOCK);

    @Test
    @DisplayName("the Auth header is computed per call from the resolved secret")
    void authorizationIsAFunctionOfTheCredential() {
        transport.answer(ProviderOutcome.success(Map.of("error_code", 0, "invoice_id", 9L), null));

        click.createInvoice(binding(true), "order-1", new SomAmount(1000, "UZS"), "998901234567");

        MerchantApiCall call = transport.last();
        // The credential is never on the call: it is applied to a function the
        // gateway invokes with a freshly resolved value, for the length of one call.
        assertThat(call.authorization().apply(SECRET))
                .containsEntry("Auth", ClickSignature.authHeader(MERCHANT_USER_ID, SECRET,
                        1712345678L));
        assertThat(call.toString()).doesNotContain("998901234567").doesNotContain(SECRET);
    }

    @Test
    @DisplayName("invoice/create sends som, and is a mutating call")
    void invoiceCreateSendsSom() {
        transport.answer(ProviderOutcome.success(Map.of("error_code", 0), null));

        click.createInvoice(binding(true), "order-1", new SomAmount(1000, "UZS"), "998901234567");

        MerchantApiCall call = transport.last();
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/invoice/create");
        // Som, not tiyin. The same payment is tiyin in submit_items.
        assertThat(call.body()).containsEntry("amount", 1000L)
                .containsEntry("service_id", SERVICE_ID)
                .containsEntry("merchant_trans_id", "order-1");
        assertThat(call.mutating()).isTrue();
    }

    @Test
    @DisplayName("status_by_mti is a GET carrying the business date, and mutates nothing")
    void statusByMerchantTransIdKeepsTheDateSegment() {
        transport.answer(ProviderOutcome.success(
                Map.of("error_code", 0, "payment_id", 777L), null));

        click.statusByMerchantTransId(binding(true), "order-1", LocalDate.of(2026, 8, 22));

        MerchantApiCall call = transport.last();
        // The PHP reference issues this as a DELETE and omits the date. Both the
        // Russian and English documentation say GET with the date, and the date is
        // what makes an uncertain outcome resolvable at all.
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.path()).isEqualTo("/payment/status_by_mti/12345/order-1/2026-08-22");
        assertThat(call.mutating()).isFalse();
    }

    @Test
    @DisplayName("a captured payment resolves through status_by_mti then payment/status")
    void resolutionTakesTwoReads() {
        transport.answer(ProviderOutcome.success(
                Map.of("error_code", 0, "payment_id", 777L), null));
        transport.answer(ProviderOutcome.success(
                Map.of("error_code", 0, "payment_status", 2), null));

        uz.qoida.platform.payments.domain.ProviderOutcome outcome =
                adapter.queryOutcome(attempt(null), binding(true));

        assertThat(transport.paths())
                .containsExactly("/payment/status_by_mti/12345/order-1/2026-08-22",
                        "/payment/status/12345/777");
        assertThat(outcome.classification())
                .isEqualTo(uz.qoida.platform.payments.domain.ProviderOutcome
                        .Classification.SUCCESS);
        assertThat(outcome.observedStatus()).isEqualTo(PaymentAttemptStatus.CAPTURED);
        assertThat(outcome.externalPaymentId()).isEqualTo("777");
    }

    @Test
    @DisplayName("payment_status 1 beside error_note Success is not money")
    void inProcessingStaysUncertain() {
        transport.answer(ProviderOutcome.success(
                Map.of("error_code", 0, "payment_id", 777L), null));
        // Click's own examples pair payment_status 1 with error_note "Success":
        // error_code 0 means the API call worked, and only payment_status 2 means
        // the money moved.
        transport.answer(ProviderOutcome.success(
                Map.of("error_code", 0, "error_note", "Success", "payment_status", 1), null));

        uz.qoida.platform.payments.domain.ProviderOutcome outcome =
                adapter.queryOutcome(attempt(null), binding(true));

        assertThat(outcome.classification())
                .isEqualTo(uz.qoida.platform.payments.domain.ProviderOutcome
                        .Classification.UNCERTAIN);
    }

    @Test
    @DisplayName("a payment Click cannot find leaves the attempt uncertain, never failed")
    void notFoundIsNotEvidenceOfAbsence() {
        // The business date the query is keyed on is undocumented — which date it
        // is, and in what timezone, is an open question with CLICK — so a not-found
        // may simply be a wrong date. Reporting "no payment" here would unblock the
        // retry the whole mechanism exists to prevent.
        transport.answer(ProviderOutcome.success(Map.of("error_code", 0), null));

        uz.qoida.platform.payments.domain.ProviderOutcome outcome =
                adapter.queryOutcome(attempt(null), binding(true));

        assertThat(outcome.classification())
                .isEqualTo(uz.qoida.platform.payments.domain.ProviderOutcome
                        .Classification.UNCERTAIN);
        assertThat(outcome.failureCode()).isEqualTo("CLICK_PAYMENT_NOT_FOUND");
        assertThat(transport.calls()).hasSize(1);
    }

    @Test
    @DisplayName("a reversal is a DELETE, and takes no amount")
    void reversalIsADelete() {
        transport.answer(ProviderOutcome.success(Map.of("error_code", 0), null));

        uz.qoida.platform.payments.domain.ProviderOutcome outcome = adapter.reverse(
                attempt("777"), binding(true), "order cancelled");

        MerchantApiCall call = transport.last();
        assertThat(call.method()).isEqualTo("DELETE");
        assertThat(call.path()).isEqualTo("/payment/reversal/12345/777");
        // There is no partial reversal in the documented API, so there is nowhere
        // for an amount to go.
        assertThat(call.body()).isNull();
        assertThat(outcome.observedStatus()).isEqualTo(PaymentAttemptStatus.REVERSED);
    }

    @Test
    @DisplayName("a lost reversal is uncertain and is never sent again")
    void anUncertainMutatingCallIsNotRetried() {
        transport.answer(ProviderOutcome.uncertain("READ_TIMEOUT", "no response"));

        uz.qoida.platform.payments.domain.ProviderOutcome outcome = adapter.reverse(
                attempt("777"), binding(true), "order cancelled");

        assertThat(outcome.classification())
                .isEqualTo(uz.qoida.platform.payments.domain.ProviderOutcome
                        .Classification.UNCERTAIN);
        // One attempt, and only one. Click's merchant API has no idempotency key on
        // any call, so a second DELETE after a lost response is a second reversal.
        assertThat(transport.calls()).hasSize(1);
    }

    @Test
    @DisplayName("a reversal answered 2xx with no error code is uncertain, not a reversal")
    void aMutatingCallWithoutAnErrorCodeIsUncertain() {
        // Click omits error_code on a successful fiscal read-back, so absence has
        // to mean success there -- and that rule was applied to every call. An
        // empty or unparsed 2xx on a DELETE, which is an ordinary answer from a
        // proxy or a WAF, therefore read as a completed reversal: the ledger
        // recorded a REVERSE for the full amount and the attempt went REVERSED,
        // asserting money reached a cardholder that Click may never have moved.
        transport.answer(ProviderOutcome.success(Map.of(), null));

        uz.qoida.platform.payments.domain.ProviderOutcome outcome = adapter.reverse(
                attempt("777"), binding(true), "order cancelled");

        assertThat(outcome.classification())
                .as("an unstated outcome on a mutating call is a question, not an answer")
                .isEqualTo(uz.qoida.platform.payments.domain.ProviderOutcome
                        .Classification.UNCERTAIN);
        assertThat(outcome.observedStatus())
                .as("nothing may claim the money went back")
                .isNotEqualTo(PaymentAttemptStatus.REVERSED);
        // Uncertain resolves by querying status_by_mti, never by sending again:
        // Click has no idempotency key, so a second DELETE is a second reversal.
        assertThat(transport.calls()).hasSize(1);
    }

    @Test
    @DisplayName("a read answered 2xx with no error code is still a success")
    void aReadWithoutAnErrorCodeIsStillSuccess() {
        // The other half, and the reason the predicate could not simply be
        // tightened everywhere: this is the documented shape of a successful
        // ofd_data read, and treating it as uncertain would turn every fiscal
        // read-back into a reconciliation job.
        transport.answer(ProviderOutcome.success(Map.of("payment_status", 2), null));

        ClickMerchantApi.ClickResponse read = click.statusByMerchantTransId(
                binding(true), "777", java.time.LocalDate.of(2026, 8, 24));

        assertThat(read.successful())
                .as("absence of error_code still means success on a read")
                .isTrue();
    }

    @Test
    @DisplayName("a binding that cannot reverse says so before anything is sent")
    void reversalIsACapabilityNotAnException() {
        uz.qoida.platform.payments.domain.ProviderOutcome outcome = adapter.reverse(
                attempt("777"), binding(false), "order cancelled");

        assertThat(outcome.failureCode()).isEqualTo("REVERSAL_UNSUPPORTED");
        assertThat(transport.calls()).isEmpty();
    }

    @Test
    @DisplayName("an unpublished error_code is unclassified, not invented")
    void unknownErrorCodesDoNotBecomeARejectionTable() {
        // The MERCHANT API error_code enumeration is not published anywhere
        // reachable: Click documents HTTP statuses only and shows error_code 0 in
        // every example. The reference implementations' -31300, -1000, -5001 and
        // -5002 are their own inventions. So a non-zero code travels verbatim for a
        // human and never through a mapping table this adapter made up.
        transport.answer(ProviderOutcome.success(
                Map.of("error_code", -5017, "error_note", "Something Click knows about"), null));

        uz.qoida.platform.payments.domain.ProviderOutcome outcome = adapter.reverse(
                attempt("777"), binding(true), "order cancelled");

        assertThat(outcome.classification())
                .isEqualTo(uz.qoida.platform.payments.domain.ProviderOutcome
                        .Classification.REJECTED);
        assertThat(outcome.failureCode()).isEqualTo("CLICK_REVERSAL_REFUSED");
        assertThat(outcome.detail()).contains("-5017");
    }

    @Test
    @DisplayName("a binding with no merchant_user_id is refused rather than sent unauthenticated")
    void missingMerchantUserIsAConfigurationFailure() {
        ProviderBinding withoutUser = new ProviderBinding(UUID.randomUUID(), TENANT,
                UUID.randomUUID(), PaymentProviderType.CLICK, UUID.randomUUID(), UUID.randomUUID(),
                SERVICE_ID, null, null, secretReference(), "click-segment", true, true,
                LocalDate.of(2026, 1, 1), null);

        assertThat(click.paymentStatus(withoutUser, "777").successful()).isFalse();
        assertThat(transport.calls()).isEmpty();
    }

    private static ProviderBinding binding(boolean supportsReversal) {
        return new ProviderBinding(UUID.randomUUID(), TENANT, UUID.randomUUID(),
                PaymentProviderType.CLICK, UUID.randomUUID(), UUID.randomUUID(), SERVICE_ID,
                MERCHANT_USER_ID, MERCHANT_ID, secretReference(), "click-segment",
                supportsReversal, true,
                LocalDate.of(2026, 1, 1), null);
    }

    private static SecretReference secretReference() {
        return new SecretReference("test", SecretCategory.PROVIDER_PAYMENT, "tenant", "click");
    }

    /**
     * An attempt with or without Click's payment id.
     *
     * <p>The state is incidental here: neither {@code queryOutcome} nor
     * {@code reverse} reads it, because deciding what a provider's answer means to
     * an attempt is the attempt service's job and not the adapter's.
     */
    private static PaymentAttempt attempt(String externalPaymentId) {
        return new PaymentAttempt(UUID.randomUUID(), TENANT, INTENT, PaymentProviderType.CLICK,
                UUID.randomUUID(), "order-1", LocalDate.of(2026, 8, 22), externalPaymentId, null,
                new SomAmount(1000, "UZS"), PaymentAttemptStatus.CAPTURED,
                null, null, null, null, null, null, 1, CLOCK.instant(), null);
    }

    /** Answers a queued outcome per call, and remembers what it was asked. */
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

        MerchantApiCall last() {
            return calls.getLast();
        }

        @Override
        public ProviderOutcome exchange(MerchantApiCall call) {
            calls.add(call);
            int index = calls.size() - 1;
            return index < answers.size() ? answers.get(index)
                    : ProviderOutcome.uncertain("NO_ANSWER_QUEUED", "the test queued no answer");
        }
    }
}
