package uz.qoida.platform.payments.infrastructure.payme;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;

import uz.qoida.platform.ordering.api.OrderDirectory;
import uz.qoida.platform.payments.application.PaymentAttemptService;
import uz.qoida.platform.payments.domain.FiscalDocument;
import uz.qoida.platform.payments.domain.FiscalDocumentType;
import uz.qoida.platform.payments.domain.FiscalReason;
import uz.qoida.platform.payments.domain.FiscalStatus;
import uz.qoida.platform.payments.domain.PaymentAttempt;
import uz.qoida.platform.payments.domain.PaymentAttemptStatus;
import uz.qoida.platform.payments.domain.PaymentIntent;
import uz.qoida.platform.payments.domain.PaymentIntentStatus;
import uz.qoida.platform.payments.domain.PaymentProviderType;
import uz.qoida.platform.payments.domain.PaymentTransactionType;
import uz.qoida.platform.payments.domain.ProviderBinding;
import uz.qoida.platform.payments.domain.ProviderEvidence;
import uz.qoida.platform.payments.domain.TiyinAmount;
import uz.qoida.platform.payments.infrastructure.persistence.JdbcFiscalDocumentStore;
import uz.qoida.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.qoida.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;

/**
 * The seven inbound methods, which are the Payme integration (ADR 0013).
 *
 * <p>Payme is the JSON-RPC client and Qoida is the server. Nothing Qoida sends
 * outbound moves money; {@code PerformTransaction} arriving here is the money, and
 * fulfilment, the ledger and the customer-facing "paid" flag hang off it rather
 * than off the browser returning to a callback URL.
 *
 * <p><strong>Idempotency is a contract requirement.</strong> Payme's sandbox sends
 * {@code CreateTransaction}, {@code PerformTransaction} and
 * {@code CancelTransaction} twice each and requires the second response to be
 * identical to the first. Every mutating method here is therefore keyed on
 * {@code params.id}, and every replay answer is <em>derived from persisted state</em>
 * rather than from a stored response body — which is what stops a re-delivered
 * cancel from overwriting a cancel time, or rewriting a {@code -2} back to a
 * {@code -1} the way Payme's own Java template does.
 *
 * <p><strong>The twelve-hour window is measured from {@code params.time}.</strong>
 * Payme's creation moment, never Qoida's. Payme's Java template measures from the
 * merchant's own clock and its PHP template inverts the comparison so the guard
 * only fires for a timestamp twelve hours in the future. Both are wrong in the
 * direction that performs an expired transaction.
 */
@Service
public class PaymeMerchantApi {

    private static final Logger log = LoggerFactory.getLogger(PaymeMerchantApi.class);

    /** 43,200,000 ms, verbatim from the {@code CreateTransaction} page. */
    static final Duration TRANSACTION_TIMEOUT = PaymentAttemptService.PAYME_TRANSACTION_TIMEOUT;

    /**
     * The format every {@code SetFiscalData} example uses, with no timezone stated
     * anywhere. Read as Tashkent local time pending an answer from Payme (U16): a
     * receipt registered at 23:10 local would otherwise be filed on the previous
     * business date, which is the kind of error a tax inspection finds.
     */
    private static final DateTimeFormatter FISCAL_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final ZoneId FISCAL_ZONE = ZoneId.of("Asia/Tashkent");

    private final JdbcPaymentAttemptStore attempts;
    private final JdbcPaymentIntentStore intents;
    private final JdbcFiscalDocumentStore fiscalDocuments;
    private final JdbcPaymeTransactionView view;
    private final PaymentAttemptService attemptService;
    private final OrderDirectory orders;
    private final Clock clock;

    public PaymeMerchantApi(JdbcPaymentAttemptStore attempts, JdbcPaymentIntentStore intents,
            JdbcFiscalDocumentStore fiscalDocuments, JdbcPaymeTransactionView view,
            PaymentAttemptService attemptService, OrderDirectory orders, Clock clock) {
        this.attempts = attempts;
        this.intents = intents;
        this.fiscalDocuments = fiscalDocuments;
        this.view = view;
        this.attemptService = attemptService;
        this.orders = orders;
        this.clock = clock;
    }

    /**
     * Runs one method and returns its {@code result} object.
     *
     * <p>One transaction around the whole dispatch, because the two writes that
     * matter — the transaction state and the order's payment status — must not be
     * separable. A crash between them leaves a performed Payme transaction against
     * an order the platform never credited, and Payme's retry then hits the
     * "already performed" branch and cheerfully reports success.
     *
     * <p>{@code noRollbackFor} is the one subtle part and it is deliberate. A
     * {@link PaymeRpcException} is a business answer rather than a fault, and one
     * path must both write and then answer with an error: a transaction found past
     * its twelve-hour window is cancelled to state {@code -1} with reason
     * {@code 4} and <em>then</em> answered {@code -31008}. Rolling that back would
     * discard the cancellation and leave the reservation held forever while
     * returning an error that claims it was cancelled. Every other throw in this
     * class happens before anything has been written, so committing on a business
     * error commits nothing else.
     */
    @Transactional(noRollbackFor = PaymeRpcException.class)
    public Map<String, Object> dispatch(ProviderBinding binding, String method, JsonNode params) {
        return switch (method) {
            case "CheckPerformTransaction" -> checkPerformTransaction(binding, params);
            case "CreateTransaction" -> createTransaction(binding, params);
            case "PerformTransaction" -> performTransaction(binding, params);
            case "CancelTransaction" -> cancelTransaction(binding, params);
            case "CheckTransaction" -> checkTransaction(binding, params);
            case "GetStatement" -> getStatement(binding, params);
            case "SetFiscalData" -> setFiscalData(binding, params);
            // ChangePassword is implemented by both of Payme's own templates and
            // appears in neither the current method index nor the current error
            // tables; its own code comments link to a dead anchor. Key rotation
            // belongs to ADR 0028's manager, where the reference on the binding
            // stays stable and the value behind it changes. So it falls to here,
            // and -32601 with the method name in `data` is the documented answer
            // for a method that does not exist.
            default -> throw PaymeErrors.methodNotFound(method);
        };
    }

    // -----------------------------------------------------------------------
    // CheckPerformTransaction
    // -----------------------------------------------------------------------

    /**
     * The pre-flight, called from the checkout page before any money moves.
     *
     * <p>It applies exactly the checks {@code CreateTransaction} will apply. If
     * this says {@code allow} and the create then errors, the customer has already
     * entered their card details — so the two must not be allowed to disagree, and
     * the shared helpers below are what keeps them together.
     *
     * <p>No {@code detail} object is returned. The fiscal lines travel with the
     * checkout form, where they are fixed before the customer pays, and returning
     * them a second time here would mean two places that can disagree about what is
     * on the receipt. The docs mark {@code items} required on this page and
     * optional on the checkout page (U10); if the sandbox insists on the former,
     * the same {@link PaymeReceiptDetail} builder feeds both.
     */
    private Map<String, Object> checkPerformTransaction(ProviderBinding binding, JsonNode params) {
        String orderReference = PaymeAccount.orderReference(params);
        PaymentAttempt attempt = attemptFor(binding, orderReference);

        requireDeclaredAmountMatches(attempt, params);
        requirePayable(attempt);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allow", true);
        return result;
    }

    // -----------------------------------------------------------------------
    // CreateTransaction
    // -----------------------------------------------------------------------

    private Map<String, Object> createTransaction(ProviderBinding binding, JsonNode params) {
        String paymeTransactionId = requireTransactionId(params);
        Instant paymeCreatedAt = requireTimestamp(params, "time");

        Optional<PaymentAttempt> known =
                attempts.findByExternalPaymentId(binding.tenantId(), PaymentProviderType.PAYME,
                        paymeTransactionId);

        if (known.isPresent()) {
            return recreate(binding, known.get(), paymeTransactionId, paymeCreatedAt);
        }

        String orderReference = PaymeAccount.orderReference(params);
        PaymentAttempt attempt = attemptFor(binding, orderReference);

        requireDeclaredAmountMatches(attempt, params);
        requirePayable(attempt);

        if (attempt.externalPaymentId() != null) {
            // A different Payme transaction already owns this order. This is the
            // check Payme's Java template is missing, and its absence is a
            // double-charge bug rather than an untidiness: a second Payme id for
            // one order creates a second transaction against the same goods.
            throw PaymeErrors.anotherTransactionIsActive();
        }

        Instant now = clock.instant();
        if (hasExpired(paymeCreatedAt, now)) {
            // Created stale — the window closed before the call arrived. Nothing is
            // reserved, so there is nothing to cancel; refusing is the whole answer.
            throw PaymeErrors.transactionExpired();
        }

        attemptService.recordProviderEvent(attempt, PaymentTransactionType.RESERVE,
                PaymentAttemptStatus.RESERVED, attempt.amount(), paymeTransactionId,
                new ProviderEvidence(String.valueOf(PaymeState.CREATED.code()), null, now),
                paymeTransactionId, null, now, null, null);

        // The twelve-hour deadline, derived from Payme's clock and written now so
        // that the expiry sweep can find it without re-reading params.time. The
        // write is refused unless this caller's Payme id is the one that claimed
        // the attempt above, so a concurrent loser cannot stamp its own longer
        // window onto the winner's row on its way to being told no.
        if (!attempts.recordProviderCreation(binding.tenantId(), attempt.id(),
                paymeTransactionId, paymeCreatedAt, paymeCreatedAt.plus(TRANSACTION_TIMEOUT))) {
            throw PaymeErrors.anotherTransactionIsActive();
        }

        // Re-read rather than trust the write. Two concurrent CreateTransaction
        // calls for one order carry two different Payme ids, so both get past the
        // "no transaction yet" check above and both append a reservation row; the
        // conditional UPDATE inside recordProviderEvent is what picks a winner, and
        // this is where the loser finds out. Without it the loser would answer
        // state 1 for a transaction that owns nothing.
        PaymentAttempt reserved = attempts.find(binding.tenantId(), attempt.id())
                .orElseThrow(PaymeErrors::internalError);
        if (reserved.status() != PaymentAttemptStatus.RESERVED
                || !paymeTransactionId.equals(reserved.externalPaymentId())) {
            throw PaymeErrors.anotherTransactionIsActive();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("create_time", now.toEpochMilli());
        result.put("transaction", reserved.id().toString());
        result.put("state", PaymeState.CREATED.code());
        return result;
    }

    /**
     * A {@code CreateTransaction} for an id already on file.
     *
     * <p>Three outcomes and only three: the stored answer replayed verbatim, a
     * timeout cancellation followed by {@code -31008}, or {@code -31008} because
     * this transaction is no longer in state {@code 1}.
     */
    private Map<String, Object> recreate(ProviderBinding binding, PaymentAttempt attempt,
            String paymeTransactionId, Instant paymeCreatedAt) {
        requireSameBinding(binding, attempt, PaymeErrors::anotherTransactionIsActive);

        PaymeState state = stateOf(attempt);
        if (state != PaymeState.CREATED) {
            throw PaymeErrors.transactionStateForbidsIt();
        }

        Instant now = clock.instant();
        if (hasExpired(paymeCreatedAt, now) || attempt.expired(now)) {
            expireByTimeout(attempt, now);
            throw PaymeErrors.transactionExpired();
        }

        PaymeTransactionView stored = requireView(binding, paymeTransactionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("create_time", stored.asTransactionState().get("create_time"));
        result.put("transaction", stored.merchantTransactionNumber());
        result.put("state", PaymeState.CREATED.code());
        return result;
    }

    // -----------------------------------------------------------------------
    // PerformTransaction
    // -----------------------------------------------------------------------

    private Map<String, Object> performTransaction(ProviderBinding binding, JsonNode params) {
        String paymeTransactionId = requireTransactionId(params);
        PaymentAttempt attempt = transactionFor(binding, paymeTransactionId);

        Instant now = clock.instant();
        return switch (stateOf(attempt)) {
            case CREATED -> {
                if (attempt.expired(now)) {
                    // Never perform an expired transaction. The cancellation is
                    // committed and the error is the answer, which is why this
                    // whole dispatch does not roll back on a business error.
                    expireByTimeout(attempt, now);
                    throw PaymeErrors.transactionExpired();
                }

                attemptService.recordProviderEvent(attempt, PaymentTransactionType.CAPTURE,
                        PaymentAttemptStatus.CAPTURED, attempt.amount(), paymeTransactionId,
                        new ProviderEvidence(String.valueOf(PaymeState.PERFORMED.code()), null, now),
                        paymeTransactionId, null, now, null, null);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("transaction", attempt.id().toString());
                result.put("perform_time", now.toEpochMilli());
                result.put("state", PaymeState.PERFORMED.code());
                yield result;
            }
            // Not an error. Payme repeats a call whose response was lost, and the
            // repeat must be answered with the first answer — including the first
            // perform_time, which is why it is read back from the appended capture
            // rather than taken from the clock.
            case PERFORMED -> {
                PaymeTransactionView stored = requireView(binding, paymeTransactionId);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("transaction", stored.merchantTransactionNumber());
                result.put("perform_time", stored.asTransactionState().get("perform_time"));
                result.put("state", PaymeState.PERFORMED.code());
                yield result;
            }
            case CANCELLED, CANCELLED_AFTER_PERFORM -> throw PaymeErrors.transactionStateForbidsIt();
        };
    }

    // -----------------------------------------------------------------------
    // CancelTransaction
    // -----------------------------------------------------------------------

    private Map<String, Object> cancelTransaction(ProviderBinding binding, JsonNode params) {
        String paymeTransactionId = requireTransactionId(params);
        int reason = optionalReason(params);
        PaymentAttempt attempt = transactionFor(binding, paymeTransactionId);

        Instant now = clock.instant();
        return switch (stateOf(attempt)) {
            case CREATED -> {
                attemptService.recordProviderEvent(attempt, PaymentTransactionType.CANCEL,
                        PaymentAttemptStatus.CANCELLED, attempt.amount(), paymeTransactionId,
                        new ProviderEvidence(String.valueOf(PaymeState.CANCELLED.code()),
                                String.valueOf(reason), now),
                        paymeTransactionId, null, now, null, null);
                yield cancellation(attempt, now, PaymeState.CANCELLED);
            }
            case PERFORMED -> {
                if (!refundIsAllowed(attempt)) {
                    // The only veto available, and it is final for this call: once
                    // -31007 has been answered the state stays 2, and Payme will
                    // call again if the situation changes. Self-transitioning to -2
                    // afterwards would move money nobody asked to move.
                    throw PaymeErrors.orderAlreadyDelivered();
                }
                // REFUND rather than REVERSE. REVERSE names a reversal Qoida
                // initiated, which only Click offers; this one was pressed in the
                // Payme cabinet and arrived here, and recording it under the
                // outbound type would make the settlement reconciliation report a
                // call Qoida never made.
                attemptService.recordProviderEvent(attempt, PaymentTransactionType.REFUND,
                        PaymentAttemptStatus.REVERSED, attempt.amount(), paymeTransactionId,
                        new ProviderEvidence(
                                String.valueOf(PaymeState.CANCELLED_AFTER_PERFORM.code()),
                                String.valueOf(reason), now),
                        paymeTransactionId, null, now, null, null);
                yield cancellation(attempt, now, PaymeState.CANCELLED_AFTER_PERFORM);
            }
            // Terminal, and idempotent: the stored answer, unchanged. Payme's Java
            // template re-cancels here instead, overwriting the cancel time and the
            // reason and turning a -2 back into a -1 — which destroys the only
            // record that money had moved before it went back.
            case CANCELLED, CANCELLED_AFTER_PERFORM -> {
                PaymeTransactionView stored = requireView(binding, paymeTransactionId);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("transaction", stored.merchantTransactionNumber());
                result.put("cancel_time", stored.asTransactionState().get("cancel_time"));
                result.put("state", stored.state());
                yield result;
            }
        };
    }

    private static Map<String, Object> cancellation(PaymentAttempt attempt, Instant now,
            PaymeState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transaction", attempt.id().toString());
        result.put("cancel_time", now.toEpochMilli());
        result.put("state", state.code());
        return result;
    }

    /**
     * Whether a refund may be honoured, which is a policy and not a lookup.
     *
     * <p>Payme's own templates ship this as {@code return false} with a todo, and
     * as {@code order.delivered}. Neither is a policy. The rule here is the literal
     * one {@code -31007} states — "the goods or service were delivered to the buyer
     * in full" — so only a {@code COMPLETED} order refuses. An order out with a
     * courier has not been delivered yet and the customer may still be refunded;
     * that costs the restaurant the food, and the alternative costs the customer
     * their money and forces a manual intervention through Payme's own staff.
     *
     * <p>An order that cannot be read is refunded rather than refused. Trapping a
     * customer's money because a lookup failed is the worse of the two errors.
     */
    private boolean refundIsAllowed(PaymentAttempt attempt) {
        return intents.find(attempt.tenantId(), attempt.intentId())
                .flatMap(intent -> orders.summary(intent.tenantId(), intent.orderId()))
                .map(order -> !"COMPLETED".equals(order.status()))
                .orElse(true);
    }

    // -----------------------------------------------------------------------
    // CheckTransaction
    // -----------------------------------------------------------------------

    /**
     * Reports state and never changes it.
     *
     * <p>Deliberately does not expire a transaction whose window has closed, even
     * though it can see that it has. A read that mutates makes the answer depend on
     * who asked last, and this is the method the platform's own uncertainty
     * resolver leans on.
     */
    private Map<String, Object> checkTransaction(ProviderBinding binding, JsonNode params) {
        return requireView(binding, requireTransactionId(params)).asTransactionState();
    }

    // -----------------------------------------------------------------------
    // GetStatement
    // -----------------------------------------------------------------------

    /**
     * The reconciliation feed, and mandatory.
     *
     * <p>Every state goes in it — {@code 1}, {@code -1} and {@code -2} as well as
     * {@code 2}. Payme's Java template filters to completed transactions, which
     * removes cancelled and pending ones from the one report whose entire purpose
     * is to agree with Payme's ledger.
     *
     * <p>The docs' response table names the field {@code transaction}; every
     * example and both reference implementations use {@code transactions}. The
     * plural is what Payme reads.
     */
    private Map<String, Object> getStatement(ProviderBinding binding, JsonNode params) {
        Instant from = requireTimestamp(params, "from");
        Instant to = requireTimestamp(params, "to");
        if (!from.isBefore(to)) {
            // Payme's PHP template answers -31050 here, reusing an account-error
            // code for a period that has nothing to do with an account. -32600 is
            // the code for a request that parsed and is structurally wrong.
            throw PaymeErrors.invalidRequest("from must be earlier than to");
        }

        List<Map<String, Object>> rows =
                view.between(binding.tenantId(), binding.bindingId(), from, to).stream()
                        .map(PaymeTransactionView::asStatementRow)
                        .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transactions", rows);
        return result;
    }

    // -----------------------------------------------------------------------
    // SetFiscalData
    // -----------------------------------------------------------------------

    /**
     * The fiscal receipt, arriving after the fact and possibly never (ADR 0038).
     *
     * <p>Optional to implement, which for a platform whose fiscal position is
     * "retain the evidence" means the money moves, the receipt is issued, and Qoida
     * holds nothing to prove it. So it is implemented.
     *
     * <p>Two rules the happy-path example hides. <strong>Arrival is not proof of a
     * receipt</strong>: {@code status_code} is a status and a non-zero one reports
     * an ОФД registration failure, so a document only becomes {@code ISSUED} on
     * zero. And <strong>{@code CANCEL} is a second document</strong>, not an update
     * — the tax authority forms two separate receipts for a payment and its
     * reversal, and writing the cancel over the sale destroys the only record that
     * the sale was ever fiscalized.
     *
     * <p>There is no merchant-initiated retry on this path: {@code receipts.set_fiscal_data}
     * runs the other way and is for a merchant who fiscalized on their own
     * equipment. A callback that never arrives is therefore ADR 0038's reporting
     * sweeper's problem, and the document it leaves behind — {@code PENDING} or
     * {@code SUBMITTED} with a submitted time — is what that sweeper reads.
     */
    private Map<String, Object> setFiscalData(ProviderBinding binding, JsonNode params) {
        String paymeTransactionId = requireTransactionId(params);
        String type = params.path("type").asString("").strip();
        if (!"PERFORM".equals(type) && !"CANCEL".equals(type)) {
            throw PaymeErrors.fiscalInvalidParameters("type");
        }

        JsonNode fiscal = params.path("fiscal_data");
        if (!fiscal.isObject()) {
            throw PaymeErrors.fiscalInvalidParameters("fiscal_data");
        }

        PaymentAttempt attempt = attempts
                .findByExternalPaymentId(binding.tenantId(), PaymentProviderType.PAYME,
                        paymeTransactionId)
                .filter(candidate -> candidate.merchantBindingId().equals(binding.bindingId()))
                .orElseThrow(PaymeErrors::fiscalReceiptNotFound);

        PaymentIntent intent = intents.find(attempt.tenantId(), attempt.intentId())
                .orElseThrow(PaymeErrors::fiscalReceiptNotFound);

        List<FiscalDocument> documents = fiscalDocuments.listForOrder(intent.tenantId(),
                intent.orderId());
        FiscalDocument sale = documents.stream()
                .filter(document -> document.documentType() == FiscalDocumentType.SALE)
                .filter(document -> document.status() != FiscalStatus.NOT_APPLICABLE)
                .findFirst()
                .orElseThrow(PaymeErrors::fiscalReceiptNotFound);

        String statusCode = fiscal.path("status_code").asString("");
        String message = truncate(fiscal.path("message").asString(null), 512);
        FiscalDocument.FiscalEvidence evidence = new FiscalDocument.FiscalEvidence(
                fiscal.path("receipt_id").asString(null),
                fiscal.path("fiscal_sign").asString(null),
                fiscal.path("terminal_id").asString(null),
                null,
                registeredAt(fiscal.path("date").asString(null)),
                fiscal.path("qr_code_url").asString(null),
                truncate(statusCode, 32),
                message);

        Instant now = clock.instant();
        FiscalDocument target = "PERFORM".equals(type)
                ? sale
                : cancellationDocument(intent, sale, documents, now);

        if (issued(statusCode, evidence)) {
            fiscalDocuments.recordEvidence(intent.tenantId(), target.id(), FiscalStatus.ISSUED,
                    FiscalReason.PARTNER_FISCALIZED, evidence, null, now);
        } else {
            log.warn("Payme reported fiscal status {} for document {}; the document is FAILED "
                    + "and needs an operator.", statusCode, target.id());
            fiscalDocuments.recordEvidence(intent.tenantId(), target.id(), FiscalStatus.FAILED,
                    FiscalReason.PROVIDER_REJECTED, evidence, null, now);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    /**
     * The document a {@code CANCEL} payload belongs on, created if this is the
     * first one.
     *
     * <p>Idempotent by lookup rather than by a unique index, because the index that
     * would express it belongs to ADR 0038's schema and not to the interim table
     * here. A repeated {@code CANCEL} finds the refund document it created the
     * first time; {@code recordEvidence} then refuses to rewrite it once it is
     * {@code ISSUED}.
     */
    private FiscalDocument cancellationDocument(PaymentIntent intent, FiscalDocument sale,
            List<FiscalDocument> documents, Instant now) {
        Optional<FiscalDocument> existing = documents.stream()
                .filter(document -> document.documentType() == FiscalDocumentType.REFUND)
                .filter(document -> sale.id().equals(document.correctsDocumentId()))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }

        FiscalDocument refund = new FiscalDocument(
                UUID.randomUUID(), intent.tenantId(), intent.orderId(), intent.legalEntityId(),
                intent.id(), null, PaymentProviderType.PAYME, FiscalDocumentType.REFUND,
                sale.id(), FiscalStatus.SUBMITTED, FiscalReason.AWAITING_PROVIDER,
                "Payme reported a cancellation receipt for this order", List.of(), null, 1, now);
        fiscalDocuments.insert(refund);
        return refund;
    }

    /**
     * Whether this payload actually evidences a receipt.
     *
     * <p>Zero is success in every documented example and the full
     * {@code status_code} enumeration is an open question to Payme (U18), so every
     * other value is treated as a failure needing an operator — the safe direction
     * to be wrong in. A zero that arrives without a fiscal sign or a receipt id is
     * also treated as a failure: the schema refuses to call a document issued
     * without the two identifiers the tax authority recognises, and an evidence
     * record that is only a status code is not evidence.
     */
    private static boolean issued(String statusCode, FiscalDocument.FiscalEvidence evidence) {
        return "0".equals(statusCode)
                && evidence.fiscalSign() != null && !evidence.fiscalSign().isBlank()
                && evidence.externalReceiptId() != null && !evidence.externalReceiptId().isBlank();
    }

    private static Instant registeredAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.strip(), FISCAL_DATE).atZone(FISCAL_ZONE).toInstant();
        } catch (DateTimeParseException unparseable) {
            // A timestamp we cannot read is not a reason to reject a receipt that
            // otherwise carries a fiscal sign. It is recorded as absent and the
            // fiscal sign, which is the thing the tax authority recognises, is kept.
            log.warn("A Payme fiscal date could not be parsed; the receipt is kept without it.");
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Shared checks
    // -----------------------------------------------------------------------

    /**
     * The order behind {@code account.order_id}.
     *
     * <p>Resolved from the account and from nothing else — there is no browser, no
     * session and no cookie in any of these calls. The binding is in the predicate
     * because an endpoint belongs to one cashbox, and an order reference belonging
     * to another cashbox must answer exactly what an unknown one answers.
     */
    private PaymentAttempt attemptFor(ProviderBinding binding, String orderReference) {
        return attempts
                .findByMerchantTransId(binding.tenantId(), PaymentProviderType.PAYME, orderReference)
                .filter(attempt -> attempt.merchantBindingId().equals(binding.bindingId()))
                .orElseThrow(PaymeErrors::orderNotFound);
    }

    private PaymentAttempt transactionFor(ProviderBinding binding, String paymeTransactionId) {
        return attempts
                .findByExternalPaymentId(binding.tenantId(), PaymentProviderType.PAYME,
                        paymeTransactionId)
                .filter(attempt -> attempt.merchantBindingId().equals(binding.bindingId()))
                .orElseThrow(PaymeErrors::transactionNotFound);
    }

    private PaymeTransactionView requireView(ProviderBinding binding, String paymeTransactionId) {
        return view.find(binding.tenantId(), binding.bindingId(), paymeTransactionId)
                .orElseThrow(PaymeErrors::transactionNotFound);
    }

    private static void requireSameBinding(ProviderBinding binding, PaymentAttempt attempt,
            java.util.function.Supplier<PaymeRpcException> otherwise) {
        if (!attempt.merchantBindingId().equals(binding.bindingId())) {
            throw otherwise.get();
        }
    }

    /**
     * The amount check, and the only thing standing between the platform and a
     * customer paying one som for a hundred thousand som order.
     *
     * <p>The checkout link is unsigned, so {@code params.amount} is
     * attacker-controlled. What it is compared against is the amount the platform
     * recomputed from the accepted quote and committed onto the attempt before any
     * link was built. Both sides are integers: Click's own reference implementations
     * compare with a floating-point tolerance and one of them lets underpayment
     * through a misplaced parenthesis.
     */
    private static void requireDeclaredAmountMatches(PaymentAttempt attempt, JsonNode params) {
        long declared = requireIntegral(params, "amount");
        if (declared <= 0) {
            throw PaymeErrors.invalidRequest("amount must be greater than zero");
        }
        if (declared != TiyinAmount.of(attempt.amount()).value()) {
            throw PaymeErrors.wrongAmount();
        }
    }

    /**
     * Whether this order may still be paid.
     *
     * <p>An e-commerce order is a one-time account in Payme's vocabulary: money may
     * arrive against it exactly once, ever. The codes below are the disputed part of
     * the mapping — see {@link PaymeErrors#operationNotPermitted} — and the
     * conditions are not: an order already paid, already holding a live transaction,
     * or already finished cannot take another payment.
     */
    private void requirePayable(PaymentAttempt attempt) {
        switch (attempt.status()) {
            case INITIATED, PRESENTED -> { }
            case RESERVED -> throw PaymeErrors.anotherTransactionIsActive();
            case CAPTURED -> throw PaymeErrors.orderAlreadyPaid();
            case UNCERTAIN -> throw PaymeErrors.operationNotPermitted(new PaymeMessage(
                    "Состояние оплаты заказа уточняется. Повторите попытку позже.",
                    "Haridning to'lov holati aniqlanmoqda. Keyinroq urinib ko'ring.",
                    "The order's payment state is being resolved. Please try again later."));
            case CANCELLED, EXPIRED, REVERSED, FAILED -> throw PaymeErrors.orderNotPayable();
        }

        intents.find(attempt.tenantId(), attempt.intentId()).ifPresent(intent -> {
            if (intent.status() == PaymentIntentStatus.PAID) {
                throw PaymeErrors.orderAlreadyPaid();
            }
            if (intent.status() != PaymentIntentStatus.PENDING
                    && intent.status() != PaymentIntentStatus.AUTHORIZING) {
                throw PaymeErrors.orderNotPayable();
            }
        });
    }

    /**
     * Cancels a transaction whose window has closed, as state {@code -1} with reason
     * {@code 4}.
     *
     * <p>The reason and the cancel time are both written. Payme's Java template sets
     * the state and neither of the others, which leaves the transaction unreportable
     * through {@code CheckTransaction} and {@code GetStatement} — the two places a
     * timed-out transaction most needs to be visible.
     *
     * <p>The transaction reference is left to the shared service to mint as
     * {@code LOCAL:{uuid}}, matching the background sweep. An expiry is Qoida's own
     * decision on both providers; Click has no expiry state at all and is never told.
     */
    private void expireByTimeout(PaymentAttempt attempt, Instant now) {
        attemptService.recordProviderEvent(attempt, PaymentTransactionType.EXPIRE,
                PaymentAttemptStatus.EXPIRED, attempt.amount(), null,
                new ProviderEvidence(String.valueOf(PaymeState.CANCELLED.code()),
                        String.valueOf(PaymeCancellationReason.TIMEOUT), now),
                null, null, now, null, null);
    }

    private static boolean hasExpired(Instant paymeCreatedAt, Instant now) {
        return now.isAfter(paymeCreatedAt.plus(TRANSACTION_TIMEOUT));
    }

    private PaymeState stateOf(PaymentAttempt attempt) {
        return PaymeState.of(attempt.status()).orElseThrow(() -> {
            log.error("Attempt {} carries a Payme transaction id in status {}, which Payme has no "
                    + "state for. Answering -32400 rather than inventing one.",
                    attempt.id(), attempt.status());
            return PaymeErrors.internalError();
        });
    }

    // -----------------------------------------------------------------------
    // Parameter reading
    // -----------------------------------------------------------------------

    private static String requireTransactionId(JsonNode params) {
        JsonNode id = params.path("id");
        if (!id.isString()) {
            throw PaymeErrors.invalidRequest("id must be a string");
        }
        String value = id.asString("").strip();
        if (value.isEmpty()) {
            throw PaymeErrors.invalidRequest("id must not be empty");
        }
        return value;
    }

    private static long requireIntegral(JsonNode params, String field) {
        JsonNode node = params.path(field);
        if (!node.isIntegralNumber()) {
            throw PaymeErrors.invalidRequest(field + " must be an integer");
        }
        return node.longValue();
    }

    private static Instant requireTimestamp(JsonNode params, String field) {
        long millis = requireIntegral(params, field);
        if (millis <= 0) {
            throw PaymeErrors.invalidRequest(field + " must be a positive millisecond timestamp");
        }
        return Instant.ofEpochMilli(millis);
    }

    /**
     * The cancellation reason, defaulted rather than demanded.
     *
     * <p>The docs type it as required and Payme has always sent it, but refusing a
     * cancellation for a missing reason code would leave a customer's money held
     * over a field that changes nothing about what has to happen. An absent reason
     * is recorded as {@code 10}, "unknown", which is a value Payme itself defines.
     */
    private static int optionalReason(JsonNode params) {
        JsonNode reason = params.path("reason");
        return reason.isIntegralNumber()
                ? (int) reason.longValue()
                : PaymeCancellationReason.UNKNOWN;
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
