package uz.horecaos.platform.loyalty.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.customers.api.ConsentDirectory;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerPhoneLookup;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.loyalty.api.LoyaltyConfigurationKeys;
import uz.horecaos.platform.loyalty.api.LoyaltySyncDiscrepancyOpened;
import uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort;
import uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort.Result;
import uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort.SyncBinding;
import uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort.TillBalance;
import uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort.TillCustomer;
import uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort.TillCustomerPage;
import uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort.TillMovement;
import uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort.TillMovementPage;
import uz.horecaos.platform.loyalty.application.LoyaltyAdjustmentService.AdjustmentCommand;
import uz.horecaos.platform.loyalty.application.PosSyncMovementImporter.Imported;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.AccountRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcPosBalanceSyncStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcPosBalanceSyncStore.DiscrepancyRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcPosBalanceSyncStore.MovementRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcPosBalanceSyncStore.PendingPush;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcPosBalanceSyncStore.RunRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcPosBalanceSyncStore.StateRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcPosBalanceSyncStore.Subject;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.ConfigurationValueAuthor;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Keeps a till's bonus balance a mirror of the platform's ledger, and says so when it is not (ADR 0171, row 6.3b).
 *
 * <p>The platform's append-only ledger is the single authority for every balance it issues. This service never
 * assigns a number to an account: a till movement becomes an order-free {@code ADJUSTMENT} through {@link
 * PosSyncMovementImporter}, a push sends the platform's number to the till, and a disagreement is a row a person
 * resolves. It holds no balance of its own and orchestrates three things.
 *
 * <ol>
 *   <li><strong>Match</strong> -- the till's customers are listed, each found by the phone the till returns through
 *       {@link CustomerPhoneLookup}, and a unique match at the binding's brand is recorded as a mapping. Nothing about
 *       the customer is sent to the till, no account is created from a till record, and no match is ever guessed.
 *   <li><strong>Pull</strong> -- what happened at the till since a stored cursor is imported as facts, bounded by caps.
 *   <li><strong>Push</strong> -- the platform's balance, at its version, is set on the till for every matched account
 *       whose version has moved since the last push. Only the latest version matters, so a burst of movements is one
 *       push; an answer that is not known is resolved by a read, never by a second send of something that is not a
 *       set-to-this-value.
 * </ol>
 *
 * and then <strong>compares</strong>: each matched account's platform balance against a fresh read of the till's,
 * counting and listing every difference. A difference is never silently corrected.
 *
 * <p><strong>Not transactional on purpose.</strong> Every call to a till happens outside any database transaction;
 * the writes that must be atomic -- one imported movement -- are in {@link PosSyncMovementImporter}.
 *
 * <p><strong>Staged by binding.</strong> A binding's mode (configuration key {@code loyalty.sync.mode}) is {@code
 * REPORT_ONLY} until somebody sets otherwise: match and compare, apply nothing, push nothing. {@code PULL} adds the
 * import; {@code PULL_AND_PUSH} adds the push. That is ADR 0171's rollout, one branch in report-only for a stated
 * number of days read by the tenant's finance, then pull, then push.
 */
@Service
public class PosBalanceSyncService {

    private static final Logger log = LoggerFactory.getLogger(PosBalanceSyncService.class);

    /** A till with more customers than this in one pass is walked on the next one. */
    static final int MAX_CUSTOMER_PAGES = 50;

    /** Likewise for one account's movement feed. */
    static final int MAX_MOVEMENT_PAGES = 20;

    /** The longest vendor cursor kept to resume a walk of the till's customers; a longer one starts the next pass at the top. */
    static final int MAX_STORED_CURSOR = 1024;

    /**
     * The kinds that are a size: the same kind with another amount is another difference, so what a person absorbed
     * was the amount and not every later gap.
     */
    private static final Set<String> SIZED_KINDS = Set.of("TILL_HIGHER", "TILL_LOWER");

    public enum Mode {
        REPORT_ONLY,
        PULL,
        PULL_AND_PUSH;

        static Mode parse(@Nullable String value) {
            if (value == null) {
                return REPORT_ONLY;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                // An unreadable mode is the safe one, not an exception that stops a tenant's sync: nothing applies and
                // nothing pushes until somebody writes a mode this reads.
                return REPORT_ONLY;
            }
        }

        boolean pulls() {
            return this != REPORT_ONLY;
        }

        boolean pushes() {
            return this == PULL_AND_PUSH;
        }
    }

    public enum RunKind {
        PULL,
        PUSH,
        RECONCILE
    }

    public enum Resolution {
        ACCEPT_TILL,
        ACCEPT_PLATFORM,
        ABSORB,
        SUSPEND
    }

    public record BindingView(
            SyncBinding binding,
            Mode mode,
            long matchedAccounts,
            long openDiscrepancies,
            @Nullable RunRow lastRun) {}

    /**
     * @param tillKeepsBalanceVersion the {@code If-Match} of a change to the brand's own row of {@code
     *                                loyalty.pos.till_keeps_balance}: the row's version plus one (the row starts at 0), and 0
     *                                when the brand has never said
     */
    public record Overview(
            boolean tillKeepsBalance,
            long tillKeepsBalanceVersion,
            boolean standingNotice,
            List<BindingView> bindings,
            long openDiscrepancies) {}

    public record RunReport(RunRow run, List<DiscrepancyRow> discrepancies) {}

    /**
     * @param applied  the discrepancy was resolved
     * @param approval PENDING when a second person has to sign first; the discrepancy stays open
     */
    public record ResolutionResult(
            DiscrepancyRow discrepancy,
            boolean applied,
            String approval,
            @Nullable UUID approvalRequestId) {}

    private final PosLoyaltyBalancePort port;
    private final JdbcPosBalanceSyncStore store;
    private final JdbcLoyaltyStore loyalty;
    private final PosSyncMovementImporter importer;
    private final LoyaltyAdjustmentService adjustments;
    private final CustomerPhoneLookup phones;
    private final ConfigurationResolver configuration;
    private final ConfigurationValueAuthor configurationAuthor;
    private final ConsentDirectory consents;
    private final AuditRecorder audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PosBalanceSyncService(
            PosLoyaltyBalancePort port,
            JdbcPosBalanceSyncStore store,
            JdbcLoyaltyStore loyalty,
            PosSyncMovementImporter importer,
            LoyaltyAdjustmentService adjustments,
            CustomerPhoneLookup phones,
            ConfigurationResolver configuration,
            ConfigurationValueAuthor configurationAuthor,
            ConsentDirectory consents,
            AuditRecorder audit,
            ApplicationEventPublisher events,
            Clock clock) {
        this.port = port;
        this.store = store;
        this.loyalty = loyalty;
        this.importer = importer;
        this.adjustments = adjustments;
        this.phones = phones;
        this.configuration = configuration;
        this.configurationAuthor = configurationAuthor;
        this.consents = consents;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    // ================================================================== overview

    public Overview overview(UUID tenantId, UUID brandId) {
        List<SyncBinding> bindings = port.bindingsOf(tenantId, brandId);
        Map<UUID, Long> open = store.openCountByBinding(tenantId, brandId);
        Map<UUID, RunRow> last = store.lastRunByBinding(tenantId, brandId);

        List<BindingView> views = new ArrayList<>();
        long totalOpen = 0;
        for (SyncBinding binding : bindings) {
            long openHere = open.getOrDefault(binding.bindingId(), 0L);
            totalOpen += openHere;
            views.add(new BindingView(
                    binding,
                    modeOf(binding),
                    store.countMatched(tenantId, binding.bindingId()),
                    openHere,
                    last.get(binding.bindingId())));
        }
        boolean keepsBalance = tillKeepsBalance(tenantId, brandId);
        boolean anySyncing = bindings.stream().anyMatch(SyncBinding::syncing);
        // The row's own version starts at 0, so «never said» needs a number of its own: the If-Match is the row's
        // version
        // plus one, and 0 is a brand that has not said.
        long keepsBalanceVersion = configurationAuthor
                .currentVersion(LoyaltyConfigurationKeys.POS_TILL_KEEPS_BALANCE, ResourceScope.brand(tenantId, brandId))
                .map(rowVersion -> rowVersion + 1)
                .orElse(0L);
        return new Overview(keepsBalance, keepsBalanceVersion, keepsBalance && !anySyncing, views, totalOpen);
    }

    // ================================================================ staging the rollout

    /**
     * Stages a binding's sync mode (ADR 0171 rollout: {@code REPORT_ONLY}, then {@code PULL}, then {@code PULL_AND_PUSH}),
     * against the version of the binding the caller read.
     *
     * <p>Any step down is allowed at once (it is the way to stop writing to a till, and stopping needs no waiting); a step
     * up is one stage at a time, because the record's rollout is staged so a tenant's finance reads a report before pulls
     * apply and watches pulls before pushes. Anything above {@code REPORT_ONLY} needs a binding that can sync: capable,
     * enabled and active, so a mode is never staged for a till the platform cannot reach.
     *
     * @throws ApiException 404 for a binding that is not this brand's, 422 for a binding that cannot sync or a step up that
     *                      skips a stage, 409 {@code STALE_VERSION} when the binding has moved on
     */
    public BindingView setMode(
            UUID tenantId,
            UUID brandId,
            UUID bindingId,
            Mode target,
            long expectedVersion,
            String reason,
            String actorSubject) {
        SyncBinding binding = requireBinding(tenantId, brandId, bindingId);
        if (binding.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, binding.version());
        }
        Mode current = modeOf(binding);
        if (target == current) {
            return viewOf(tenantId, brandId, binding);
        }
        if (target != Mode.REPORT_ONLY) {
            requireSyncable(binding);
        }
        if (target.ordinal() > current.ordinal() + 1) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "The rollout is staged: %s comes before %s".formatted(Mode.values()[current.ordinal() + 1], target),
                    Map.of("currentMode", current.name(), "requestedMode", target.name()));
        }
        java.util.OptionalLong moved = port.setSyncMode(tenantId, bindingId, target.name(), expectedVersion);
        if (moved.isEmpty()) {
            SyncBinding now = port.binding(tenantId, bindingId);
            throw ApiException.staleVersion(expectedVersion, now == null ? expectedVersion : now.version());
        }
        audit.record(AuditFact.of("loyalty.pos_sync.mode.changed", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("PosSyncBinding", bindingId)
                .targetVersion(moved.getAsLong())
                .because(reason)
                .changed(ChangeDocuments.diff(Map.of("mode", current.name()), Map.of("mode", target.name())))
                .usingCapability("loyalty.sync.manage")
                .correlatedBy(bindingId.toString())
                .occurredAt(clock.instant())
                .build());
        SyncBinding after = requireBinding(tenantId, brandId, bindingId);
        return viewOf(tenantId, brandId, after);
    }

    /**
     * Says, for this brand, whether its till keeps a bonus balance of its own (ADR 0171 Decision 8): the statement that
     * turns on the standing notice when no binding mirrors the till. Written through the configuration author, which
     * keeps the row's own version and writes the audit fact; the brand's {@code If-Match} is {@link
     * Overview#tillKeepsBalanceVersion}, 0 for a brand that has never said.
     */
    public Overview setTillKeepsBalance(
            UUID tenantId, UUID brandId, boolean value, long expectedVersion, String reason, String actorSubject) {
        configurationAuthor.set(
                LoyaltyConfigurationKeys.POS_TILL_KEEPS_BALANCE,
                ResourceScope.brand(tenantId, brandId),
                value,
                false,
                expectedVersion == 0 ? null : expectedVersion - 1,
                ActorRef.user(actorSubject, null),
                reason);
        return overview(tenantId, brandId);
    }

    private BindingView viewOf(UUID tenantId, UUID brandId, SyncBinding binding) {
        return new BindingView(
                binding,
                modeOf(binding),
                store.countMatched(tenantId, binding.bindingId()),
                store.openCountByBinding(tenantId, brandId).getOrDefault(binding.bindingId(), 0L),
                store.lastRunByBinding(tenantId, brandId).get(binding.bindingId()));
    }

    public boolean tillKeepsBalance(UUID tenantId, UUID brandId) {
        Boolean value = configuration.value(
                LoyaltyConfigurationKeys.POS_TILL_KEEPS_BALANCE, ResourceScope.brand(tenantId, brandId));
        return Boolean.TRUE.equals(value);
    }

    // ===================================================================== runs

    /**
     * Runs one pass over one binding and returns its report row.
     *
     * @throws ApiException 404 for a binding that is not this brand's, 422 for one that cannot sync (a vendor that
     *                      has not been assessed, a capability that is not enabled, a suspended binding)
     */
    public RunRow run(UUID tenantId, UUID brandId, UUID bindingId, RunKind kind, String requestedBy) {
        SyncBinding binding = requireBinding(tenantId, brandId, bindingId);
        requireSyncable(binding);
        Mode mode = modeOf(binding);

        UUID runId = Ids.newId();
        Instant started = clock.instant();
        store.insertRun(runId, tenantId, brandId, bindingId, kind.name(), requestedBy, started);
        Counts counts = new Counts();
        String status = "SUCCEEDED";
        String failure = null;
        try {
            if (kind != RunKind.PUSH) {
                match(tenantId, brandId, binding, runId, counts);
            }
            if (kind == RunKind.PULL && mode.pulls()) {
                pull(tenantId, brandId, binding, runId, counts);
            }
            if (kind == RunKind.PUSH && mode.pushes()) {
                push(tenantId, brandId, binding, runId, counts);
            }
            if (kind == RunKind.RECONCILE) {
                if (mode.pulls()) {
                    pull(tenantId, brandId, binding, runId, counts);
                }
                if (mode.pushes()) {
                    push(tenantId, brandId, binding, runId, counts);
                }
                compare(tenantId, brandId, binding, runId, counts);
            }
        } catch (RuntimeException failed) {
            status = "FAILED";
            failure = failed.getClass().getSimpleName();
            log.warn("A loyalty till sync pass failed for binding {} ({}): {}", bindingId, kind, failure);
        }

        RunRow finished = counts.toRow(runId, tenantId, brandId, bindingId, kind.name(), started, requestedBy);
        store.finishRun(tenantId, runId, finished, status, failure, clock.instant());
        if (counts.imported > 0) {
            audit.record(AuditFact.of("loyalty.pos_sync.imported", AuditClass.BUSINESS)
                    .by(ActorRef.service(PosSyncMovementImporter.actorOf(bindingId)))
                    .at(ResourceScope.brand(tenantId, brandId))
                    .target("PosSyncRun", runId)
                    .because("Movements at the till were imported into the ledger")
                    .changed(ChangeDocuments.created(Map.of("imported", counts.imported, "held", counts.held)))
                    .correlatedBy(runId.toString())
                    .occurredAt(clock.instant())
                    .build());
        }
        return store.findRun(tenantId, runId).orElse(finished);
    }

    // ==================================================================== match

    private void match(UUID tenantId, UUID brandId, SyncBinding binding, UUID runId, Counts counts) {
        UUID bindingId = binding.bindingId();
        if (Boolean.FALSE.equals(binding.limits().get("one_to_one"))) {
            // ADR 0046's rejected row stays rejected: a programme whose unit is not one point to one som cannot be
            // bound, and the refusal says why. Opened once, refreshed after.
            observe(
                    tenantId,
                    brandId,
                    bindingId,
                    runId,
                    "RATIO_MISMATCH",
                    Subject.BINDING,
                    null,
                    null,
                    null,
                    null,
                    null);
            return;
        }

        Set<String> seen = new HashSet<>();
        // Where the last pass stopped. One pass reads a bounded number of pages, so a till with more customers than
        // that is walked over several; a walk that reached the end starts again at the top, where the customers who
        // joined the till since are.
        String cursor = store.matchCursor(tenantId, bindingId).orElse(null);
        boolean readable = false;
        for (int page = 0; page < MAX_CUSTOMER_PAGES; page++) {
            TillCustomerPage customers = port.listTillCustomers(tenantId, bindingId, cursor);
            if (customers.result() != Result.OK) {
                counts.customerListUnverifiable = true;
                observe(
                        tenantId,
                        brandId,
                        bindingId,
                        runId,
                        "UNVERIFIABLE",
                        Subject.BINDING,
                        null,
                        null,
                        null,
                        null,
                        null);
                if (customers.result() == Result.REJECTED && cursor != null) {
                    // The till will not take the cursor kept for it: start again at the top next time, instead of
                    // asking for the same page for ever and never reaching a customer beyond it.
                    store.saveMatchCursor(tenantId, bindingId, null, clock.instant());
                }
                return;
            }
            if (!readable) {
                readable = true;
                conditionEnded(
                        tenantId,
                        bindingId,
                        "UNVERIFIABLE",
                        Subject.BINDING,
                        null,
                        null,
                        "The till's customer list can be read again");
            }
            for (TillCustomer customer : customers.customers()) {
                if (!seen.add(customer.externalCustomerId())) {
                    continue;
                }
                matchOne(tenantId, brandId, binding, runId, customer, counts);
            }
            cursor = customers.nextCursor();
            // After the page's customers are matched, so a crash repeats a page (which matches each customer once)
            // and never skips one.
            store.saveMatchCursor(tenantId, bindingId, storable(bindingId, cursor), clock.instant());
            if (cursor == null) {
                return;
            }
        }
    }

    private boolean requiresConsent(UUID tenantId, UUID brandId) {
        return Boolean.TRUE.equals(configuration.value(
                LoyaltyConfigurationKeys.POS_SYNC_REQUIRE_CONSENT, ResourceScope.brand(tenantId, brandId)));
    }

    /** Whether the customer granted the sync's purpose at this brand; absence is withheld. */
    private boolean consented(UUID tenantId, UUID brandId, UUID customerAccountId) {
        return consents.consentFor(
                        tenantId, customerAccountId, brandId, LoyaltyConfigurationKeys.POS_SYNC_CONSENT_PURPOSE, null)
                .map(ConsentDirectory.ConsentState::granted)
                .orElse(false);
    }

    /** A cursor too long to keep is not kept: the next pass starts at the top, and the operator is told once. */
    private static @Nullable String storable(UUID bindingId, @Nullable String cursor) {
        if (cursor != null && cursor.length() > MAX_STORED_CURSOR) {
            log.warn(
                    "A till's customer cursor for binding {} is too long to resume from; the next pass starts at the top",
                    bindingId);
            return null;
        }
        return cursor;
    }

    private void matchOne(
            UUID tenantId, UUID brandId, SyncBinding binding, UUID runId, TillCustomer customer, Counts counts) {
        UUID bindingId = binding.bindingId();
        String external = customer.externalCustomerId();
        if (store.findStateByExternal(tenantId, bindingId, external).isPresent()) {
            return;
        }
        String phone = customer.phone();
        if (phone == null || phone.isBlank()) {
            tillCustomerDifference(tenantId, brandId, bindingId, runId, "TILL_ONLY", null, external);
            counts.tillOnly++;
            return;
        }
        List<CustomerAccountRef> found = phones.findByPhone(tenantId, phone);
        if (found.isEmpty()) {
            tillCustomerDifference(tenantId, brandId, bindingId, runId, "TILL_ONLY", null, external);
            counts.tillOnly++;
            return;
        }
        if (found.size() > 1) {
            // ADR 0015: two households can share one phone, and a guess would be the auto-merge it forbids.
            tillCustomerDifference(tenantId, brandId, bindingId, runId, "AMBIGUOUS_MATCH", null, external);
            counts.ambiguous++;
            return;
        }
        AccountRow account = loyalty.findAccount(
                        tenantId, brandId, found.getFirst().accountId())
                .orElse(null);
        if (account == null) {
            // A customer who has never earned or spent points at this brand has no points account, and one is not
            // created from a till row.
            tillCustomerDifference(tenantId, brandId, bindingId, runId, "TILL_ONLY", null, external);
            counts.tillOnly++;
            return;
        }
        if (requiresConsent(tenantId, brandId)
                && !consented(tenantId, brandId, found.getFirst().accountId())) {
            // ADR 0171, open input 4: built dark. When counsel says a customer's consent is needed, the key is flipped
            // and an
            // absent or withheld consent is not a match, whichever the reason: absence is not consent (ADR 0015, ADR
            // 0039).
            tillCustomerDifference(tenantId, brandId, bindingId, runId, "NOT_CONSENTED", account.id(), external);
            return;
        }
        if (store.findStateByAccount(tenantId, bindingId, account.id()).isPresent()) {
            // The account is already paired with another till customer: two till records for one person.
            tillCustomerDifference(tenantId, brandId, bindingId, runId, "AMBIGUOUS_MATCH", account.id(), external);
            counts.ambiguous++;
            return;
        }
        UUID mappingId =
                port.recordMatch(tenantId, bindingId, account.id(), external).orElse(null);
        if (mappingId == null) {
            tillCustomerDifference(tenantId, brandId, bindingId, runId, "AMBIGUOUS_MATCH", account.id(), external);
            counts.ambiguous++;
            return;
        }
        Instant now = clock.instant();
        store.insertState(Ids.newId(), tenantId, brandId, bindingId, account.id(), mappingId, external, now);
        // Matched: what said this till customer had no account, or too many, is over.
        String reason = "The till customer is matched to a points account now";
        conditionEnded(tenantId, bindingId, "TILL_ONLY", Subject.TILL_CUSTOMER, null, external, reason);
        conditionEnded(tenantId, bindingId, "AMBIGUOUS_MATCH", Subject.TILL_CUSTOMER, null, external, reason);
        conditionEnded(tenantId, bindingId, "NOT_CONSENTED", Subject.TILL_CUSTOMER, account.id(), external, reason);
        audit.record(AuditFact.of("loyalty.pos_sync.matched", AuditClass.BUSINESS)
                .by(ActorRef.service(PosSyncMovementImporter.actorOf(bindingId)))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("LoyaltyAccount", account.id())
                .because("A till customer was matched to a points account by phone")
                .changed(ChangeDocuments.created(Map.of("bindingId", bindingId, "accountId", account.id())))
                .correlatedBy(runId.toString())
                .occurredAt(now)
                .build());
    }

    private void tillCustomerDifference(
            UUID tenantId,
            UUID brandId,
            UUID bindingId,
            UUID runId,
            String kind,
            @Nullable UUID accountId,
            String externalCustomerId) {
        observe(
                tenantId,
                brandId,
                bindingId,
                runId,
                kind,
                Subject.TILL_CUSTOMER,
                accountId,
                externalCustomerId,
                null,
                null,
                null);
    }

    // ===================================================================== pull

    private void pull(UUID tenantId, UUID brandId, SyncBinding binding, UUID runId, Counts counts) {
        long maxAuto = longPolicy(
                tenantId,
                brandId,
                LoyaltyConfigurationKeys.POS_SYNC_MAX_AUTO_CREDIT_MINOR,
                LoyaltyConfigurationKeys.POS_SYNC_MAX_AUTO_CREDIT_DEFAULT);
        long maxBinding = longPolicy(
                tenantId,
                brandId,
                LoyaltyConfigurationKeys.POS_SYNC_MAX_BINDING_CREDIT_MINOR_PER_DAY,
                LoyaltyConfigurationKeys.POS_SYNC_MAX_BINDING_CREDIT_PER_DAY_DEFAULT);

        for (StateRow state : store.matchedStates(tenantId, binding.bindingId())) {
            String cursor = state.lastPullCursor();
            for (int page = 0; page < MAX_MOVEMENT_PAGES; page++) {
                TillMovementPage movements =
                        port.readMovements(tenantId, binding.bindingId(), state.externalCustomerId(), cursor);
                if (movements.result() != Result.OK) {
                    unverifiable(tenantId, brandId, binding.bindingId(), runId, state, counts);
                    break;
                }
                for (TillMovement movement : movements.movements()) {
                    Imported imported = importer.importMovement(
                            tenantId,
                            brandId,
                            binding.bindingId(),
                            runId,
                            state.accountId(),
                            movement,
                            maxAuto,
                            maxBinding);
                    switch (imported.outcome()) {
                        case APPLIED, PARTIALLY_APPLIED -> counts.imported++;
                        case HELD -> counts.held++;
                        case REJECTED, DUPLICATE -> {}
                    }
                }
                String next = movements.nextCursor();
                // The cursor advances only after the page's movements are in the ledger, so a crash replays a page
                // (which imports each movement once) and never skips one.
                store.setCursor(tenantId, state.id(), next != null ? next : cursor, clock.instant());
                if (next == null || next.equals(cursor)) {
                    break;
                }
                cursor = next;
            }
        }
    }

    // ===================================================================== push

    private void push(UUID tenantId, UUID brandId, SyncBinding binding, UUID runId, Counts counts) {
        for (PendingPush pending : store.pendingPushes(tenantId, binding.bindingId())) {
            // Only the latest version matters: this reads the account as it is now, so a burst of movements since the
            // last pass is one write of one number.
            String key = "POSPUSH:" + binding.bindingId() + ":" + pending.accountId() + ":" + pending.accountVersion();
            Result result = port.writeBalance(
                    tenantId,
                    binding.bindingId(),
                    pending.externalCustomerId(),
                    pending.balanceMinor(),
                    pending.accountVersion(),
                    key);
            if (result == Result.UNCERTAIN) {
                // Resolved by a read, never by assuming either way. A set-to-this-value write converges, so leaving the
                // account pending and sending the same value again next pass is safe; marking it pushed on a guess is
                // not.
                TillBalance read = port.readBalance(tenantId, binding.bindingId(), pending.externalCustomerId());
                if (read.result() == Result.OK
                        && read.balanceMinor() != null
                        && read.balanceMinor() == pending.balanceMinor()) {
                    result = Result.OK;
                }
            }
            if (result == Result.OK) {
                store.markPushed(
                        tenantId, pending.stateId(), pending.accountVersion(), pending.balanceMinor(), clock.instant());
                counts.pushed++;
            } else {
                unverifiable(
                        tenantId,
                        brandId,
                        binding.bindingId(),
                        runId,
                        pending.accountId(),
                        pending.externalCustomerId(),
                        pending.balanceMinor(),
                        counts);
            }
        }
    }

    // =================================================================== compare

    private void compare(UUID tenantId, UUID brandId, SyncBinding binding, UUID runId, Counts counts) {
        UUID bindingId = binding.bindingId();
        for (StateRow state : store.matchedStates(tenantId, bindingId)) {
            AccountRow account =
                    loyalty.findAccountById(tenantId, state.accountId()).orElse(null);
            if (account == null) {
                continue;
            }
            counts.compared++;
            TillBalance till = port.readBalance(tenantId, bindingId, state.externalCustomerId());
            Instant now = clock.instant();
            if (till.result() == Result.REJECTED) {
                // The till no longer knows this customer: matched on the platform side only.
                observe(
                        tenantId,
                        brandId,
                        bindingId,
                        runId,
                        "PLATFORM_ONLY",
                        Subject.ACCOUNT,
                        account.id(),
                        state.externalCustomerId(),
                        account.balanceMinor(),
                        null,
                        null);
                counts.platformOnly++;
                endUnverifiableUnlessThisRunFoundIt(tenantId, bindingId, account.id(), counts);
                continue;
            }
            if (till.result() != Result.OK
                    || till.balanceMinor() == null
                    || (till.currency() != null && !till.currency().equals(account.currency()))) {
                // "We could not tell" and "it differs" lead to different actions, and a till balance in another
                // currency is refused rather than compared.
                unverifiable(
                        tenantId,
                        brandId,
                        bindingId,
                        runId,
                        account.id(),
                        state.externalCustomerId(),
                        account.balanceMinor(),
                        counts);
                continue;
            }
            // The till answered with a number we can compare: the two conditions that said it did not are over.
            conditionEnded(
                    tenantId,
                    bindingId,
                    "PLATFORM_ONLY",
                    Subject.ACCOUNT,
                    account.id(),
                    null,
                    "The till knows this customer again");
            endUnverifiableUnlessThisRunFoundIt(tenantId, bindingId, account.id(), counts);
            long tillBalance = till.balanceMinor();
            store.observeTill(tenantId, state.id(), tillBalance, till.observedAt() != null ? till.observedAt() : now);
            if (tillBalance == account.balanceMinor()) {
                counts.matched++;
                endDifference(tenantId, bindingId, account.id(), "TILL_HIGHER");
                endDifference(tenantId, bindingId, account.id(), "TILL_LOWER");
                continue;
            }
            long delta = tillBalance - account.balanceMinor();
            String kind = delta > 0 ? "TILL_HIGHER" : "TILL_LOWER";
            // The opposite direction is stale the moment this one is true.
            endDifference(tenantId, bindingId, account.id(), delta > 0 ? "TILL_LOWER" : "TILL_HIGHER");
            observe(
                    tenantId,
                    brandId,
                    bindingId,
                    runId,
                    kind,
                    Subject.ACCOUNT,
                    account.id(),
                    state.externalCustomerId(),
                    account.balanceMinor(),
                    tillBalance,
                    delta);
            if (delta > 0) {
                counts.tillHigher++;
            } else {
                counts.tillLower++;
            }
        }
    }

    /**
     * A matched account the run could not verify, once per run however many steps failed on it: the same account
     * unreadable in the movement feed, in the push and in the balance read is one account the report cannot vouch for.
     * It is also a row a person can read, so the number in the run is the number of rows listed under it.
     */
    private void unverifiable(UUID tenantId, UUID brandId, UUID bindingId, UUID runId, StateRow state, Counts counts) {
        Long platformBalance = loyalty.findAccountById(tenantId, state.accountId())
                .map(AccountRow::balanceMinor)
                .orElse(null);
        unverifiable(
                tenantId,
                brandId,
                bindingId,
                runId,
                state.accountId(),
                state.externalCustomerId(),
                platformBalance,
                counts);
    }

    private void unverifiable(
            UUID tenantId,
            UUID brandId,
            UUID bindingId,
            UUID runId,
            UUID accountId,
            String externalCustomerId,
            @Nullable Long platformBalance,
            Counts counts) {
        if (!counts.unverifiableAccounts.add(accountId)) {
            return;
        }
        observe(
                tenantId,
                brandId,
                bindingId,
                runId,
                "UNVERIFIABLE",
                Subject.ACCOUNT,
                accountId,
                externalCustomerId,
                platformBalance,
                null,
                null);
    }

    /**
     * The till has answered for this account; an earlier "could not tell" is over unless this very run met one on the
     * account in the movement feed or the push, in which case it still stands.
     */
    private void endUnverifiableUnlessThisRunFoundIt(UUID tenantId, UUID bindingId, UUID accountId, Counts counts) {
        if (!counts.unverifiableAccounts.contains(accountId)) {
            conditionEnded(
                    tenantId,
                    bindingId,
                    "UNVERIFIABLE",
                    Subject.ACCOUNT,
                    accountId,
                    null,
                    "The till answers for this customer again");
        }
    }

    private void endDifference(UUID tenantId, UUID bindingId, UUID accountId, String kind) {
        conditionEnded(tenantId, bindingId, kind, Subject.ACCOUNT, accountId, null, "The two balances agree again");
    }

    /**
     * Records that a run saw a difference, and opens it only if it is new.
     *
     * <p>An open one of the same kind about the same subject is brought up to date; a pass that finds it again
     * updates the numbers a person is looking at instead of stacking rows. One a person <em>absorbed</em> and that
     * still holds is not reopened either: the brand has said it carries this, and asking again every pass is how an
     * inbox stops being read. A difference whose size is what was absorbed (a balance difference of the same
     * amount) is the same difference; a different size is a new one, because what was accepted was the amount.
     * Either way the run lists it, so its counts and its listing agree.
     */
    private void observe(
            UUID tenantId,
            UUID brandId,
            UUID bindingId,
            UUID runId,
            String kind,
            Subject subject,
            @Nullable UUID accountId,
            @Nullable String externalCustomerId,
            @Nullable Long platformBalance,
            @Nullable Long tillBalance,
            @Nullable Long delta) {
        Instant now = clock.instant();
        DiscrepancyRow open = store.findOpen(tenantId, bindingId, kind, subject, accountId, externalCustomerId)
                .orElse(null);
        if (open != null) {
            store.refreshDiscrepancy(tenantId, open.id(), platformBalance, tillBalance, delta, now);
            store.recordFinding(tenantId, runId, open.id(), platformBalance, tillBalance, delta, now);
            return;
        }
        DiscrepancyRow carried = store.findCarried(tenantId, bindingId, kind, subject, accountId, externalCustomerId)
                .orElse(null);
        if (carried != null) {
            if (!SIZED_KINDS.contains(kind) || java.util.Objects.equals(carried.deltaMinor(), delta)) {
                store.recordFinding(tenantId, runId, carried.id(), platformBalance, tillBalance, delta, now);
                return;
            }
            store.uncarry(tenantId, carried.id());
        }
        UUID id = Ids.newId();
        store.insertDiscrepancy(
                id,
                tenantId,
                brandId,
                bindingId,
                accountId,
                runId,
                externalCustomerId,
                kind,
                platformBalance,
                tillBalance,
                delta,
                null,
                now);
        events.publishEvent(new LoyaltySyncDiscrepancyOpened(tenantId, brandId, bindingId, id, accountId, kind));
    }

    /**
     * A difference that is no longer true is closed by the system with the one resolution that describes what happened:
     * the two sides agree again without the platform having been rewritten, which is what ACCEPT_PLATFORM means. It is
     * never a way to make a difference disappear that still exists. One a person had absorbed is let go of, so that if
     * the condition comes back it is reported as the new difference it is.
     */
    private void conditionEnded(
            UUID tenantId,
            UUID bindingId,
            String kind,
            Subject subject,
            @Nullable UUID accountId,
            @Nullable String externalCustomerId,
            String reason) {
        store.findOpen(tenantId, bindingId, kind, subject, accountId, externalCustomerId)
                .ifPresent(open -> store.resolve(
                        tenantId,
                        open.id(),
                        open.version(),
                        Resolution.ACCEPT_PLATFORM.name(),
                        reason,
                        PosSyncMovementImporter.actorOf(bindingId),
                        null,
                        false,
                        clock.instant()));
        store.findCarried(tenantId, bindingId, kind, subject, accountId, externalCustomerId)
                .ifPresent(carried -> store.uncarry(tenantId, carried.id()));
    }

    // ================================================================ resolution

    /**
     * Settles one discrepancy, once, against the version the caller read (ADR 0171 Decision 6).
     *
     * <p>The decision is a person's. {@code ACCEPT_TILL} applies the till's number as an adjustment -- for a credit
     * above the manual-adjustment threshold only after a second person has signed, in which case the discrepancy stays
     * open and the answer is {@code PENDING}. {@code ACCEPT_PLATFORM} pushes the platform's number again.
     * {@code ABSORB} records that the brand carries it. {@code SUSPEND} stops syncing that customer.
     */
    public ResolutionResult resolve(
            UUID tenantId,
            UUID brandId,
            UUID discrepancyId,
            Resolution resolution,
            String reason,
            long expectedVersion,
            String actorSubject) {

        DiscrepancyRow discrepancy = store.findDiscrepancy(tenantId, discrepancyId)
                .filter(row -> row.brandId().equals(brandId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such discrepancy"));
        if (!"OPEN".equals(discrepancy.status())) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This discrepancy is already resolved");
        }
        if (discrepancy.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, discrepancy.version());
        }
        requireAllowed(discrepancy, resolution);

        UUID approvalId = null;
        ApprovalOutcome outcome = null;
        switch (resolution) {
            case ACCEPT_TILL -> {
                outcome = acceptTill(tenantId, brandId, discrepancy, reason, actorSubject);
                if (!outcome.mayProceed()) {
                    return new ResolutionResult(discrepancy, false, approvalLabel(outcome), approvalRequestId(outcome));
                }
                approvalId = approvalRequestId(outcome);
            }
            case ACCEPT_PLATFORM -> acceptPlatform(tenantId, discrepancy);
            case SUSPEND -> {
                if (discrepancy.accountId() == null) {
                    throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, "There is no matched customer to suspend");
                }
                store.findStateByAccount(tenantId, discrepancy.bindingId(), discrepancy.accountId())
                        .ifPresent(state -> store.setStateStatus(tenantId, state.id(), "SUSPENDED", clock.instant()));
            }
            case ABSORB -> {}
        }

        Instant now = clock.instant();
        if (!store.resolve(
                tenantId,
                discrepancyId,
                discrepancy.version(),
                resolution.name(),
                reason,
                actorSubject,
                approvalId,
                // The brand carries it: a later pass that sees the same condition does not ask again. A difference
                // that stands on a movement is that movement's, settled once, and is never seen again.
                resolution == Resolution.ABSORB && discrepancy.movementId() == null,
                now)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "This discrepancy was resolved or changed by someone else");
        }
        audit.record(AuditFact.of("loyalty.pos_sync.resolved", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("PosSyncDiscrepancy", discrepancyId)
                .because(reason)
                .changed(ChangeDocuments.diff(
                        Map.of("status", "OPEN"),
                        Map.of("status", "RESOLVED", "resolution", resolution.name(), "kind", discrepancy.kind())))
                .correlatedBy(discrepancyId.toString())
                .occurredAt(now)
                .build());
        DiscrepancyRow settled = store.findDiscrepancy(tenantId, discrepancyId).orElse(discrepancy);
        return new ResolutionResult(
                settled, true, outcome == null ? "NOT_REQUIRED" : approvalLabel(outcome), approvalId);
    }

    private ApprovalOutcome acceptTill(
            UUID tenantId, UUID brandId, DiscrepancyRow d, String reason, String actorSubject) {
        AccountRow account = d.accountId() == null
                ? null
                : loyalty.findAccountById(tenantId, d.accountId()).orElse(null);
        if (account == null) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "There is no points account to apply the till's number to");
        }

        long amount;
        String reasonCode;
        String key;
        if ("CREDIT_HELD".equals(d.kind())) {
            MovementRow movement = d.movementId() == null
                    ? null
                    : store.findMovement(tenantId, d.movementId()).orElse(null);
            if (movement == null || !"HELD".equals(movement.status())) {
                throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, "The held credit is no longer held");
            }
            amount = movement.amountMinor();
            reasonCode = PosBalanceSyncReasons.EARN;
            // The key the sync would have written, so the credit a person accepts is the same entry the till movement
            // would have been, and can be applied once.
            key = PosSyncMovementImporter.entryKey(d.bindingId(), movement.externalMovementId());
        } else {
            // A fresh read, never the stored numbers: they may be five minutes old and the till keeps moving.
            TillBalance fresh = port.readBalance(tenantId, d.bindingId(), externalIdOf(tenantId, d));
            if (fresh.result() != Result.OK || fresh.balanceMinor() == null) {
                throw new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "The till's balance could not be read just now, so its number cannot be accepted");
            }
            amount = fresh.balanceMinor() - account.balanceMinor();
            reasonCode = PosBalanceSyncReasons.CORRECTION;
            key = "POSSYNC-RESOLVE:" + d.id();
            if (amount == 0) {
                return new ApprovalOutcome.NotRequired();
            }
        }

        ApprovalOutcome outcome = adjustments.adjustForTillResolution(new AdjustmentCommand(
                tenantId,
                brandId,
                account.customerAccountId(),
                amount,
                account.currency(),
                reasonCode,
                reason,
                ActorRef.user(actorSubject, null),
                key,
                d.id().toString()));
        if (outcome.mayProceed() && "CREDIT_HELD".equals(d.kind()) && d.movementId() != null) {
            store.settleMovement(tenantId, d.movementId(), "APPLIED", amount, 0, null, d.id());
        }
        return outcome;
    }

    private void acceptPlatform(UUID tenantId, DiscrepancyRow d) {
        if ("CREDIT_HELD".equals(d.kind())) {
            if (d.movementId() != null) {
                store.settleMovement(tenantId, d.movementId(), "REJECTED", 0, 0, null, d.id());
            }
            return;
        }
        if (d.accountId() == null) {
            return;
        }
        AccountRow account = loyalty.findAccountById(tenantId, d.accountId()).orElse(null);
        if (account == null) {
            return;
        }
        Result result = port.writeBalance(
                tenantId,
                d.bindingId(),
                externalIdOf(tenantId, d),
                account.balanceMinor(),
                account.version(),
                "POSPUSH-RESOLVE:" + d.id() + ":" + account.version());
        if (result != Result.OK) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "The till did not accept the platform's number (" + result + "); the difference stays open");
        }
        store.findStateByAccount(tenantId, d.bindingId(), account.id())
                .ifPresent(state -> store.markPushed(
                        tenantId, state.id(), account.version(), account.balanceMinor(), clock.instant()));
    }

    private String externalIdOf(UUID tenantId, DiscrepancyRow d) {
        if (d.externalCustomerId() != null) {
            return d.externalCustomerId();
        }
        return store.findStateByAccount(tenantId, d.bindingId(), java.util.Objects.requireNonNull(d.accountId()))
                .map(StateRow::externalCustomerId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE, "This customer is not matched to a till customer"));
    }

    private static void requireAllowed(DiscrepancyRow d, Resolution resolution) {
        Set<Resolution> allowed =
                switch (d.kind()) {
                    case "TILL_HIGHER", "TILL_LOWER" ->
                        Set.of(
                                Resolution.ACCEPT_TILL,
                                Resolution.ACCEPT_PLATFORM,
                                Resolution.ABSORB,
                                Resolution.SUSPEND);
                    // A held credit is accepted (credited, under approval above the threshold) or declined; there is
                    // nothing
                    // for the brand to absorb, because nothing has moved.
                    case "CREDIT_HELD" ->
                        Set.of(Resolution.ACCEPT_TILL, Resolution.ACCEPT_PLATFORM, Resolution.SUSPEND);
                    // Acknowledged by the brand: the shortfall is already on the liability report.
                    case "SPEND_EXCEEDS_BALANCE" -> Set.of(Resolution.ABSORB, Resolution.SUSPEND);
                    // Nothing to apply: a person has seen it.
                    default -> Set.of(Resolution.ABSORB);
                };
        if (!allowed.contains(resolution)) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    resolution + " is not a resolution of a " + d.kind() + " discrepancy",
                    Map.of("allowed", allowed.stream().map(Enum::name).sorted().toList()));
        }
    }

    private static String approvalLabel(ApprovalOutcome outcome) {
        return switch (outcome) {
            case ApprovalOutcome.NotRequired ignored -> "NOT_REQUIRED";
            case ApprovalOutcome.Pending ignored -> "PENDING";
            case ApprovalOutcome.Approved ignored -> "APPROVED";
            case ApprovalOutcome.Declined ignored -> "DECLINED";
        };
    }

    private static @Nullable UUID approvalRequestId(ApprovalOutcome outcome) {
        return switch (outcome) {
            case ApprovalOutcome.NotRequired ignored -> null;
            case ApprovalOutcome.Pending pending -> pending.requestId();
            case ApprovalOutcome.Approved approved -> approved.requestId();
            case ApprovalOutcome.Declined declined -> declined.requestId();
        };
    }

    // ==================================================================== reads

    public List<RunRow> runs(UUID tenantId, UUID brandId, Instant from, Instant to, int limit) {
        return store.runs(tenantId, brandId, from, to, limit);
    }

    public RunReport report(UUID tenantId, UUID brandId, UUID runId) {
        RunRow run = store.findRun(tenantId, runId)
                .filter(row -> row.brandId().equals(brandId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such reconciliation run"));
        return new RunReport(run, store.discrepanciesOfRun(tenantId, runId));
    }

    public List<DiscrepancyRow> discrepancies(UUID tenantId, UUID brandId, @Nullable String status, int limit) {
        return store.discrepancies(tenantId, brandId, status, limit);
    }

    public Mode modeOf(SyncBinding binding) {
        return Mode.parse(binding.configuration().get("loyalty.sync.mode"));
    }

    // ================================================================== helpers

    private SyncBinding requireBinding(UUID tenantId, UUID brandId, UUID bindingId) {
        SyncBinding binding = port.binding(tenantId, bindingId);
        if (binding == null
                || port.bindingsOf(tenantId, brandId).stream()
                        .noneMatch(b -> b.bindingId().equals(bindingId))) {
            // Another brand's binding and an unknown one answer alike.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such point-of-sale binding for this brand");
        }
        return binding;
    }

    private static void requireSyncable(SyncBinding binding) {
        if (!binding.state().configurable()) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "This point of sale cannot sync loyalty balances: its capability is " + binding.state(),
                    Map.of("capabilityState", binding.state().name()));
        }
        if (!binding.enabled()) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "Loyalty balance sync is not enabled on this binding");
        }
        if (!"ACTIVE".equals(binding.bindingStatus())) {
            // A suspended binding imports nothing.
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, "This binding is " + binding.bindingStatus());
        }
    }

    private long longPolicy(
            UUID tenantId, UUID brandId, uz.horecaos.platform.tenancy.api.ConfigurationKey<Long> key, long fallback) {
        Long value = configuration.value(key, ResourceScope.brand(tenantId, brandId));
        return value == null ? fallback : value;
    }

    /** The counts a run reports. Mutable only inside one pass. */
    private static final class Counts {
        int compared;
        int matched;
        int tillHigher;
        int tillLower;
        int tillOnly;
        int platformOnly;
        int ambiguous;
        int imported;
        int held;
        int pushed;

        /** The matched accounts this run could not vouch for, each once however many steps failed on it. */
        final Set<UUID> unverifiableAccounts = new HashSet<>();

        /** The till's customer list could not be read: not an account, but a thing the report cannot vouch for. */
        boolean customerListUnverifiable;

        RunRow toRow(
                UUID id,
                UUID tenantId,
                UUID brandId,
                UUID bindingId,
                String kind,
                Instant started,
                String requestedBy) {
            return new RunRow(
                    id,
                    tenantId,
                    brandId,
                    bindingId,
                    kind,
                    started,
                    null,
                    compared,
                    matched,
                    tillHigher,
                    tillLower,
                    tillOnly,
                    platformOnly,
                    ambiguous,
                    imported,
                    held,
                    unverifiableAccounts.size() + (customerListUnverifiable ? 1 : 0),
                    pushed,
                    "RUNNING",
                    null,
                    requestedBy);
        }
    }
}
