package uz.horecaos.platform.commercial.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.commercial.domain.CardOnFile;
import uz.horecaos.platform.commercial.domain.PaymentMethod;
import uz.horecaos.platform.commercial.domain.TenantBilling;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcCardChargeAttemptStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcCardTopUpStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcWalletStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A tenant keeps its own card on file with HorecaOS, and chooses how it is collected (ADR 0095, the
 * recurring token flow).
 *
 * <p>The card number never passes through here: the tenant types it into the provider's own form and
 * HorecaOS receives a provider token and a bank's verification code ({@link CardEnrolment}). What is
 * stored is a reference into the provider's vault, plus what is safe to show back — the last four digits,
 * the brand and the month it lapses — so a screen can say which card is on file and a sweep can say it is
 * about to stop working.
 *
 * <p>Every provider call here runs with no transaction open, for the reason {@link
 * WalletService#settleCardRemainders} documents: a provider timeout must not roll back a database change,
 * and a connection must not be held across a network wait.
 *
 * <p>A reference replaced or removed is revoked at the provider afterwards, best effort: the reference is
 * already gone from HorecaOS, so a provider that cannot be reached leaves an orphan on its side that can
 * only ever be charged by a merchant account holder, which is HorecaOS. That is logged, not hidden.
 */
@Service
public class CardOnFileService {

    private static final Logger log = LoggerFactory.getLogger(CardOnFileService.class);

    private final JdbcWalletStore wallet;
    private final JdbcCardTopUpStore topUps;
    private final JdbcCardChargeAttemptStore attempts;
    private final CardEnrolment enrolment;
    private final AuditRecorder audit;
    private final TransactionTemplate unitOfWork;
    private final Clock clock;

    public CardOnFileService(
            JdbcWalletStore wallet,
            JdbcCardTopUpStore topUps,
            JdbcCardChargeAttemptStore attempts,
            CardEnrolment enrolment,
            AuditRecorder audit,
            TransactionTemplate unitOfWork,
            Clock clock) {
        this.wallet = wallet;
        this.topUps = topUps;
        this.attempts = attempts;
        this.enrolment = enrolment;
        this.audit = audit;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    /** What the browser needs to show the provider's form. */
    public CardEnrolment.BeginOutcome.Begun beginEnrolment(UUID tenantId) {
        refuseInsideATransaction();
        return switch (enrolment.begin(tenantId)) {
            case CardEnrolment.BeginOutcome.Begun begun -> begun;
            case CardEnrolment.BeginOutcome.NotConfigured ignored -> throw cardsNotAvailable();
        };
    }

    /**
     * Completes an enrolment and puts the card on file, replacing any other. The method does not change:
     * a card to top up with is not consent to be charged for every statement.
     */
    public CardOnFile confirmEnrolment(
            UUID tenantId,
            String sessionReference,
            String providerToken,
            String verificationCode,
            ActorRef actor,
            String correlationId) {
        refuseInsideATransaction();
        CardEnrolment.ConfirmOutcome outcome =
                enrolment.confirm(tenantId, sessionReference, providerToken, verificationCode);
        CardEnrolment.ConfirmOutcome.Enrolled enrolled =
                switch (outcome) {
                    case CardEnrolment.ConfirmOutcome.Enrolled done -> done;
                    case CardEnrolment.ConfirmOutcome.Refused refused ->
                        throw new ApiException(
                                ErrorCode.UNPROCESSABLE_STATE,
                                "The card could not be added",
                                Map.of("reason", refused.reason()));
                    case CardEnrolment.ConfirmOutcome.NotConfigured ignored -> throw cardsNotAvailable();
                };

        Bound bound =
                Objects.requireNonNull(unitOfWork.execute(status -> bind(tenantId, enrolled, actor, correlationId)));
        if (bound.replacedReference() != null && !bound.replacedReference().equals(enrolled.cardTokenReference())) {
            revokeBestEffort(tenantId, bound.replacedReference());
        }
        return bound.card();
    }

    /**
     * Takes the card off file. A tenant that was collected by CARD becomes INVOICE in the same step,
     * because CARD with nothing to charge is a promise nothing can keep, and the answer says so.
     */
    public RemovedCard removeCard(UUID tenantId, ActorRef actor, String correlationId) {
        refuseInsideATransaction();
        Removed removed = Objects.requireNonNull(unitOfWork.execute(status -> remove(tenantId, actor, correlationId)));
        revokeBestEffort(tenantId, removed.reference());
        return new RemovedCard(removed.methodNow());
    }

    /** The tenant's own choice of how it is collected. CARD needs a card on file and is the tenant's consent to be charged. */
    public PaymentMethod chooseMethod(UUID tenantId, PaymentMethod method, ActorRef actor, String correlationId) {
        refuseInsideATransaction();
        unitOfWork.executeWithoutResult(status -> choose(tenantId, method, actor, correlationId));
        return method;
    }

    // ------------------------------------------------------------------ internals

    private Bound bind(
            UUID tenantId, CardEnrolment.ConfirmOutcome.Enrolled enrolled, ActorRef actor, String correlationId) {
        Instant now = clock.instant();
        TenantBilling billing = wallet.lockBilling(tenantId, now);
        CardOnFile before = wallet.findCardOnFile(tenantId).orElse(null);
        String replaced = billing.cardTokenReference();
        wallet.bindCard(
                tenantId,
                enrolled.cardTokenReference(),
                enrolled.last4(),
                enrolled.brand(),
                enrolled.expiryMonth(),
                enrolled.expiryYear(),
                subject(actor),
                now);
        CardOnFile after = wallet.findCardOnFile(tenantId).orElseThrow();
        audit.record(AuditFact.of("commercial.wallet.card_bound", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.tenant_billing", tenantId)
                .because("the tenant put a card on file through the provider's own form")
                .changed(ChangeDocuments.diff(display(before), display(after)))
                .usingCapability(Capability.COMMERCIAL_CARD_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return new Bound(after, replaced);
    }

    private Removed remove(UUID tenantId, ActorRef actor, String correlationId) {
        Instant now = clock.instant();
        TenantBilling billing = wallet.lockBilling(tenantId, now);
        String reference = billing.cardTokenReference();
        if (reference == null) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "There is no card on file", Map.of("reason", "NO_CARD_ON_FILE"));
        }
        requireNoChargeInFlight(tenantId);
        CardOnFile before = wallet.findCardOnFile(tenantId).orElseGet(CardOnFile::unknown);
        wallet.clearCard(tenantId, subject(actor), now);
        PaymentMethod methodNow = wallet.findBilling(tenantId).orElseThrow().paymentMethod();
        Map<String, Object> beforeDocument = display(before);
        beforeDocument.put("paymentMethod", billing.paymentMethod().name());
        Map<String, Object> afterDocument = display(CardOnFile.unknown());
        afterDocument.put("paymentMethod", methodNow.name());
        audit.record(AuditFact.of("commercial.wallet.card_removed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.tenant_billing", tenantId)
                .because("the tenant took its card off file")
                .changed(ChangeDocuments.diff(beforeDocument, afterDocument))
                .usingCapability(Capability.COMMERCIAL_CARD_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return new Removed(reference, methodNow);
    }

    private void choose(UUID tenantId, PaymentMethod method, ActorRef actor, String correlationId) {
        Instant now = clock.instant();
        TenantBilling before = wallet.lockBilling(tenantId, now);
        if (before.paymentMethod() == PaymentMethod.CARD && method != PaymentMethod.CARD) {
            // A statement settlement only looks at CARD tenants, so a charge still waiting for its answer
            // would never be resolved once the tenant stopped being one.
            requireNoChargeInFlight(tenantId);
        }
        if (method == PaymentMethod.CARD && before.cardTokenReference() == null) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Collecting by card needs a card on file; add one first",
                    Map.of("reason", "NO_CARD_ON_FILE"));
        }
        wallet.setPaymentMethod(tenantId, method, null, subject(actor), now);
        audit.record(AuditFact.of("commercial.wallet.payment_method_changed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.tenant_billing", tenantId)
                .because("the tenant chose how it is collected")
                .changed(ChangeDocuments.change(
                        "paymentMethod", before.paymentMethod().name(), method.name()))
                .usingCapability(Capability.COMMERCIAL_CARD_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
    }

    /**
     * Neither removing the card nor leaving CARD while a charge asked under it has no answer yet. A tenant
     * that is no longer CARD with a card on file is not looked at by the statement sweep, so a statement
     * charge waiting for its answer would be a charge nobody is looking for any more: money taken and never
     * recorded. A top-up is held the same way, conservatively.
     *
     * <p>Replacing the card is deliberately not refused (ADR 0095): the tenant stays CARD with a card on
     * file, so the sweep still looks. A statement attempt is superseded and a top-up keeps the card it was
     * asked about, and the replaced reference is revoked at once. That is safe because the adapter contract
     * keeps a charge answerable and replayable after its card is revoked ({@link CardProviderAdapter}), not
     * because the revocation waits.
     */
    private void requireNoChargeInFlight(UUID tenantId) {
        if (topUps.findPending(tenantId).isPresent() || attempts.hasPending(tenantId)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A card charge is still waiting for the provider's answer; try again once it has one",
                    Map.of("reason", "CHARGE_IN_FLIGHT"));
        }
    }

    private void revokeBestEffort(UUID tenantId, String reference) {
        try {
            CardEnrolment.RevokeOutcome outcome = enrolment.revoke(reference);
            if (outcome instanceof CardEnrolment.RevokeOutcome.Failed failed) {
                // Never the reference (ADR 0028).
                log.warn(
                        "The provider kept a card reference that tenant {} replaced or removed: {}",
                        tenantId,
                        failed.reason());
            }
        } catch (RuntimeException unreachable) {
            log.warn(
                    "A card reference that tenant {} replaced or removed could not be revoked at the provider",
                    tenantId);
            log.debug("The card enrolment threw", unreachable);
        }
    }

    /** Only what is safe to say; never the reference. Mutable on purpose, so a caller can add the method. */
    private static Map<String, Object> display(@Nullable CardOnFile card) {
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("cardLast4", card == null ? null : card.last4());
        shown.put("cardBrand", card == null ? null : card.brand());
        shown.put("cardExpiryMonth", card == null ? null : card.expiryMonth());
        shown.put("cardExpiryYear", card == null ? null : card.expiryYear());
        return shown;
    }

    private static ApiException cardsNotAvailable() {
        return new ApiException(
                ErrorCode.UNPROCESSABLE_STATE,
                "Card payments are not available yet",
                Map.of("reason", "CARDS_NOT_AVAILABLE"));
    }

    private static void refuseInsideATransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Putting a card on file asks a provider HorecaOS does not control, so it runs outside any transaction.");
        }
    }

    private static String subject(ActorRef actor) {
        return actor.subject() == null ? "" : actor.subject();
    }

    /** What removing a card left the tenant on. */
    public record RemovedCard(PaymentMethod paymentMethod) {}

    private record Bound(CardOnFile card, @Nullable String replacedReference) {}

    private record Removed(String reference, PaymentMethod methodNow) {

        @Override
        public String toString() {
            return "Removed[method=" + methodNow + "]";
        }
    }
}
