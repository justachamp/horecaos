package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.domain.CustomerRefund;
import uz.horecaos.platform.ordering.domain.LiabilityParty;
import uz.horecaos.platform.ordering.domain.OutcomeReasonKind;
import uz.horecaos.platform.ordering.domain.OutcomeSystemCategory;
import uz.horecaos.platform.ordering.domain.StockDisposition;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOutcomeReasonStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOutcomeReasonStore.ReasonRow;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * Authoring the tenant's cancellation and completion reasons (ADR 0039).
 *
 * <p>Two rules run through it.
 *
 * <p>A reason is authored in every locale at once. Saving one language at a time
 * would make a half-translated reason a legitimate intermediate state, and
 * intermediate states are what get used by accident — after which a customer
 * reading Uzbek is told nothing, or is told the operator's shorthand.
 *
 * <p>The consequence fields belong to the reason, not to the cancel dialog. ADR
 * 0039 refuses an operator checkbox by name: under pressure an operator picks
 * whatever closes the dialog fastest, and the write-off rate becomes noise
 * instead of a number the kitchen can act on.
 *
 * <p><b>Every mutation leaves an audit fact</b> (ADR 0027, staff 9.3a), in the
 * transaction that made it. The consequence fields are exactly the ones a
 * finance reviewer asks about later -- who changed «Не дозвонились» from
 * RELEASE to WRITE_OFF, and when -- and before this the answer was nothing at
 * all: only the reason's own {@code version} moved, and it says a change
 * happened, not what or by whom.
 */
@Service
public class OrderOutcomeReasonService {

    private final JdbcOutcomeReasonStore reasons;
    private final AuditRecorder audit;
    private final Clock clock;

    public OrderOutcomeReasonService(JdbcOutcomeReasonStore reasons, AuditRecorder audit, Clock clock) {
        this.reasons = reasons;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Who is authoring, and why -- ADR 0027 requires a reason on every
     * user-initiated fact. The reason is optional on the wire (the console form
     * has no field for it yet), so a blank one is replaced by a plain statement
     * of the action rather than refusing an edit the endpoint has always accepted.
     */
    public record Authorship(ActorRef actor, @Nullable String reason) {

        public static Authorship of(ActorRef actor) {
            return new Authorship(actor, null);
        }

        String reasonOr(String fallback) {
            return reason == null || reason.isBlank() ? fallback : reason.strip();
        }
    }

    /** The locales every reason must be written in before it can be used. */
    public static final Set<String> REQUIRED_LOCALES = Set.of("ru", "uz-Latn", "en");

    @Transactional
    public UUID create(UUID tenantId, Authorship by, CreateReason command) {
        validate(command);

        UUID reasonId = UUID.randomUUID();
        Instant now = clock.instant();

        // validate() below already refused a CANCELLATION reason with any of the
        // three null, or a COMPLETION reason with modes absent; NullAway cannot
        // see that cross-field invariant.
        reasons.insert(new JdbcOutcomeReasonStore.NewReason(
                reasonId,
                tenantId,
                command.kind(),
                command.systemCategory().name(),
                command.internalName().strip(),
                command.kind() == OutcomeReasonKind.CANCELLATION
                        ? requireCancellationField(command.stockDisposition()).name()
                        : null,
                command.kind() == OutcomeReasonKind.CANCELLATION
                        ? requireCancellationField(command.liabilityParty()).name()
                        : null,
                command.kind() == OutcomeReasonKind.CANCELLATION
                        ? requireCancellationField(command.customerRefund()).name()
                        : null,
                command.kind() == OutcomeReasonKind.COMPLETION
                        ? requireCompletionModes(command.allowedFulfillmentModes()).stream()
                                .map(Enum::name)
                                .toList()
                        : null,
                now));

        reasons.replaceTexts(reasonId, command.customerTexts());

        // A creation has no prior state: every field's "before" is null.
        recordAudit(
                "ordering.outcome-reason.created",
                tenantId,
                reasonId,
                1,
                by,
                by.reasonOr("Order outcome reason created"),
                Map.of(),
                snapshotOfCommand(command, "ACTIVE"),
                now);
        return reasonId;
    }

    @Transactional
    public int update(UUID tenantId, Authorship by, UUID reasonId, int expectedVersion, CreateReason command) {
        ReasonRow existing = reasons.find(tenantId, reasonId).orElseThrow(() -> new ReasonNotFoundException(reasonId));
        // Read before replaceTexts below rewrites them: the customer wording is
        // part of what an edit changes.
        Map<String, Object> before = snapshotOfRow(existing, reasons.texts(reasonId));
        if (existing.kind() != command.kind()) {
            // A cancellation reason cannot become a completion reason. Every
            // outcome already recorded under it cited a kind, and changing it
            // would move historical rows between two funnels.
            throw new IllegalArgumentException("A reason's kind is fixed at creation; archive it and author a new one");
        }
        validate(command);

        // validate() above already refused a CANCELLATION reason with any of the
        // three null, or a COMPLETION reason with modes absent; NullAway cannot
        // see that cross-field invariant.
        int version = reasons.update(
                        tenantId,
                        reasonId,
                        expectedVersion,
                        command.systemCategory().name(),
                        command.internalName().strip(),
                        command.kind() == OutcomeReasonKind.CANCELLATION
                                ? requireCancellationField(command.stockDisposition())
                                        .name()
                                : null,
                        command.kind() == OutcomeReasonKind.CANCELLATION
                                ? requireCancellationField(command.liabilityParty())
                                        .name()
                                : null,
                        command.kind() == OutcomeReasonKind.CANCELLATION
                                ? requireCancellationField(command.customerRefund())
                                        .name()
                                : null,
                        command.kind() == OutcomeReasonKind.COMPLETION
                                ? requireCompletionModes(command.allowedFulfillmentModes()).stream()
                                        .map(Enum::name)
                                        .toList()
                                : null,
                        clock.instant())
                .orElseThrow(() -> new StaleReasonException(expectedVersion, existing.version()));

        reasons.replaceTexts(reasonId, command.customerTexts());

        // The "after" is what the row holds now, read back -- not what the
        // request asked for. The two were the same only for as long as every
        // field of the request reached the UPDATE; a fact built from the request
        // states a change the store may never have made.
        ReasonRow stored = reasons.find(tenantId, reasonId).orElseThrow(() -> new ReasonNotFoundException(reasonId));
        recordAudit(
                "ordering.outcome-reason.updated",
                tenantId,
                reasonId,
                version,
                by,
                by.reasonOr("Order outcome reason updated"),
                before,
                snapshotOfRow(stored, reasons.texts(reasonId)),
                clock.instant());
        return version;
    }

    @Transactional
    public void archive(UUID tenantId, Authorship by, UUID reasonId, int expectedVersion) {
        Instant now = clock.instant();
        if (!reasons.archive(tenantId, reasonId, expectedVersion, now)) {
            ReasonRow existing =
                    reasons.find(tenantId, reasonId).orElseThrow(() -> new ReasonNotFoundException(reasonId));
            throw new StaleReasonException(expectedVersion, existing.version());
        }
        ReasonRow archived = reasons.find(tenantId, reasonId).orElseThrow(() -> new ReasonNotFoundException(reasonId));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("internalName", archived.internalName());
        before.put("status", "ACTIVE");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("internalName", archived.internalName());
        after.put("status", archived.status());
        recordAudit(
                "ordering.outcome-reason.archived",
                tenantId,
                reasonId,
                archived.version(),
                by,
                by.reasonOr("Order outcome reason retired"),
                before,
                after,
                now);
    }

    public List<ReasonRow> list(UUID tenantId, OutcomeReasonKind kind, boolean activeOnly) {
        return reasons.list(tenantId, kind, activeOnly);
    }

    /**
     * Ranks every active reason of one kind (gap-map row {@code 10.10a}) —
     * before this existed a reason's position in the list was an alphabetic
     * accident ({@code system_category, internal_name}), not a deliberate
     * choice of which reasons a dispatcher reaches for first.
     *
     * <p>Whole-set, the same contract {@code ServiceScheduleService
     * #replaceRules} and {@code q-schedule-grid}'s own writes use elsewhere
     * in this codebase: {@code orderedReasonIds} must name every currently
     * active reason of this kind exactly once, so a caller working from a
     * stale list (one missing a reason someone else just created, or still
     * naming one somebody else just archived) is refused rather than
     * silently reordering a subset and leaving the rest's rank undefined.
     *
     * <p>{@code expectedVersion} guards the whole list rather than one row:
     * a dated exception's delete borrows its owning schedule's version for
     * the same reason ({@code ServiceScheduleService#deleteException}) —
     * there is no separate "the list itself" aggregate to version, so a
     * single number computed from every reason being reordered stands in
     * for it. That number is the <strong>sum</strong> of their versions,
     * not the max: {@code version} only ever increments by one row's own
     * write ({@code update}, {@code archive}, {@code setDisplayOrder} — see
     * {@code JdbcOutcomeReasonStore}), never decrements, so the sum strictly
     * increases whenever any one active reason changes, whichever row it is
     * — a concurrent rename of a reason that happens not to hold the
     * current max would otherwise still read as "nothing changed" and let a
     * stale reorder through, which the max alone could not catch. The
     * console already holds every reason's own version from the list it
     * rendered and computes this the same way; a concurrent edit, archive
     * or reorder of any one of them changes the sum and the whole reorder
     * is refused rather than partially applied over stale data.
     */
    @Transactional
    public void reorder(
            UUID tenantId, Authorship by, OutcomeReasonKind kind, List<UUID> orderedReasonIds, int expectedVersion) {
        List<ReasonRow> active = reasons.list(tenantId, kind, true);

        Set<UUID> activeIds = active.stream().map(ReasonRow::id).collect(Collectors.toUnmodifiableSet());
        Set<UUID> requestedIds = Set.copyOf(orderedReasonIds);
        if (requestedIds.size() != orderedReasonIds.size()) {
            throw new IllegalArgumentException("A reorder cannot name the same reason twice");
        }
        if (!requestedIds.equals(activeIds)) {
            throw new IllegalArgumentException(
                    "A reorder must name every active reason of this kind exactly once — reload the list and retry");
        }

        int currentFingerprint = active.stream().mapToInt(ReasonRow::version).sum();
        if (currentFingerprint != expectedVersion) {
            throw new StaleReasonException(expectedVersion, currentFingerprint);
        }

        if (orderedReasonIds.isEmpty()) {
            // Nothing is active, so there is no ranking to change and no fact to record.
            return;
        }
        Instant now = clock.instant();
        int position = 0;
        for (UUID reasonId : orderedReasonIds) {
            reasons.setDisplayOrder(tenantId, reasonId, position++, now);
        }

        // One fact for the whole ranking -- a list has no single target row --
        // carrying the ranking before and after as ordered id lists.
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("kind", kind.name());
        before.put("order", active.stream().map(row -> row.id().toString()).toList());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("kind", kind.name());
        after.put("order", orderedReasonIds.stream().map(UUID::toString).toList());
        recordAudit(
                "ordering.outcome-reason.reordered",
                tenantId,
                orderedReasonIds.getFirst(),
                null,
                by,
                by.reasonOr("Order outcome reasons re-ranked"),
                before,
                after,
                now);
    }

    public Optional<ReasonRow> find(UUID tenantId, UUID reasonId) {
        return reasons.find(tenantId, reasonId);
    }

    public Map<String, String> texts(UUID reasonId) {
        return reasons.texts(reasonId);
    }

    /**
     * The reason as it read at this moment, for the outcome snapshot.
     *
     * <p>Deliberately not the JSON of the live row read later. Renaming a reason
     * next year must not rewrite last year's funnel, and the only way that stays
     * true is to copy the whole row at the moment it is cited.
     */
    public Map<String, Object> snapshotOf(ReasonRow reason) {
        return Map.of(
                "reasonId", reason.id().toString(),
                "version", reason.version(),
                "kind", reason.kind().name(),
                "systemCategory", reason.systemCategory(),
                "internalName", reason.internalName(),
                "stockDisposition", String.valueOf(reason.stockDisposition()),
                "liabilityParty", String.valueOf(reason.liabilityParty()),
                "customerRefund", String.valueOf(reason.customerRefund()),
                "allowedFulfillmentModes",
                        reason.allowedFulfillmentModes() == null ? List.of() : reason.allowedFulfillmentModes());
    }

    private void recordAudit(
            String actionCode,
            UUID tenantId,
            UUID reasonId,
            @Nullable Integer version,
            Authorship by,
            String reason,
            Map<String, Object> before,
            Map<String, Object> after,
            Instant now) {
        AuditFact.Builder fact = AuditFact.of(actionCode, AuditClass.BUSINESS)
                .by(by.actor())
                .at(ResourceScope.tenant(tenantId))
                .target("ordering.outcome-reason", reasonId)
                .because(reason)
                .changed(ChangeDocuments.diff(before, after))
                .correlatedBy(reasonId.toString())
                .occurredAt(now);
        if (version != null) {
            fact.targetVersion((long) version);
        }
        audit.record(fact.build());
    }

    /** The fields an author sets, as they will read once the write lands. */
    private static Map<String, Object> snapshotOfCommand(CreateReason command, String status) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("kind", command.kind().name());
        snapshot.put("systemCategory", command.systemCategory().name());
        snapshot.put("internalName", command.internalName().strip());
        snapshot.put("stockDisposition", enumName(command.stockDisposition()));
        snapshot.put("liabilityParty", enumName(command.liabilityParty()));
        snapshot.put("customerRefund", enumName(command.customerRefund()));
        snapshot.put(
                "allowedFulfillmentModes",
                command.allowedFulfillmentModes() == null
                        ? null
                        : command.allowedFulfillmentModes().stream()
                                .map(Enum::name)
                                .sorted()
                                .toList());
        snapshot.put("customerTexts", new java.util.TreeMap<>(command.customerTexts()));
        snapshot.put("status", status);
        return snapshot;
    }

    /** The same fields as {@link #snapshotOfCommand}, read back from the stored row. */
    private static Map<String, Object> snapshotOfRow(ReasonRow row, Map<String, String> texts) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("kind", row.kind().name());
        snapshot.put("systemCategory", row.systemCategory());
        snapshot.put("internalName", row.internalName());
        snapshot.put("stockDisposition", row.stockDisposition());
        snapshot.put("liabilityParty", row.liabilityParty());
        snapshot.put("customerRefund", row.customerRefund());
        snapshot.put(
                "allowedFulfillmentModes",
                row.allowedFulfillmentModes() == null
                        ? null
                        : row.allowedFulfillmentModes().stream().sorted().toList());
        snapshot.put("customerTexts", new java.util.TreeMap<>(texts));
        snapshot.put("status", row.status());
        return snapshot;
    }

    private static @Nullable String enumName(@Nullable Enum<?> value) {
        return value == null ? null : value.name();
    }

    private void validate(CreateReason command) {
        if (command.internalName() == null || command.internalName().isBlank()) {
            throw new IllegalArgumentException("A reason needs an internal name");
        }
        if (!command.systemCategory().availableFor(command.kind())) {
            throw new IllegalArgumentException(
                    "%s is not a category a %s reason can carry".formatted(command.systemCategory(), command.kind()));
        }
        // The two texts are genuinely different statements. Publishing the
        // internal name to a customer is what the split prevents, and it can only
        // prevent it if the customer wording actually exists.
        Set<String> provided = command.customerTexts().keySet();
        if (!provided.containsAll(REQUIRED_LOCALES)) {
            throw new IllegalArgumentException("A reason needs customer wording in ru, uz-Latn and en; missing "
                    + REQUIRED_LOCALES.stream()
                            .filter(locale -> !provided.contains(locale))
                            .collect(Collectors.joining(", ")));
        }
        if (command.kind() == OutcomeReasonKind.CANCELLATION) {
            if (command.stockDisposition() == null
                    || command.liabilityParty() == null
                    || command.customerRefund() == null) {
                throw new IllegalArgumentException(
                        "A cancellation reason decides the stock disposition, the liable party "
                                + "and the refund posture; none of the three has a safe default");
            }
            if (command.allowedFulfillmentModes() != null
                    && !command.allowedFulfillmentModes().isEmpty()) {
                throw new IllegalArgumentException(
                        "Fulfilment modes belong to a completion reason, not a cancellation");
            }
        } else {
            if (command.allowedFulfillmentModes() == null
                    || command.allowedFulfillmentModes().isEmpty()) {
                // Without this, «Самовывоз выполнен» lands on a delivery order and
                // both the courier SLA report and the external-logistics
                // settlement quietly lose that order.
                throw new IllegalArgumentException("A completion reason names the fulfilment modes it is valid for");
            }
            if (command.stockDisposition() != null
                    || command.liabilityParty() != null
                    || command.customerRefund() != null) {
                throw new IllegalArgumentException("A completed order moves no stock and costs nobody anything");
            }
        }
    }

    /**
     * Restates, for the type system, what {@link #validate} already enforced: a
     * cancellation reason never reaches {@code create}/{@code update} with this
     * field null.
     */
    private static <T> T requireCancellationField(@Nullable T value) {
        return Objects.requireNonNull(value, "validate() already required this field for a cancellation reason");
    }

    /** The completion counterpart of {@link #requireCancellationField}. */
    private static List<FulfillmentMode> requireCompletionModes(@Nullable List<FulfillmentMode> modes) {
        return Objects.requireNonNull(modes, "validate() already required modes for a completion reason");
    }

    /**
     * A reason to author or update, in every required locale at once.
     *
     * @param customerTexts what the customer is told, per locale. A different
     *                      statement from {@code internalName}: «Не дозвонились»
     *                      is what the operator needs in the list, and the
     *                      customer gets the softened wording the tenant wrote
     */
    public record CreateReason(
            OutcomeReasonKind kind,
            OutcomeSystemCategory systemCategory,
            String internalName,
            @Nullable StockDisposition stockDisposition,
            @Nullable LiabilityParty liabilityParty,
            @Nullable CustomerRefund customerRefund,
            @Nullable List<FulfillmentMode> allowedFulfillmentModes,
            Map<String, String> customerTexts) {}

    public static class ReasonNotFoundException extends RuntimeException {
        public ReasonNotFoundException(UUID reasonId) {
            super("No outcome reason " + reasonId + " for this tenant");
        }
    }

    /** The caller's expected version no longer matches the stored reason. */
    public static class StaleReasonException extends RuntimeException {

        private final int expected;
        private final int actual;

        public StaleReasonException(int expected, int actual) {
            super("The reason has changed since version %d was read".formatted(expected));
            this.expected = expected;
            this.actual = actual;
        }

        public int expected() {
            return expected;
        }

        public int actual() {
            return actual;
        }
    }
}
