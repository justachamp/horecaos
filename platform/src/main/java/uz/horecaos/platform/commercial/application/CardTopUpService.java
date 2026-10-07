package uz.horecaos.platform.commercial.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.commercial.domain.CardTopUp;
import uz.horecaos.platform.commercial.domain.TenantBilling;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcCardTopUpStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcWalletStore;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * A tenant tops its wallet up from the card it keeps on file (ADR 0095, decision 2: money in by "a
 * card charge that succeeded").
 *
 * <p>The shape is the one {@link WalletService#settleCardRemainders} already proved, for the same
 * reasons, and it is not optional:
 *
 * <ol>
 *   <li>one transaction writes the attempt as {@code PENDING} and commits, so the idempotency key is
 *       durable before anything can be charged under it;
 *   <li>the provider is asked with <em>nothing</em> held — no transaction, no connection, no billing
 *       lock — which {@code ExternalCallTransactionBoundaryTests} enforces for every provider call;
 *   <li>a second transaction takes the billing lock, resolves the attempt exactly once, and writes the
 *       money and the row that says so together.
 * </ol>
 *
 * <p>An answer that never comes leaves the attempt {@code PENDING}, and {@link #reconcilePending}
 * resolves it: ask the provider what it believes, and only then replay the charge under the same key.
 * A provider that honours keys makes the replay the same attempt, never a second charge. At most one
 * attempt per tenant is unresolved at a time (V0505's index), so an impatient second click cannot
 * become a second charge.
 */
@Service
public class CardTopUpService {

    private static final Logger log = LoggerFactory.getLogger(CardTopUpService.class);

    private static final String TOP_UP_METRIC = "commercial.wallet.card_top_up";

    private final JdbcCardTopUpStore topUps;
    private final JdbcWalletStore wallet;
    private final WalletService walletService;
    private final CardCharger charger;
    private final AuditRecorder audit;
    private final MeterRegistry meters;
    private final TransactionTemplate unitOfWork;
    private final Clock clock;

    public CardTopUpService(
            JdbcCardTopUpStore topUps,
            JdbcWalletStore wallet,
            WalletService walletService,
            CardCharger charger,
            AuditRecorder audit,
            MeterRegistry meters,
            TransactionTemplate unitOfWork,
            Clock clock) {
        this.topUps = topUps;
        this.wallet = wallet;
        this.walletService = walletService;
        this.charger = charger;
        this.audit = audit;
        this.meters = meters;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    /** Newest first. */
    public List<CardTopUp> recent(UUID tenantId, int limit) {
        return topUps.recent(tenantId, limit);
    }

    /**
     * Charges the tenant's card for {@code amountMinor} and, once the provider has answered, credits
     * the wallet with it.
     *
     * @return the attempt as it stands: {@code SUCCEEDED} and credited, {@code FAILED} with the
     *     provider's reason, {@code NOT_CONFIGURED}, or {@code PENDING} when the provider has not
     *     answered yet and a reconciliation pass will resolve it
     */
    public CardTopUp requestTopUp(UUID tenantId, long amountMinor, ActorRef actor, String correlationId) {
        refuseInsideATransaction();
        if (amountMinor <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A top-up is a positive amount");
        }
        Opened opened;
        try {
            opened = Objects.requireNonNull(unitOfWork.execute(status -> open(tenantId, amountMinor, subject(actor))));
        } catch (DuplicateKeyException raced) {
            throw waitingForTheProvider();
        }
        CardTopUp settled = ask(tenantId, opened, actor, false);
        if (CardTopUp.SUCCEEDED.equals(settled.outcome())) {
            // Once the money is committed and never inside its transaction: a CARD tenant's remainder is
            // collected like after any other money in (ADR 0095).
            walletService.settleCardRemainders(tenantId);
        }
        return settled;
    }

    /**
     * Resolves every attempt that has been waiting for the provider longer than {@code olderThan}.
     *
     * @return how many it resolved one way or the other
     */
    public int reconcilePending(Duration olderThan, int limit) {
        refuseInsideATransaction();
        Instant cutoff = clock.instant().minus(olderThan);
        int resolved = 0;
        for (CardTopUp pending : topUps.pendingRequestedBefore(cutoff, limit)) {
            try {
                String token =
                        topUps.cardTokenOf(pending.tenantId(), pending.id()).orElse(null);
                Opened opened = new Opened(pending.id(), pending.amountMinor(), pending.currency(), token);
                CardTopUp after = ask(pending.tenantId(), opened, ActorRef.systemJob("wallet-settlement"), true);
                if (!CardTopUp.PENDING.equals(after.outcome())) {
                    resolved++;
                    if (CardTopUp.SUCCEEDED.equals(after.outcome())) {
                        walletService.settleCardRemainders(pending.tenantId());
                    }
                }
            } catch (RuntimeException failure) {
                // One attempt that cannot be resolved must not hold back the ones behind it.
                log.error("Reconciling card top-up {} of tenant {} failed", pending.id(), pending.tenantId(), failure);
            }
        }
        return resolved;
    }

    // ------------------------------------------------------------------ internals

    private Opened open(UUID tenantId, long amountMinor, String requestedBy) {
        Instant now = clock.instant();
        TenantBilling billing = wallet.lockBilling(tenantId, now);
        String token = billing.cardTokenReference();
        if (token == null) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "There is no card on file to charge; add one first",
                    Map.of("reason", "NO_CARD_ON_FILE"));
        }
        if (topUps.findPending(tenantId).isPresent()) {
            throw waitingForTheProvider();
        }
        UUID id = Ids.newId();
        String currency = wallet.currencyOf(tenantId);
        topUps.begin(id, tenantId, amountMinor, currency, token, requestedBy, now);
        return new Opened(id, amountMinor, currency, token);
    }

    /**
     * Asks the provider, under the attempt's own key, and records whatever it said.
     *
     * @param retried true for an attempt a previous call already asked about and never heard back: it is
     *     asked what the provider believes first, because a charge that went through and whose answer was
     *     lost must be recorded and not charged again
     */
    private CardTopUp ask(UUID tenantId, Opened opened, ActorRef actor, boolean retried) {
        UUID id = opened.id();
        CardCharger.Outcome outcome;
        try {
            CardCharger.StatusOutcome known =
                    retried ? knownToProvider(id) : new CardCharger.StatusOutcome.NotSucceeded();
            if (known instanceof CardCharger.StatusOutcome.Succeeded succeeded) {
                // Asked before, answer lost, money moved: record it without charging a second time.
                outcome = new CardCharger.Outcome.Succeeded(succeeded.providerReference());
            } else {
                outcome = charger.charge(
                        tenantId, opened.cardTokenReference(), opened.amountMinor(), opened.currency(), id.toString());
            }
        } catch (RuntimeException unanswered) {
            // The row stays PENDING, which is what PENDING means: we asked and never learned the answer.
            // Nothing is written to the ledger and nothing is called declined. Never the token, never the
            // amount beside a tenant (ADR 0028, ADR 0029).
            log.warn("A card top-up {} of tenant {} was never answered", id, tenantId);
            log.debug("The card charger threw", unanswered);
            count("unanswered");
            return topUps.find(tenantId, id).orElseThrow();
        }
        return Objects.requireNonNull(unitOfWork.execute(status -> record(tenantId, opened, outcome, actor)));
    }

    /** What the provider currently believes about an attempt; an unanswered question is "not known to have succeeded". */
    private CardCharger.StatusOutcome knownToProvider(UUID attemptId) {
        try {
            return charger.status(attemptId.toString());
        } catch (RuntimeException unanswered) {
            return new CardCharger.StatusOutcome.NotSucceeded();
        }
    }

    private CardTopUp record(UUID tenantId, Opened opened, CardCharger.Outcome outcome, ActorRef actor) {
        Instant now = clock.instant();
        wallet.lockBilling(tenantId, now);
        CardTopUp current = topUps.find(tenantId, opened.id()).orElseThrow();
        if (!CardTopUp.PENDING.equals(current.outcome())) {
            // A reconciliation pass and the request that charged were both told the answer; the first one
            // resolved it, and the second must not credit it again.
            return current;
        }
        switch (outcome) {
            case CardCharger.Outcome.Succeeded succeeded -> {
                UUID entryId = walletService.creditCardTopUp(
                        tenantId,
                        opened.amountMinor(),
                        opened.currency(),
                        succeeded.providerReference(),
                        current.requestedBy(),
                        actor,
                        opened.id());
                topUps.settleSucceeded(tenantId, opened.id(), succeeded.providerReference(), entryId, now);
                count("succeeded");
            }
            case CardCharger.Outcome.Failed failed -> {
                topUps.settleWithoutMoney(tenantId, opened.id(), CardTopUp.FAILED, failed.reason(), now);
                log.warn("A card top-up {} of tenant {} was declined: {}", opened.id(), tenantId, failed.reason());
                audit.record(AuditFact.of("commercial.wallet.card_top_up_declined", AuditClass.BUSINESS)
                        .by(actor)
                        .at(ResourceScope.tenant(tenantId))
                        .target("commercial.card_top_up", opened.id())
                        .outcome(AuditFact.Outcome.REJECTED)
                        .because("the card top-up was declined by the provider")
                        .changed(ChangeDocuments.diff(
                                Map.of("outcome", CardTopUp.PENDING),
                                Map.of("outcome", CardTopUp.FAILED, "reason", failed.reason())))
                        .usingCapability(Capability.COMMERCIAL_WALLET_TOPUP.code())
                        .correlatedBy(opened.id().toString())
                        .occurredAt(now)
                        .build());
                count("failed");
            }
            case CardCharger.Outcome.NotConfigured ignored -> {
                topUps.settleWithoutMoney(tenantId, opened.id(), CardTopUp.NOT_CONFIGURED, null, now);
                count("not_configured");
            }
        }
        return topUps.find(tenantId, opened.id()).orElseThrow();
    }

    private void count(String outcome) {
        Counter.builder(TOP_UP_METRIC)
                .description("ADR 0095 tenant card top-ups, by how the charge ended")
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }

    private static ApiException waitingForTheProvider() {
        return new ApiException(
                ErrorCode.RESOURCE_CONFLICT,
                "A card top-up is still waiting for the provider's answer; it will be resolved on its own",
                Map.of("reason", "TOP_UP_IN_FLIGHT"));
    }

    private static void refuseInsideATransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "A card top-up asks a provider HorecaOS does not control, so it runs between two committed "
                            + "transactions and never inside a caller's.");
        }
    }

    private static String subject(ActorRef actor) {
        return actor.subject() == null ? "" : actor.subject();
    }

    private record Opened(
            UUID id,
            long amountMinor,
            String currency,
            @Nullable String cardTokenReference) {

        @Override
        public String toString() {
            return "Opened[" + id + "]";
        }
    }
}
