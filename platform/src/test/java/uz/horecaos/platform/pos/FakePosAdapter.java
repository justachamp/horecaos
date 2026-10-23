package uz.horecaos.platform.pos;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.pos.api.CapabilitySnapshot;
import uz.horecaos.platform.pos.api.CapabilitySnapshot.Entry;
import uz.horecaos.platform.pos.api.CapabilitySnapshot.IdempotencyBehaviour;
import uz.horecaos.platform.pos.api.CapabilitySupport;
import uz.horecaos.platform.pos.api.PosCapability;
import uz.horecaos.platform.pos.application.port.PosAdapter;
import uz.horecaos.platform.pos.domain.CatalogSnapshot;
import uz.horecaos.platform.pos.domain.ExportCandidate;
import uz.horecaos.platform.pos.domain.SourceKind;

/**
 * The ADR 0007 fake point of sale.
 *
 * <p>Test sources only, and it exists to be <em>unlike</em> the one real adapter
 * in every way that matters. Clopos has no idempotency key, no push, no
 * preparation feed, and an offset-only catalog walk; this fake has all four. If
 * the provider-neutral contract were only ever exercised against Clopos, the
 * abstraction would be a description of Clopos with an interface in front of it,
 * and the first genuinely different till would break it.
 *
 * <p>The one property worth stating plainly: {@link #exportOrder} deduplicates on
 * the correlation reference and counts its side effects, so a test can assert
 * that a repeat under one reference produced one order. That is precisely the
 * guarantee Clopos does not offer, and the guarantee whose absence shapes the
 * whole export path.
 */
public final class FakePosAdapter implements PosAdapter {

    public static final String PROVIDER_TYPE = "fake-pos";

    private final Map<String, String> ordersByCorrelation = new ConcurrentHashMap<>();
    private final AtomicInteger sideEffects = new AtomicInteger();

    // Plain ArrayList, not List.copyOf below: OrderExport#correlationReference is
    // @Nullable (the provider may silently drop the field), and both ArrayList and
    // Collections.unmodifiableList tolerate that where List.copyOf would throw.
    private final List<@Nullable String> exportedCorrelations = new ArrayList<>();

    private @Nullable ProviderOutcome nextExportOutcome;

    /** What {@link #readApprovalStatus} answers next, per external order id. Absent means still PENDING. */
    private final Map<String, PosAdapter.ApprovalRead.Decision> approvalDecisions = new ConcurrentHashMap<>();

    private @Nullable ProviderOutcome nextApprovalOutcome;

    /** Whether an export reports that the till still owes a clerk's decision. */
    private boolean exportsPendApproval;

    /** What {@link #readAvailability} answers, until {@link #scriptAvailability} changes it. */
    private List<CatalogSnapshot.Availability> nextAvailabilityEntries = List.of();

    private @Nullable ProviderOutcome nextAvailabilityOutcome;

    /** What {@link #discoverPaymentTypes}/{@link #discoverDiscounts} answer by default: one fake entry each. */
    private List<PosAdapter.ExternalReference> nextPaymentTypes =
            List.of(new PosAdapter.ExternalReference("fake-cash", "Cash"));

    private @Nullable ProviderOutcome nextPaymentTypesOutcome;

    private List<PosAdapter.ExternalReference> nextDiscounts =
            List.of(new PosAdapter.ExternalReference("fake-happy-hour", "Happy hour"));

    private @Nullable ProviderOutcome nextDiscountsOutcome;

    /** What {@link #discoverOperators} answers by default: two fake till operators. */
    private List<PosAdapter.ExternalReference> nextOperators = List.of(
            new PosAdapter.ExternalReference("fake-op-17", "Aziza"),
            new PosAdapter.ExternalReference("fake-op-18", "Bobur"));

    private @Nullable ProviderOutcome nextOperatorsOutcome;

    /**
     * Whether this till's order API carries the operator (PosCapability#ORDER_OPERATOR_ATTRIBUTION). True
     * by default, because the point of the fake is to be the shape Clopos is not; {@link
     * #withoutOperatorAttribution} turns it into a till that, like Clopos, receives none.
     */
    private boolean carriesOperator = true;

    /** The body each create-order call would put on the wire, in the order they came. */
    private final List<Map<String, Object>> wireBodies = new ArrayList<>();

    /** The {@code operatorExternalId} each create-order call was handed, in the order they came. */
    private final List<@Nullable String> operatorsReceived = new ArrayList<>();

    // ------------------------------------------------------------------
    // The customer wallet (ADR 0171, PosCapability#LOYALTY_BALANCE_SYNC)
    //
    // An in-memory till wallet that keeps customers by the till's own id, a balance in som, and the
    // movements that happened at the terminal -- with every failure mode a real till has injectable: a reply
    // lost after a write has been applied, a page of movements delivered twice, a spend at the terminal during a
    // push, a balance in another currency, a movement feed or a customer list that cannot be read.
    // ------------------------------------------------------------------

    private final Map<String, Wallet> wallets = new LinkedHashMap<>();
    private final Set<String> appliedBalanceWrites = ConcurrentHashMap.newKeySet();
    private final AtomicInteger balanceWrites = new AtomicInteger();
    private final AtomicInteger movementSequence = new AtomicInteger();
    private int walletPageSize = 100;
    private boolean loseNextBalanceWriteReply;
    private boolean duplicateNextMovementPage;
    private boolean movementFeedUnreadable;
    private boolean customerListUnreadable;
    private @Nullable String balanceCurrency;
    private @Nullable Runnable atNextBalanceWrite;

    // Order revision (ADR 0137, ORDER_REVISION): what the till was told, and the failure modes it can be commanded
    // into.
    private final List<RecordedRevision> revisions = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Set<String> appliedRevisionKeys = ConcurrentHashMap.newKeySet();
    private @Nullable ProviderOutcome nextRevisionOutcome;
    private boolean loseNextRevisionReply;

    /** One revised total the fake applied. */
    public record RecordedRevision(String externalOrderId, PosAdapter.TillRevision revision) {}

    private static final class Wallet {
        final String externalId;

        @Nullable
        String phone;

        long balanceMinor;
        final List<PosAdapter.TillMovement> movements = new ArrayList<>();

        Wallet(String externalId, @Nullable String phone, long balanceMinor) {
            this.externalId = externalId;
            this.phone = phone;
            this.balanceMinor = balanceMinor;
        }
    }

    /** Back to an empty till with no failure armed, for a suite that shares one fake across its tests. */
    public synchronized FakePosAdapter resetWallet() {
        wallets.clear();
        appliedBalanceWrites.clear();
        balanceWrites.set(0);
        movementSequence.set(0);
        walletPageSize = 100;
        loseNextBalanceWriteReply = false;
        duplicateNextMovementPage = false;
        movementFeedUnreadable = false;
        customerListUnreadable = false;
        balanceCurrency = null;
        atNextBalanceWrite = null;
        return this;
    }

    /** A till customer with an opening balance that came from before the sync: no movement explains it. */
    public synchronized FakePosAdapter tillCustomer(
            String externalId, @Nullable String phone, long openingBalanceMinor) {
        wallets.put(externalId, new Wallet(externalId, phone, openingBalanceMinor));
        return this;
    }

    public synchronized FakePosAdapter removeTillCustomer(String externalId) {
        wallets.remove(externalId);
        return this;
    }

    /** Points earned at the terminal: the balance rises and the movement joins the feed. */
    public synchronized String tillEarn(String externalId, long amountMinor) {
        return tillMove(externalId, PosAdapter.TillMovementKind.EARN, amountMinor);
    }

    /** Points spent at the terminal: the balance falls (never below zero) and the movement joins the feed. */
    public synchronized String tillSpend(String externalId, long amountMinor) {
        return tillMove(externalId, PosAdapter.TillMovementKind.SPEND, amountMinor);
    }

    private String tillMove(String externalId, PosAdapter.TillMovementKind kind, long amountMinor) {
        Wallet wallet = wallets.get(externalId);
        if (wallet == null) {
            throw new IllegalArgumentException("No till customer " + externalId);
        }
        String id = "M-" + movementSequence.incrementAndGet();
        wallet.movements.add(new PosAdapter.TillMovement(id, externalId, kind, amountMinor, Instant.EPOCH));
        wallet.balanceMinor = kind == PosAdapter.TillMovementKind.EARN
                ? wallet.balanceMinor + amountMinor
                : Math.max(0, wallet.balanceMinor - amountMinor);
        return id;
    }

    /** Sets the till's number directly, as a clerk or a tampered database would: no movement explains it. */
    public synchronized FakePosAdapter corruptTillBalance(String externalId, long balanceMinor) {
        wallet(externalId).balanceMinor = balanceMinor;
        return this;
    }

    public synchronized long tillBalance(String externalId) {
        return wallet(externalId).balanceMinor;
    }

    private Wallet wallet(String externalId) {
        Wallet wallet = wallets.get(externalId);
        if (wallet == null) {
            throw new IllegalArgumentException("No till customer " + externalId);
        }
        return wallet;
    }

    /** How many balance writes the fake actually applied or was asked to apply, counting a repeat. */
    public int balanceWriteCount() {
        return balanceWrites.get();
    }

    public FakePosAdapter walletPageSize(int size) {
        this.walletPageSize = size;
        return this;
    }

    /** The next balance write is applied, and its reply is lost: the caller sees UNCERTAIN. */
    public FakePosAdapter loseNextBalanceWriteReply() {
        this.loseNextBalanceWriteReply = true;
        return this;
    }

    /** The next page of movements repeats every movement it carries. */
    public FakePosAdapter duplicateNextMovementPage() {
        this.duplicateNextMovementPage = true;
        return this;
    }

    public FakePosAdapter movementFeedUnreadable(boolean unreadable) {
        this.movementFeedUnreadable = unreadable;
        return this;
    }

    public FakePosAdapter customerListUnreadable(boolean unreadable) {
        this.customerListUnreadable = unreadable;
        return this;
    }

    /** Every balance read answers in this currency instead of the account's. */
    public FakePosAdapter balanceInCurrency(@Nullable String currency) {
        this.balanceCurrency = currency;
        return this;
    }

    /** Runs once, inside the next balance write, before it lands: a spend at the terminal during a push. */
    public FakePosAdapter duringNextBalanceWrite(Runnable action) {
        this.atNextBalanceWrite = action;
        return this;
    }

    @Override
    public synchronized TillCustomersRead listTillCustomers(PosContext context, @Nullable String cursor) {
        if (customerListUnreadable) {
            return new TillCustomersRead(
                    ProviderOutcome.retryable("FAKE_DOWN", "customer list unreadable", null), List.of(), null);
        }
        int from = cursor == null ? 0 : Integer.parseInt(cursor);
        List<Wallet> all = new ArrayList<>(wallets.values());
        int to = Math.min(all.size(), from + walletPageSize);
        List<TillCustomer> page = new ArrayList<>();
        for (int i = from; i < to; i++) {
            page.add(new TillCustomer(all.get(i).externalId, all.get(i).phone));
        }
        return new TillCustomersRead(
                ProviderOutcome.success(Map.of(), null), page, to < all.size() ? Integer.toString(to) : null);
    }

    @Override
    public synchronized TillBalanceRead readBalance(PosContext context, String externalCustomerId) {
        Wallet wallet = wallets.get(externalCustomerId);
        if (wallet == null) {
            return new TillBalanceRead(
                    ProviderOutcome.rejected("NOT_FOUND", "no such till customer"), null, null, null);
        }
        return new TillBalanceRead(
                ProviderOutcome.success(Map.of(), externalCustomerId),
                wallet.balanceMinor,
                balanceCurrency == null ? "UZS" : balanceCurrency,
                Instant.EPOCH);
    }

    @Override
    public synchronized TillMovementsRead readMovements(
            PosContext context, @Nullable String externalCustomerId, @Nullable String cursor) {
        if (movementFeedUnreadable) {
            return new TillMovementsRead(
                    ProviderOutcome.retryable("FAKE_DOWN", "movement feed unreadable", null), List.of(), null);
        }
        List<PosAdapter.TillMovement> feed = new ArrayList<>();
        if (externalCustomerId != null) {
            Wallet wallet = wallets.get(externalCustomerId);
            if (wallet != null) {
                feed.addAll(wallet.movements);
            }
        } else {
            wallets.values().forEach(wallet -> feed.addAll(wallet.movements));
        }
        int from = cursor == null ? 0 : Integer.parseInt(cursor);
        int to = Math.min(feed.size(), from + walletPageSize);
        List<PosAdapter.TillMovement> page = new ArrayList<>(feed.subList(Math.min(from, feed.size()), to));
        if (duplicateNextMovementPage && !page.isEmpty()) {
            duplicateNextMovementPage = false;
            page.addAll(new ArrayList<>(page));
        }
        // The cursor is "how many movements have been read", so a caller that stores it and returns resumes at
        // the next one; at the end of the feed it stays where it is rather than going null, which is how a real
        // feed's "nothing new since this" cursor behaves.
        return new TillMovementsRead(ProviderOutcome.success(Map.of(), null), page, Integer.toString(to));
    }

    @Override
    public ProviderOutcome writeBalance(
            PosContext context,
            String externalCustomerId,
            long balanceMinor,
            long ledgerVersion,
            String idempotencyKey) {
        Runnable during = null;
        synchronized (this) {
            during = atNextBalanceWrite;
            atNextBalanceWrite = null;
        }
        if (during != null) {
            during.run();
        }
        synchronized (this) {
            balanceWrites.incrementAndGet();
            Wallet wallet = wallets.get(externalCustomerId);
            if (wallet == null) {
                return ProviderOutcome.rejected("NOT_FOUND", "no such till customer");
            }
            // KEYED: a repeat of an applied write under the same key changes nothing.
            if (appliedBalanceWrites.add(idempotencyKey)) {
                wallet.balanceMinor = balanceMinor;
            }
            if (loseNextBalanceWriteReply) {
                loseNextBalanceWriteReply = false;
                return ProviderOutcome.uncertain("FAKE_TIMEOUT", "the reply was lost after the write was applied");
            }
            return ProviderOutcome.success(Map.of(), externalCustomerId);
        }
    }

    /** How many orders the fake actually created, as opposed to was asked to. */
    /** The next revision is answered with {@code outcome} and not applied: a refusal, or a till that is down. */
    public synchronized FakePosAdapter failNextRevisionWith(ProviderOutcome outcome) {
        this.nextRevisionOutcome = outcome;
        return this;
    }

    /** The next revision is applied and the reply is lost: the platform sees an unknown outcome. */
    public synchronized FakePosAdapter loseNextRevisionReply() {
        this.loseNextRevisionReply = true;
        return this;
    }

    /** Every revised total the fake applied, oldest first; a repeat under one key is applied once. */
    public List<RecordedRevision> revisions() {
        return List.copyOf(revisions);
    }

    @Override
    public synchronized ProviderOutcome reviseExportedOrder(
            PosContext context, String externalOrderId, TillRevision revision) {
        ProviderOutcome scripted = nextRevisionOutcome;
        nextRevisionOutcome = null;
        if (scripted != null) {
            return scripted;
        }
        if (appliedRevisionKeys.add(revision.idempotencyKey())) {
            revisions.add(new RecordedRevision(externalOrderId, revision));
            sideEffects.incrementAndGet();
        }
        if (loseNextRevisionReply) {
            loseNextRevisionReply = false;
            return ProviderOutcome.uncertain("CONNECTION_RESET", "Connection lost after sending");
        }
        return ProviderOutcome.success(Map.of("revised", true), externalOrderId);
    }

    public int sideEffectCount() {
        return sideEffects.get();
    }

    public List<@Nullable String> exportedCorrelations() {
        return Collections.unmodifiableList(new ArrayList<>(exportedCorrelations));
    }

    /** Makes the next export fail the way a real one does. */
    public FakePosAdapter failNextExportWith(ProviderOutcome outcome) {
        this.nextExportOutcome = outcome;
        return this;
    }

    /** From the next {@link #readApprovalStatus} call for this order onward, the clerk has decided. */
    public FakePosAdapter scriptApprovalDecision(String externalOrderId, PosAdapter.ApprovalRead.Decision decision) {
        approvalDecisions.put(externalOrderId, decision);
        return this;
    }

    /** Makes the next {@link #readApprovalStatus} call fail the way a real one does. */
    /**
     * Makes every export report {@code approvalPending}, which is what causes
     * {@code PosOrderExportService} to flag the row for the approval poll.
     */
    public FakePosAdapter exportsPendApproval() {
        this.exportsPendApproval = true;
        return this;
    }

    public FakePosAdapter failNextApprovalReadWith(ProviderOutcome outcome) {
        this.nextApprovalOutcome = outcome;
        return this;
    }

    /** From the next {@link #readAvailability} call onward, this is the stop list. */
    public FakePosAdapter scriptAvailability(List<CatalogSnapshot.Availability> entries) {
        this.nextAvailabilityEntries = List.copyOf(entries);
        return this;
    }

    /** Makes the next {@link #readAvailability} call fail the way a real one does. */
    public FakePosAdapter failNextAvailabilityReadWith(ProviderOutcome outcome) {
        this.nextAvailabilityOutcome = outcome;
        return this;
    }

    /** From the next {@link #discoverPaymentTypes} call onward, this is what it finds. */
    public FakePosAdapter scriptPaymentTypes(List<ExternalReference> entries) {
        this.nextPaymentTypes = List.copyOf(entries);
        return this;
    }

    /** Makes {@link #discoverPaymentTypes} answer the way an unsupported vendor does. */
    public FakePosAdapter failPaymentTypesWith(ProviderOutcome outcome) {
        this.nextPaymentTypesOutcome = outcome;
        return this;
    }

    /** From the next {@link #discoverDiscounts} call onward, this is what it finds. */
    public FakePosAdapter scriptDiscounts(List<ExternalReference> entries) {
        this.nextDiscounts = List.copyOf(entries);
        return this;
    }

    /** Makes {@link #discoverDiscounts} answer the way an unsupported vendor does. */
    public FakePosAdapter failDiscountsWith(ProviderOutcome outcome) {
        this.nextDiscountsOutcome = outcome;
        return this;
    }

    /** From the next {@link #discoverOperators} call onward, this is what it finds. */
    public FakePosAdapter scriptOperators(List<ExternalReference> entries) {
        this.nextOperators = List.copyOf(entries);
        return this;
    }

    /** Makes {@link #discoverOperators} answer the way an unsupported vendor does. */
    public FakePosAdapter failOperatorsWith(ProviderOutcome outcome) {
        this.nextOperatorsOutcome = outcome;
        return this;
    }

    /**
     * A till like Clopos: it lists its users but its order API receives no operator, so the capability is not
     * declared and the body never carries one.
     */
    public FakePosAdapter withoutOperatorAttribution() {
        this.carriesOperator = false;
        return this;
    }

    /** What each create-order call would have put on the wire. */
    public List<Map<String, Object>> wireBodies() {
        return Collections.unmodifiableList(new ArrayList<>(wireBodies));
    }

    /** What each create-order call was handed as the operator, before this adapter decided what to send. */
    public List<@Nullable String> operatorsReceived() {
        return Collections.unmodifiableList(new ArrayList<>(operatorsReceived));
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public Set<PosCapability> declaredCapabilities() {
        // Everything, including the ones Clopos lacks. A capability no adapter in
        // the build implements is a capability whose refusal path is never
        // exercised, and its support path never exercised either.
        EnumSet<PosCapability> declared = EnumSet.allOf(PosCapability.class);
        if (!carriesOperator) {
            declared.remove(PosCapability.ORDER_OPERATOR_ATTRIBUTION);
        }
        return declared;
    }

    @Override
    public CapabilitySnapshot discoverCapabilities(PosContext context) {
        Map<PosCapability, Entry> entries = new EnumMap<>(PosCapability.class);
        for (PosCapability capability : PosCapability.values()) {
            if (!declaredCapabilities().contains(capability)) {
                entries.put(capability, Entry.unsupported("Fake provider configured without " + capability));
                continue;
            }
            entries.put(
                    capability,
                    new Entry(
                            CapabilitySupport.SUPPORTED,
                            capability == PosCapability.ORDER_EXPORT
                                    ? IdempotencyBehaviour.KEYED
                                    : IdempotencyBehaviour.NATURALLY_IDEMPOTENT,
                            true,
                            "fake-1",
                            Map.of(),
                            "Fake provider",
                            Instant.EPOCH));
        }
        return new CapabilitySnapshot(entries, Instant.EPOCH, "fake-1");
    }

    @Override
    public CatalogRead readCatalog(PosContext context) {
        CatalogSnapshot snapshot = new CatalogSnapshot(
                Instant.EPOCH,
                // A keyset walk: this fake pages by identifier, so an absence
                // from one read really is an absence.
                true,
                1,
                List.of(),
                List.of(new CatalogSnapshot.Product(
                        "f-1",
                        "Fake dish",
                        "f-cat",
                        SourceKind.DISH,
                        true,
                        false,
                        10_000L,
                        "UZS",
                        true,
                        false,
                        null,
                        Map.of())),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        return new CatalogRead(ProviderOutcome.success(Map.of(), null), snapshot);
    }

    @Override
    public AvailabilityRead readAvailability(PosContext context) {
        if (nextAvailabilityOutcome != null) {
            ProviderOutcome scripted = nextAvailabilityOutcome;
            nextAvailabilityOutcome = null;
            return new AvailabilityRead(scripted, List.of());
        }
        return new AvailabilityRead(ProviderOutcome.success(Map.of(), null), nextAvailabilityEntries);
    }

    @Override
    public ReferenceListRead discoverPaymentTypes(PosContext context) {
        if (nextPaymentTypesOutcome != null) {
            ProviderOutcome scripted = nextPaymentTypesOutcome;
            nextPaymentTypesOutcome = null;
            return new ReferenceListRead(scripted, List.of());
        }
        return new ReferenceListRead(ProviderOutcome.success(Map.of(), null), nextPaymentTypes);
    }

    @Override
    public ReferenceListRead discoverOperators(PosContext context) {
        if (nextOperatorsOutcome != null) {
            ProviderOutcome scripted = nextOperatorsOutcome;
            nextOperatorsOutcome = null;
            return new ReferenceListRead(scripted, List.of());
        }
        return new ReferenceListRead(ProviderOutcome.success(Map.of(), null), nextOperators);
    }

    @Override
    public ReferenceListRead discoverDiscounts(PosContext context) {
        if (nextDiscountsOutcome != null) {
            ProviderOutcome scripted = nextDiscountsOutcome;
            nextDiscountsOutcome = null;
            return new ReferenceListRead(scripted, List.of());
        }
        return new ReferenceListRead(ProviderOutcome.success(Map.of(), null), nextDiscounts);
    }

    @Override
    public ExportResult exportOrder(PosContext context, OrderExport order) {
        if (nextExportOutcome != null) {
            ProviderOutcome scripted = nextExportOutcome;
            nextExportOutcome = null;
            return new ExportResult(scripted, null, false);
        }
        exportedCorrelations.add(order.correlationReference());
        operatorsReceived.add(order.operatorExternalId());
        // The body this till would be sent. The operator rides on it as operator_id exactly when the
        // till's API carries one - the clause PosAdapterContractTests holds every adapter to.
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("order_number", order.correlationReference());
        if (carriesOperator && order.operatorExternalId() != null) {
            wire.put("operator_id", order.operatorExternalId());
        }
        wireBodies.add(wire);
        // ConcurrentHashMap refuses a null key outright, and a null correlation
        // reference has no identity to deduplicate on anyway, so it always gets a
        // fresh external id instead of going through the map.
        String correlation = order.correlationReference();
        String external = correlation == null
                ? "fake-order-" + sideEffects.incrementAndGet()
                : ordersByCorrelation.computeIfAbsent(
                        correlation, key -> "fake-order-" + sideEffects.incrementAndGet());
        return new ExportResult(ProviderOutcome.success(Map.of(), external), external, exportsPendApproval);
    }

    @Override
    public RecoveryRead findExportedOrder(PosContext context, ExportProbe probe) {
        String probeCorrelation = probe.correlationReference();
        String external = probeCorrelation == null ? null : ordersByCorrelation.get(probeCorrelation);
        if (external == null) {
            return new RecoveryRead(ProviderOutcome.success(Map.of(), null), List.of());
        }
        // The fake echoes the reference, which is the whole difference: its match
        // is an identity where Clopos's is a resemblance.
        return new RecoveryRead(
                ProviderOutcome.success(Map.of(), null),
                List.of(new ExportCandidate(external, "ACCEPTED", Instant.EPOCH, true, true, true, 0)));
    }

    @Override
    public ProviderOutcome cancelExportedOrder(PosContext context, String externalOrderId, String reason) {
        return ProviderOutcome.success(Map.of(), externalOrderId);
    }

    @Override
    public ProviderOutcome writeFiscalIdentifier(PosContext context, String externalReceiptId, String fiscalId) {
        return ProviderOutcome.success(Map.of(), externalReceiptId);
    }

    @Override
    public ProviderOutcome writeFulfillmentStatus(PosContext context, String externalReceiptId, String status) {
        return ProviderOutcome.success(Map.of(), externalReceiptId);
    }

    @Override
    public ApprovalRead readApprovalStatus(PosContext context, String externalOrderId) {
        if (nextApprovalOutcome != null) {
            ProviderOutcome scripted = nextApprovalOutcome;
            nextApprovalOutcome = null;
            return new ApprovalRead(scripted, null);
        }
        return new ApprovalRead(
                ProviderOutcome.success(Map.of(), externalOrderId),
                approvalDecisions.getOrDefault(externalOrderId, ApprovalRead.Decision.PENDING));
    }
}
